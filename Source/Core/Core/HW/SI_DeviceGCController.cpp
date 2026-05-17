// Copyright 2008 Dolphin Emulator Project
// Licensed under GPLv2+
// Refer to the license.txt file included.

#include "Core/HW/SI_Device.h"

#include <atomic>
#include <chrono>
#include <cstdint>

#ifdef __ANDROID__
#include <android/log.h>
#endif

#include "Common/ChunkFile.h"
#include "Common/CommonTypes.h"
#include "Common/Logging/Log.h"
#include "Common/MsgHandler.h"
#include "Core/CoreTiming.h"
#include "Core/HW/GCPad.h"
#include "Core/HW/ProcessorInterface.h"
#include "Core/HW/SI_DeviceGCController.h"
#include "Core/HW/SystemTimers.h"
#include "Common/AndroidInputDiagnostics.h"
#include "Core/Movie.h"
#include "Core/NetPlayProto.h"
#include "InputCommon/GCPadStatus.h"

// Per-port GCPadStatus override. Used by the Android launcher to push
// already-calibrated controller bytes down without going through
// Dolphin's ControllerEmu / ButtonManager pipeline (whose half-axis +
// radius math doubles inputs in the touchscreen-device case).
//
// Set from the JNI thread, read from the emulator thread — every
// field is stored in a separate std::atomic so the reads / writes
// don't need a mutex on the hot input path.
namespace
{
struct PadOverride
{
	std::atomic<bool> active{false};
	std::atomic<uint16_t> button{0};
	std::atomic<uint8_t>  stickX{128}, stickY{128};
	std::atomic<uint8_t>  substickX{128}, substickY{128};
	std::atomic<uint8_t>  triggerLeft{0}, triggerRight{0};
	std::atomic<uint8_t>  analogA{0}, analogB{0};
	std::atomic<uint64_t> setTimeUs{0};
};
static PadOverride s_pad_overrides[4];

uint64_t NowUs()
{
	return static_cast<uint64_t>(
	    std::chrono::duration_cast<std::chrono::microseconds>(
	        std::chrono::steady_clock::now().time_since_epoch())
	        .count());
}

#ifdef __ANDROID__
struct PadDiagnosticSignature
{
	uint16_t button = 0;
	uint8_t stickX = 128;
	uint8_t stickY = 128;
	uint8_t substickX = 128;
	uint8_t substickY = 128;
	uint8_t triggerLeft = 0;
	uint8_t triggerRight = 0;
	uint8_t analogA = 0;
	uint8_t analogB = 0;
	bool active = false;
};

bool SamePadDiagnosticSignature(const PadDiagnosticSignature& a, const PadDiagnosticSignature& b)
{
	return a.button == b.button && a.stickX == b.stickX && a.stickY == b.stickY &&
	       a.substickX == b.substickX && a.substickY == b.substickY &&
	       a.triggerLeft == b.triggerLeft && a.triggerRight == b.triggerRight &&
	       a.analogA == b.analogA && a.analogB == b.analogB && a.active == b.active;
}

void RecordPadStatusDiagnostic(int stage_idx, const char* stage, int port,
                               const GCPadStatus& status, bool active, uint64_t age_us)
{
	if (stage_idx < 0 || stage_idx >= 3 || port < 0 || port >= 4)
		return;

	static std::mutex s_diag_mutex;
	static PadDiagnosticSignature s_last[3][4];
	static bool s_have_last[3][4] = {};
	static int s_count[3][4] = {};

	PadDiagnosticSignature sig;
	sig.button = static_cast<uint16_t>(status.button);
	sig.stickX = status.stickX;
	sig.stickY = status.stickY;
	sig.substickX = status.substickX;
	sig.substickY = status.substickY;
	sig.triggerLeft = status.triggerLeft;
	sig.triggerRight = status.triggerRight;
	sig.analogA = status.analogA;
	sig.analogB = status.analogB;
	sig.active = active;

	bool should_log = false;
	{
		std::lock_guard<std::mutex> lock(s_diag_mutex);
		++s_count[stage_idx][port];
		should_log = !s_have_last[stage_idx][port] ||
		             !SamePadDiagnosticSignature(s_last[stage_idx][port], sig) ||
		             s_count[stage_idx][port] == 1 ||
		             (s_count[stage_idx][port] % 120) == 0;
		if (should_log)
		{
			s_last[stage_idx][port] = sig;
			s_have_last[stage_idx][port] = true;
		}
	}

	if (!should_log)
		return;

	Common::AndroidInputDiagnostics::Record(
	    "SlippiPadStatus",
	    "%s port=%d active=%d age_us=%llu button=0x%04x main=(%u,%u) c=(%u,%u) "
	    "triggers=(%u,%u) analog=(%u,%u)",
	    stage, port, active ? 1 : 0, static_cast<unsigned long long>(age_us),
	    static_cast<unsigned>(status.button), static_cast<unsigned>(status.stickX),
	    static_cast<unsigned>(status.stickY), static_cast<unsigned>(status.substickX),
	    static_cast<unsigned>(status.substickY), static_cast<unsigned>(status.triggerLeft),
	    static_cast<unsigned>(status.triggerRight), static_cast<unsigned>(status.analogA),
	    static_cast<unsigned>(status.analogB));
}
#endif
}  // namespace

namespace SI_PadOverride
{
static constexpr bool kPadOverrideDiagnostics = false;
static std::atomic<int> s_set_count{0};

void Set(int port, uint16_t button,
         uint8_t stickX, uint8_t stickY,
         uint8_t substickX, uint8_t substickY,
         uint8_t triggerLeft, uint8_t triggerRight,
         uint8_t analogA, uint8_t analogB)
{
	if (port < 0 || port >= 4) return;
	auto& o = s_pad_overrides[port];
	o.button.store(button);
	o.stickX.store(stickX);
	o.stickY.store(stickY);
	o.substickX.store(substickX);
	o.substickY.store(substickY);
	o.triggerLeft.store(triggerLeft);
	o.triggerRight.store(triggerRight);
	o.analogA.store(analogA);
	o.analogB.store(analogB);
	o.setTimeUs.store(NowUs(), std::memory_order_release);
	o.active.store(true);

#ifdef __ANDROID__
	GCPadStatus status = {};
	status.button = button;
	status.stickX = stickX;
	status.stickY = stickY;
	status.substickX = substickX;
	status.substickY = substickY;
	status.triggerLeft = triggerLeft;
	status.triggerRight = triggerRight;
	status.analogA = analogA;
	status.analogB = analogB;
	RecordPadStatusDiagnostic(0, "override_set", port, status, true, 0);
#endif

	if (kPadOverrideDiagnostics)
	{
		int count = s_set_count.fetch_add(1) + 1;
		if (count == 1 || count % 200 == 0)
		{
#ifdef __ANDROID__
			__android_log_print(ANDROID_LOG_INFO, "SlippiPadOverride",
			    "Set #%d port=%d stick=(%u,%u) addr=%p verify_after=(%u,%u)",
			    count, port, stickX, stickY, (void*)&s_pad_overrides[0],
			    (unsigned)o.stickX.load(), (unsigned)o.stickY.load());
#endif
		}
	}
}

void Clear(int port)
{
	if (port < 0 || port >= 4) return;
	s_pad_overrides[port].active.store(false);
#ifdef __ANDROID__
	GCPadStatus status = {};
	status.stickX = GCPadStatus::MAIN_STICK_CENTER_X;
	status.stickY = GCPadStatus::MAIN_STICK_CENTER_Y;
	status.substickX = GCPadStatus::MAIN_STICK_CENTER_X;
	status.substickY = GCPadStatus::MAIN_STICK_CENTER_Y;
	RecordPadStatusDiagnostic(0, "override_clear", port, status, false, 0);
#endif
	if (kPadOverrideDiagnostics)
	{
#ifdef __ANDROID__
		__android_log_print(ANDROID_LOG_INFO, "SlippiPadOverride", "Clear port=%d", port);
#else
		INFO_LOG(SERIALINTERFACE, "PadOverride.Clear port=%d", port);
#endif
	}
}

static std::atomic<int> s_get_count{0};

bool Get(int port, GCPadStatus* out)
{
	if (port < 0 || port >= 4 || !out) return false;
	auto& o = s_pad_overrides[port];
	bool active = o.active.load();

	if (kPadOverrideDiagnostics)
	{
		int count = s_get_count.fetch_add(1) + 1;
		if (count == 1 || count % 60 == 0)
		{
#ifdef __ANDROID__
			__android_log_print(ANDROID_LOG_INFO, "SlippiPadOverride",
			    "Get #%d port=%d active=%d addr=%p raw=(%u,%u)",
			    count, port, active ? 1 : 0, (void*)&s_pad_overrides[0],
			    (unsigned)o.stickX.load(), (unsigned)o.stickY.load());
#endif
		}
	}

	if (!active) return false;
	out->button       = o.button.load();
	out->stickX       = o.stickX.load();
	out->stickY       = o.stickY.load();
	out->substickX    = o.substickX.load();
	out->substickY    = o.substickY.load();
	out->triggerLeft  = o.triggerLeft.load();
	out->triggerRight = o.triggerRight.load();
	out->analogA      = o.analogA.load();
	out->analogB      = o.analogB.load();
#ifdef __ANDROID__
	RecordPadStatusDiagnostic(1, "override_get", port, *out, true, LatestSetAgeUs(port));
#endif
	return true;
}

uint64_t LatestSetTimeUs(int port)
{
	if (port < 0 || port >= 4) return 0;
	return s_pad_overrides[port].setTimeUs.load(std::memory_order_acquire);
}

uint64_t LatestSetAgeUs(int port)
{
	const uint64_t set_time = LatestSetTimeUs(port);
	if (set_time == 0) return 0;
	const uint64_t now = NowUs();
	return now > set_time ? now - set_time : 0;
}
}  // namespace SI_PadOverride

// --- standard GameCube controller ---
CSIDevice_GCController::CSIDevice_GCController(SIDevices device, int _iDeviceNumber)
	: ISIDevice(device, _iDeviceNumber), m_TButtonComboStart(0), m_TButtonCombo(0),
	m_LastButtonCombo(COMBO_NONE)
{
	// Dunno if we need to do this, game/lib should set it?
	m_Mode = 0x03;

	m_Calibrated = false;
}

void CSIDevice_GCController::Calibrate()
{
	GCPadStatus pad_origin = GetPadStatus();
	memset(&m_Origin, 0, sizeof(SOrigin));
	m_Origin.uButton = pad_origin.button;
	m_Origin.uOriginStickX = pad_origin.stickX;
	m_Origin.uOriginStickY = pad_origin.stickY;
	m_Origin.uSubStickStickX = pad_origin.substickX;
	m_Origin.uSubStickStickY = pad_origin.substickY;
	m_Origin.uTrigger_L = pad_origin.triggerLeft;
	m_Origin.uTrigger_R = pad_origin.triggerRight;

	m_Calibrated = true;
}

int CSIDevice_GCController::RunBuffer(u8* _pBuffer, int _iLength)
{
	// For debug logging only
	ISIDevice::RunBuffer(_pBuffer, _iLength);

	// Read the command
	EBufferCommands command = static_cast<EBufferCommands>(_pBuffer[3]);

	// Handle it
	switch (command)
	{
	case CMD_RESET:
	case CMD_ID:
		*(u32*)&_pBuffer[0] = SI_GC_CONTROLLER;
		break;

	case CMD_DIRECT:
	{
		INFO_LOG(SERIALINTERFACE, "PAD - Direct (Length: %d)", _iLength);
		u32 high, low;
		GetData(high, low);
		for (int i = 0; i < (_iLength - 1) / 2; i++)
		{
			_pBuffer[i + 0] = (high >> (i * 8)) & 0xff;
			_pBuffer[i + 4] = (low >> (i * 8)) & 0xff;
		}
	}
	break;

	case CMD_ORIGIN:
	{
		INFO_LOG(SERIALINTERFACE, "PAD - Get Origin");

		if (!m_Calibrated)
			Calibrate();

		u8* pCalibration = reinterpret_cast<u8*>(&m_Origin);
		for (int i = 0; i < (int)sizeof(SOrigin); i++)
		{
			_pBuffer[i ^ 3] = *pCalibration++;
		}
	}
	break;

	// Recalibrate (FiRES: i am not 100 percent sure about this)
	case CMD_RECALIBRATE:
	{
		INFO_LOG(SERIALINTERFACE, "PAD - Recalibrate");

		if (!m_Calibrated)
			Calibrate();

		u8* pCalibration = reinterpret_cast<u8*>(&m_Origin);
		for (int i = 0; i < (int)sizeof(SOrigin); i++)
		{
			_pBuffer[i ^ 3] = *pCalibration++;
		}
	}
	break;

	// DEFAULT
	default:
	{
		ERROR_LOG(SERIALINTERFACE, "Unknown SI command     (0x%x)", command);
		PanicAlert("SI: Unknown command (0x%x)", command);
	}
	break;
	}

	return _iLength;
}

void CSIDevice_GCController::HandleMoviePadStatus(GCPadStatus* PadStatus)
{
	Movie::CallGCInputManip(PadStatus, ISIDevice::m_iDeviceNumber);

	Movie::SetPolledDevice();
	if (NetPlay_GetInput(ISIDevice::m_iDeviceNumber, PadStatus))
	{
	}
	else if (Movie::IsPlayingInput())
	{
		Movie::PlayController(PadStatus, ISIDevice::m_iDeviceNumber);
		Movie::InputUpdate();
	}
	else if (Movie::IsRecordingInput())
	{
		Movie::RecordInput(PadStatus, ISIDevice::m_iDeviceNumber);
		Movie::InputUpdate();
	}
	else
	{
		Movie::CheckPadStatus(PadStatus, ISIDevice::m_iDeviceNumber);
	}
}

GCPadStatus CSIDevice_GCController::GetPadStatus()
{
	GCPadStatus pad_status = {};
	bool from_override = false;

	if (SI_PadOverride::Get(m_iDeviceNumber, &pad_status))
	{
		from_override = true;
		HandleMoviePadStatus(&pad_status);
	}
	else
	{
		if (!NetPlay::IsNetPlayRunning())
		{
			pad_status = Pad::GetStatus(m_iDeviceNumber);
		}
		HandleMoviePadStatus(&pad_status);
	}


#ifdef __ANDROID__
	RecordPadStatusDiagnostic(2, "si_gc_out", m_iDeviceNumber, pad_status, from_override,
	                          SI_PadOverride::LatestSetAgeUs(m_iDeviceNumber));
#endif

	return pad_status;
}

GCPadStatus CSIDevice_GCController::GetPadStatus(std::chrono::high_resolution_clock::time_point)
{
	return GetPadStatus();
}

// GetData

// Return true on new data (max 7 Bytes and 6 bits ;)
// [00?SYXBA] [1LRZUDRL] [x] [y] [cx] [cy] [l] [r]
//  |\_ ERR_LATCH (error latched - check SISR)
//  |_ ERR_STATUS (error on last GetData or SendCmd?)

bool CSIDevice_GCController::GetData(u32 &_Hi, u32 &_Low)
{
	GCPadStatus PadStatus = GetPadStatus();
	return GetDataFromPadStatus(_Hi, _Low, PadStatus);
}
bool CSIDevice_GCController::GetData(u32 &_Hi, u32 &_Low, std::chrono::high_resolution_clock::time_point when)
{
	GCPadStatus PadStatus = GetPadStatus(when);
	return GetDataFromPadStatus(_Hi, _Low, PadStatus);
}

bool CSIDevice_GCController::GetDataFromPadStatus(u32 &_Hi, u32 &_Low, GCPadStatus &PadStatus)
{
	// bool CSIDevice_GCController::GetData(u32 &_Hi, u32 &_Low) {
	// GCPadStatus PadStatus = GetPadStatus();

	if (HandleButtonCombos(PadStatus) == COMBO_ORIGIN)
		PadStatus.button |= PAD_GET_ORIGIN;

	_Hi = MapPadStatus(PadStatus);

	// Low bits are packed differently per mode
	if (m_Mode == 0 || m_Mode == 5 || m_Mode == 6 || m_Mode == 7)
	{
		_Low = (u8)(PadStatus.analogB >> 4);                   // Top 4 bits
		_Low |= (u32)((u8)(PadStatus.analogA >> 4) << 4);      // Top 4 bits
		_Low |= (u32)((u8)(PadStatus.triggerRight >> 4) << 8); // Top 4 bits
		_Low |= (u32)((u8)(PadStatus.triggerLeft >> 4) << 12); // Top 4 bits
		_Low |= (u32)((u8)(PadStatus.substickY) << 16);        // All 8 bits
		_Low |= (u32)((u8)(PadStatus.substickX) << 24);        // All 8 bits
	}
	else if (m_Mode == 1)
	{
		_Low = (u8)(PadStatus.analogB >> 4);              // Top 4 bits
		_Low |= (u32)((u8)(PadStatus.analogA >> 4) << 4); // Top 4 bits
		_Low |= (u32)((u8)PadStatus.triggerRight << 8);   // All 8 bits
		_Low |= (u32)((u8)PadStatus.triggerLeft << 16);   // All 8 bits
		_Low |= (u32)((u8)PadStatus.substickY << 24);     // Top 4 bits
		_Low |= (u32)((u8)PadStatus.substickX << 28);     // Top 4 bits
	}
	else if (m_Mode == 2)
	{
		_Low = (u8)(PadStatus.analogB);                         // All 8 bits
		_Low |= (u32)((u8)(PadStatus.analogA) << 8);            // All 8 bits
		_Low |= (u32)((u8)(PadStatus.triggerRight >> 4) << 16); // Top 4 bits
		_Low |= (u32)((u8)(PadStatus.triggerLeft >> 4) << 20);  // Top 4 bits
		_Low |= (u32)((u8)PadStatus.substickY << 24);           // Top 4 bits
		_Low |= (u32)((u8)PadStatus.substickX << 28);           // Top 4 bits
	}
	else if (m_Mode == 3)
	{
		// Analog A/B are always 0
		_Low = (u8)PadStatus.triggerRight;             // All 8 bits
		_Low |= (u32)((u8)PadStatus.triggerLeft << 8); // All 8 bits
		_Low |= (u32)((u8)PadStatus.substickY << 16);  // All 8 bits
		_Low |= (u32)((u8)PadStatus.substickX << 24);  // All 8 bits
	}
	else if (m_Mode == 4)
	{
		_Low = (u8)(PadStatus.analogB);               // All 8 bits
		_Low |= (u32)((u8)(PadStatus.analogA) << 8);  // All 8 bits
		                                              // triggerLeft/Right are always 0
		_Low |= (u32)((u8)PadStatus.substickY << 16); // All 8 bits
		_Low |= (u32)((u8)PadStatus.substickX << 24); // All 8 bits
	}

	// Unset all bits except those that represent
	// A, B, X, Y, Start and the error bits, as they
	// are not used.
	if (m_simulate_konga)
		_Hi &= ~0x20FFFFFF;

	return true;
}

u32 CSIDevice_GCController::MapPadStatus(const GCPadStatus& pad_status)
{
	// Thankfully changing mode does not change the high bits ;)
	u32 _Hi = 0;
	_Hi = (u32)((u8)pad_status.stickY);
	_Hi |= (u32)((u8)pad_status.stickX << 8);
	_Hi |= (u32)((u16)(pad_status.button | PAD_USE_ORIGIN) << 16);
	return _Hi;
}

CSIDevice_GCController::EButtonCombo
CSIDevice_GCController::HandleButtonCombos(const GCPadStatus& pad_status)
{
	// Keep track of the special button combos (embedded in controller hardware... :( )
	EButtonCombo tempCombo;
	if ((pad_status.button & 0xff00) == (PAD_BUTTON_Y | PAD_BUTTON_X | PAD_BUTTON_START))
		tempCombo = COMBO_ORIGIN;
	else if ((pad_status.button & 0xff00) == (PAD_BUTTON_B | PAD_BUTTON_X | PAD_BUTTON_START))
		tempCombo = COMBO_RESET;
	else
		tempCombo = COMBO_NONE;
	if (tempCombo != m_LastButtonCombo)
	{
		m_LastButtonCombo = tempCombo;
		if (m_LastButtonCombo != COMBO_NONE)
			m_TButtonComboStart = CoreTiming::GetTicks();
	}
	if (m_LastButtonCombo != COMBO_NONE)
	{
		m_TButtonCombo = CoreTiming::GetTicks();
		if ((m_TButtonCombo - m_TButtonComboStart) > SystemTimers::GetTicksPerSecond() * 3)
		{
			if (m_LastButtonCombo == COMBO_RESET)
				ProcessorInterface::ResetButton_Tap();
			else if (m_LastButtonCombo == COMBO_ORIGIN)
			{
				m_Origin.uOriginStickX = pad_status.stickX;
				m_Origin.uOriginStickY = pad_status.stickY;
				m_Origin.uSubStickStickX = pad_status.substickX;
				m_Origin.uSubStickStickY = pad_status.substickY;
				m_Origin.uTrigger_L = pad_status.triggerLeft;
				m_Origin.uTrigger_R = pad_status.triggerRight;
			}
			m_LastButtonCombo = COMBO_NONE;
			return tempCombo;
		}
	}

	return COMBO_NONE;
}

// SendCommand
void CSIDevice_GCController::SendCommand(u32 _Cmd, u8 _Poll)
{
	UCommand command(_Cmd);

	switch (command.Command)
	{
		// Costis sent it in some demos :)
	case 0x00:
		break;

	case CMD_WRITE:
	{
		unsigned int uType = command.Parameter1;  // 0 = stop, 1 = rumble, 2 = stop hard
		unsigned int uStrength = command.Parameter2;

		// get the correct pad number that should rumble locally when using netplay
		const int numPAD = NetPlay_InGamePadToLocalPad(ISIDevice::m_iDeviceNumber);

		if (numPAD < 4)
		{
			if (uType == 1 && uStrength > 2)
				CSIDevice_GCController::Rumble(numPAD, 1.0);
			else
				CSIDevice_GCController::Rumble(numPAD, 0.0);
		}

		if (!_Poll)
		{
			m_Mode = command.Parameter2;
			INFO_LOG(SERIALINTERFACE, "PAD %i set to mode %i", ISIDevice::m_iDeviceNumber, m_Mode);
		}
	}
	break;

	default:
	{
		ERROR_LOG(SERIALINTERFACE, "Unknown direct command     (0x%x)", _Cmd);
		PanicAlert("SI: Unknown direct command");
	}
	break;
	}
}

// Savestate support
void CSIDevice_GCController::DoState(PointerWrap& p)
{
	p.Do(m_Calibrated);
	p.Do(m_Origin);
	p.Do(m_Mode);
	p.Do(m_TButtonComboStart);
	p.Do(m_TButtonCombo);
	p.Do(m_LastButtonCombo);
}
