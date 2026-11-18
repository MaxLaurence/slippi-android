// Copyright 2014 Dolphin Emulator Project
// Licensed under GPLv2+
// Refer to the license.txt file included.

#pragma once

#include <functional>

#include "Common/CommonTypes.h"

struct GCPadStatus;

namespace GCAdapter
{
enum ControllerTypes
{
	CONTROLLER_NONE = 0,
	CONTROLLER_WIRED = 1,
	CONTROLLER_WIRELESS = 2
};

// An input report is a report id byte followed by 9 bytes per port
constexpr int ADAPTER_PAYLOAD_SIZE = 37;

// A report as received from the adapter, along with how many times the game had polled each port (see
// GetGamePollCount) when the report was stored
struct AdapterReport
{
	u64 game_poll_counts[4];
	u8 payload[ADAPTER_PAYLOAD_SIZE];
};

// Called on the adapter read thread for every report received without error. The callback must be
// safe to call at any time from that thread.
using ReportCallback = void (*)(const AdapterReport &report);
void SetReportCallback(ReportCallback callback);
// How many times the game itself has polled a port
u64 GetGamePollCount(int chan);

void ResetAdapterIfNecessary();
bool IsReadingAtReducedRate();
double ReadRate();

void Init();
void ResetRumble();
void Shutdown();
void SetAdapterCallback(std::function<void(void)> func);
void StartScanThread();
void StopScanThread();
// is_game_poll should be set when the game itself is reading the pad, which counts towards GetGamePollCount
GCPadStatus Input(int chan, bool is_game_poll = false);
void Output(int chan, u8 rumble_command);
bool IsDetected();
bool IsDriverDetected();
bool DeviceConnected(int chan);
bool UseAdapter();

// Deprecated Android JNI entry point. Physical GC adapter sticks pass
// through unchanged; app-level StickCalibration is only for Android/HID
// controller paths.
void SetStickCalibration(int chan, int stick_idx,
                         float center_x_byte, float center_y_byte,
                         float scale_x_pos, float scale_x_neg,
                         float scale_y_pos, float scale_y_neg,
                         float deadzone_normalized,
                         float sensitivity_exponent);

// Snapshot the latest raw stick bytes from a port. Returns true and
// populates out_x/out_y if the port is connected and has data.
bool GetLatestRawStick(int chan, int stick_idx, u8* out_x, u8* out_y);

// Per-port button remap. source_bit must be one of the PAD_BUTTON_* /
// PAD_TRIGGER_* bitmask constants (single bit). target_bit is the
// output mask emitted when source_bit is set on this port. Identity
// by default. ClearButtonRemap restores the port to identity.
void SetButtonRemap(int chan, uint16_t source_bit, uint16_t target_bit);
void ClearButtonRemap(int chan);

// Latest pressed-buttons bitmask on the given port, BEFORE remap.
// Used by the Android remap wizard's listen mode to identify which
// physical button on an adapter controller the user just pressed.
// Returns 0 if no controller is connected on that port.
uint16_t GetLatestRawButtons(int chan);

}  // end of namespace GCAdapter
