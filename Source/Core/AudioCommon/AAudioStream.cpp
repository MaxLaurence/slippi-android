// Copyright 2026 Dolphin Emulator Project
// Licensed under GPLv2+
// Refer to the license.txt file included.

#ifdef ANDROID

#include "AudioCommon/AAudioStream.h"

#include <aaudio/AAudio.h>
#include <android/log.h>
#include <algorithm>
#include <cstring>

#include "AudioCommon/Mixer.h"
#include "Common/CommonTypes.h"
#include "Core/ConfigManager.h"

namespace
{
constexpr const char* TAG = "SlippiAudio";
constexpr int kMinBufferBursts = 1;
constexpr int kMaxBufferBursts = 12;

int ConfiguredBufferBursts()
{
  return std::min(std::max(SConfig::GetInstance().iAndroidAudioBufferBursts, kMinBufferBursts),
                  kMaxBufferBursts);
}

aaudio_data_callback_result_t DataCallback(AAudioStream* stream, void* user_data,
                                           void* audio_data, int32_t num_frames)
{
  AAudioSoundStream* self = static_cast<AAudioSoundStream*>(user_data);
  CMixer* mixer = self ? self->GetMixer() : nullptr;
  if (!mixer)
  {
    std::memset(audio_data, 0, static_cast<size_t>(num_frames) * 2 * sizeof(s16));
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
  }

  mixer->Mix(static_cast<s16*>(audio_data), static_cast<unsigned int>(num_frames));
  return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void ErrorCallback(AAudioStream* stream, void* user_data, aaudio_result_t error)
{
  __android_log_print(ANDROID_LOG_WARN, TAG, "AAudio stream error: %s",
                      AAudio_convertResultToText(error));
}

bool OpenStream(AAudioStreamBuilder* builder, AAudioStream** stream, aaudio_sharing_mode_t mode)
{
  AAudioStreamBuilder_setSharingMode(builder, mode);
  aaudio_result_t result = AAudioStreamBuilder_openStream(builder, stream);
  if (result == AAUDIO_OK)
    return true;

  __android_log_print(ANDROID_LOG_WARN, TAG, "AAudio open %s failed: %s",
                      mode == AAUDIO_SHARING_MODE_EXCLUSIVE ? "exclusive" : "shared",
                      AAudio_convertResultToText(result));
  return false;
}
}  // namespace

bool AAudioSoundStream::Start()
{
  AAudioStreamBuilder* builder = nullptr;
  aaudio_result_t result = AAudio_createStreamBuilder(&builder);
  if (result != AAUDIO_OK || !builder)
  {
    __android_log_print(ANDROID_LOG_WARN, TAG, "AAudio builder failed: %s",
                        AAudio_convertResultToText(result));
    return false;
  }

  AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
  AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
  AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
  AAudioStreamBuilder_setChannelCount(builder, 2);
  AAudioStreamBuilder_setSampleRate(builder, m_mixer->GetSampleRate());
  AAudioStreamBuilder_setDataCallback(builder, DataCallback, this);
  AAudioStreamBuilder_setErrorCallback(builder, ErrorCallback, this);
#if __ANDROID_API__ >= 28
  AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_GAME);
  AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);
#endif

  AAudioStream* stream = nullptr;
  bool opened = OpenStream(builder, &stream, AAUDIO_SHARING_MODE_EXCLUSIVE);
  if (!opened)
    opened = OpenStream(builder, &stream, AAUDIO_SHARING_MODE_SHARED);

  AAudioStreamBuilder_delete(builder);
  if (!opened || !stream)
    return false;

  const int32_t frames_per_burst = AAudioStream_getFramesPerBurst(stream);
  if (frames_per_burst > 0)
  {
    const int bursts = ConfiguredBufferBursts();
    const int32_t target_frames = frames_per_burst * bursts;
    const int32_t actual_frames = AAudioStream_setBufferSizeInFrames(stream, target_frames);
    __android_log_print(ANDROID_LOG_INFO, TAG,
                        "AAudio burst=%d bufferBursts=%d targetBuffer=%d actualBuffer=%d "
                        "capacity=%d sampleRate=%d sharing=%d",
                        frames_per_burst, bursts, target_frames, actual_frames,
                        AAudioStream_getBufferCapacityInFrames(stream),
                        AAudioStream_getSampleRate(stream), AAudioStream_getSharingMode(stream));
  }

  result = AAudioStream_requestStart(stream);
  if (result != AAUDIO_OK)
  {
    __android_log_print(ANDROID_LOG_WARN, TAG, "AAudio start failed: %s",
                        AAudio_convertResultToText(result));
    AAudioStream_close(stream);
    return false;
  }

  m_stream = stream;
  return true;
}

void AAudioSoundStream::Stop()
{
  AAudioStream* stream = static_cast<AAudioStream*>(m_stream);
  m_stream = nullptr;
  if (!stream)
    return;

  AAudioStream_requestStop(stream);
  AAudioStream_close(stream);
}

#endif
