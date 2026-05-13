// Copyright 2026 Slippi
// Licensed under GPLv2+

#include <android/log.h>
#include <jni.h>

#include <array>
#include <string>

namespace
{
std::string GetJString(JNIEnv* env, jstring string)
{
  if (!string)
    return {};
  const char* chars = env->GetStringUTFChars(string, nullptr);
  std::string result = chars ? chars : "";
  if (chars)
    env->ReleaseStringUTFChars(string, chars);
  return result;
}
}  // namespace

extern "C" int xd3_main_cmdline(int argc, char** argv);

extern "C" JNIEXPORT jint JNICALL
Java_org_dolphinemu_dolphinemu_NativeLibrary_ApplyXdeltaPatch(JNIEnv* env, jobject,
                                                              jstring jSource,
                                                              jstring jPatch,
                                                              jstring jOutput)
{
  const std::string source = GetJString(env, jSource);
  const std::string patch = GetJString(env, jPatch);
  const std::string output = GetJString(env, jOutput);
  if (source.empty() || patch.empty() || output.empty())
    return -1;

  std::array<std::string, 7> args = {
      "xdelta3", "-f", "-d", "-s", source, patch, output,
  };
  std::array<char*, 7> argv = {};
  for (size_t i = 0; i < args.size(); ++i)
    argv[i] = &args[i][0];

  __android_log_print(ANDROID_LOG_INFO, "SlippiTraining",
                      "Applying TM-CE xdelta patch: source=%s patch=%s output=%s",
                      source.c_str(), patch.c_str(), output.c_str());
  const int result = xd3_main_cmdline(static_cast<int>(argv.size()), argv.data());
  if (result != 0)
  {
    __android_log_print(ANDROID_LOG_WARN, "SlippiTraining",
                        "xdelta3 patch failed with code %d", result);
  }
  return result;
}
