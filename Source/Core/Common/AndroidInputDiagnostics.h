// Copyright 2026 Dolphin Emulator Project
// Licensed under GPLv2+
// Refer to the license.txt file included.

#pragma once

#include <string>

namespace Common
{
namespace AndroidInputDiagnostics
{
void SetEnabled(bool enabled);
bool IsEnabled();
void SetLogcatEnabled(bool enabled);
void Record(const char* tag, const char* format, ...);
std::string Dump();
void Clear();
}  // namespace AndroidInputDiagnostics
}  // namespace Common
