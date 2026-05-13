// Copyright 2026 Dolphin Emulator Project
// Licensed under GPLv2+
// Refer to the license.txt file included.

#pragma once

#include "AudioCommon/SoundStream.h"

#ifdef ANDROID

#include <atomic>
#include <memory>

#include <oboe/Oboe.h>

class OboeSoundStream final : public SoundStream, public oboe::AudioStreamDataCallback,
                              public oboe::AudioStreamErrorCallback
{
public:
  bool Start() override;
  void Stop() override;
  oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audio_data,
                                        int32_t num_frames) override;
  void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

  static bool isValid()
  {
    return true;
  }

private:
  bool OpenAndStartStream(oboe::SharingMode sharing_mode);
  void ConfigureBuffer();

  std::shared_ptr<oboe::AudioStream> m_stream;
  std::atomic<int32_t> m_last_xruns{0};
};

#else

class OboeSoundStream final : public SoundStream
{
public:
  static bool isValid()
  {
    return false;
  }
};

#endif
