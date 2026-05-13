// Copyright 2026 Dolphin Emulator Project
// Licensed under GPLv2+
// Refer to the license.txt file included.

#ifdef ANDROID

#include "AudioCommon/OboeStream.h"

#include <algorithm>
#include <cstring>

#include <android/log.h>

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
}  // namespace

bool OboeSoundStream::Start()
{
  return OpenAndStartStream(oboe::SharingMode::Exclusive) ||
         OpenAndStartStream(oboe::SharingMode::Shared);
}

bool OboeSoundStream::OpenAndStartStream(oboe::SharingMode sharing_mode)
{
  oboe::AudioStreamBuilder builder;
  builder.setDirection(oboe::Direction::Output)
      ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
      ->setSharingMode(sharing_mode)
      ->setFormat(oboe::AudioFormat::I16)
      ->setChannelCount(oboe::ChannelCount::Stereo)
      ->setSampleRate(m_mixer->GetSampleRate())
      ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
      ->setUsage(oboe::Usage::Game)
      ->setContentType(oboe::ContentType::Music)
      ->setDataCallback(this)
      ->setErrorCallback(this);

  std::shared_ptr<oboe::AudioStream> stream;
  oboe::Result result = builder.openStream(stream);
  if (result != oboe::Result::OK || !stream)
  {
    __android_log_print(ANDROID_LOG_WARN, TAG, "Oboe open %s failed: %s",
                        sharing_mode == oboe::SharingMode::Exclusive ? "exclusive" : "shared",
                        oboe::convertToText(result));
    return false;
  }

  m_stream = std::move(stream);
  ConfigureBuffer();

  oboe::Result start_result = m_stream->requestStart();
  if (start_result != oboe::Result::OK)
  {
    __android_log_print(ANDROID_LOG_WARN, TAG, "Oboe start %s failed: %s",
                        sharing_mode == oboe::SharingMode::Exclusive ? "exclusive" : "shared",
                        oboe::convertToText(start_result));
    Stop();
    return false;
  }

  return true;
}

void OboeSoundStream::ConfigureBuffer()
{
  const int32_t frames_per_burst = m_stream->getFramesPerBurst();
  const int32_t capacity = m_stream->getBufferCapacityInFrames();
  int bursts = ConfiguredBufferBursts();
  int32_t target_frames = frames_per_burst > 0 ? frames_per_burst * bursts : capacity;
  if (capacity > 0)
    target_frames = std::min(target_frames, capacity);

  int32_t actual_frames = m_stream->getBufferSizeInFrames();
  oboe::ResultWithValue<int32_t> buffer_result = m_stream->setBufferSizeInFrames(target_frames);
  if (buffer_result)
    actual_frames = buffer_result.value();

  oboe::ResultWithValue<int32_t> xrun_result = m_stream->getXRunCount();
  if (xrun_result)
    m_last_xruns.store(xrun_result.value());

  __android_log_print(
      ANDROID_LOG_INFO, TAG,
      "Oboe api=%s sharing=%s burst=%d bufferBursts=%d targetBuffer=%d actualBuffer=%d "
      "capacity=%d sampleRate=%d xruns=%d",
      oboe::convertToText(m_stream->getAudioApi()), oboe::convertToText(m_stream->getSharingMode()),
      frames_per_burst, bursts, target_frames, actual_frames, capacity, m_stream->getSampleRate(),
      m_last_xruns.load());
}

oboe::DataCallbackResult OboeSoundStream::onAudioReady(oboe::AudioStream* stream, void* audio_data,
                                                       int32_t num_frames)
{
  CMixer* mixer = GetMixer();
  if (!mixer)
  {
    std::memset(audio_data, 0, static_cast<size_t>(num_frames) * 2 * sizeof(s16));
    return oboe::DataCallbackResult::Continue;
  }

  mixer->Mix(static_cast<s16*>(audio_data), static_cast<unsigned int>(num_frames));

  oboe::ResultWithValue<int32_t> xrun_result = stream->getXRunCount();
  if (xrun_result && xrun_result.value() != m_last_xruns.load())
  {
    __android_log_print(ANDROID_LOG_WARN, TAG, "Oboe xruns=%d", xrun_result.value());
    m_last_xruns.store(xrun_result.value());
  }

  return oboe::DataCallbackResult::Continue;
}

void OboeSoundStream::onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error)
{
  __android_log_print(ANDROID_LOG_WARN, TAG, "Oboe stream error after close: %s",
                      oboe::convertToText(error));
}

void OboeSoundStream::Stop()
{
  if (!m_stream)
    return;

  m_stream->requestStop();
  m_stream->close();
  m_stream.reset();
}

#endif
