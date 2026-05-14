// Copyright 2003 Dolphin Emulator Project
// Licensed under GPLv2+
// Refer to the license.txt file included.

#include <EGL/egl.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <cerrno>
#include <chrono>
#include <cinttypes>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <jni.h>
#include <linux/input.h>
#include <memory>
#include <atomic>
#include <mutex>
#include <poll.h>
#include <sched.h>
#include <string>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <thread>
#include <unistd.h>

#include "ButtonManager.h"
#include "InputCommon/GCAdapter.h"
#include "Core/HW/SI_DeviceGCController.h"
#include "Core/HW/Memmap.h"
#include "Core/Core.h"
#include "Core/Slippi/SlippiPlayback.h"

extern std::unique_ptr<SlippiPlaybackStatus> g_playbackStatus;

#include "Common/CPUDetect.h"
#include "Common/CommonPaths.h"
#include "Common/CommonTypes.h"
#include "Common/Event.h"
#include "Common/FileUtil.h"
#include "Common/GL/GLInterfaceBase.h"
#include "Common/Logging/LogManager.h"

#include "Core/BootManager.h"
#include "Core/ConfigManager.h"
#include "Core/Core.h"
#include "Core/HW/Wiimote.h"
#include "Core/HW/WiimoteReal/WiimoteReal.h"
#include "Core/Host.h"
#include "Core/PowerPC/JitInterface.h"
#include "Core/PowerPC/Profiler.h"
#include "Core/State.h"

#include "DiscIO/Enums.h"
#include "DiscIO/Volume.h"
#include "DiscIO/VolumeCreator.h"

#include "UICommon/UICommon.h"

#include "VideoCommon/OnScreenDisplay.h"
#include "VideoCommon/RenderBase.h"
#include "VideoCommon/VideoBackendBase.h"

ANativeWindow* surf;
std::string g_filename;
std::string g_set_userpath = "";
// Path passed in from Java via SetSlippiInputPath. Cannot be applied to
// SConfig directly from arbitrary threads/timings — SConfig is constructed
// during UICommon::Init() on the emu thread. We stash it here and apply
// inside Run() once SConfig is alive. Empty string = leave default.
static std::mutex s_slippi_input_path_mutex;
static std::string s_slippi_input_path;
static std::atomic<int> g_emu_thread_tid{0};
static std::mutex s_exi_override_mutex;
static int s_exi_overrides[3] = {-1, -1, -1};

JavaVM* g_java_vm;
jclass g_jni_class;
jmethodID g_jni_method_alert;
jmethodID g_jni_method_end;

#define DOLPHIN_TAG "DolphinEmuNative"

static constexpr bool kInputLatencyDiagnostics = false;
static constexpr bool kMeleePadProbeEnabled = false;

namespace
{
struct RawAxis
{
  int code = -1;
  int minimum = 0;
  int maximum = 0;
  int value = 0;
};

struct RawGamepad
{
  int fd = -1;
  std::string path;
  std::string name;
  RawAxis axes[4];
  uint64_t next_scan_ms = 0;
  bool logged_unavailable = false;
};

std::mutex s_raw_gamepad_mutex;
RawGamepad s_raw_gamepad;

uint64_t RawInputNowMs()
{
  return static_cast<uint64_t>(
      std::chrono::duration_cast<std::chrono::milliseconds>(
          std::chrono::steady_clock::now().time_since_epoch())
          .count());
}

bool InitRawAxis(int fd, int code, RawAxis* out)
{
  struct input_absinfo info = {};
  if (ioctl(fd, EVIOCGABS(code), &info) != 0)
    return false;
  if (info.maximum <= info.minimum)
    return false;
  out->code = code;
  out->minimum = info.minimum;
  out->maximum = info.maximum;
  out->value = info.value;
  return true;
}

std::string ReadInputDeviceName(int fd)
{
  char name[128] = {};
  if (ioctl(fd, EVIOCGNAME(sizeof(name)), name) < 0)
    return "";
  return name;
}

bool TryOpenRawGamepadLocked(const char* path)
{
  int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
  if (fd < 0)
  {
    if (!s_raw_gamepad.logged_unavailable && (errno == EACCES || errno == EPERM))
    {
      s_raw_gamepad.logged_unavailable = true;
      __android_log_print(ANDROID_LOG_WARN, "SlippiRawInput",
          "cannot open raw input device %s: %s; falling back to MotionEvent",
          path, strerror(errno));
    }
    return false;
  }

  RawAxis main_x;
  RawAxis main_y;
  RawAxis c_x;
  RawAxis c_y;
  if (!InitRawAxis(fd, ABS_X, &main_x) || !InitRawAxis(fd, ABS_Y, &main_y))
  {
    close(fd);
    return false;
  }

  if (!(InitRawAxis(fd, ABS_RX, &c_x) && InitRawAxis(fd, ABS_RY, &c_y)) &&
      !(InitRawAxis(fd, ABS_Z, &c_x) && InitRawAxis(fd, ABS_RZ, &c_y)))
  {
    close(fd);
    return false;
  }

  s_raw_gamepad.fd = fd;
  s_raw_gamepad.path = path;
  s_raw_gamepad.name = ReadInputDeviceName(fd);
  s_raw_gamepad.axes[0] = main_x;
  s_raw_gamepad.axes[1] = main_y;
  s_raw_gamepad.axes[2] = c_x;
  s_raw_gamepad.axes[3] = c_y;
  s_raw_gamepad.logged_unavailable = false;

  __android_log_print(ANDROID_LOG_INFO, "SlippiRawInput",
      "using raw gamepad %s (%s), axes main=(%d,%d) c=(%d,%d)",
      s_raw_gamepad.path.c_str(), s_raw_gamepad.name.c_str(),
      main_x.code, main_y.code, c_x.code, c_y.code);
  return true;
}

bool EnsureRawGamepadLocked()
{
  if (s_raw_gamepad.fd >= 0)
    return true;

  uint64_t now = RawInputNowMs();
  if (now < s_raw_gamepad.next_scan_ms)
    return false;
  s_raw_gamepad.next_scan_ms = now + 2000;

  DIR* dir = opendir("/dev/input");
  if (!dir)
  {
    if (!s_raw_gamepad.logged_unavailable)
    {
      s_raw_gamepad.logged_unavailable = true;
      __android_log_print(ANDROID_LOG_WARN, "SlippiRawInput",
          "cannot open /dev/input: %s; falling back to MotionEvent",
          strerror(errno));
    }
    return false;
  }

  bool opened = false;
  while (dirent* entry = readdir(dir))
  {
    if (strncmp(entry->d_name, "event", 5) != 0)
      continue;

    char path[64];
    snprintf(path, sizeof(path), "/dev/input/%s", entry->d_name);
    if (TryOpenRawGamepadLocked(path))
    {
      opened = true;
      break;
    }
  }
  closedir(dir);

  if (!opened && !s_raw_gamepad.logged_unavailable)
  {
    s_raw_gamepad.logged_unavailable = true;
    __android_log_print(ANDROID_LOG_WARN, "SlippiRawInput",
        "no readable raw gamepad with ABS_X/Y and ABS_RX/RY or ABS_Z/RZ; falling back to MotionEvent");
  }
  return opened;
}

float NormalizeRawAxis(const RawAxis& axis)
{
  const float center = (static_cast<float>(axis.minimum) + static_cast<float>(axis.maximum)) * 0.5f;
  const float value = static_cast<float>(axis.value);
  const float extent = value >= center ? static_cast<float>(axis.maximum) - center :
                                         center - static_cast<float>(axis.minimum);
  if (extent <= 0.0f)
    return 0.0f;

  float normalized = (value - center) / extent;
  if (normalized > 1.0f)
    normalized = 1.0f;
  else if (normalized < -1.0f)
    normalized = -1.0f;
  return normalized;
}

bool DrainRawGamepadEventsLocked(bool* saw_event);
void FillRawGamepadAxesLocked(float out[4]);

bool PollRawGamepadAxes(float out[4])
{
  std::lock_guard<std::mutex> lock(s_raw_gamepad_mutex);
  if (!EnsureRawGamepadLocked())
    return false;

  bool saw_event = false;
  if (!DrainRawGamepadEventsLocked(&saw_event))
    return false;

  FillRawGamepadAxesLocked(out);
  return true;
}

bool DrainRawGamepadEventsLocked(bool* saw_event)
{
  struct input_event event = {};
  while (true)
  {
    ssize_t bytes = read(s_raw_gamepad.fd, &event, sizeof(event));
    if (bytes == static_cast<ssize_t>(sizeof(event)))
    {
      if (saw_event)
        *saw_event = true;
      if (event.type == EV_ABS)
      {
        for (RawAxis& axis : s_raw_gamepad.axes)
        {
          if (axis.code == event.code)
          {
            axis.value = event.value;
            break;
          }
        }
      }
      continue;
    }

    if (bytes < 0 && (errno == EAGAIN || errno == EWOULDBLOCK))
      break;

    __android_log_print(ANDROID_LOG_WARN, "SlippiRawInput",
        "lost raw gamepad %s: read returned %zd errno=%s",
        s_raw_gamepad.path.c_str(), bytes, strerror(errno));
    close(s_raw_gamepad.fd);
    s_raw_gamepad.fd = -1;
    s_raw_gamepad.next_scan_ms = 0;
    return false;
  }

  return true;
}

void FillRawGamepadAxesLocked(float out[4])
{
  for (int i = 0; i < 4; i++)
    out[i] = NormalizeRawAxis(s_raw_gamepad.axes[i]);
}

bool WaitRawGamepadAxes(int timeout_ms, float out[4])
{
  std::lock_guard<std::mutex> lock(s_raw_gamepad_mutex);
  if (!EnsureRawGamepadLocked())
    return false;

  if (timeout_ms < 0)
    timeout_ms = 0;
  else if (timeout_ms > 100)
    timeout_ms = 100;

  pollfd pfd = {};
  pfd.fd = s_raw_gamepad.fd;
  pfd.events = POLLIN | POLLERR | POLLHUP | POLLNVAL;
  int poll_result = poll(&pfd, 1, timeout_ms);
  if (poll_result < 0 && errno != EINTR)
  {
    __android_log_print(ANDROID_LOG_WARN, "SlippiRawInput",
        "raw gamepad poll failed for %s: %s",
        s_raw_gamepad.path.c_str(), strerror(errno));
    close(s_raw_gamepad.fd);
    s_raw_gamepad.fd = -1;
    s_raw_gamepad.next_scan_ms = 0;
    return false;
  }
  if (poll_result > 0 && (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)))
  {
    __android_log_print(ANDROID_LOG_WARN, "SlippiRawInput",
        "lost raw gamepad %s: poll revents=0x%x",
        s_raw_gamepad.path.c_str(), pfd.revents);
    close(s_raw_gamepad.fd);
    s_raw_gamepad.fd = -1;
    s_raw_gamepad.next_scan_ms = 0;
    return false;
  }

  bool saw_event = false;
  if (poll_result > 0 && !DrainRawGamepadEventsLocked(&saw_event))
    return false;

  FillRawGamepadAxesLocked(out);
  return true;
}
}  // namespace

/*
 * Cache the JavaVM so that we can call into it later.
 */
jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
  g_java_vm = vm;

  return JNI_VERSION_1_6;
}

void Host_NotifyMapLoaded()
{
}
void Host_RefreshDSPDebuggerWindow()
{
}

// The Core only supports using a single Host thread.
// If multiple threads want to call host functions then they need to queue
// sequentially for access.
static std::mutex s_host_identity_lock;
Common::Event updateMainFrameEvent;
static bool s_have_wm_user_stop = false;
void Host_Message(int Id)
{
  if (Id == WM_USER_JOB_DISPATCH)
  {
    updateMainFrameEvent.Set();
  }
  else if (Id == WM_USER_STOP)
  {
    s_have_wm_user_stop = true;
    if (Core::IsRunning())
      Core::QueueHostJob(&Core::Stop);
  }
}

void* Host_GetRenderHandle()
{
  return surf;
}

void Host_UpdateTitle(const std::string& title)
{
  __android_log_write(ANDROID_LOG_INFO, DOLPHIN_TAG, title.c_str());
}

void Host_UpdateDisasmDialog()
{
}

void Host_UpdateMainFrame()
{
}

void Host_RequestRenderWindowSize(int width, int height)
{
}

void Host_SetStartupDebuggingParameters()
{
}

bool Host_UIHasFocus()
{
  return true;
}

bool Host_RendererHasFocus()
{
  return true;
}

bool Host_RendererIsFullscreen()
{
  return false;
}

void Host_ConnectWiimote(int wm_idx, bool connect)
{
}

void Host_SetWiiMoteConnectionState(int _State)
{
}

void Host_ShowVideoConfig(void*, const std::string&)
{
}

void Host_YieldToUI()
{
}

static bool MsgAlert(const char* caption, const char* text, bool yes_no, int /*Style*/)
{
  __android_log_print(ANDROID_LOG_ERROR, DOLPHIN_TAG, "%s:%s", caption, text);

  JNIEnv* env = nullptr;
  bool did_attach = false;
  jint env_status = g_java_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
  if (env_status == JNI_EDETACHED)
  {
    if (g_java_vm->AttachCurrentThread(&env, NULL) != JNI_OK)
      return false;
    did_attach = true;
  }
  else if (env_status != JNI_OK)
  {
    return false;
  }

  // Execute the Java method.
  jstring message = env->NewStringUTF(text);
  env->CallStaticVoidMethod(g_jni_class, g_jni_method_alert, message);
  env->DeleteLocalRef(message);

  if (did_attach)
    g_java_vm->DetachCurrentThread();

  return false;
}

#define DVD_BANNER_WIDTH 96
#define DVD_BANNER_HEIGHT 32

static inline u32 Average32(u32 a, u32 b)
{
  return ((a >> 1) & 0x7f7f7f7f) + ((b >> 1) & 0x7f7f7f7f);
}

static inline u32 GetPixel(u32* buffer, unsigned int x, unsigned int y)
{
  // thanks to unsignedness, these also check for <0 automatically.
  if (x > 191)
    return 0;
  if (y > 63)
    return 0;
  return buffer[y * 192 + x];
}

static bool LoadBanner(std::string filename, u32* Banner)
{
  std::unique_ptr<DiscIO::IVolume> pVolume(DiscIO::CreateVolumeFromFilename(filename));

  if (pVolume != nullptr)
  {
    int Width, Height;
    std::vector<u32> BannerVec = pVolume->GetBanner(&Width, &Height);
    // This code (along with above inlines) is moved from
    // elsewhere.  Someone who knows anything about Android
    // please get rid of it and use proper high-resolution
    // images.
    if (Height == 64 && Width == 192)
    {
      u32* Buffer = &BannerVec[0];
      for (int y = 0; y < 32; y++)
      {
        for (int x = 0; x < 96; x++)
        {
          // simplified plus-shaped "gaussian"
          u32 surround = Average32(
              Average32(GetPixel(Buffer, x * 2 - 1, y * 2), GetPixel(Buffer, x * 2 + 1, y * 2)),
              Average32(GetPixel(Buffer, x * 2, y * 2 - 1), GetPixel(Buffer, x * 2, y * 2 + 1)));
          Banner[y * 96 + x] = Average32(GetPixel(Buffer, x * 2, y * 2), surround);
        }
      }
      return true;
    }
    else if (Height == 32 && Width == 96)
    {
      memcpy(Banner, &BannerVec[0], 96 * 32 * 4);
      return true;
    }
  }

  return false;
}

static int GetCountry(std::string filename)
{
  std::unique_ptr<DiscIO::IVolume> pVolume(DiscIO::CreateVolumeFromFilename(filename));

  if (pVolume != nullptr)
  {
    int country = static_cast<int>(pVolume->GetCountry());

    __android_log_print(ANDROID_LOG_INFO, DOLPHIN_TAG, "Country Code: %i", country);

    return country;
  }

  return static_cast<int>(DiscIO::Country::COUNTRY_UNKNOWN);
}

static int GetPlatform(std::string filename)
{
  std::unique_ptr<DiscIO::IVolume> pVolume(DiscIO::CreateVolumeFromFilename(filename));

  if (pVolume != nullptr)
  {
    switch (pVolume->GetVolumeType())
    {
    case DiscIO::Platform::GAMECUBE_DISC:
      __android_log_print(ANDROID_LOG_INFO, DOLPHIN_TAG, "Volume is a GameCube disc.");
      return 0;
    case DiscIO::Platform::WII_DISC:
      __android_log_print(ANDROID_LOG_INFO, DOLPHIN_TAG, "Volume is a Wii disc.");
      return 1;
    case DiscIO::Platform::WII_WAD:
      __android_log_print(ANDROID_LOG_INFO, DOLPHIN_TAG, "Volume is a Wii WAD.");
      return 2;
    }
  }

  return -1;
}

static std::string GetTitle(std::string filename)
{
  __android_log_print(ANDROID_LOG_WARN, DOLPHIN_TAG, "Getting Title for file: %s",
                      filename.c_str());

  std::unique_ptr<DiscIO::IVolume> pVolume(DiscIO::CreateVolumeFromFilename(filename));

  if (pVolume != nullptr)
  {
    std::map<DiscIO::Language, std::string> titles = pVolume->GetLongNames();
    if (titles.empty())
      titles = pVolume->GetShortNames();

    /*
    bool is_wii_title = pVolume->GetVolumeType() != DiscIO::Platform::GAMECUBE_DISC;
    DiscIO::Language language = SConfig::GetInstance().GetCurrentLanguage(is_wii_title);

    auto it = titles.find(language);
    if (it != end)
      return it->second;*/

    auto end = titles.end();

    // English tends to be a good fallback when the requested language isn't available
    // if (language != DiscIO::Language::LANGUAGE_ENGLISH) {
    auto it = titles.find(DiscIO::Language::LANGUAGE_ENGLISH);
    if (it != end)
      return it->second;
    //}

    // If English isn't available either, just pick something
    if (!titles.empty())
      return titles.cbegin()->second;

    // No usable name, return filename (better than nothing)
    std::string name;
    SplitPath(filename, nullptr, &name, nullptr);
    return name;
  }

  return std::string("");
}

static std::string GetDescription(std::string filename)
{
  __android_log_print(ANDROID_LOG_WARN, DOLPHIN_TAG, "Getting Description for file: %s",
                      filename.c_str());

  std::unique_ptr<DiscIO::IVolume> volume(DiscIO::CreateVolumeFromFilename(filename));

  if (volume != nullptr)
  {
    std::map<DiscIO::Language, std::string> descriptions = volume->GetDescriptions();

    /*
    bool is_wii_title = pVolume->GetVolumeType() != DiscIO::Platform::GAMECUBE_DISC;
    DiscIO::Language language = SConfig::GetInstance().GetCurrentLanguage(is_wii_title);

    auto it = descriptions.find(language);
    if (it != end)
      return it->second;*/

    auto end = descriptions.end();

    // English tends to be a good fallback when the requested language isn't available
    // if (language != DiscIO::Language::LANGUAGE_ENGLISH) {
    auto it = descriptions.find(DiscIO::Language::LANGUAGE_ENGLISH);
    if (it != end)
      return it->second;
    //}

    // If English isn't available either, just pick something
    if (!descriptions.empty())
      return descriptions.cbegin()->second;
  }

  return std::string();
}

static std::string GetGameId(std::string filename)
{
  __android_log_print(ANDROID_LOG_WARN, DOLPHIN_TAG, "Getting ID for file: %s", filename.c_str());

  std::unique_ptr<DiscIO::IVolume> volume(DiscIO::CreateVolumeFromFilename(filename));
  if (volume == nullptr)
    return std::string();

  std::string id = volume->GetGameID();
  __android_log_print(ANDROID_LOG_INFO, DOLPHIN_TAG, "Game ID: %s", id.c_str());
  return id;
}

static std::string GetCompany(std::string filename)
{
  __android_log_print(ANDROID_LOG_WARN, DOLPHIN_TAG, "Getting Company for file: %s",
                      filename.c_str());

  std::unique_ptr<DiscIO::IVolume> volume(DiscIO::CreateVolumeFromFilename(filename));
  if (volume == nullptr)
    return std::string();

  std::string company = DiscIO::GetCompanyFromID(volume->GetMakerID());
  __android_log_print(ANDROID_LOG_INFO, DOLPHIN_TAG, "Company: %s", company.c_str());
  return company;
}

static u64 GetFileSize(std::string filename)
{
  __android_log_print(ANDROID_LOG_WARN, DOLPHIN_TAG, "Getting size of file: %s", filename.c_str());

  std::unique_ptr<DiscIO::IVolume> volume(DiscIO::CreateVolumeFromFilename(filename));
  if (volume == nullptr)
    return -1;

  u64 size = volume->GetSize();
  __android_log_print(ANDROID_LOG_INFO, DOLPHIN_TAG, "Size: %" PRIu64, size);
  return size;
}

static std::string GetJString(JNIEnv* env, jstring jstr)
{
  std::string result = "";
  if (!jstr)
    return result;

  const char* s = env->GetStringUTFChars(jstr, nullptr);
  result = s;
  env->ReleaseStringUTFChars(jstr, s);
  return result;
}

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_UnPauseEmulation(JNIEnv* env,
                                                                                     jobject obj);
JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_PauseEmulation(JNIEnv* env,
                                                                                   jobject obj);
JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_StopEmulation(JNIEnv* env,
                                                                                  jobject obj);
JNIEXPORT jboolean JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_onGamePadEvent(
    JNIEnv* env, jobject obj, jstring jDevice, jint Button, jint Action);
JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_onGamePadMoveEvent(
    JNIEnv* env, jobject obj, jstring jDevice, jint Axis, jfloat Value);
JNIEXPORT jfloatArray JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_PollRawGamepadAxes(
    JNIEnv* env, jobject obj);
JNIEXPORT jfloatArray JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_WaitRawGamepadAxes(
    JNIEnv* env, jobject obj, jint timeout_ms);
JNIEXPORT jintArray JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_GetBanner(JNIEnv* env,
                                                                                   jobject obj,
                                                                                   jstring jFile);
JNIEXPORT jstring JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_GetTitle(JNIEnv* env,
                                                                                jobject obj,
                                                                                jstring jFilename);
JNIEXPORT jstring JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_GetDescription(
    JNIEnv* env, jobject obj, jstring jFilename);
JNIEXPORT jstring JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_GetGameId(JNIEnv* env,
                                                                                 jobject obj,
                                                                                 jstring jFilename);
JNIEXPORT jint JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_GetCountry(JNIEnv* env,
                                                                               jobject obj,
                                                                               jstring jFilename);
JNIEXPORT jstring JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_GetCompany(
    JNIEnv* env, jobject obj, jstring jFilename);
JNIEXPORT jlong JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_GetFilesize(JNIEnv* env,
                                                                                 jobject obj,
                                                                                 jstring jFilename);
JNIEXPORT jint JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_GetPlatform(JNIEnv* env,
                                                                                jobject obj,
                                                                                jstring jFilename);
JNIEXPORT jstring JNICALL
Java_org_dolphinemu_dolphinemu_NativeLibrary_GetVersionString(JNIEnv* env, jobject obj);
JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_SaveScreenShot(JNIEnv* env,
                                                                                   jobject obj);
JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_eglBindAPI(JNIEnv* env,
                                                                               jobject obj,
                                                                               jint api);
JNIEXPORT jstring JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_GetConfig(
    JNIEnv* env, jobject obj, jstring jFile, jstring jSection, jstring jKey, jstring jDefault);
JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_SetConfig(
    JNIEnv* env, jobject obj, jstring jFile, jstring jSection, jstring jKey, jstring jValue);
JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_SetEXIDeviceOverride(
    JNIEnv* env, jobject obj, jint slot, jint device);
JNIEXPORT void JNICALL
Java_org_dolphinemu_dolphinemu_NativeLibrary_ClearEXIDeviceOverrides(JNIEnv* env, jobject obj);
JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_SetFilename(JNIEnv* env,
                                                                                jobject obj,
                                                                                jstring jFile);
JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_SaveState(JNIEnv* env,
                                                                              jobject obj,
                                                                              jint slot);
JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_LoadState(JNIEnv* env,
                                                                              jobject obj,
                                                                              jint slot);
JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_CreateUserFolders(JNIEnv* env,
                                                                                      jobject obj);
JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_SetUserDirectory(
    JNIEnv* env, jobject obj, jstring jDirectory);
JNIEXPORT jstring JNICALL
Java_org_dolphinemu_dolphinemu_NativeLibrary_GetUserDirectory(JNIEnv* env, jobject obj);
JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_SetProfiling(JNIEnv* env,
                                                                                 jobject obj,
                                                                                 jboolean enable);
JNIEXPORT void JNICALL
Java_org_dolphinemu_dolphinemu_NativeLibrary_WriteProfileResults(JNIEnv* env, jobject obj);
JNIEXPORT void JNICALL
Java_org_dolphinemu_dolphinemu_NativeLibrary_CacheClassesAndMethods(JNIEnv* env, jobject obj);
JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_Run(JNIEnv* env, jobject obj);
JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_SurfaceChanged(JNIEnv* env,
                                                                                   jobject obj,
                                                                                   jobject _surf);
JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_SurfaceDestroyed(JNIEnv* env,
                                                                                     jobject obj);

JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_SetSlippiInputPath(
    JNIEnv* env, jobject obj, jstring jPath);
JNIEXPORT jlong JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_GetPadOverrideAgeUs(
    JNIEnv* env, jobject obj, jint port);
JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_ClearSlippiInputPath(
    JNIEnv* env, jobject obj);
JNIEXPORT jint JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_GetReplayLatestFrame(
    JNIEnv* env, jobject obj);
JNIEXPORT jint JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_GetReplayCurrentFrame(
    JNIEnv* env, jobject obj);
JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_SetReplayTargetFrame(
    JNIEnv* env, jobject obj, jint frame);
JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_SetReplayJump(
    JNIEnv* env, jobject obj, jboolean forward);
JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_SetReplaySpeedMode(
    JNIEnv* env, jobject obj, jint mode);

JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_UnPauseEmulation(JNIEnv* env,
                                                                                     jobject obj)
{
  std::lock_guard<std::mutex> guard(s_host_identity_lock);
  Core::SetState(Core::CORE_RUN);
}
JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_PauseEmulation(JNIEnv* env,
                                                                                   jobject obj)
{
  std::lock_guard<std::mutex> guard(s_host_identity_lock);
  Core::SetState(Core::CORE_PAUSE);
}

JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_StopEmulation(JNIEnv* env,
                                                                                  jobject obj)
{
  std::lock_guard<std::mutex> guard(s_host_identity_lock);
  Core::Stop();
  updateMainFrameEvent.Set();  // Kick the waiting event
}
JNIEXPORT jboolean JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_onGamePadEvent(
    JNIEnv* env, jobject obj, jstring jDevice, jint Button, jint Action)
{
  return ButtonManager::GamepadEvent(GetJString(env, jDevice), Button, Action);
}
JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_onGamePadMoveEvent(
    JNIEnv* env, jobject obj, jstring jDevice, jint Axis, jfloat Value)
{
  ButtonManager::GamepadAxisEvent(GetJString(env, jDevice), Axis, Value);
}

JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_SetGCAdapterStickCalibration(
    JNIEnv* env, jobject obj, jint port, jint stickIdx,
    jfloat centerX, jfloat centerY,
    jfloat scaleXPos, jfloat scaleXNeg,
    jfloat scaleYPos, jfloat scaleYNeg,
    jfloat deadzone, jfloat sensitivity)
{
  GCAdapter::SetStickCalibration(port, stickIdx,
                                 centerX, centerY,
                                 scaleXPos, scaleXNeg,
                                 scaleYPos, scaleYNeg,
                                 deadzone, sensitivity);
}

JNIEXPORT jintArray JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_GetRawAdapterStick(
    JNIEnv* env, jobject obj, jint port, jint stickIdx)
{
  u8 x, y;
  if (!GCAdapter::GetLatestRawStick(port, stickIdx, &x, &y))
    return nullptr;
  jintArray arr = env->NewIntArray(2);
  if (!arr) return nullptr;
  jint vals[2] = {static_cast<jint>(x), static_cast<jint>(y)};
  env->SetIntArrayRegion(arr, 0, 2, vals);
  return arr;
}

JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_SetGCAdapterButtonMap(
    JNIEnv* env, jobject obj, jint port, jint sourceBit, jint targetBit)
{
  GCAdapter::SetButtonRemap(port,
      static_cast<uint16_t>(sourceBit), static_cast<uint16_t>(targetBit));
}

JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_ClearGCAdapterButtonMap(
    JNIEnv* env, jobject obj, jint port)
{
  GCAdapter::ClearButtonRemap(port);
}

JNIEXPORT jint JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_GetGCAdapterButtonsRaw(
    JNIEnv* env, jobject obj, jint port)
{
  return static_cast<jint>(GCAdapter::GetLatestRawButtons(port));
}

JNIEXPORT jboolean JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_IsGCAdapterPortConnected(
    JNIEnv* env, jobject obj, jint port)
{
  if (port < 0 || port >= 4)
    return JNI_FALSE;
  return GCAdapter::DeviceConnected(port) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloatArray JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_PollRawGamepadAxes(
    JNIEnv* env, jobject obj)
{
  float axes[4];
  if (!PollRawGamepadAxes(axes))
    return nullptr;

  jfloatArray arr = env->NewFloatArray(4);
  if (!arr)
    return nullptr;

  env->SetFloatArrayRegion(arr, 0, 4, axes);
  return arr;
}

JNIEXPORT jfloatArray JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_WaitRawGamepadAxes(
    JNIEnv* env, jobject obj, jint timeout_ms)
{
  float axes[4];
  if (!WaitRawGamepadAxes(static_cast<int>(timeout_ms), axes))
    return nullptr;

  jfloatArray arr = env->NewFloatArray(4);
  if (!arr)
    return nullptr;

  env->SetFloatArrayRegion(arr, 0, 4, axes);
  return arr;
}

JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_SetPadOverride(
    JNIEnv* env, jobject obj, jint port, jint button,
    jint stickX, jint stickY, jint substickX, jint substickY,
    jint triggerL, jint triggerR, jint analogA, jint analogB)
{
  // Diagnostic: log only when the sticks aren't centered (so we don't
  // flood, but we DO see what's coming through the JNI when the user
  // is actually moving).
  if (kInputLatencyDiagnostics &&
      (stickX != 128 || stickY != 128 || substickX != 128 || substickY != 128 || button != 0))
  {
    __android_log_print(ANDROID_LOG_INFO, "SlippiJNI",
        "SetPadOverride port=%d btn=0x%04x stick=(%d,%d) substick=(%d,%d) trig=(%d,%d)",
        port, button, stickX, stickY, substickX, substickY, triggerL, triggerR);
  }
  SI_PadOverride::Set(port,
                      static_cast<uint16_t>(button),
                      static_cast<uint8_t>(stickX),
                      static_cast<uint8_t>(stickY),
                      static_cast<uint8_t>(substickX),
                      static_cast<uint8_t>(substickY),
                      static_cast<uint8_t>(triggerL),
                      static_cast<uint8_t>(triggerR),
                      static_cast<uint8_t>(analogA),
                      static_cast<uint8_t>(analogB));
}

JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_ClearPadOverride(
    JNIEnv* env, jobject obj, jint port)
{
  SI_PadOverride::Clear(port);
}

JNIEXPORT jlong JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_GetPadOverrideAgeUs(
    JNIEnv* env, jobject obj, jint port)
{
  return static_cast<jlong>(SI_PadOverride::LatestSetAgeUs(port));
}

// ─── Gecko-style direct write into Melee's HSDPad array ───
//
// Melee 1.02 NTSC keeps per-port pad data at 0x804C1FAC + port * 0x44.
// Within each struct, the post-conversion float values live at:
//   +0x10 stickX  +0x14 stickY  +0x18 substickX  +0x1C substickY
// Writing here bypasses Dolphin's SI pipeline AND Melee's own
// byte-to-float conversion (which has its own deadzone/curve we can't
// otherwise turn off).
//
// Each call writes the four floats; we call this on every Java input
// event (~100Hz), which is faster than Melee polls (60Hz), so any
// races with Melee re-populating the struct from the byte buffer get
// stomped on next event.
JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_SetMeleePadFloats(
    JNIEnv* env, jobject obj, jint port,
    jfloat sx, jfloat sy, jfloat csx, jfloat csy)
{
  if (port < 0 || port >= 4) return;
  if (!Core::IsRunning()) return;
  if (!kMeleePadProbeEnabled) return;

  // DIAGNOSTIC: writes DISABLED. We are searching for where Melee
  // actually keeps the post-conversion stick value. Read several
  // plausible addresses at the stickX/stickY pair offset and look for
  // values that match the user's physical stick position WITHOUT us
  // having written anything.
  static std::atomic<int> diag_count{0};
  int n = diag_count.fetch_add(1) + 1;
  if (n % 30 == 0 && port == 0)
  {
    auto readF = [](u32 addr) -> float {
      void* p = Memory::GetPointer(addr);
      if (!p) return 0.0f / 0.0f;
      u32 raw;
      std::memcpy(&raw, p, sizeof(u32));
      raw = __builtin_bswap32(raw);
      union { u32 u; float f; } c;
      c.u = raw;
      return c.f;
    };
    // Read pairs of floats at +0x10/+0x14 from each candidate base.
    // Also support reading raw byte at an address (for SI poll buffer
    // candidates which store stickX as a byte centered at 0x80).
    auto readB = [](u32 addr) -> int {
      void* p = Memory::GetPointer(addr);
      if (!p) return -1;
      u8 v;
      std::memcpy(&v, p, sizeof(u8));
      return (int)v;
    };

    // Scan a wider range. For each candidate base, read FLOAT pair
    // at +0x10 and BYTE at +0x02/+0x03 (poll-format stickX/stickY).
    struct Cand { u32 base; const char* tag; };
    Cand cands[] = {
        {0x80453008, "8045_3008"},
        {0x80453090, "8045_3090"},
        {0x803F1F78, "803F_1F78"},
        {0x804C20BC, "804C_20BC"},
        {0x804C2300, "804C_2300"},
        {0x804C2400, "804C_2400"},
        {0x804C24F0, "804C_24F0"},
        {0x804C2520, "804C_2520"},
        {0x804C2530, "804C_2530"},
    };
    char line[1024];
    int len = 0;
    len += snprintf(line + len, sizeof(line) - len,
        "raw=(%+.2f,%+.2f) | ", sx, sy);
    for (auto& c : cands)
    {
      float fx = readF(c.base + 0x10);
      float fy = readF(c.base + 0x14);
      int bx = readB(c.base + 0x02);  // byte stickX in poll-format
      int by = readB(c.base + 0x03);
      len += snprintf(line + len, sizeof(line) - len,
          "%s[f=%+.2f,%+.2f b=%d,%d] ", c.tag, fx, fy, bx, by);
      if (len >= (int)sizeof(line) - 4) break;
    }
    __android_log_print(ANDROID_LOG_INFO, "MeleePadProbe", "%s", line);
  }
}

JNIEXPORT jintArray JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_GetBanner(JNIEnv* env,
                                                                                   jobject obj,
                                                                                   jstring jFile)
{
  std::string file = GetJString(env, jFile);
  u32 uBanner[DVD_BANNER_WIDTH * DVD_BANNER_HEIGHT];
  jintArray Banner = env->NewIntArray(DVD_BANNER_WIDTH * DVD_BANNER_HEIGHT);

  if (LoadBanner(file, uBanner))
  {
    env->SetIntArrayRegion(Banner, 0, DVD_BANNER_WIDTH * DVD_BANNER_HEIGHT, (jint*)uBanner);
  }
  return Banner;
}

JNIEXPORT jstring JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_GetTitle(JNIEnv* env,
                                                                                jobject obj,
                                                                                jstring jFilename)
{
  std::string filename = GetJString(env, jFilename);
  std::string name = GetTitle(filename);
  return env->NewStringUTF(name.c_str());
}

JNIEXPORT jstring JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_GetDescription(
    JNIEnv* env, jobject obj, jstring jFilename)
{
  std::string filename = GetJString(env, jFilename);
  std::string description = GetDescription(filename);
  return env->NewStringUTF(description.c_str());
}

JNIEXPORT jstring JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_GetGameId(JNIEnv* env,
                                                                                 jobject obj,
                                                                                 jstring jFilename)
{
  std::string filename = GetJString(env, jFilename);
  std::string id = GetGameId(filename);
  return env->NewStringUTF(id.c_str());
}

JNIEXPORT jstring JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_GetCompany(JNIEnv* env,
                                                                                  jobject obj,
                                                                                  jstring jFilename)
{
  std::string filename = GetJString(env, jFilename);
  std::string company = GetCompany(filename);
  return env->NewStringUTF(company.c_str());
}

JNIEXPORT jint JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_GetCountry(JNIEnv* env,
                                                                               jobject obj,
                                                                               jstring jFilename)
{
  std::string filename = GetJString(env, jFilename);
  int country = GetCountry(filename);
  return country;
}

JNIEXPORT jlong JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_GetFilesize(JNIEnv* env,
                                                                                 jobject obj,
                                                                                 jstring jFilename)
{
  std::string filename = GetJString(env, jFilename);
  u64 size = GetFileSize(filename);
  return size;
}

JNIEXPORT jint JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_GetPlatform(JNIEnv* env,
                                                                                jobject obj,
                                                                                jstring jFilename)
{
  std::string filename = GetJString(env, jFilename);
  int platform = GetPlatform(filename);
  return platform;
}

JNIEXPORT jstring JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_GetVersionString(JNIEnv* env,
                                                                                        jobject obj)
{
  return env->NewStringUTF(scm_rev_str.c_str());
}

JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_SaveScreenShot(JNIEnv* env,
                                                                                   jobject obj)
{
  std::lock_guard<std::mutex> guard(s_host_identity_lock);
  Core::SaveScreenShot();
}

JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_eglBindAPI(JNIEnv* env,
                                                                               jobject obj,
                                                                               jint api)
{
  eglBindAPI(api);
}

JNIEXPORT jstring JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_GetConfig(
    JNIEnv* env, jobject obj, jstring jFile, jstring jSection, jstring jKey, jstring jDefault)
{
  IniFile ini;
  std::string file = GetJString(env, jFile);
  std::string section = GetJString(env, jSection);
  std::string key = GetJString(env, jKey);
  std::string defaultValue = GetJString(env, jDefault);

  ini.Load(File::GetUserPath(D_CONFIG_IDX) + std::string(file));
  std::string value;

  ini.GetOrCreateSection(section)->Get(key, &value, defaultValue);

  return env->NewStringUTF(value.c_str());
}
JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_SetConfig(
    JNIEnv* env, jobject obj, jstring jFile, jstring jSection, jstring jKey, jstring jValue)
{
  IniFile ini;
  std::string file = GetJString(env, jFile);
  std::string section = GetJString(env, jSection);
  std::string key = GetJString(env, jKey);
  std::string value = GetJString(env, jValue);

  ini.Load(File::GetUserPath(D_CONFIG_IDX) + std::string(file));

  ini.GetOrCreateSection(section)->Set(key, value);
  ini.Save(File::GetUserPath(D_CONFIG_IDX) + std::string(file));
}

JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_SetEXIDeviceOverride(
    JNIEnv* env, jobject obj, jint slot, jint device)
{
  if (slot < 0 || slot >= 3)
    return;
  std::lock_guard<std::mutex> guard(s_exi_override_mutex);
  s_exi_overrides[slot] = device;
}

JNIEXPORT void JNICALL
Java_org_dolphinemu_dolphinemu_NativeLibrary_ClearEXIDeviceOverrides(JNIEnv* env, jobject obj)
{
  std::lock_guard<std::mutex> guard(s_exi_override_mutex);
  for (int& override_device : s_exi_overrides)
    override_device = -1;
}

JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_SetFilename(JNIEnv* env,
                                                                                jobject obj,
                                                                                jstring jFile)
{
  g_filename = GetJString(env, jFile);
}

JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_SaveState(JNIEnv* env,
                                                                              jobject obj,
                                                                              jint slot)
{
  std::lock_guard<std::mutex> guard(s_host_identity_lock);
  State::Save(slot);
}

JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_LoadState(JNIEnv* env,
                                                                              jobject obj,
                                                                              jint slot)
{
  std::lock_guard<std::mutex> guard(s_host_identity_lock);
  State::Load(slot);
}

JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_CreateUserFolders(JNIEnv* env,
                                                                                      jobject obj)
{
  File::CreateFullPath(File::GetUserPath(D_CONFIG_IDX));
  File::CreateFullPath(File::GetUserPath(D_GCUSER_IDX));
  File::CreateFullPath(File::GetUserPath(D_WIIROOT_IDX) + DIR_SEP WII_WC24CONF_DIR DIR_SEP
                       "mbox" DIR_SEP);
  File::CreateFullPath(File::GetUserPath(D_WIIROOT_IDX) + DIR_SEP "shared2" DIR_SEP
                                                                  "succession" DIR_SEP);
  File::CreateFullPath(File::GetUserPath(D_WIIROOT_IDX) + DIR_SEP "shared2" DIR_SEP "ec" DIR_SEP);
  File::CreateFullPath(File::GetUserPath(D_WIIROOT_IDX) + DIR_SEP WII_SYSCONF_DIR DIR_SEP);
  File::CreateFullPath(File::GetUserPath(D_CACHE_IDX));
  File::CreateFullPath(File::GetUserPath(D_DUMPDSP_IDX));
  File::CreateFullPath(File::GetUserPath(D_DUMPTEXTURES_IDX));
  File::CreateFullPath(File::GetUserPath(D_HIRESTEXTURES_IDX));
  File::CreateFullPath(File::GetUserPath(D_SCREENSHOTS_IDX));
  File::CreateFullPath(File::GetUserPath(D_STATESAVES_IDX));
  File::CreateFullPath(File::GetUserPath(D_MAILLOGS_IDX));
  File::CreateFullPath(File::GetUserPath(D_SHADERS_IDX) + "Anaglyph" DIR_SEP);
  File::CreateFullPath(File::GetUserPath(D_GCUSER_IDX) + USA_DIR DIR_SEP);
  File::CreateFullPath(File::GetUserPath(D_GCUSER_IDX) + EUR_DIR DIR_SEP);
  File::CreateFullPath(File::GetUserPath(D_GCUSER_IDX) + JAP_DIR DIR_SEP);
}

JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_SetUserDirectory(
    JNIEnv* env, jobject obj, jstring jDirectory)
{
  std::lock_guard<std::mutex> guard(s_host_identity_lock);
  std::string directory = GetJString(env, jDirectory);
  g_set_userpath = directory;
  UICommon::SetUserDirectory(directory);
}

JNIEXPORT void JNICALL
Java_org_dolphinemu_dolphinemu_utils_DirectoryInitialization_SetSysDirectory(
    JNIEnv* env, jclass obj, jstring jDirectory)
{
  File::SetSysDirectory(GetJString(env, jDirectory));
}

JNIEXPORT void JNICALL
Java_org_dolphinemu_dolphinemu_utils_DirectoryInitialization_SetGpuDriverDirectories(
    JNIEnv* env, jclass obj, jstring jDirectory, jstring jLibraryDirectory)
{
  File::SetGpuDriverDirectories(GetJString(env, jDirectory),
                                GetJString(env, jLibraryDirectory));
}

JNIEXPORT jstring JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_GetUserDirectory(JNIEnv* env,
                                                                                        jobject obj)
{
  return env->NewStringUTF(File::GetUserPath(D_USER_IDX).c_str());
}

// Stash the playback config path for the next Run(). We can't poke
// SConfig::GetInstance() directly here — SConfig isn't constructed
// until UICommon::Init runs on the emu thread, so doing it from the
// activity's onCreate (where this is called) hits a null deref.
JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_SetSlippiInputPath(
    JNIEnv* env, jobject obj, jstring jPath)
{
  std::lock_guard<std::mutex> guard(s_slippi_input_path_mutex);
  s_slippi_input_path = GetJString(env, jPath);
}

JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_ClearSlippiInputPath(
    JNIEnv* env, jobject obj)
{
  std::lock_guard<std::mutex> guard(s_slippi_input_path_mutex);
  s_slippi_input_path.clear();
}

// Replay HUD getters / setters. g_playbackStatus is only allocated while a
// CEXISlippi instance exists (i.e. during a Slippi game), so every entry
// point null-checks. The HUD treats INT_MIN as "not loaded yet" and skips
// rendering until the seek thread publishes a real frame.
JNIEXPORT jint JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_GetReplayLatestFrame(
    JNIEnv* env, jobject obj)
{
  return g_playbackStatus ? static_cast<jint>(g_playbackStatus->latestFrame) : INT_MIN;
}

JNIEXPORT jint JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_GetReplayCurrentFrame(
    JNIEnv* env, jobject obj)
{
  return g_playbackStatus ? static_cast<jint>(g_playbackStatus->currentPlaybackFrame) : INT_MIN;
}

JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_SetReplayTargetFrame(
    JNIEnv* env, jobject obj, jint frame)
{
  if (g_playbackStatus)
    g_playbackStatus->targetFrameNum = static_cast<s32>(frame);
}

JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_SetReplayJump(
    JNIEnv* env, jobject obj, jboolean forward)
{
  if (!g_playbackStatus)
    return;
  if (forward)
    g_playbackStatus->shouldJumpForward = true;
  else
    g_playbackStatus->shouldJumpBack = true;
}

// 0 = normal speed, 1 = hard fast-forward (~4x). setHardFFW mutates the
// global SConfig OC settings; resetPlayback restores them on game exit.
JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_SetReplaySpeedMode(
    JNIEnv* env, jobject obj, jint mode)
{
  if (g_playbackStatus)
    g_playbackStatus->setHardFFW(mode == 1);
}

JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_SetProfiling(JNIEnv* env,
                                                                                 jobject obj,
                                                                                 jboolean enable)
{
  std::lock_guard<std::mutex> guard(s_host_identity_lock);
  Core::SetState(Core::CORE_PAUSE);
  JitInterface::ClearCache();
  Profiler::g_ProfileBlocks = enable;
  Core::SetState(Core::CORE_RUN);
}

JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_WriteProfileResults(JNIEnv* env,
                                                                                        jobject obj)
{
  std::lock_guard<std::mutex> guard(s_host_identity_lock);
  std::string filename = File::GetUserPath(D_DUMP_IDX) + "Debug/profiler.txt";
  File::CreateFullPath(filename);
  JitInterface::WriteProfileResults(filename);
}

JNIEXPORT void JNICALL
Java_org_dolphinemu_dolphinemu_NativeLibrary_CacheClassesAndMethods(JNIEnv* env, jobject obj)
{
  // This class reference is only valid for the lifetime of this method.
  jclass localClass = env->FindClass("org/dolphinemu/dolphinemu/NativeLibrary");

  // This reference, however, is valid until we delete it.
  g_jni_class = reinterpret_cast<jclass>(env->NewGlobalRef(localClass));

  // TODO Find a place for this.
  // So we don't leak a reference to NativeLibrary.class.
  // env->DeleteGlobalRef(g_jni_class);

  // Method signature taken from javap -s
  // Source/Android/app/build/intermediates/classes/arm/debug/org/dolphinemu/dolphinemu/NativeLibrary.class
  g_jni_method_alert =
      env->GetStaticMethodID(g_jni_class, "displayAlertMsg", "(Ljava/lang/String;)V");
  g_jni_method_end = env->GetStaticMethodID(g_jni_class, "endEmulationActivity", "()V");
}

// Surface Handling
JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_SurfaceChanged(JNIEnv* env,
                                                                                   jobject obj,
                                                                                   jobject _surf)
{
  surf = ANativeWindow_fromSurface(env, _surf);
  if (surf == nullptr)
    __android_log_print(ANDROID_LOG_ERROR, DOLPHIN_TAG, "Error: Surface is null.");

  if (g_renderer)
    g_renderer->ChangeSurface(surf);
}

JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_SurfaceDestroyed(JNIEnv* env,
                                                                                     jobject obj)
{
  if (g_renderer)
    g_renderer->ChangeSurface(nullptr);

  if (surf)
  {
    ANativeWindow_release(surf);
    surf = nullptr;
  }
}
// Try to keep this thread on the SoC's big core(s) and out of the CFS
// fair-share rotation. Slippi's emu thread is single-hot and feeds the
// renderer + EXI poll every frame; any scheduler hiccup is felt as
// input lag. On 8-core ARM phones the kernel typically numbers cores
// efficiency-first (0..N small, last few big), so binding to the top
// half of the available mask is a decent heuristic when we can't query
// the cluster topology.
static void PinEmuThreadToPerformanceCores()
{
  // setpriority(PRIO_PROCESS, 0, niceval) on Bionic uses Android's
  // thread-priority convention: -8 == THREAD_PRIORITY_URGENT_DISPLAY.
  // Lower is higher priority. We can't ask for real-time without root,
  // but URGENT_DISPLAY is what SurfaceFlinger uses and is the highest
  // priority a normal app can request.
  if (setpriority(PRIO_PROCESS, 0, -8) != 0)
  {
    __android_log_print(ANDROID_LOG_WARN, DOLPHIN_TAG,
        "setpriority(-8) failed: %d", errno);
  }

  cpu_set_t mask;
  CPU_ZERO(&mask);
  if (sched_getaffinity(0, sizeof(mask), &mask) != 0)
    return;

  int total = CPU_COUNT(&mask);
  if (total <= 1)
    return;

  // Keep only the upper half of the allowed cores. On the Ayn Thor /
  // SD8 Gen 2 this lands the emu thread on the Cortex-X3 + A715 cluster
  // rather than the A510 efficiency cores.
  cpu_set_t pinned;
  CPU_ZERO(&pinned);
  int kept = 0;
  int want = total / 2;
  for (int cpu = CPU_SETSIZE - 1; cpu >= 0 && kept < want; --cpu)
  {
    if (CPU_ISSET(cpu, &mask))
    {
      CPU_SET(cpu, &pinned);
      ++kept;
    }
  }
  if (kept > 0)
  {
    if (sched_setaffinity(0, sizeof(pinned), &pinned) != 0)
    {
      __android_log_print(ANDROID_LOG_WARN, DOLPHIN_TAG,
          "sched_setaffinity failed: %d", errno);
    }
  }
}

JNIEXPORT jint JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_GetEmuThreadTid(JNIEnv*, jobject)
{
  return g_emu_thread_tid.load();
}

JNIEXPORT void JNICALL Java_org_dolphinemu_dolphinemu_NativeLibrary_Run(JNIEnv* env, jobject obj)
{
  __android_log_print(ANDROID_LOG_INFO, DOLPHIN_TAG, "Running : %s", g_filename.c_str());
  g_emu_thread_tid.store((int)syscall(SYS_gettid));
  PinEmuThreadToPerformanceCores();

  // Install our callbacks
  OSD::AddCallback(OSD::CallbackType::Initialization, ButtonManager::Init);
  OSD::AddCallback(OSD::CallbackType::Shutdown, ButtonManager::Shutdown);

  RegisterMsgAlertHandler(&MsgAlert);

  std::unique_lock<std::mutex> guard(s_host_identity_lock);
  UICommon::SetUserDirectory(g_set_userpath);
  UICommon::Init();

  // SConfig is alive now; apply any pending Slippi playback config
  // path before BootCore constructs CEXISlippi (which reads it).
  {
    std::lock_guard<std::mutex> slip_guard(s_slippi_input_path_mutex);
    SConfig::GetInstance().m_strSlippiInput = s_slippi_input_path;
  }
  {
    std::lock_guard<std::mutex> exi_guard(s_exi_override_mutex);
    for (int i = 0; i < 3; ++i)
    {
      if (s_exi_overrides[i] >= 0)
        SConfig::GetInstance().m_EXIDevice[i] = static_cast<TEXIDevices>(s_exi_overrides[i]);
    }
  }

  WiimoteReal::InitAdapterClass();

  // No use running the loop when booting fails
  s_have_wm_user_stop = false;
  if (BootManager::BootCore(g_filename.c_str()))
  {
    static constexpr int TIMEOUT = 10000;
    static constexpr int WAIT_STEP = 25;
    int time_waited = 0;
    // A Core::CORE_ERROR state would be helpful here.
    while (!Core::IsRunning() && time_waited < TIMEOUT && !s_have_wm_user_stop)
    {
      std::this_thread::sleep_for(std::chrono::milliseconds(WAIT_STEP));
      time_waited += WAIT_STEP;
    }
    while (Core::IsRunning())
    {
      guard.unlock();
      updateMainFrameEvent.Wait();
      guard.lock();
      Core::HostDispatchJobs();
    }
  }

  Core::Shutdown();
  UICommon::Shutdown();
  guard.unlock();

  if (surf)
  {
    ANativeWindow_release(surf);
    surf = nullptr;
  }

  // Execute the Java method.
  env->CallStaticVoidMethod(g_jni_class, g_jni_method_end);
}

#ifdef __cplusplus
}
#endif
