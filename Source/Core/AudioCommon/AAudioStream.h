// Copyright 2026 Dolphin Emulator Project
// Licensed under GPLv2+
// Refer to the license.txt file included.

#pragma once

#include "AudioCommon/SoundStream.h"

class AAudioSoundStream final : public SoundStream
{
#ifdef ANDROID
public:
  bool Start() override;
  void Stop() override;
  static bool isValid()
  {
    return true;
  }

private:
  void* m_stream = nullptr;
#else
public:
  static bool isValid()
  {
    return false;
  }
#endif
};
