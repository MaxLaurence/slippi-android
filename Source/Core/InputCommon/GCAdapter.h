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

void ResetAdapterIfNecessary();
bool IsReadingAtReducedRate();
double ReadRate();

void Init();
void ResetRumble();
void Shutdown();
void SetAdapterCallback(std::function<void(void)> func);
void StartScanThread();
void StopScanThread();
GCPadStatus Input(int chan, std::chrono::high_resolution_clock::time_point *tp=nullptr);
void Output(int chan, u8 rumble_command);
bool IsDetected();
bool IsDriverDetected();
bool DeviceConnected(int chan);
bool UseAdapter();

// Per-port stick calibration. Set from the Android launcher; applied
// in Input() before the GCPadStatus is returned to the emulator.
// stick_idx: 0 = main stick, 1 = C-stick. Pass identity values
// (center 128/128, scales all 1, deadzone 0) to disable.
void SetStickCalibration(int chan, int stick_idx,
                         float center_x_byte, float center_y_byte,
                         float scale_x_pos, float scale_x_neg,
                         float scale_y_pos, float scale_y_neg,
                         float deadzone_normalized,
                         float sensitivity_exponent);

// Snapshot the latest raw (pre-calibration) stick bytes from a port.
// Returns true and populates out_x/out_y if the port is connected and
// has data. Used by the Android calibration wizard to capture the
// controller's actual range without the calibration that Input() would
// otherwise apply.
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
