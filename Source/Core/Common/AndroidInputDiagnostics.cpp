// Copyright 2026 Dolphin Emulator Project
// Licensed under GPLv2+
// Refer to the license.txt file included.

#include "Common/AndroidInputDiagnostics.h"

#include <array>
#include <cstdarg>
#include <cstdio>
#include <mutex>
#include <sstream>
#include <string>

#ifdef ANDROID
#include <android/log.h>
#endif

namespace Common
{
namespace AndroidInputDiagnostics
{
namespace
{
constexpr size_t RING_SIZE = 512;
constexpr size_t MAX_LINE_BYTES = 1024;

std::mutex s_mutex;
std::array<std::string, RING_SIZE> s_lines;
size_t s_next_line = 0;
size_t s_line_count = 0;

void PushLine(const char* tag, const char* message)
{
  if (!message || message[0] == '\0')
    return;

#ifdef ANDROID
  __android_log_print(ANDROID_LOG_INFO, tag && tag[0] ? tag : "SlippiInputDiag", "%s", message);
#endif

  std::lock_guard<std::mutex> lock(s_mutex);
  s_lines[s_next_line] = std::string(tag && tag[0] ? tag : "SlippiInputDiag") + ": " + message;
  s_next_line = (s_next_line + 1) % RING_SIZE;
  if (s_line_count < RING_SIZE)
    ++s_line_count;
}
}  // namespace

void Record(const char* tag, const char* format, ...)
{
  if (!format)
    return;

  char line[MAX_LINE_BYTES];
  va_list args;
  va_start(args, format);
  vsnprintf(line, sizeof(line), format, args);
  va_end(args);
  line[sizeof(line) - 1] = '\0';

  PushLine(tag, line);
}

std::string Dump()
{
  std::lock_guard<std::mutex> lock(s_mutex);
  if (s_line_count == 0)
    return {};

  std::ostringstream out;
  const size_t first = s_line_count == RING_SIZE ? s_next_line : 0;
  for (size_t i = 0; i < s_line_count; ++i)
  {
    const size_t idx = (first + i) % RING_SIZE;
    out << s_lines[idx] << '\n';
  }
  return out.str();
}

void Clear()
{
  std::lock_guard<std::mutex> lock(s_mutex);
  for (std::string& line : s_lines)
    line.clear();
  s_next_line = 0;
  s_line_count = 0;
}
}  // namespace AndroidInputDiagnostics
}  // namespace Common
