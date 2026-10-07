#!/usr/bin/env python3
"""Run the real adapter Output functions against a fake USB output queue.

No JNI/device is required: compile the unmodified function bodies from both
cores with only their surrounding state stubbed. Mainline also runs its real
RefreshConfig function against fake per-port config values. This checks emulator rumble
preferences, not Melee's in-game setting, Slippi routing, or physical hardware.
Pass --mainline-source to check Gradle's patched mainline tree as well.

Adapter output parity: Ishiiruka Android omitted the per-port AdapterRumble guard.
Mainline already enforces it through s_config_rumble_enabled, so it needs no
output-guard change; run the same disabled/enabled output cases against both.
This does not reproduce issue #17's reported mainline online behavior.
"""

import argparse
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[2]


def source_function(path, signature):
    source = path.read_text()
    start = source.index(signature + "\n{")
    end = source.index("\n}\n", start) + 2
    return source[start:end]


PRELUDE = r"""
#include <array>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#define GCADAPTER_USE_LIBUSB_IMPLEMENTATION 0
#define GCADAPTER_USE_ANDROID_IMPLEMENTATION 1
using u8 = uint8_t;
constexpr int CONTROLLER_OUTPUT_RUMBLE_PAYLOAD_SIZE = 5;
enum ControllerTypes { CONTROLLER_NONE, CONTROLLER_WIRED, CONTROLLER_WIRELESS };
enum class ControllerType { None, Wired, Wireless };
struct PortState { ControllerType controller_type = ControllerType::Wired; };
static PortState s_port_states[4];
static u8 s_controller_type[4] = {1, 1, 1, 1};
static u8 s_controller_rumble[4] = {};
static bool s_config_rumble_enabled[4] = {};
struct SConfig {
  bool m_AdapterRumble[4] = {};
  static SConfig& GetInstance() { static SConfig config; return config; }
};
static bool wanted = true, s_detected = true;
static int s_fd = 1;
bool UseAdapter() { return wanted; }
static std::mutex s_write_mutex;
static std::atomic<int> s_controller_write_payload_size{0};
struct Event { int count = 0; void Set() { ++count; } } s_write_happened;
"""

MAINLINE_CONFIG = r"""
namespace SerialInterface {
constexpr int MAX_SI_CHANNELS = 4;
enum class SIDevices { SIDEVICE_WIIU_ADAPTER };
}
namespace Config {
struct RumbleInfo { int port; };
struct DeviceInfo { int port; };
static bool rumble[4] = {};
RumbleInfo GetInfoForAdapterRumble(int i) { return {i}; }
DeviceInfo GetInfoForSIDevice(int i) { return {i}; }
bool Get(RumbleInfo info) { return rumble[info.port]; }
SerialInterface::SIDevices Get(DeviceInfo) {
  return SerialInterface::SIDevices::SIDEVICE_WIIU_ADAPTER;
}
}
static bool& s_is_adapter_wanted = wanted;
"""

CASES = r"""
int main() {
  int failures = 0;
  auto check = [&](bool ok, const char* label, int port) {
    if (!ok) { std::fprintf(stderr, "FAIL port %d: %s\n", port, label); ++failures; }
  };
  for (int port = 0; port < 4; ++port) {
    // Deliberately alternate preferences so using another port's flag fails.
    for (int i = 0; i < 4; ++i) {
      s_controller_rumble[i] = 0;
      ConfigureRumble(i, i != port);
    }
    s_write_happened.count = 0;
    s_controller_write_payload_size = 0;
    Output(port, 1);
    check(s_write_happened.count == 0 && s_controller_write_payload_size == 0 &&
          s_controller_rumble[port] == 0, "disabled rumble queued a motor-on command", port);

    s_controller_rumble[port] = 0;
    s_write_happened.count = 0;
    ConfigureRumble(port, true);
    Output(port, 1);
    check(s_write_happened.count == 1 && s_controller_write_payload_size == 5 &&
          s_controller_write_payload[0] == 0x11 && s_controller_write_payload[port + 1] == 1,
          "enabled rumble did not queue motor-on", port);
    for (int i = 0; i < 4; ++i)
      check(s_controller_write_payload[i + 1] == (i == port), "wrong USB port", port);
    Output(port, 1);
    check(s_write_happened.count == 1, "duplicate command queued", port);
    Output(port, 0);
    check(s_write_happened.count == 2 && s_controller_write_payload[port + 1] == 0,
          "motor-off not queued", port);

    s_controller_type[port] = CONTROLLER_WIRELESS;
    s_port_states[port].controller_type = ControllerType::Wireless;
    Output(port, 1);
    check(s_write_happened.count == 2, "wireless controller received rumble", port);
    s_controller_type[port] = CONTROLLER_WIRED;
    s_port_states[port].controller_type = ControllerType::Wired;
    wanted = false; Output(port, 1); wanted = true;
    s_detected = false; Output(port, 1); s_detected = true;
    s_fd = 0; Output(port, 1); s_fd = 1;
    check(s_write_happened.count == 2, "unavailable adapter received rumble", port);
  }
  return failures ? 1 : 0;
}
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mainline-source", type=Path,
                        default=ROOT / "Externals/MainlineSlippiDolphin")
    args = parser.parse_args()
    sources = [
        ("Ishiiruka Android", ROOT / "Source/Core/InputCommon/GCAdapter_Android.cpp",
         "static u8 s_controller_write_payload[5] = {};"),
        ("Mainline Android", args.mainline_source / "Source/Core/InputCommon/GCAdapter.cpp",
         "static std::array<u8, 5> s_controller_write_payload{};"),
    ]
    failed = False
    with tempfile.TemporaryDirectory(prefix="gc-adapter-rumble-") as directory:
        source, binary = Path(directory) / "test.cpp", Path(directory) / "test"
        for label, path, payload in sources:
            if label == "Mainline Android":
                config = MAINLINE_CONFIG + source_function(path, "static void RefreshConfig()")
                config += "\nvoid ConfigureRumble(int port, bool enabled) { " \
                          "Config::rumble[port] = enabled; RefreshConfig(); }\n"
            else:
                config = "\nvoid ConfigureRumble(int port, bool enabled) { " \
                         "SConfig::GetInstance().m_AdapterRumble[port] = enabled; }\n"
            source.write_text(PRELUDE + payload + "\n" + config +
                              source_function(path, "void Output(int chan, u8 rumble_command)") + CASES)
            subprocess.run(["c++", "-std=c++17", "-pthread", str(source), "-o", str(binary)],
                           check=True)
            result = subprocess.run([str(binary)])
            print(f"{label}: {'FAIL' if result.returncode else 'PASS'}", flush=True)
            failed |= result.returncode != 0
    return int(failed)


if __name__ == "__main__":
    raise SystemExit(main())
