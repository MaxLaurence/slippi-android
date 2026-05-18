// Copyright 2014 Dolphin Emulator Project
// Licensed under GPLv2+
// Refer to the license.txt file included.

#include <algorithm>
#include <atomic>
#include <jni.h>
#include <mutex>
#ifdef ANDROID
#include <sched.h>
#include <sys/resource.h>
#endif

#include "Common/Event.h"
#include "Common/Flag.h"
#include "Common/AndroidInputDiagnostics.h"
#include "Common/Logging/Log.h"
#include "Common/Thread.h"
#include "Core/ConfigManager.h"
#include "Core/Core.h"
#include "Core/CoreTiming.h"
#include "Core/HW/SI.h"
#include "Core/HW/SystemTimers.h"

#include "InputCommon/GCAdapter.h"
#include "InputCommon/GCPadStatus.h"

// Global java_vm class
extern JavaVM* g_java_vm;

namespace GCAdapter
{
static void Setup();
static void Reset();

// Java classes
static jclass s_adapter_class;

static bool s_detected = false;
static int s_fd = 0;
static u8 s_controller_type[MAX_SI_CHANNELS] = {
    ControllerTypes::CONTROLLER_NONE, ControllerTypes::CONTROLLER_NONE,
    ControllerTypes::CONTROLLER_NONE, ControllerTypes::CONTROLLER_NONE};
static u8 s_controller_rumble[4];

// Input handling
static std::mutex s_read_mutex;
static u8 s_controller_payload[37];
static std::atomic<int> s_controller_payload_size{0};
static std::atomic<u64> s_controller_payload_sequence{0};
static std::atomic<u64> s_controller_payload_time_us{0};
static constexpr u64 STALE_PAYLOAD_NEUTRAL_US = 25000;

// Output handling
static std::mutex s_write_mutex;
static u8 s_controller_write_payload[5];
static std::atomic<int> s_controller_write_payload_size{0};

// Per-port button remap. For each of the 16 GC button bits, we store
// the OUTPUT bitmask to emit when that input bit is set. Identity by
// default (so an un-customized port behaves exactly as before). The
// Android launcher writes per-port maps via SetButtonRemap() after the
// user runs the remap wizard. customized=false short-circuits the
// remap pass in Input() so the cost of identity ports is zero.
struct BtnRemap
{
  std::atomic<uint16_t> out[16];
  std::atomic<bool> customized{false};
};
static BtnRemap s_btn_remap[MAX_SI_CHANNELS];

static uint16_t ApplyButtonRemap(int chan, uint16_t in_buttons)
{
  if (!s_btn_remap[chan].customized.load()) return in_buttons;
  uint16_t out = 0;
  for (int i = 0; i < 16; i++)
  {
    if ((in_buttons >> i) & 1u)
    {
      uint16_t mapped = s_btn_remap[chan].out[i].load();
      // 0 → "use identity for this bit" (the input was never customized
      // through this slot, or the user wiped it back to default).
      out |= mapped ? mapped : static_cast<uint16_t>(1u << i);
    }
  }
  return out;
}

static u64 NowUs()
{
  return static_cast<u64>(std::chrono::duration_cast<std::chrono::microseconds>(
                              std::chrono::steady_clock::now().time_since_epoch())
                              .count());
}

struct PadSignature
{
  u16 button = 0;
  u8 stickX = 128;
  u8 stickY = 128;
  u8 substickX = 128;
  u8 substickY = 128;
  u8 triggerLeft = 0;
  u8 triggerRight = 0;
  u8 type = 0;
};

static bool SameSignature(const PadSignature& a, const PadSignature& b)
{
  return a.button == b.button && a.stickX == b.stickX && a.stickY == b.stickY &&
         a.substickX == b.substickX && a.substickY == b.substickY &&
         a.triggerLeft == b.triggerLeft && a.triggerRight == b.triggerRight && a.type == b.type;
}

static void RecordDecodedPad(int chan, u8 type_byte, u8 b1, u8 b2, const GCPadStatus& pad,
                             bool get_origin, u64 sequence, u64 age_us)
{
  if (!Common::AndroidInputDiagnostics::IsEnabled())
    return;

  static std::mutex s_diag_mutex;
  static PadSignature s_last[4];
  static bool s_have_last[4] = {};
  static int s_count[4] = {};

  if (chan < 0 || chan >= 4)
    return;

  PadSignature sig;
  sig.button = static_cast<u16>(pad.button);
  sig.stickX = pad.stickX;
  sig.stickY = pad.stickY;
  sig.substickX = pad.substickX;
  sig.substickY = pad.substickY;
  sig.triggerLeft = pad.triggerLeft;
  sig.triggerRight = pad.triggerRight;
  sig.type = type_byte >> 4;

  bool should_log = false;
  {
    std::lock_guard<std::mutex> lock(s_diag_mutex);
    ++s_count[chan];
    should_log = !s_have_last[chan] || !SameSignature(s_last[chan], sig) ||
                 s_count[chan] == 1 || (s_count[chan] % 120) == 0;
    if (should_log)
    {
      s_last[chan] = sig;
      s_have_last[chan] = true;
    }
  }

  if (!should_log)
    return;

  Common::AndroidInputDiagnostics::Record(
      "SlippiGCAdapter",
      "wup_decoded port=%d seq=%llu age_us=%llu type_byte=0x%02x type=%u raw_buttons=0x%02x%02x "
      "button=0x%04x get_origin=%d main=(%u,%u) c=(%u,%u) triggers=(%u,%u)",
      chan, static_cast<unsigned long long>(sequence), static_cast<unsigned long long>(age_us),
      type_byte, type_byte >> 4, b1, b2, static_cast<unsigned>(pad.button), get_origin ? 1 : 0,
      static_cast<unsigned>(pad.stickX), static_cast<unsigned>(pad.stickY),
      static_cast<unsigned>(pad.substickX), static_cast<unsigned>(pad.substickY),
      static_cast<unsigned>(pad.triggerLeft), static_cast<unsigned>(pad.triggerRight));
}

static GCPadStatus NeutralPadForStalePayload(int chan, u8 type_byte, u64 sequence, u64 age_us)
{
  GCPadStatus pad = {};
  s_controller_type[chan] = type_byte >> 4;
  if (s_controller_type[chan] == ControllerTypes::CONTROLLER_NONE)
  {
    pad.button = PAD_ERR_STATUS;
    return pad;
  }

  pad.stickX = GCPadStatus::MAIN_STICK_CENTER_X;
  pad.stickY = GCPadStatus::MAIN_STICK_CENTER_Y;
  pad.substickX = GCPadStatus::C_STICK_CENTER_X;
  pad.substickY = GCPadStatus::C_STICK_CENTER_Y;
  static std::mutex s_stale_diag_mutex;
  static u64 s_last_stale_sequence[4] = {};
  static u64 s_last_stale_log_us[4] = {};
  const u64 now_us = NowUs();
  bool should_log = false;
  {
    std::lock_guard<std::mutex> lock(s_stale_diag_mutex);
    should_log = s_last_stale_sequence[chan] != sequence ||
                 now_us - s_last_stale_log_us[chan] > 500000;
    if (should_log)
    {
      s_last_stale_sequence[chan] = sequence;
      s_last_stale_log_us[chan] = now_us;
    }
  }
  if (should_log)
  {
    Common::AndroidInputDiagnostics::Record(
        "SlippiGCAdapter",
        "wup_stale_neutralized port=%d seq=%llu age_us=%llu threshold_us=%llu type_byte=0x%02x",
        chan, static_cast<unsigned long long>(sequence), static_cast<unsigned long long>(age_us),
        static_cast<unsigned long long>(STALE_PAYLOAD_NEUTRAL_US), type_byte);
  }
  return pad;
}

// Adapter running thread
static std::thread s_read_adapter_thread;
static Common::Flag s_read_adapter_thread_running;

static Common::Flag s_write_adapter_thread_running;
static Common::Event s_write_happened;

// Adapter scanning thread
static std::thread s_adapter_detect_thread;
static Common::Flag s_adapter_detect_thread_running;

static u64 s_last_init = 0;

static void ScanThreadFunc()
{
  Common::SetCurrentThreadName("GC Adapter Scanning Thread");
  NOTICE_LOG(SERIALINTERFACE, "GC Adapter scanning thread started");

  JNIEnv* env;
  g_java_vm->AttachCurrentThread(&env, NULL);

  jmethodID queryadapter_func = env->GetStaticMethodID(s_adapter_class, "QueryAdapter", "()Z");

  while (s_adapter_detect_thread_running.IsSet())
  {
    if (!s_detected && UseAdapter() &&
        env->CallStaticBooleanMethod(s_adapter_class, queryadapter_func))
      Setup();
    Common::SleepCurrentThread(1000);
  }
  g_java_vm->DetachCurrentThread();

  NOTICE_LOG(SERIALINTERFACE, "GC Adapter scanning thread stopped");
}

static void Write()
{
  Common::SetCurrentThreadName("GC Adapter Write Thread");
  NOTICE_LOG(SERIALINTERFACE, "GC Adapter write thread started");

  JNIEnv* env;
  g_java_vm->AttachCurrentThread(&env, NULL);
  jmethodID output_func = env->GetStaticMethodID(s_adapter_class, "Output", "([B)I");

  while (s_write_adapter_thread_running.IsSet())
  {
    s_write_happened.Wait();
    int write_size = s_controller_write_payload_size.load();
    if (write_size)
    {
      jbyteArray jrumble_array = env->NewByteArray(5);
      jbyte* jrumble = env->GetByteArrayElements(jrumble_array, NULL);

      {
        std::lock_guard<std::mutex> lk(s_write_mutex);
        memcpy(jrumble, s_controller_write_payload, write_size);
      }

      env->ReleaseByteArrayElements(jrumble_array, jrumble, 0);
      int size = env->CallStaticIntMethod(s_adapter_class, output_func, jrumble_array);
      // Netplay sends invalid data which results in size = 0x00.  Ignore it.
      if (size != write_size && size != 0x00)
      {
        ERROR_LOG(SERIALINTERFACE, "error writing rumble (size: %d)", size);
        Reset();
      }
    }

    Common::YieldCPU();
  }

  g_java_vm->DetachCurrentThread();

  NOTICE_LOG(SERIALINTERFACE, "GC Adapter write thread stopped");
}

static void Read()
{
  Common::SetCurrentThreadName("GC Adapter Read Thread");
  NOTICE_LOG(SERIALINTERFACE, "GC Adapter read thread started");

#ifdef ANDROID
  // Match the emu-thread treatment: bump scheduling priority and pin to the
  // SoC's big cores. Without this the Android scheduler can park this
  // thread on a Cortex-A510 efficiency core, where the JNI hop + USB
  // request_wait loop takes long enough to fall behind the WUP-028's 1 ms
  // poll cadence — which surfaces as input frames appearing to drop.
  setpriority(PRIO_PROCESS, 0, -8);
  cpu_set_t mask;
  CPU_ZERO(&mask);
  if (sched_getaffinity(0, sizeof(mask), &mask) == 0)
  {
    int total = CPU_COUNT(&mask);
    cpu_set_t pinned;
    CPU_ZERO(&pinned);
    int kept = 0, want = total / 2;
    for (int cpu = CPU_SETSIZE - 1; cpu >= 0 && kept < want; --cpu)
      if (CPU_ISSET(cpu, &mask)) { CPU_SET(cpu, &pinned); ++kept; }
    if (kept > 0) sched_setaffinity(0, sizeof(pinned), &pinned);
  }
#endif

  bool first_read = true;
  JNIEnv* env;
  g_java_vm->AttachCurrentThread(&env, NULL);

  jfieldID payload_field = env->GetStaticFieldID(s_adapter_class, "controller_payload", "[B");
  jobject payload_object = env->GetStaticObjectField(s_adapter_class, payload_field);
  jbyteArray* java_controller_payload = reinterpret_cast<jbyteArray*>(&payload_object);

  // Get function pointers
  jmethodID getfd_func = env->GetStaticMethodID(s_adapter_class, "GetFD", "()I");
  jmethodID input_func = env->GetStaticMethodID(s_adapter_class, "Input", "()I");
  jmethodID openadapter_func = env->GetStaticMethodID(s_adapter_class, "OpenAdapter", "()Z");

  bool connected = env->CallStaticBooleanMethod(s_adapter_class, openadapter_func);

  if (connected)
  {
    s_write_adapter_thread_running.Set(true);
    std::thread write_adapter_thread(Write);

    // Reset rumble once on initial reading
    ResetRumble();

    while (s_read_adapter_thread_running.IsSet())
    {
      int read_size = env->CallStaticIntMethod(s_adapter_class, input_func);

      jbyte* java_data = env->GetByteArrayElements(*java_controller_payload, nullptr);
      {
        std::lock_guard<std::mutex> lk(s_read_mutex);
        // Upstream wrote `memcpy(s_controller_payload, java_data, 0x37)` here,
        // but s_controller_payload is u8[37] (and the GC adapter only sends
        // 37 bytes per poll). 0x37 == 55 — clang/Bionic _FORTIFY_SOURCE
        // catches that as a write past the end and SIGABRTs on launch.
        // The 0x37 was almost certainly someone typing hex when they meant
        // decimal 37.
        memcpy(s_controller_payload, java_data, sizeof(s_controller_payload));
        s_controller_payload_size.store(read_size);
        s_controller_payload_time_us.store(NowUs());
        s_controller_payload_sequence.fetch_add(1);
      }
      env->ReleaseByteArrayElements(*java_controller_payload, java_data, 0);

      if (first_read)
      {
        first_read = false;
        s_fd = env->CallStaticIntMethod(s_adapter_class, getfd_func);
      }

      Common::YieldCPU();
    }

    // Terminate the write thread on leaving
    if (s_write_adapter_thread_running.TestAndClear())
    {
      s_controller_write_payload_size.store(0);
      s_write_happened.Set();  // Kick the waiting event
      write_adapter_thread.join();
    }
  }

  s_fd = 0;
  s_detected = false;

  g_java_vm->DetachCurrentThread();

  NOTICE_LOG(SERIALINTERFACE, "GC Adapter read thread stopped");
}

void Init()
{
  if (s_fd)
    return;

  if (Core::GetState() != Core::CORE_UNINITIALIZED)
  {
    if ((CoreTiming::GetTicks() - s_last_init) < SystemTimers::GetTicksPerSecond())
      return;

    s_last_init = CoreTiming::GetTicks();
  }

  JNIEnv* env;
  g_java_vm->AttachCurrentThread(&env, NULL);

  jclass adapter_class = env->FindClass("org/dolphinemu/dolphinemu/utils/Java_GCAdapter");
  s_adapter_class = reinterpret_cast<jclass>(env->NewGlobalRef(adapter_class));

  if (UseAdapter())
    StartScanThread();
}

static void Setup()
{
  s_fd = 0;
  s_detected = true;

  // Make sure the thread isn't in the middle of shutting down while starting a new one
  if (s_read_adapter_thread_running.TestAndClear())
    s_read_adapter_thread.join();

  s_read_adapter_thread_running.Set(true);
  s_read_adapter_thread = std::thread(Read);
}

static void Reset()
{
  if (!s_detected)
    return;

  if (s_read_adapter_thread_running.TestAndClear())
    s_read_adapter_thread.join();

  for (int i = 0; i < MAX_SI_CHANNELS; i++)
    s_controller_type[i] = ControllerTypes::CONTROLLER_NONE;

  s_detected = false;
  s_fd = 0;
  NOTICE_LOG(SERIALINTERFACE, "GC Adapter detached");
}

void Shutdown()
{
  StopScanThread();
  Reset();
}

void StartScanThread()
{
  if (s_adapter_detect_thread_running.IsSet())
    return;

  s_adapter_detect_thread_running.Set(true);
  s_adapter_detect_thread = std::thread(ScanThreadFunc);
}

void StopScanThread()
{
  if (s_adapter_detect_thread_running.TestAndClear())
    s_adapter_detect_thread.join();
}

// Slippi added a high-resolution timestamp out-param so the netplay layer can
// correlate pad reads with sample times. The Android USB path doesn't have
// real time-of-poll info available here, so just stamp `now()` if requested.
bool IsReadingAtReducedRate()
{
  return false;
}

double ReadRate()
{
  return 0.0;
}

GCPadStatus Input(int chan, std::chrono::high_resolution_clock::time_point* tp)
{
  if (tp)
    *tp = std::chrono::high_resolution_clock::now();
  if (!UseAdapter() || !s_detected || !s_fd)
    return {};

  int payload_size = 0;
  u64 payload_sequence = 0;
  u64 payload_time_us = 0;
  u8 controller_payload_copy[37];

  {
    std::lock_guard<std::mutex> lk(s_read_mutex);
    std::copy(std::begin(s_controller_payload), std::end(s_controller_payload),
              std::begin(controller_payload_copy));
    payload_size = s_controller_payload_size.load();
    payload_sequence = s_controller_payload_sequence.load();
    payload_time_us = s_controller_payload_time_us.load();
  }
  const u64 now_us = NowUs();
  const u64 age_us = payload_time_us > 0 && now_us > payload_time_us ? now_us - payload_time_us : 0;

  GCPadStatus pad = {};
  if (payload_size != sizeof(controller_payload_copy))
  {
    ERROR_LOG(SERIALINTERFACE, "error reading payload (size: %d, type: %02x)", payload_size,
              controller_payload_copy[0]);
    Common::AndroidInputDiagnostics::Record(
        "SlippiGCAdapter", "wup_payload_invalid chan=%d size=%d type=0x%02x seq=%llu age_us=%llu",
        chan, payload_size, controller_payload_copy[0],
        static_cast<unsigned long long>(payload_sequence),
        static_cast<unsigned long long>(age_us));
    Reset();
  }
  else if (payload_time_us > 0 && age_us > STALE_PAYLOAD_NEUTRAL_US)
  {
    return NeutralPadForStalePayload(chan, controller_payload_copy[1 + (9 * chan)],
                                     payload_sequence, age_us);
  }
  else
  {
    bool get_origin = false;
    u8 type = controller_payload_copy[1 + (9 * chan)] >> 4;
    if (type != ControllerTypes::CONTROLLER_NONE &&
        s_controller_type[chan] == ControllerTypes::CONTROLLER_NONE)
    {
      ERROR_LOG(SERIALINTERFACE, "New device connected to Port %d of Type: %02x", chan + 1,
                controller_payload_copy[1 + (9 * chan)]);
      get_origin = true;
    }

    s_controller_type[chan] = type;

    if (s_controller_type[chan] != ControllerTypes::CONTROLLER_NONE)
    {
      u8 b1 = controller_payload_copy[1 + (9 * chan) + 1];
      u8 b2 = controller_payload_copy[1 + (9 * chan) + 2];

      if (b1 & (1 << 0))
        pad.button |= PAD_BUTTON_A;
      if (b1 & (1 << 1))
        pad.button |= PAD_BUTTON_B;
      if (b1 & (1 << 2))
        pad.button |= PAD_BUTTON_X;
      if (b1 & (1 << 3))
        pad.button |= PAD_BUTTON_Y;

      if (b1 & (1 << 4))
        pad.button |= PAD_BUTTON_LEFT;
      if (b1 & (1 << 5))
        pad.button |= PAD_BUTTON_RIGHT;
      if (b1 & (1 << 6))
        pad.button |= PAD_BUTTON_DOWN;
      if (b1 & (1 << 7))
        pad.button |= PAD_BUTTON_UP;

      if (b2 & (1 << 0))
        pad.button |= PAD_BUTTON_START;
      if (b2 & (1 << 1))
        pad.button |= PAD_TRIGGER_Z;
      if (b2 & (1 << 2))
        pad.button |= PAD_TRIGGER_R;
      if (b2 & (1 << 3))
        pad.button |= PAD_TRIGGER_L;

      if (get_origin)
        pad.button |= PAD_GET_ORIGIN;

      pad.stickX = controller_payload_copy[1 + (9 * chan) + 3];
      pad.stickY = controller_payload_copy[1 + (9 * chan) + 4];
      pad.substickX = controller_payload_copy[1 + (9 * chan) + 5];
      pad.substickY = controller_payload_copy[1 + (9 * chan) + 6];
      pad.triggerLeft = controller_payload_copy[1 + (9 * chan) + 7];
      pad.triggerRight = controller_payload_copy[1 + (9 * chan) + 8];
      // GC adapter stick bytes are already produced by real GameCube
      // controllers. Do not apply the app's secondary stick calibration here.
      // Apply per-port button remap. PAD_GET_ORIGIN is preserved
      // verbatim because Melee's bootup origin-poll uses it as a
      // signal, not a real button.
      const uint16_t preserve = pad.button & PAD_GET_ORIGIN;
      pad.button = ApplyButtonRemap(chan, static_cast<uint16_t>(pad.button & ~PAD_GET_ORIGIN))
                   | preserve;
      RecordDecodedPad(chan, controller_payload_copy[1 + (9 * chan)], b1, b2, pad, get_origin,
                       payload_sequence, age_us);
    }
    else
    {
      pad.button = PAD_ERR_STATUS;
    }
  }

  return pad;
}

void Output(int chan, u8 rumble_command)
{
  if (!UseAdapter() || !s_detected || !s_fd)
    return;

  // Skip over rumble commands if it has not changed or the controller is wireless
  if (rumble_command != s_controller_rumble[chan] &&
      s_controller_type[chan] != ControllerTypes::CONTROLLER_WIRELESS)
  {
    s_controller_rumble[chan] = rumble_command;
    unsigned char rumble[5] = {0x11, s_controller_rumble[0], s_controller_rumble[1],
                               s_controller_rumble[2], s_controller_rumble[3]};
    {
      std::lock_guard<std::mutex> lk(s_write_mutex);
      memcpy(s_controller_write_payload, rumble, 5);
      s_controller_write_payload_size.store(5);
    }
    s_write_happened.Set();
  }
}

bool IsDetected()
{
  return s_detected;
}
bool IsDriverDetected()
{
  return true;
}
bool DeviceConnected(int chan)
{
  return s_controller_type[chan] != ControllerTypes::CONTROLLER_NONE;
}

bool UseAdapter()
{
  return SConfig::GetInstance().m_SIDevice[0] == SIDEVICE_WIIU_ADAPTER ||
         SConfig::GetInstance().m_SIDevice[1] == SIDEVICE_WIIU_ADAPTER ||
         SConfig::GetInstance().m_SIDevice[2] == SIDEVICE_WIIU_ADAPTER ||
         SConfig::GetInstance().m_SIDevice[3] == SIDEVICE_WIIU_ADAPTER;
}

void ResetRumble()
{
  unsigned char rumble[5] = {0x11, 0, 0, 0, 0};
  {
    std::lock_guard<std::mutex> lk(s_read_mutex);
    memcpy(s_controller_write_payload, rumble, 5);
    s_controller_write_payload_size.store(5);
  }
  s_write_happened.Set();
}

void SetAdapterCallback(std::function<void(void)> func)
{
}

void SetStickCalibration(int, int, float, float, float, float, float, float, float, float)
{
  // Kept as a JNI-compatible no-op. Physical GC adapter sticks should pass
  // through unchanged; only Android/HID controller paths use StickCalibration.
}

bool GetLatestRawStick(int chan, int stick_idx, u8* out_x, u8* out_y)
{
  if (chan < 0 || chan >= MAX_SI_CHANNELS) return false;
  if (stick_idx < 0 || stick_idx >= 2) return false;
  if (!out_x || !out_y) return false;
  u8 payload_local[37];
  int payload_size_local;
  {
    std::lock_guard<std::mutex> lk(s_read_mutex);
    if (s_controller_payload_size.load() != sizeof(s_controller_payload)) return false;
    std::memcpy(payload_local, s_controller_payload, sizeof(payload_local));
    payload_size_local = s_controller_payload_size.load();
  }
  (void)payload_size_local;
  // Same offsets as Input(): byte 1 + 9*chan + {3,4} for main stick X/Y,
  // +{5,6} for C-stick X/Y. byte 0 is a status byte.
  int base = 1 + 9 * chan + (stick_idx == 0 ? 3 : 5);
  *out_x = payload_local[base + 0];
  *out_y = payload_local[base + 1];
  return true;
}

void SetButtonRemap(int chan, uint16_t source_bit, uint16_t target_bit)
{
  if (chan < 0 || chan >= MAX_SI_CHANNELS) return;
  // source_bit must be a single bit (one of the PAD_BUTTON_* / PAD_TRIGGER_*
  // bitmask constants). Find its index, then store the target mask at
  // that slot. target_bit may be any combination (including 0 to mute
  // that source bit entirely).
  int idx = -1;
  for (int i = 0; i < 16; i++)
    if (source_bit == static_cast<uint16_t>(1u << i)) { idx = i; break; }
  if (idx < 0) return;
  // First customization on this port: pre-populate the table with
  // identity values so un-touched bits keep their normal behavior.
  if (!s_btn_remap[chan].customized.load())
  {
    for (int i = 0; i < 16; i++)
      s_btn_remap[chan].out[i].store(static_cast<uint16_t>(1u << i));
  }
  s_btn_remap[chan].out[idx].store(target_bit);
  s_btn_remap[chan].customized.store(true);
}

void ClearButtonRemap(int chan)
{
  if (chan < 0 || chan >= MAX_SI_CHANNELS) return;
  s_btn_remap[chan].customized.store(false);
}

uint16_t GetLatestRawButtons(int chan)
{
  if (chan < 0 || chan >= MAX_SI_CHANNELS) return 0;
  u8 payload_local[37];
  {
    std::lock_guard<std::mutex> lk(s_read_mutex);
    if (s_controller_payload_size.load() != sizeof(s_controller_payload)) return 0;
    std::memcpy(payload_local, s_controller_payload, sizeof(payload_local));
  }
  // byte 0 is a status byte; each channel occupies bytes 1+9*chan .. +8.
  // controller type lives in the upper nibble of byte 1+9*chan.
  if (((payload_local[1 + 9 * chan] >> 4) & 0xF) == 0) return 0;
  u8 b1 = payload_local[1 + 9 * chan + 1];
  u8 b2 = payload_local[1 + 9 * chan + 2];
  uint16_t buttons = 0;
  if (b1 & (1 << 0)) buttons |= PAD_BUTTON_A;
  if (b1 & (1 << 1)) buttons |= PAD_BUTTON_B;
  if (b1 & (1 << 2)) buttons |= PAD_BUTTON_X;
  if (b1 & (1 << 3)) buttons |= PAD_BUTTON_Y;
  if (b1 & (1 << 4)) buttons |= PAD_BUTTON_LEFT;
  if (b1 & (1 << 5)) buttons |= PAD_BUTTON_RIGHT;
  if (b1 & (1 << 6)) buttons |= PAD_BUTTON_DOWN;
  if (b1 & (1 << 7)) buttons |= PAD_BUTTON_UP;
  if (b2 & (1 << 0)) buttons |= PAD_BUTTON_START;
  if (b2 & (1 << 1)) buttons |= PAD_TRIGGER_Z;
  if (b2 & (1 << 2)) buttons |= PAD_TRIGGER_R;
  if (b2 & (1 << 3)) buttons |= PAD_TRIGGER_L;
  return buttons;
}

}  // end of namespace GCAdapter
