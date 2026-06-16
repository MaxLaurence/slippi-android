// Copyright 2010 Dolphin Emulator Project
// Licensed under GPLv2+
// Refer to the license.txt file included.

#include "Core/Slippi/SlippiNetplay.h"
#include "Common/AndroidInputDiagnostics.h"
#include "Common/CommonTypes.h"
#include "Common/ENetUtil.h"
#include "Common/MsgHandler.h"
#include "Common/Thread.h"
#include "Common/Timer.h"
#include "Core/ConfigManager.h"
#include "Core/Core.h"
#include "Core/HW/SI_DeviceGCController.h"
#include "SlippiPremadeText.h"
#include "VideoCommon/OnScreenDisplay.h"
#include "VideoCommon/VideoConfig.h"
#include <algorithm>
#include <climits>
#include <cinttypes>
#include <cstring>
#include <fstream>
#include <memory>
#include <thread>
#ifdef __ANDROID__
#include <android/log.h>
#include <sys/system_properties.h>
#include <sys/resource.h>
#endif

//#include "Common/MD5.h"
//#include "Common/Common.h"
//#include "Common/CommonPaths.h"
//#include "Core/HW/EXI_DeviceIPL.h"
//#include "Core/HW/SI.h"
//#include "Core/HW/SI_DeviceGCController.h"
//#include "Core/HW/Sram.h"
//#include "Core/HW/WiimoteEmu/WiimoteEmu.h"
//#include "Core/HW/WiimoteReal/WiimoteReal.h"
//#include "Core/IPC_HLE/WII_IPC_HLE_Device_usb_bt_emu.h"
//#include "Core/Movie.h"
//#include "InputCommon/GCAdapter.h"
//#include <mbedtls/md5.h>
//#include <SlippiGame.h>

static std::mutex ack_mutex;

SlippiNetplayClient *SLIPPI_NETPLAY = nullptr;

namespace
{
#ifdef __ANDROID__
constexpr const char* LATENCY_TAG = "SlippiLatency";

void RecordLocalPadQueueDiagnostic(const char* stage, const SlippiPad* pad, size_t queue_size)
{
	if (!Common::AndroidInputDiagnostics::IsEnabled())
		return;

	if (!pad)
		return;

	static std::mutex s_diag_mutex;
	static u8 s_last[SLIPPI_PAD_DATA_SIZE] = {};
	static bool s_have_last = false;
	static int s_count = 0;

	bool changed = false;
	{
		std::lock_guard<std::mutex> lock(s_diag_mutex);
		++s_count;
		changed = !s_have_last || std::memcmp(s_last, pad->padBuf, SLIPPI_PAD_DATA_SIZE) != 0;
		if (changed)
		{
			std::memcpy(s_last, pad->padBuf, SLIPPI_PAD_DATA_SIZE);
			s_have_last = true;
		}
		if (!changed && s_count != 1 && (s_count % 60) != 0)
			return;
	}

	Common::AndroidInputDiagnostics::Record(
	    "SlippiPadBuffer",
	    "%s frame=%d queue=%zu pad=%02x%02x%02x%02x%02x%02x%02x%02x changed=%d",
	    stage, pad->frame, queue_size, pad->padBuf[0], pad->padBuf[1], pad->padBuf[2],
	    pad->padBuf[3], pad->padBuf[4], pad->padBuf[5], pad->padBuf[6], pad->padBuf[7],
	    changed ? 1 : 0);
}

bool AndroidDebugPropertyEnabled(const char* name)
{
	char value[PROP_VALUE_MAX] = {};
	if (__system_property_get(name, value) <= 0)
		return false;
	return value[0] == '1' || value[0] == 'y' || value[0] == 'Y' ||
	       value[0] == 't' || value[0] == 'T';
}

bool AndroidLatencyTraceEnabled()
{
	static const bool enabled = AndroidDebugPropertyEnabled("debug.slippi.latency_trace");
	return enabled;
}

void ConfigureAndroidLowLatencySocket(ENetHost* host)
{
	if (!host)
		return;

	// UDP sockets ignore TCP_NODELAY, but ENet exposes buffer sizing and Android/Linux
	// accept DSCP and socket priority when permissions allow it. Failures are logged only
	// when explicit tracing is enabled because some OEM kernels reject these knobs.
	enet_socket_set_option(host->socket, ENET_SOCKOPT_SNDBUF, 64 * 1024);
	enet_socket_set_option(host->socket, ENET_SOCKOPT_RCVBUF, 64 * 1024);

	int priority = 7;
	int priority_result = setsockopt(host->socket, SOL_SOCKET, SO_PRIORITY,
	                                 &priority, sizeof(priority));
	int tos_val = 0xb8;
	int tos_result = setsockopt(host->socket, IPPROTO_IP, IP_TOS, &tos_val, sizeof(tos_val));
	if (AndroidLatencyTraceEnabled())
	{
		__android_log_print(ANDROID_LOG_INFO, LATENCY_TAG,
		                    "net socket low-latency sndbuf=64k rcvbuf=64k priorityResult=%d tosResult=%d",
		                    priority_result, tos_result);
	}
}
#endif
}  // namespace

void SlippiNetplayClient::PadRing::Clear()
{
	const u32 generation = m_generation.fetch_add(1, std::memory_order_acq_rel) + 1;
	m_latestFrame.store(INT_MIN, std::memory_order_release);
	m_readFloor.store(INT_MIN, std::memory_order_release);
	for (auto &slot : m_slots)
	{
		slot.sequence.store(1, std::memory_order_release);
		slot.pad = SlippiPad();
		slot.generation.store(generation, std::memory_order_release);
		slot.sequence.store(2, std::memory_order_release);
	}
}

void SlippiNetplayClient::PadRing::Push(const SlippiPad &pad)
{
	auto &slot = m_slots[static_cast<size_t>(pad.frame) % m_slots.size()];
	const u32 generation = m_generation.load(std::memory_order_acquire);
	u32 sequence = slot.sequence.load(std::memory_order_relaxed);
	if ((sequence % 2) == 0)
		sequence++;
	slot.sequence.store(sequence, std::memory_order_release);
	slot.pad = pad;
	slot.generation.store(generation, std::memory_order_release);
	slot.sequence.store(sequence + 1, std::memory_order_release);

	s32 latest = m_latestFrame.load(std::memory_order_acquire);
	while (pad.frame > latest &&
	       !m_latestFrame.compare_exchange_weak(latest, pad.frame, std::memory_order_release,
	                                            std::memory_order_acquire))
	{
	}
}

void SlippiNetplayClient::PadRing::DropBefore(s32 frame)
{
	s32 floor = m_readFloor.load(std::memory_order_acquire);
	while (frame > floor &&
	       !m_readFloor.compare_exchange_weak(floor, frame, std::memory_order_release,
	                                          std::memory_order_acquire))
	{
	}
}

bool SlippiNetplayClient::PadRing::TryGetFrame(s32 frame, SlippiPad *out) const
{
	if (frame < m_readFloor.load(std::memory_order_acquire))
		return false;

	auto latest = m_latestFrame.load(std::memory_order_acquire);
	if (latest == INT_MIN || frame > latest ||
	    latest - frame >= static_cast<s32>(SLIPPI_PAD_RING_CAPACITY))
	{
		return false;
	}

	const auto &slot = m_slots[static_cast<size_t>(frame) % m_slots.size()];
	for (int attempts = 0; attempts < 3; attempts++)
	{
		u32 generation = m_generation.load(std::memory_order_acquire);
		u32 before = slot.sequence.load(std::memory_order_acquire);
		if ((before % 2) != 0)
			continue;
		SlippiPad copied = slot.pad;
		u32 slotGeneration = slot.generation.load(std::memory_order_acquire);
		u32 after = slot.sequence.load(std::memory_order_acquire);
		if (before == after && (after % 2) == 0 && copied.frame == frame &&
		    slotGeneration == generation &&
		    generation == m_generation.load(std::memory_order_acquire))
		{
			if (out)
				*out = copied;
			return true;
		}
	}
	return false;
}

s32 SlippiNetplayClient::PadRing::LatestFrame() const
{
	return m_latestFrame.load(std::memory_order_acquire);
}

size_t SlippiNetplayClient::PadRing::CopyNewestFirst(
    s32 minFrameInclusive, size_t maxCount, std::array<SlippiPad, SLIPPI_PAD_RING_CAPACITY> *out) const
{
	if (!out)
		return 0;

	size_t count = 0;
	auto latest = LatestFrame();
	if (latest == INT_MIN)
		return 0;

	s32 floor = std::max(minFrameInclusive, m_readFloor.load(std::memory_order_acquire));
	floor = std::max(floor, latest - static_cast<s32>(SLIPPI_PAD_RING_CAPACITY) + 1);
	for (s32 frame = latest; frame >= floor && count < maxCount; frame--)
	{
		SlippiPad pad;
		if (TryGetFrame(frame, &pad))
			(*out)[count++] = pad;
		if (frame == INT_MIN)
			break;
	}
	return count;
}

size_t SlippiNetplayClient::PadRing::CopyNewestFirst(size_t maxCount, u8 *out, size_t outCapacity,
                                                     s32 *latestFrame) const
{
	if (!out || outCapacity == 0 || maxCount == 0)
		return 0;

	auto latest = LatestFrame();
	if (latestFrame)
		*latestFrame = latest == INT_MIN ? 0 : latest;
	if (latest == INT_MIN)
		return 0;

	// Remote input reads expect the oldest still-needed window, not the newest
	// window. The EXI side computes an offset from latestFrame back to the
	// requested frame; returning a too-new window can make valid early frames
	// look absent during match startup and trigger a stall/disconnect.
	s32 floor = std::max<s32>(1, m_readFloor.load(std::memory_order_acquire));
	floor = std::max(floor, latest - static_cast<s32>(SLIPPI_PAD_RING_CAPACITY) + 1);
	if (floor > latest)
	{
		if (latestFrame)
			*latestFrame = latest;
		return 0;
	}

	s32 windowLatest = std::min(latest, floor + static_cast<s32>(maxCount) - 1);
	if (latestFrame)
		*latestFrame = windowLatest;

	size_t count = 0;
	size_t bytes = 0;
	SlippiPad emptyPad;
	for (s32 frame = windowLatest;
	     frame >= floor && count < maxCount && bytes + SLIPPI_PAD_FULL_SIZE <= outCapacity; frame--)
	{
		SlippiPad pad;
		const SlippiPad &source = TryGetFrame(frame, &pad) ? pad : emptyPad;
		std::memcpy(out + bytes, source.padBuf, SLIPPI_PAD_FULL_SIZE);
		bytes += SLIPPI_PAD_FULL_SIZE;
		count++;
		if (frame == INT_MIN)
			break;
	}
	return bytes;
}

// called from ---GUI--- thread
SlippiNetplayClient::~SlippiNetplayClient()
{
	m_do_loop.Clear();
	if (m_thread.joinable())
		m_thread.join();

	if (!m_server.empty())
	{
		Disconnect();
	}

	if (g_MainNetHost.get() == m_client)
	{
		g_MainNetHost.release();
	}
	if (m_client)
	{
		enet_host_destroy(m_client);
		m_client = nullptr;
	}

	SLIPPI_NETPLAY = nullptr;

	WARN_LOG(SLIPPI_ONLINE, "Netplay client cleanup complete");
}

// called from ---SLIPPI EXI--- thread
SlippiNetplayClient::SlippiNetplayClient(std::vector<std::string> addrs, std::vector<u16> ports,
                                         const u8 remotePlayerCount, const u16 localPort, bool isDecider, u8 playerIdx)
#ifdef _WIN32
    : m_qos_handle(nullptr)
    , m_qos_flow_id(0)
#endif
{
	WARN_LOG(SLIPPI_ONLINE, "Initializing Slippi Netplay for port: %d, with host: %s, player idx: %d", localPort,
	         isDecider ? "true" : "false", playerIdx);
	this->isDecider = isDecider;
	this->m_remotePlayerCount = remotePlayerCount;
	this->playerIdx = playerIdx;

	// Set up remote player data structures
	int j = 0;
	for (int i = 0; i < SLIPPI_REMOTE_PLAYER_MAX; i++, j++)
	{
		if (j == playerIdx)
			j++;
		this->matchInfo.remotePlayerSelections[i] = SlippiPlayerSelections();
		this->matchInfo.remotePlayerSelections[i].playerIdx = j;

		this->remotePadRings[i].Clear();
		this->frameOffsetData[i] = FrameOffsetData();
		this->lastFrameTiming[i] = FrameTiming();
		this->pingUs[i] = 0;
		this->lastFrameAcked[i] = 0; // First frame should be 1 in this context so 0 is the correct reset (I think)
		this->remoteChecksumFrame[i].store(0, std::memory_order_release);
		this->remoteChecksumValue[i].store(0, std::memory_order_release);
	}
	localPadRing.Clear();

	SLIPPI_NETPLAY = std::move(this);

	// Local address
	ENetAddress *localAddr = nullptr;
	ENetAddress localAddrDef;

	// It is important to be able to set the local port to listen on even in a client connection because
	// not doing so will break hole punching, the host is expecting traffic to come from a specific ip/port
	// and if the port does not match what it is expecting, it will not get through the NAT on some routers
	if (localPort > 0)
	{
		INFO_LOG(SLIPPI_ONLINE, "Setting up local address");

		localAddrDef.host = ENET_HOST_ANY;
		localAddrDef.port = localPort;

		localAddr = &localAddrDef;
	}

	// TODO: Figure out how to use a local port when not hosting without accepting incoming connections
	m_client = enet_host_create(localAddr, 10, 3, 0, 0);

	if (m_client == nullptr)
	{
		PanicAlertT("Couldn't Create Client");
	}
#ifdef __ANDROID__
	ConfigureAndroidLowLatencySocket(m_client);
#endif

	for (int i = 0; i < remotePlayerCount; i++)
	{
		ENetAddress addr;
		enet_address_set_host(&addr, addrs[i].c_str());
		addr.port = ports[i];
		// INFO_LOG(SLIPPI_ONLINE, "Set ENet host, addr = %x, port = %d", addr.host, addr.port);

		ENetPeer *peer = enet_host_connect(m_client, &addr, 3, 0);
		m_server.push_back(peer);

		// Store this connection
		std::stringstream keyStrm;
		keyStrm << addr.host << "-" << addr.port;
		ActiveConnectionInfo connInfo;
		connInfo.playerIdx = matchInfo.remotePlayerSelections[i].playerIdx;
		activeConnections[keyStrm.str()][peer] = connInfo;
		playerActive[connInfo.playerIdx].store(true, std::memory_order_release);

		if (peer == nullptr)
		{
			PanicAlertT("Couldn't create peer.");
		}
		else
		{
			// INFO_LOG(SLIPPI_ONLINE, "Connecting to ENet host, addr = %x, port = %d", peer->address.host,
			//         peer->address.port);
		}
	}

	slippiConnectStatus.store(SlippiConnectStatus::NET_CONNECT_STATUS_INITIATED, std::memory_order_release);

	m_thread = std::thread(&SlippiNetplayClient::ThreadFunc, this);
}

// Make a dummy client
SlippiNetplayClient::SlippiNetplayClient(bool isDecider)
{
	this->isDecider = isDecider;
	SLIPPI_NETPLAY = std::move(this);
	slippiConnectStatus.store(SlippiConnectStatus::NET_CONNECT_STATUS_FAILED, std::memory_order_release);
}

u8 SlippiNetplayClient::PlayerIdxFromPort(u8 port)
{
	u8 p = port;
	if (port > playerIdx)
	{
		p--;
	}
	return p;
}

u8 SlippiNetplayClient::LocalPlayerPort()
{
	return this->playerIdx;
}

// called from ---NETPLAY--- thread
unsigned int SlippiNetplayClient::OnData(sf::Packet &packet, ENetPeer *peer)
{
	MessageId mid = 0;
	if (!(packet >> mid))
	{
		ERROR_LOG(SLIPPI_ONLINE, "Received empty netplay packet");
		return 0;
	}

	switch (mid)
	{
	case NP_MSG_SLIPPI_PAD:
	{
		// Fetch current time immediately for the most accurate timing calculations
		u64 curTime = Common::Timer::GetTimeUs();

		s32 frame;
		s32 checksumFrame;
		u32 checksum;
		if (!(packet >> frame))
		{
			ERROR_LOG(SLIPPI_ONLINE, "Netplay packet too small to read frame count");
			break;
		}
		u8 packetPlayerPort;
		if (!(packet >> packetPlayerPort))
		{
			ERROR_LOG(SLIPPI_ONLINE, "Netplay packet too small to read player index");
			break;
		}
		if (!(packet >> checksumFrame))
		{
			ERROR_LOG(SLIPPI_ONLINE, "Netplay packet too small to read checksum frame");
			break;
		}
		if (!(packet >> checksum))
		{
			ERROR_LOG(SLIPPI_ONLINE, "Netplay packet too small to read checksum value");
			break;
		}
		u8 pIdx = PlayerIdxFromPort(packetPlayerPort);
		if (pIdx >= m_remotePlayerCount)
		{
			ERROR_LOG(SLIPPI_ONLINE, "Got packet with invalid player idx %d", pIdx);
			break;
		}

		// This is the amount of bytes from the start of the packet where the pad data starts
		int padDataOffset = 14;

		// ERROR_LOG(SLIPPI_ONLINE, "Received Checksum. CurFrame: %d, ChkFrame: %d, Chk: %08x", frame, checksumFrame,
		// checksum);

		// This fetches the m_server index that stores the connection we want to overwrite (if necessary). Note that
		// this index is not necessarily the same as the pIdx because if we have users connecting with the same
		// WAN, the m_server indices might not match
		int connIdx = 0;
		for (int i = 0; i < m_server.size(); i++)
		{
			if (peer->address.host == m_server[i]->address.host && peer->address.port == m_server[i]->address.port)
			{
				connIdx = i;
				break;
			}
		}

		// Here we check if we have more than 1 connection for a specific player, this can happen because both
		// players try to connect to each other at the same time to increase the odds that one direction might
		// work and for hole punching. That said there's no point in keeping more than 1 connection alive. I
		// think they might use bandwidth with keep alives or something. Only the lower port player will
		// initiate the disconnect
		std::stringstream keyStrm;
		keyStrm << peer->address.host << "-" << peer->address.port;
		int liveConnCount = 0;
		bool isCurrentActive = false;
		for (auto &c : activeConnections[keyStrm.str()])
		{
			if (c.second.isDisconnected)
				continue;

			if (c.first == peer)
				isCurrentActive = true;

			liveConnCount++;
		}
		if (isCurrentActive && liveConnCount > 1 && playerIdx < packetPlayerPort)
		{
			m_server[connIdx] = peer;
			INFO_LOG(SLIPPI_ONLINE,
			         "Multiple connections detected for single peer. %x:%d. %x. Disconnecting superfluous "
			         "connections. oppIdx: %d. pIdx: %d",
			         peer->address.host, peer->address.port, peer, pIdx, playerIdx);

			for (auto &activeConn : activeConnections[keyStrm.str()])
			{
				if (activeConn.first == peer)
					continue;
				if (activeConn.second.isDisconnected)
					continue;

				// Tell our peer to terminate this connection. Mark it locally as disconnected
				// immediately so we stop counting it before the ENET DISCONNECT event lands.
				enet_peer_disconnect(activeConn.first, 0);
				activeConn.second.isDisconnected = true;
			}
		}

		// Pad received, try to guess what our local time was when the frame was sent by our opponent
		// before we initialized
		// We can compare this to when we sent a pad for last frame to figure out how far/behind we
		// are with respect to the opponent
		auto timing = lastFrameTiming[pIdx];
		if (!hasGameStarted)
		{
			// Handle case where opponent starts sending inputs before our game has reached frame 1. This will
			// continuously say frame 0 is now to prevent opp from getting too far ahead
			timing.frame = 0;
			timing.timeUs = curTime;
		}

		s64 opponentSendTimeUs = curTime - (pingUs[pIdx] / 2);
		s64 frameDiffOffsetUs = 16683 * (timing.frame - frame);
		s64 timeOffsetUs = opponentSendTimeUs - timing.timeUs + frameDiffOffsetUs;

		// INFO_LOG(SLIPPI_ONLINE, "[Offset] Opp Frame: %d, My Frame: %d. Time offset: %lld", frame, timing.frame,
		//         timeOffsetUs);

		// Add this offset to circular buffer for use later
		if (frameOffsetData[pIdx].buf.size() < SLIPPI_ONLINE_LOCKSTEP_INTERVAL)
			frameOffsetData[pIdx].buf.push_back(static_cast<s32>(timeOffsetUs));
		else
			frameOffsetData[pIdx].buf[frameOffsetData[pIdx].idx] = static_cast<s32>(timeOffsetUs);

		frameOffsetData[pIdx].idx = (frameOffsetData[pIdx].idx + 1) % SLIPPI_ONLINE_LOCKSTEP_INTERVAL;

		auto packetData = (u8 *)packet.getData();
		s64 frame64 = static_cast<s64>(frame);
		s32 headFrame = remotePadRings[pIdx].LatestFrame();
		if (headFrame == INT_MIN)
			headFrame = 0;
		s64 inputsToCopy = frame64 - static_cast<s64>(headFrame);

		// Check that the packet actually contains the data it claims to.
		if ((padDataOffset + inputsToCopy * SLIPPI_PAD_DATA_SIZE) > static_cast<s64>(packet.getDataSize()))
		{
			ERROR_LOG(SLIPPI_ONLINE,
			          "Netplay packet too small to read pad buffer. Size: %d, Inputs: %d, MinSize: %d",
			          (int)packet.getDataSize(), inputsToCopy,
			          padDataOffset + inputsToCopy * SLIPPI_PAD_DATA_SIZE);
			break;
		}

		if (inputsToCopy > 128)
		{
			ERROR_LOG(SLIPPI_ONLINE, "Netplay packet contained too many frames: %d", inputsToCopy);
			break;
		}

		for (s64 i = inputsToCopy - 1; i >= 0; i--)
		{
			SlippiPad pad(static_cast<s32>(frame64 - i),
			              &packetData[padDataOffset + i * SLIPPI_PAD_DATA_SIZE]);
			remotePadRings[pIdx].Push(pad);
		}

		remoteChecksumFrame[pIdx].store(checksumFrame, std::memory_order_release);
		remoteChecksumValue[pIdx].store(checksum, std::memory_order_release);

		// Only ack if inputsToCopy is greater than 0. Otherwise we are receiving an old input and
		// we should have already acked something in the future. This can also happen in the case
		// where a new game starts quickly before the remote queue is reset and if we ack the early
		// inputs we will never receive them
		if (inputsToCopy > 0)
		{
			// Send Ack
			sf::Packet spac;
			spac << (MessageId)NP_MSG_SLIPPI_PAD_ACK;
			spac << frame;
			spac << playerIdx;
			// INFO_LOG(SLIPPI_ONLINE, "Sending ack packet for frame %d (player %d) to peer at %d:%d", frame,
			// packetPlayerPort,
			//         peer->address.host, peer->address.port);

			ENetPacket *epac = enet_packet_create(spac.getData(), spac.getDataSize(), ENET_PACKET_FLAG_UNSEQUENCED);
			int sendResult = enet_peer_send(peer, 2, epac);
		}
	}
	break;

	case NP_MSG_SLIPPI_PAD_ACK:
	{
		std::lock_guard<std::mutex> lk(ack_mutex); // Trying to fix rare crash on ackTimers.count

		// Store last frame acked
		int32_t frame;
		if (!(packet >> frame))
		{
			ERROR_LOG(SLIPPI_ONLINE, "Ack packet too small to read frame");
			break;
		}
		u8 packetPlayerPort;
		if (!(packet >> packetPlayerPort))
		{
			ERROR_LOG(SLIPPI_ONLINE, "Netplay ack packet too small to read player index");
			break;
		}
		u8 pIdx = PlayerIdxFromPort(packetPlayerPort);
		if (pIdx >= m_remotePlayerCount)
		{
			ERROR_LOG(SLIPPI_ONLINE, "Got ack packet with invalid player idx %d", pIdx);
			break;
		}

		// INFO_LOG(SLIPPI_ONLINE, "Received ack packet from player %d(%d) [%d]...", packetPlayerPort, pIdx, frame);

		lastFrameAcked[pIdx] = frame > lastFrameAcked[pIdx] ? frame : lastFrameAcked[pIdx];

		// Remove old timings
		while (!ackTimers[pIdx].Empty() && ackTimers[pIdx].Front().frame < frame)
		{
			ackTimers[pIdx].Pop();
		}

		// Don't get a ping if we do not have the right ack frame
		if (ackTimers[pIdx].Empty() || ackTimers[pIdx].Front().frame != frame)
		{
			break;
		}

		auto sendTime = ackTimers[pIdx].Front().timeUs;
		ackTimers[pIdx].Pop();

		pingUs[pIdx] = Common::Timer::GetTimeUs() - sendTime;
		pingSampleSumUs.fetch_add(pingUs[pIdx], std::memory_order_relaxed);
		pingSampleCount.fetch_add(1, std::memory_order_relaxed);
#ifdef __ANDROID__
		if (AndroidLatencyTraceEnabled())
		{
			m_ack_count++;
			m_total_ack_us += pingUs[pIdx];
			if (pingUs[pIdx] > m_max_ack_us)
				m_max_ack_us = pingUs[pIdx];
			if (m_ack_count == 1 || m_ack_count % 120 == 0)
			{
				__android_log_print(ANDROID_LOG_INFO, LATENCY_TAG,
				                    "ack frame=%d player=%d rttUs=%" PRIu64
				                    " avgRttUs=%" PRIu64 " maxRttUs=%" PRIu64,
				                    frame, pIdx, pingUs[pIdx], m_total_ack_us / m_ack_count,
				                    m_max_ack_us);
				m_max_ack_us = 0;
			}
		}
#endif
		if (g_ActiveConfig.bShowNetPlayPing && frame % SLIPPI_PING_DISPLAY_INTERVAL == 0 && pIdx == 0)
		{
			std::stringstream pingDisplay;
			pingDisplay << "Ping: " << (pingUs[0] / 1000);
			for (int i = 1; i < m_remotePlayerCount; i++)
			{
				pingDisplay << " | " << (pingUs[i] / 1000);
			}
			OSD::AddTypedMessage(OSD::MessageType::NetPlayPing, pingDisplay.str(), OSD::Duration::NORMAL,
			                     OSD::Color::CYAN);
		}
	}
	break;

	case NP_MSG_SLIPPI_MATCH_SELECTIONS:
	{
		auto s = readSelectionsFromPacket(packet);
		if (!s->error)
		{
			INFO_LOG(SLIPPI_ONLINE, "[Netplay] Received selections from opponent with player idx %d", s->playerIdx);
			u8 idx = PlayerIdxFromPort(s->playerIdx);
			if (idx >= m_remotePlayerCount)
			{
				ERROR_LOG(SLIPPI_ONLINE, "Got match selection packet with invalid player idx %d", idx);
				break;
			}
			matchInfo.remotePlayerSelections[idx].Merge(*s);

			// This might be a good place to reset some logic? Game can't start until we receive this msg
			// so this should ensure that everything is initialized before the game starts
			hasGameStarted = false;

			// Reset remote pad ring such that next inputs are not compared to inputs from last game.
			remotePadRings[idx].Clear();
			remoteChecksumFrame[idx].store(0, std::memory_order_release);
			remoteChecksumValue[idx].store(0, std::memory_order_release);
		}
	}
	break;

	case NP_MSG_SLIPPI_CHAT_MESSAGE:
	{
		auto playerSelection = ReadChatMessageFromPacket(packet);
		INFO_LOG(SLIPPI_ONLINE, "[Netplay] Received chat message from opponent %d: %d", playerSelection->playerIdx,
		         playerSelection->messageId);

		if (!playerSelection->error)
		{
			// set message id to netplay instance
			remoteChatMessageSelection = std::move(playerSelection);
		}
	}
	break;

	case NP_MSG_SLIPPI_CONN_SELECTED:
	{
		// Currently this is unused but the intent is to support two-way simultaneous connection attempts
		isConnectionSelected = true;
	}
	break;

	case NP_MSG_SLIPPI_COMPLETE_STEP:
	{
		SlippiGamePrepStepResults results;

		packet >> results.step_idx;
		packet >> results.char_selection;
		packet >> results.char_color_selection;
		packet >> results.stage_selections[0];
		packet >> results.stage_selections[1];

		gamePrepStepQueue.push_back(results);
	}
	break;

	case NP_MSG_SLIPPI_SYNCED_STATE:
	{
		u8 packetPlayerPort;
		if (!(packet >> packetPlayerPort))
		{
			ERROR_LOG(SLIPPI_ONLINE, "Netplay packet too small to read player index");
			break;
		}
		u8 pIdx = PlayerIdxFromPort(packetPlayerPort);
		if (pIdx >= m_remotePlayerCount)
		{
			ERROR_LOG(SLIPPI_ONLINE, "Got packet with invalid player idx %d", pIdx);
			break;
		}

		SlippiSyncedGameState results;
		packet >> results.match_id;
		packet >> results.game_index;
		packet >> results.tiebreak_index;
		packet >> results.seconds_remaining;
		for (int i = 0; i < 4; i++)
		{
			packet >> results.fighters[i].stocks_remaining;
			packet >> results.fighters[i].current_health;
		}

		// ERROR_LOG(SLIPPI_ONLINE, "Received synced state from opponent. %s, %d, %d, %d. F1: %d (%d%%), F2: %d (%d%%)",
		//         results.match_id.c_str(), results.game_index, results.tiebreak_index, results.seconds_remaining,
		//         results.fighters[0].stocks_remaining, results.fighters[0].current_health,
		//         results.fighters[1].stocks_remaining, results.fighters[1].current_health);

		remote_sync_states[pIdx] = results;
	}
	break;

	default:
		WARN_LOG(SLIPPI_ONLINE, "Unknown message received with id : %d", mid);
		break;
	}

	return 0;
}

void SlippiNetplayClient::writeToPacket(sf::Packet &packet, SlippiPlayerSelections &s)
{
	packet << static_cast<MessageId>(NP_MSG_SLIPPI_MATCH_SELECTIONS);
	packet << s.characterId << s.characterColor << s.isCharacterSelected;
	packet << s.playerIdx;
	packet << s.stageId << s.isStageSelected;
	packet << s.rngOffset;
	packet << s.teamId;
	packet << s.alt_stage_mode;
}

void SlippiNetplayClient::WriteChatMessageToPacket(sf::Packet &packet, int messageId, u8 playerIdx)
{
	packet << static_cast<MessageId>(NP_MSG_SLIPPI_CHAT_MESSAGE);
	packet << messageId;
	packet << playerIdx;
}

std::unique_ptr<SlippiPlayerSelections> SlippiNetplayClient::ReadChatMessageFromPacket(sf::Packet &packet)
{
	auto s = std::make_unique<SlippiPlayerSelections>();

	if (!(packet >> s->messageId))
	{
		ERROR_LOG(SLIPPI_ONLINE, "Chat packet too small to read message ID");
		s->error = true;
		return std::move(s);
	}
	if (!(packet >> s->playerIdx))
	{
		ERROR_LOG(SLIPPI_ONLINE, "Chat packet too small to read player index");
		s->error = true;
		return std::move(s);
	}

	switch (s->messageId)
	{
	// Only these 16 message IDs are allowed
	case 136:
	case 129:
	case 130:
	case 132:
	case 34:
	case 40:
	case 33:
	case 36:
	case 72:
	case 66:
	case 68:
	case 65:
	case 24:
	case 18:
	case 20:
	case 17:
	case SlippiPremadeText::CHAT_MSG_CHAT_DISABLED: // Opponent Chat Message Disabled
	{
		// Good message ID. Do nothing
		break;
	}
	default:
	{
		ERROR_LOG(SLIPPI_ONLINE, "Received invalid chat message index: %d", s->messageId);
		s->error = true;
		break;
	}
	}

	return std::move(s);
}

std::unique_ptr<SlippiPlayerSelections> SlippiNetplayClient::readSelectionsFromPacket(sf::Packet &packet)
{
	auto s = std::make_unique<SlippiPlayerSelections>();

	if (!(packet >> s->characterId))
	{
		ERROR_LOG(SLIPPI_ONLINE, "Received invalid player selection");
		s->error = true;
	}
	if (!(packet >> s->characterColor))
	{
		ERROR_LOG(SLIPPI_ONLINE, "Received invalid player selection");
		s->error = true;
	}
	if (!(packet >> s->isCharacterSelected))
	{
		ERROR_LOG(SLIPPI_ONLINE, "Received invalid player selection");
		s->error = true;
	}
	if (!(packet >> s->playerIdx))
	{
		ERROR_LOG(SLIPPI_ONLINE, "Received invalid player selection");
		s->error = true;
	}
	if (!(packet >> s->stageId))
	{
		ERROR_LOG(SLIPPI_ONLINE, "Received invalid player selection");
		s->error = true;
	}
	if (!(packet >> s->isStageSelected))
	{
		ERROR_LOG(SLIPPI_ONLINE, "Received invalid player selection");
		s->error = true;
	}
	if (!(packet >> s->rngOffset))
	{
		ERROR_LOG(SLIPPI_ONLINE, "Received invalid player selection");
		s->error = true;
	}
	if (!(packet >> s->teamId))
	{
		ERROR_LOG(SLIPPI_ONLINE, "Received invalid player selection");
		s->error = true;
	}
	if (!(packet >> s->alt_stage_mode))
	{
		ERROR_LOG(SLIPPI_ONLINE, "Received invalid player selection");
		s->error = true;
	}
	return std::move(s);
}

void SlippiNetplayClient::Send(sf::Packet &packet)
{
	enet_uint32 flags = ENET_PACKET_FLAG_RELIABLE;
	u8 channelId = 0;

	for (int i = 0; i < m_server.size(); i++)
	{
		// Check if this specific peer is disconnected via activeConnections
		std::stringstream keyStrm;
		keyStrm << m_server[i]->address.host << "-" << m_server[i]->address.port;
		auto connIt = activeConnections.find(keyStrm.str());
		if (connIt != activeConnections.end())
		{
			auto peerIt = connIt->second.find(m_server[i]);
			if (peerIt != connIt->second.end() && peerIt->second.isDisconnected)
				continue;
		}

		MessageId mid = ((u8 *)packet.getData())[0];
		if (mid == NP_MSG_SLIPPI_PAD || mid == NP_MSG_SLIPPI_PAD_ACK)
		{
			// Slippi communications do not need reliable connection and do not need to
			// be received in order. Channel is changed so that other reliable communications
			// do not block anything. This may not be necessary if order is not maintained?
			flags = ENET_PACKET_FLAG_UNSEQUENCED;
			channelId = 1;
		}

		ENetPacket *epac = enet_packet_create(packet.getData(), packet.getDataSize(), flags);
		int sendResult = enet_peer_send(m_server[i], channelId, epac);
	}
}

void SlippiNetplayClient::SendSlippiPadBatchAsync(
    const std::array<SlippiPad, SLIPPI_PAD_RING_CAPACITY> &pads, size_t padCount)
{
	if (padCount == 0)
		return;

	if (slippiConnectStatus.load(std::memory_order_acquire) ==
	    SlippiConnectStatus::NET_CONNECT_STATUS_DISCONNECTED)
	{
		return;
	}

	size_t head = m_pad_packet_head.load(std::memory_order_acquire);
	size_t tail = m_pad_packet_tail.load(std::memory_order_acquire);
	size_t waitCount = 0;
	while (head - tail >= SLIPPI_PAD_PACKET_QUEUE_CAPACITY)
	{
		auto status = slippiConnectStatus.load(std::memory_order_acquire);
		if (status == SlippiConnectStatus::NET_CONNECT_STATUS_FAILED ||
		    status == SlippiConnectStatus::NET_CONNECT_STATUS_DISCONNECTED)
		{
			return;
		}

		if (waitCount == 0 || waitCount % 60 == 0)
		{
			WARN_LOG(SLIPPI_ONLINE,
			         "Waiting for Slippi pad packet ring to drain; outbound queue is full");
		}
		if (waitCount >= 120)
		{
			ERROR_LOG(SLIPPI_ONLINE,
			          "Disconnecting after Slippi pad packet ring stayed full for %zu ms",
			          waitCount);
			slippiConnectStatus.store(SlippiConnectStatus::NET_CONNECT_STATUS_DISCONNECTED,
			                          std::memory_order_release);
			if (m_client)
				ENetUtil::WakeupThread(m_client);
			return;
		}
		waitCount++;
		Common::SleepCurrentThread(1);
		head = m_pad_packet_head.load(std::memory_order_acquire);
		tail = m_pad_packet_tail.load(std::memory_order_acquire);
	}

	QueuedPadPacket &queued = m_pad_packet_queue[head % SLIPPI_PAD_PACKET_QUEUE_CAPACITY];
	queued.frame = pads[0].frame;
	queued.playerIdx = playerIdx;
	queued.checksumFrame = pads[0].checksumFrame;
	queued.checksum = pads[0].checksum;
	queued.padCount = std::min(padCount, static_cast<size_t>(SLIPPI_PAD_RING_CAPACITY));
	for (size_t i = 0; i < queued.padCount; i++)
	{
		std::memcpy(&queued.padBytes[i * SLIPPI_PAD_DATA_SIZE], pads[i].padBuf,
		            SLIPPI_PAD_DATA_SIZE);
	}

	m_pad_packet_head.store(head + 1, std::memory_order_release);
	m_async_queue_depth.fetch_add(1, std::memory_order_relaxed);
	if (m_client)
		ENetUtil::WakeupThread(m_client);
}

void SlippiNetplayClient::DrainQueuedPadPackets()
{
	size_t tail = m_pad_packet_tail.load(std::memory_order_acquire);
	size_t head = m_pad_packet_head.load(std::memory_order_acquire);
	while (tail < head)
	{
		SendQueuedPadPacket(m_pad_packet_queue[tail % SLIPPI_PAD_PACKET_QUEUE_CAPACITY]);
		m_pad_packet_tail.store(tail + 1, std::memory_order_release);
		m_async_queue_depth.fetch_sub(1, std::memory_order_relaxed);
		tail = m_pad_packet_tail.load(std::memory_order_acquire);
		head = m_pad_packet_head.load(std::memory_order_acquire);
	}
}

void SlippiNetplayClient::SendQueuedPadPacket(const QueuedPadPacket &queued)
{
	sf::Packet packet;
	packet << static_cast<MessageId>(NP_MSG_SLIPPI_PAD);
	packet << queued.frame;
	packet << queued.playerIdx;
	packet << queued.checksumFrame;
	packet << queued.checksum;
	for (size_t i = 0; i < queued.padCount; i++)
	{
		packet.append(&queued.padBytes[i * SLIPPI_PAD_DATA_SIZE], SLIPPI_PAD_DATA_SIZE);
	}
	Send(packet);
}

void SlippiNetplayClient::Disconnect()
{
	ENetEvent netEvent;
	slippiConnectStatus.store(SlippiConnectStatus::NET_CONNECT_STATUS_DISCONNECTED, std::memory_order_release);
	if (activeConnections.empty())
	{
		return;
	}

	for (auto conn : activeConnections)
	{
		for (auto peer : conn.second)
		{
			// Carry the pending reason (default 0 = unspecified) so an intentional teardown such as a
			// poor-performance termination reaches the peer. In 1v1, ForceDisconnect flips the status to
			// DISCONNECTED before the network loop's force-kick runs, so this teardown call is the one
			// that actually reaches the peer — it must forward the reason rather than send 0.
			u32 reason = m_pendingDisconnectReason.load(std::memory_order_acquire);
			INFO_LOG(SLIPPI_ONLINE, "[Netplay] Disconnecting peer %d with reason %u", peer.first->address.port, reason);
			enet_peer_disconnect(peer.first, reason);
		}
	}

	while (enet_host_service(m_client, &netEvent, 3000) > 0)
	{
		switch (netEvent.type)
		{
		case ENET_EVENT_TYPE_RECEIVE:
			enet_packet_destroy(netEvent.packet);
			break;
		case ENET_EVENT_TYPE_DISCONNECT:
			INFO_LOG(SLIPPI_ONLINE, "[Netplay] Got disconnect from peer %d", netEvent.peer->address.port);
			break;
		default:
			break;
		}
	}

	// didn't disconnect gracefully force disconnect
	for (auto conn : activeConnections)
	{
		for (auto peer : conn.second)
		{
			enet_peer_reset(peer.first);
		}
	}
	activeConnections.clear();
	for (auto &active : playerActive)
		active.store(false, std::memory_order_release);
	m_server.clear();
	SLIPPI_NETPLAY = nullptr;
}

void SlippiNetplayClient::SendAsync(std::unique_ptr<sf::Packet> packet)
{
	// Drop outbound packets once we've decided to tear down. Saves work and avoids
	// queuing sends that the network thread is about to stop draining anyway.
	if (slippiConnectStatus.load(std::memory_order_acquire) == SlippiConnectStatus::NET_CONNECT_STATUS_DISCONNECTED)
	{
		return;
	}

	{
		std::lock_guard<std::recursive_mutex> lkq(m_crit.async_queue_write);
		m_async_queue.Push(std::move(packet));
		m_async_queue_depth.fetch_add(1, std::memory_order_relaxed);
	}
	ENetUtil::WakeupThread(m_client);
}

// called from ---NETPLAY--- thread
void SlippiNetplayClient::ThreadFunc()
{
	Common::SetCurrentThreadName("NetPlay Client");
#ifdef __ANDROID__
	setpriority(PRIO_PROCESS, 0, -8);
#endif

	// Let client die 1 second before host such that after a swap, the client won't be connected to
	u64 startTime = Common::Timer::GetTimeMs();
	u64 timeout = 8000;

	std::vector<bool> connections;
	std::vector<ENetAddress> remoteAddrs;
	for (int i = 0; i < m_remotePlayerCount; i++)
	{
		remoteAddrs.push_back(m_server[i]->address);
		connections.push_back(false);
	}

	while (slippiConnectStatus.load(std::memory_order_acquire) == SlippiConnectStatus::NET_CONNECT_STATUS_INITIATED)
	{
		// This will confirm that connection went through successfully
		ENetEvent netEvent;
		int net = enet_host_service(m_client, &netEvent, 500);
		if (net > 0)
		{
			sf::Packet rpac;
			switch (netEvent.type)
			{
			case ENET_EVENT_TYPE_RECEIVE:
				if (!netEvent.peer)
				{
					INFO_LOG(SLIPPI_ONLINE, "[Netplay] got receive event with nil peer");
					continue;
				}
				INFO_LOG(SLIPPI_ONLINE, "[Netplay] got receive event with peer addr %x:%d", netEvent.peer->address.host,
				         netEvent.peer->address.port);
				rpac.append(netEvent.packet->data, netEvent.packet->dataLength);

				OnData(rpac, netEvent.peer);

				enet_packet_destroy(netEvent.packet);
				break;

			case ENET_EVENT_TYPE_DISCONNECT:
				if (!netEvent.peer)
				{
					INFO_LOG(SLIPPI_ONLINE, "[Netplay] got disconnect event with nil peer");
					continue;
				}
				INFO_LOG(SLIPPI_ONLINE, "[Netplay] got disconnect event with peer addr %x:%d. %x",
				         netEvent.peer->address.host, netEvent.peer->address.port, netEvent.peer);
				break;

			case ENET_EVENT_TYPE_CONNECT:
			{
				if (!netEvent.peer)
				{
					INFO_LOG(SLIPPI_ONLINE, "[Netplay] got connect event with nil peer");
					continue;
				}

				std::stringstream keyStrm;
				keyStrm << netEvent.peer->address.host << "-" << netEvent.peer->address.port;
				int earlyConnRemoteIdx = 0;
				for (int i = 0; i < (int)remoteAddrs.size(); i++)
				{
					if (remoteAddrs[i].host == netEvent.peer->address.host &&
					    remoteAddrs[i].port == netEvent.peer->address.port)
					{
						earlyConnRemoteIdx = i;
						break;
					}
				}
				ActiveConnectionInfo earlyConnInfo;
				earlyConnInfo.playerIdx = matchInfo.remotePlayerSelections[earlyConnRemoteIdx].playerIdx;
				activeConnections[keyStrm.str()][netEvent.peer] = earlyConnInfo;
				playerActive[earlyConnInfo.playerIdx].store(true, std::memory_order_release);

				// INFO_LOG(SLIPPI_ONLINE, "[Netplay] got connect event with peer addr %x:%d. %x",
				//         netEvent.peer->address.host, netEvent.peer->address.port, netEvent.peer);

				auto isAlreadyConnected = false;
				for (int i = 0; i < m_server.size(); i++)
				{
					if (connections[i] && netEvent.peer->address.host == m_server[i]->address.host &&
					    netEvent.peer->address.port == m_server[i]->address.port)
					{
						m_server[i] = netEvent.peer;
						isAlreadyConnected = true;
						break;
					}
				}

				if (isAlreadyConnected)
				{
					// Don't add this person again if they are already connected. Not doing this can cause one person to
					// take up 2 or more spots, denying one or more players from connecting and thus getting stuck on
					// the "Waiting" step
					INFO_LOG(SLIPPI_ONLINE, "Already connected!");
					break; // Breaks out of case
				}

				for (int i = 0; i < m_server.size(); i++)
				{
					// This check used to check for port as well as host. The problem was that for some people, their
					// internet will switch the port they're sending from. This means these people struggle to connect
					// to others but they sometimes do succeed. When we were checking for port here though we would get
					// into a state where the person they succeeded to connect to would not accept the connection with
					// them, this would lead the player with this internet issue to get stuck waiting for the other
					// player. The only downside to this that I can guess is that if you fail to connect to one person
					// out of two that are on your LAN, it might report that you failed to connect to the wrong person.
					// There might be more problems tho, not sure
					INFO_LOG(SLIPPI_ONLINE, "[Netplay] Comparing connection address: %x - %x", remoteAddrs[i].host,
					         netEvent.peer->address.host);
					if (remoteAddrs[i].host == netEvent.peer->address.host && !connections[i])
					{
						INFO_LOG(SLIPPI_ONLINE, "[Netplay] Overwriting ENetPeer for address: %x:%d",
						         netEvent.peer->address.host, netEvent.peer->address.port);
						INFO_LOG(SLIPPI_ONLINE, "[Netplay] Overwriting ENetPeer with id (%d) with new peer of id %d",
						         m_server[i]->connectID, netEvent.peer->connectID);
						m_server[i] = netEvent.peer;
						connections[i] = true;
						break;
					}
				}
				break;
			}
			}
		}

		bool allConnected = true;
		for (int i = 0; i < m_remotePlayerCount; i++)
		{
			if (!connections[i])
				allConnected = false;
		}

		if (allConnected)
		{
			m_client->intercept = ENetUtil::InterceptCallback;
			INFO_LOG(SLIPPI_ONLINE, "Slippi online connection successful!");
			slippiConnectStatus.store(SlippiConnectStatus::NET_CONNECT_STATUS_CONNECTED, std::memory_order_release);
			break;
		}

		for (int i = 0; i < m_remotePlayerCount; i++)
		{
			INFO_LOG(SLIPPI_ONLINE, "m_client peer %d state: %d", i, m_client->peers[i].state);
		}
		INFO_LOG(SLIPPI_ONLINE, "[Netplay] Not yet connected. Res: %d, Type: %d", net, netEvent.type);

		// Time out after enough time has passed
		u64 curTime = Common::Timer::GetTimeMs();
		if ((curTime - startTime) >= timeout || !m_do_loop.IsSet())
		{
			for (int i = 0; i < m_remotePlayerCount; i++)
			{
				if (!connections[i])
				{
					failedConnections.push_back(i);
				}
			}

			slippiConnectStatus.store(SlippiConnectStatus::NET_CONNECT_STATUS_FAILED, std::memory_order_release);
			INFO_LOG(SLIPPI_ONLINE, "Slippi online connection failed");
			return;
		}
	}

	INFO_LOG(SLIPPI_ONLINE, "Successfully initialized %d connections", m_server.size());
	for (int i = 0; i < m_server.size(); i++)
	{
		INFO_LOG(SLIPPI_ONLINE, "Connection %d: %d, %d", i, m_server[i]->address.host, m_server[i]->address.port);
	}

	bool qos_success = false;
#ifdef _WIN32
	QOS_VERSION ver = {1, 0};

	if (SConfig::GetInstance().bQoSEnabled && QOSCreateHandle(&ver, &m_qos_handle))
	{
		for (int i = 0; i < m_server.size(); i++)
		{
			// from win32.c
			struct sockaddr_in sin = {0};

			sin.sin_family = AF_INET;
			sin.sin_port = ENET_HOST_TO_NET_16(m_server[i]->host->address.port);
			sin.sin_addr.s_addr = m_server[i]->host->address.host;

			if (QOSAddSocketToFlow(m_qos_handle, m_server[i]->host->socket, reinterpret_cast<PSOCKADDR>(&sin),
			                       // this is 0x38
			                       QOSTrafficTypeControl, QOS_NON_ADAPTIVE_FLOW, &m_qos_flow_id))
			{
				DWORD dscp = 0x2e;

				// this will fail if we're not admin
				// sets DSCP to the same as linux (0x2e)
				QOSSetFlow(m_qos_handle, m_qos_flow_id, QOSSetOutgoingDSCPValue, sizeof(DWORD), &dscp, 0, nullptr);

				qos_success = true;
			}
		}
	}
#else
	if (SConfig::GetInstance().bQoSEnabled)
	{
		for (int i = 0; i < m_server.size(); i++)
		{
#ifdef __APPLE__
			// Apple systems don't support SO_PRIORITY on BSD sockets, but they do support a flag for
			// marking sockets as a specific type of traffic. `NET_SERVICE_TYPE_RV` should roughly
			// correspond to "low delay tolerant, low-medium loss tolerant, elastic flow, variable
			// packet interval, rate and size".
			int srv_type = NET_SERVICE_TYPE_RV;
			setsockopt(m_server[i]->host->socket, SOL_SOCKET, SO_NET_SERVICE_TYPE, &srv_type, sizeof(srv_type));
#endif

#ifdef __linux__
			// highest priority
			int priority = 7;
			setsockopt(m_server[i]->host->socket, SOL_SOCKET, SO_PRIORITY, &priority, sizeof(priority));
#endif

			// https://www.tucny.com/Home/dscp-tos
			// ef is better than cs7
			int tos_val = 0xb8;
			qos_success = setsockopt(m_server[i]->host->socket, IPPROTO_IP, IP_TOS, &tos_val, sizeof(tos_val)) == 0;
		}
	}
#endif

	while (m_do_loop.IsSet())
	{
		// If anyone (e.g. ForceDisconnectPlayer on the EXI thread) flipped the status
		// to DISCONNECTED, exit the loop so the existing teardown path runs Disconnect()
		// and tears down all ENet peers cleanly.
		if (slippiConnectStatus.load(std::memory_order_acquire) == SlippiConnectStatus::NET_CONNECT_STATUS_DISCONNECTED)
		{
			INFO_LOG(SLIPPI_ONLINE, "[Netplay] Status is DISCONNECTED, exiting netplay thread loop");
			break;
		}

		// If a player has been marked inactive (e.g. by ForceDisconnectPlayer on the
		// EXI thread) but their peer hasn't been torn down yet, kick the peer now so
		// the connection doesn't linger. We mark the activeConnections entry as
		// disconnected immediately to avoid re-issuing the disconnect on subsequent
		// loop iterations; the eventual ENET_EVENT_TYPE_DISCONNECT handler is a no-op
		// in that case (already marked).
		for (auto &connEntry : activeConnections)
		{
			for (auto &peerEntry : connEntry.second)
			{
				if (peerEntry.second.isDisconnected)
					continue;
				if (playerActive[peerEntry.second.playerIdx].load(std::memory_order_acquire))
					continue;
				INFO_LOG(SLIPPI_ONLINE, "[Netplay] Force-disconnecting ENet peer %x:%d for player %d",
				         peerEntry.first->address.host, peerEntry.first->address.port, peerEntry.second.playerIdx);
				enet_peer_disconnect(peerEntry.first, m_pendingDisconnectReason.load(std::memory_order_acquire));
				peerEntry.second.isDisconnected = true;
			}
		}

		ENetEvent netEvent;
		int net;
		net = enet_host_service(m_client, &netEvent, 4);
		DrainQueuedPadPackets();
		while (!m_async_queue.Empty())
		{
			m_async_queue_depth.fetch_sub(1, std::memory_order_relaxed);
			Send(*(m_async_queue.Front().get()));
			m_async_queue.Pop();
		}

		if (net > 0)
		{
			sf::Packet rpac;
			bool isConnectedClient = false;
			switch (netEvent.type)
			{
			case ENET_EVENT_TYPE_RECEIVE:
			{
				rpac.append(netEvent.packet->data, netEvent.packet->dataLength);
				OnData(rpac, netEvent.peer);
				enet_packet_destroy(netEvent.packet);
				break;
			}
			case ENET_EVENT_TYPE_DISCONNECT:
			{
				std::stringstream keyStrm;
				keyStrm << netEvent.peer->address.host << "-" << netEvent.peer->address.port;
				auto key = keyStrm.str();
				if (activeConnections.count(key) && activeConnections[key].count(netEvent.peer))
				{
					activeConnections[key][netEvent.peer].isDisconnected = true;
				}

				bool allPeersDisconnectedForKey = AreAllPeersDisconnectedForKey(key);

				// If this was the last live peer for that player, publish them as inactive
				// to the lock-free playerActive view used by the main thread.
				if (allPeersDisconnectedForKey && activeConnections.count(key) &&
				    activeConnections[key].count(netEvent.peer))
				{
					auto playerIdxForKey = activeConnections[key][netEvent.peer].playerIdx;
					playerActive[playerIdxForKey].store(false, std::memory_order_release);
				}

				// Check to make sure this address+port are one of the ones we are actually connected to.
				// When connecting to someone that randomizes ports, you can get one valid connection from
				// one port and a failed connection on another port. We don't want to cause a real disconnect
				// if we receive a disconnect message from the port we never connected to
				bool isConnectedClient = false;
				for (int i = 0; i < m_server.size(); i++)
				{
					if (netEvent.peer->address.host == m_server[i]->address.host &&
					    netEvent.peer->address.port == m_server[i]->address.port)
					{
						isConnectedClient = true;
						break;
					}
				}

				// Capture any reason the peer encoded in the disconnect data (e.g. poor performance) so the
				// EXI thread can surface the same UI we'd show on the initiating side. Only a deliberate
				// disconnect sends a non-zero value; organic disconnects send 0.
				if (isConnectedClient && netEvent.data != 0)
					m_disconnectReason.store(netEvent.data, std::memory_order_release);

				INFO_LOG(SLIPPI_ONLINE,
				         "[Netplay] Disconnect late %x:%d. %x. All peers disconnected: %s. Is connected client: %s",
				         netEvent.peer->address.host, netEvent.peer->address.port, netEvent.peer,
				         allPeersDisconnectedForKey ? "true" : "false", isConnectedClient ? "true" : "false");

				// If the disconnect event doesn't come from the client we are actually listening to,
				// it can be safely ignored
				if (isConnectedClient && allPeersDisconnectedForKey)
				{
					INFO_LOG(SLIPPI_ONLINE, "[Netplay] Final disconnect received for a client.");

					if (AreAllConnectionsDisconnected())
					{
						m_do_loop.Clear(); // Stop the loop, will trigger a disconnect
					}
				}
				break;
			}
			case ENET_EVENT_TYPE_CONNECT:
			{
				std::stringstream keyStrm;
				keyStrm << netEvent.peer->address.host << "-" << netEvent.peer->address.port;
				int lateConnRemoteIdx = 0;
				for (int i = 0; i < (int)m_server.size(); i++)
				{
					if (m_server[i]->address.host == netEvent.peer->address.host &&
					    m_server[i]->address.port == netEvent.peer->address.port)
					{
						lateConnRemoteIdx = i;
						break;
					}
				}
				ActiveConnectionInfo lateConnInfo;
				lateConnInfo.playerIdx = matchInfo.remotePlayerSelections[lateConnRemoteIdx].playerIdx;
				activeConnections[keyStrm.str()][netEvent.peer] = lateConnInfo;
				playerActive[lateConnInfo.playerIdx].store(true, std::memory_order_release);
				INFO_LOG(SLIPPI_ONLINE, "New connection (late): %s, %X", keyStrm.str().c_str(), netEvent.peer);
				break;
			}
			default:
				break;
			}
		}
	}

#ifdef _WIN32
	if (m_qos_handle != 0)
	{
		if (m_qos_flow_id != 0)
		{
			for (int i = 0; i < m_server.size(); i++)
			{
				QOSRemoveSocketFromFlow(m_qos_handle, m_server[i]->host->socket, m_qos_flow_id, 0);
			}
		}

		QOSCloseHandle(m_qos_handle);
	}
#endif

	Disconnect();
	return;
}

bool SlippiNetplayClient::IsDecider()
{
	return isDecider;
}

bool SlippiNetplayClient::IsConnectionSelected()
{
	return isConnectionSelected;
}

SlippiNetplayClient::SlippiConnectStatus SlippiNetplayClient::GetSlippiConnectStatus()
{
	return slippiConnectStatus.load(std::memory_order_acquire);
}

std::vector<int> SlippiNetplayClient::GetFailedConnections()
{
	return failedConnections;
}

void SlippiNetplayClient::StartSlippiGame()
{
	// Reset variables to start a new game
	hasGameStarted = false;

	localPadRing.Clear();

	for (int i = 0; i < m_remotePlayerCount; i++)
	{
		FrameTiming timing;
		timing.frame = 0;
		timing.timeUs = Common::Timer::GetTimeUs();
		lastFrameTiming[i] = timing;
		lastFrameAcked[i] = 0;

		// Reset ack timers
		ackTimers[i].Clear();
	}

	is_desync_recovery = false;

	// Clear game prep queue in case anything is still lingering
	gamePrepStepQueue.clear();

	// Reset match info for next game
	matchInfo.Reset();
}

void SlippiNetplayClient::SendConnectionSelected()
{
	isConnectionSelected = true;

	auto spac = std::make_unique<sf::Packet>();
	*spac << static_cast<MessageId>(NP_MSG_SLIPPI_CONN_SELECTED);
	SendAsync(std::move(spac));
}

void SlippiNetplayClient::SendSlippiPad(const SlippiPad *pad)
{
	auto status = slippiConnectStatus.load(std::memory_order_acquire);
	bool connectionFailed = status == SlippiNetplayClient::SlippiConnectStatus::NET_CONNECT_STATUS_FAILED;
	bool connectionDisconnected = status == SlippiNetplayClient::SlippiConnectStatus::NET_CONNECT_STATUS_DISCONNECTED;
	if (connectionFailed || connectionDisconnected)
	{
		return;
	}

	// if (pad && isDecider)
	//{
	//  ERROR_LOG(SLIPPI_ONLINE, "[%d] %X %X %X %X %X %X %X %X", pad->frame, pad->padBuf[0], pad->padBuf[1],
	//  pad->padBuf[2], pad->padBuf[3], pad->padBuf[4], pad->padBuf[5], pad->padBuf[6], pad->padBuf[7]);
	//}

	if (pad)
	{
		// Add latest local pad report to queue
#ifdef __ANDROID__
		if (AndroidLatencyTraceEnabled())
		{
			const uint64_t age_us = SI_PadOverride::LatestSetAgeUs(playerIdx);
			if (age_us > m_max_pad_override_age_us)
				m_max_pad_override_age_us = age_us;
		}
#endif
		localPadRing.Push(*pad);
#ifdef __ANDROID__
		RecordLocalPadQueueDiagnostic("local_queue_push", pad, 0);
#endif
	}

	// Remove pad reports that have been received and acked. Skip disconnected players,
	// otherwise their lastFrameAcked is stuck and minAckFrame never advances
	int minAckFrame = INT_MAX;
	for (int i = 0; i < m_remotePlayerCount; i++)
	{
		auto remotePlayerIdx = matchInfo.remotePlayerSelections[i].playerIdx;
		if (!playerActive[remotePlayerIdx].load(std::memory_order_acquire))
			continue;
		if (lastFrameAcked[i] < minAckFrame)
			minAckFrame = lastFrameAcked[i];
	}

	// Cap how far behind minAckFrame is allowed to fall. This protects against a peer
	// that stops acking. The value used should be sensibly large enough to prevent
	// any issues
	auto currentFrame = localPadRing.LatestFrame();
	if (currentFrame != INT_MIN)
	{
		int minimumAllowed = currentFrame - 128; // Large enough for any reasonable delay combinations
		minAckFrame = std::max(minAckFrame, minimumAllowed);
	}
	localPadRing.DropBefore(minAckFrame);

	if (currentFrame == INT_MIN)
	{
		// If pad queue is empty now, there's no reason to send anything
		return;
	}

	std::array<SlippiPad, SLIPPI_PAD_RING_CAPACITY> padsToSend;
	size_t padCount = localPadRing.CopyNewestFirst(minAckFrame, SLIPPI_PAD_RING_CAPACITY, &padsToSend);
	if (padCount == 0)
		return;

#ifdef __ANDROID__
	RecordLocalPadQueueDiagnostic("local_queue_send_front", &padsToSend[0], padCount);
#endif

	auto frame = padsToSend[0].frame;

	SendSlippiPadBatchAsync(padsToSend, padCount);

	u64 time = Common::Timer::GetTimeUs();
#ifdef __ANDROID__
	if (AndroidLatencyTraceEnabled())
	{
		m_pad_send_count++;
		const uint64_t age_us = SI_PadOverride::LatestSetAgeUs(playerIdx);
		m_total_pad_override_age_us += age_us;
		if (age_us > m_max_pad_override_age_us)
			m_max_pad_override_age_us = age_us;
		if (m_pad_send_count == 1 || m_pad_send_count % 120 == 0)
		{
			__android_log_print(ANDROID_LOG_INFO, LATENCY_TAG,
			                    "sendPad frame=%d queue=%zu asyncDepth=%d padAgeUs=%" PRIu64
			                    " avgPadAgeUs=%" PRIu64 " maxPadAgeUs=%" PRIu64,
			                    frame, padCount,
			                    m_async_queue_depth.load(std::memory_order_relaxed), age_us,
			                    m_total_pad_override_age_us / m_pad_send_count,
			                    m_max_pad_override_age_us);
			m_max_pad_override_age_us = 0;
		}
	}
#endif

	hasGameStarted = true;

	for (int i = 0; i < m_remotePlayerCount; i++)
	{
		FrameTiming timing;
		timing.frame = frame;
		timing.timeUs = time;
		lastFrameTiming[i] = timing;

		// Add send time to ack timers
		FrameTiming sendTime;
		sendTime.frame = frame;
		sendTime.timeUs = time;
		ackTimers[i].Push(sendTime);
	}
}

void SlippiNetplayClient::SetMatchSelections(SlippiPlayerSelections &s)
{
	matchInfo.localPlayerSelections.Merge(s);
	matchInfo.localPlayerSelections.playerIdx = playerIdx;

	// Send packet containing selections
	auto spac = std::make_unique<sf::Packet>();
	INFO_LOG(SLIPPI_ONLINE, "Setting match selections for %d", playerIdx);
	writeToPacket(*spac, matchInfo.localPlayerSelections);
	SendAsync(std::move(spac));
}

void SlippiNetplayClient::SendGamePrepStep(SlippiGamePrepStepResults &s)
{
	auto spac = std::make_unique<sf::Packet>();
	*spac << static_cast<MessageId>(NP_MSG_SLIPPI_COMPLETE_STEP);
	*spac << s.step_idx;
	*spac << s.char_selection;
	*spac << s.char_color_selection;
	*spac << s.stage_selections[0] << s.stage_selections[1];
	SendAsync(std::move(spac));
}

void SlippiNetplayClient::SendSyncedGameState(SlippiSyncedGameState &s)
{
	// WARN_LOG(SLIPPI_ONLINE, "Sending synced state. %s, %d, %d, %d. F1: %d (%d%%), F2: %d (%d%%)",
	//          s.match_id.c_str(), s.game_index, s.tiebreak_index, s.seconds_remaining,
	//          s.fighters[0].stocks_remaining, s.fighters[0].current_health,
	//          s.fighters[1].stocks_remaining, s.fighters[1].current_health);

	is_desync_recovery = true;
	local_sync_state = s;

	auto spac = std::make_unique<sf::Packet>();
	*spac << static_cast<MessageId>(NP_MSG_SLIPPI_SYNCED_STATE);
	*spac << this->playerIdx;
	*spac << s.match_id;
	*spac << s.game_index;
	*spac << s.tiebreak_index;
	*spac << s.seconds_remaining;
	for (int i = 0; i < 4; i++)
	{
		*spac << s.fighters[i].stocks_remaining;
		*spac << s.fighters[i].current_health;
	}
	SendAsync(std::move(spac));
}

bool SlippiNetplayClient::GetGamePrepResults(u8 stepIdx, SlippiGamePrepStepResults &res)
{
	// Just pull stuff off until we find something for the right step. I think that should be fine
	while (!gamePrepStepQueue.empty())
	{
		auto front = gamePrepStepQueue.front();
		if (front.step_idx == stepIdx)
		{
			res = front;
			return true;
		}

		gamePrepStepQueue.pop_front();
	}

	return false;
}

SlippiPlayerSelections SlippiNetplayClient::GetSlippiRemoteChatMessage(bool isChatEnabled)
{
	SlippiPlayerSelections copiedSelection = SlippiPlayerSelections();

	if (remoteChatMessageSelection != nullptr && isChatEnabled)
	{
		copiedSelection.messageId = remoteChatMessageSelection->messageId;
		copiedSelection.playerIdx = remoteChatMessageSelection->playerIdx;

		// Clear it out
		remoteChatMessageSelection->messageId = 0;
		remoteChatMessageSelection->playerIdx = 0;
	}
	else
	{
		copiedSelection.messageId = 0;
		copiedSelection.playerIdx = 0;

		// if chat is not enabled, automatically send back a message saying so.
		if (remoteChatMessageSelection != nullptr && !isChatEnabled &&
		    (remoteChatMessageSelection->messageId > 0 &&
		     remoteChatMessageSelection->messageId != SlippiPremadeText::CHAT_MSG_CHAT_DISABLED))
		{
			auto packet = std::make_unique<sf::Packet>();
			remoteSentChatMessageId = SlippiPremadeText::CHAT_MSG_CHAT_DISABLED;
			WriteChatMessageToPacket(*packet, remoteSentChatMessageId, LocalPlayerPort());
			SendAsync(std::move(packet));
			remoteSentChatMessageId = 0;
			remoteChatMessageSelection = nullptr;
		}
	}

	return copiedSelection;
}

u8 SlippiNetplayClient::GetSlippiRemoteSentChatMessage(bool isChatEnabled)
{
	if (!isChatEnabled)
	{
		return 0;
	}
	u8 copiedMessageId = remoteSentChatMessageId;
	remoteSentChatMessageId = 0; // Clear it out
	return copiedMessageId;
}

SlippiRemotePadOutput SlippiNetplayClient::GetFakePadOutput(int frame) {
	// Used for testing purposes, will ignore the opponent's actual inputs and provide fake
	// ones to trigger rollback scenarios
	SlippiRemotePadOutput padOutput;

	// Triggers rollback where the first few inputs were correctly predicted
	if (frame % 60 < 5)
	{
		// Return old inputs for a bit
		padOutput.latestFrame = frame - (frame % 60);
		padOutput.dataLen = SLIPPI_PAD_FULL_SIZE;
	}
	else if (frame % 60 == 5)
	{
		padOutput.latestFrame = frame;
		padOutput.dataLen = 5 * SLIPPI_PAD_FULL_SIZE;

		// Press A button for 2 inputs prior to this frame causing a rollback
		padOutput.data[2 * SLIPPI_PAD_FULL_SIZE] = 1;
	}
	else
	{
		padOutput.latestFrame = frame;
		padOutput.dataLen = SLIPPI_PAD_FULL_SIZE;
	}

	return padOutput;
}

SlippiRemotePadOutput SlippiNetplayClient::GetSlippiRemotePad(int index, int maxFrameCount)
{
	SlippiRemotePadOutput padOutput;

	if (index < 0 || index >= SLIPPI_REMOTE_PLAYER_MAX)
	{
		return padOutput;
	}

	padOutput.latestFrame = 0;
	padOutput.checksumFrame = remoteChecksumFrame[index].load(std::memory_order_acquire);
	padOutput.checksum = remoteChecksumValue[index].load(std::memory_order_acquire);
	padOutput.playerIdx = index >= playerIdx ? index + 1 : index;
	padOutput.isDisconnected = !playerActive[padOutput.playerIdx].load(std::memory_order_acquire);
	padOutput.dataLen = remotePadRings[index].CopyNewestFirst(
	    static_cast<size_t>(maxFrameCount), padOutput.data.data(), padOutput.data.size(),
	    &padOutput.latestFrame);
	if (padOutput.dataLen == 0)
	{
		SlippiPad emptyPad(padOutput.latestFrame);
		std::memcpy(padOutput.data.data(), emptyPad.padBuf, SLIPPI_PAD_FULL_SIZE);
		padOutput.dataLen = SLIPPI_PAD_FULL_SIZE;
	}

	return padOutput;
}

void SlippiNetplayClient::DropOldRemoteInputs(int32_t finalizedFrame)
{
	for (int i = 0; i < m_remotePlayerCount; i++)
	{
		remotePadRings[i].DropBefore(finalizedFrame);
	}
}

std::unordered_map<u8, bool> SlippiNetplayClient::GetActivePlayerIndices()
{
	// Lock-free: reads the playerActive atomics maintained by the network thread.
	std::unordered_map<u8, bool> result;
	for (u8 i = 0; i < SLIPPI_PLAYER_COUNT_MAX; i++)
	{
		if (playerActive[i].load(std::memory_order_acquire))
			result[i] = true;
	}
	return result;
}

void SlippiNetplayClient::ForceDisconnectPlayer(u8 playerIdx)
{
	// Flips the lock-free liveness view to false so the main/EXI thread treats this
	// player as disconnected. The network thread observes !playerActive on its next
	// loop iteration and issues enet_peer_disconnect on any still-live peer for this
	// player so the connection doesn't linger.
	if (playerIdx >= SLIPPI_PLAYER_COUNT_MAX)
		return;
	playerActive[playerIdx].store(false, std::memory_order_release);

	// If no remote players are still active, transition the overall status to
	// DISCONNECTED so the existing disconnect-detection paths fire (game ends in
	// 1v1; in multiplayer this branch only runs once everyone else has dropped).
	// The network thread observes this and tears down ENet peers via Disconnect().
	bool anyRemainingActive = false;
	for (u8 i = 0; i < m_remotePlayerCount; i++)
	{
		auto remoteIdx = matchInfo.remotePlayerSelections[i].playerIdx;
		if (playerActive[remoteIdx].load(std::memory_order_acquire))
		{
			anyRemainingActive = true;
			break;
		}
	}

	if (!anyRemainingActive &&
	    slippiConnectStatus.load(std::memory_order_acquire) == SlippiConnectStatus::NET_CONNECT_STATUS_CONNECTED)
	{
		WARN_LOG(SLIPPI_ONLINE, "[Netplay] All remote players force-disconnected, flipping status to DISCONNECTED");
		slippiConnectStatus.store(SlippiConnectStatus::NET_CONNECT_STATUS_DISCONNECTED, std::memory_order_release);
	}

	// Wake the network thread so it picks up either the pending peer disconnect or
	// the status flip without waiting for its 250ms enet_host_service timeout.
	if (m_client)
		ENetUtil::WakeupThread(m_client);
}

// Force-disconnect every remote player. Delegates to ForceDisconnectPlayer so the liveness
// flip, peer teardown, and network-thread wakeup all reuse the same logic; the final call
// transitions the overall status to DISCONNECTED once no remote players remain active.
void SlippiNetplayClient::ForceDisconnect(SlippiDisconnectReason reason)
{
	// Record the reason both for our own UI (we never receive a disconnect event from ourselves)
	// and for the network thread to forward to the peer via the enet_peer_disconnect data field.
	m_pendingDisconnectReason.store(static_cast<u32>(reason), std::memory_order_release);
	m_disconnectReason.store(static_cast<u32>(reason), std::memory_order_release);

	for (u8 i = 0; i < m_remotePlayerCount; i++)
	{
		ForceDisconnectPlayer(matchInfo.remotePlayerSelections[i].playerIdx);
	}
}

SlippiNetplayClient::SlippiDisconnectReason SlippiNetplayClient::GetDisconnectReason()
{
	return static_cast<SlippiDisconnectReason>(m_disconnectReason.load(std::memory_order_acquire));
}

// Network-thread only (called from ThreadFunc disconnect handler).
bool SlippiNetplayClient::AreAllPeersDisconnectedForKey(const std::string &key)
{
	if (!activeConnections.count(key))
		return true;

	for (auto &peer : activeConnections[key])
	{
		if (!peer.second.isDisconnected)
			return false;
	}
	return true;
}

// Network-thread only (called from ThreadFunc disconnect handler).
bool SlippiNetplayClient::AreAllConnectionsDisconnected()
{
	for (auto &conn : activeConnections)
	{
		for (auto &peer : conn.second)
		{
			if (!peer.second.isDisconnected)
				return false;
		}
	}
	return true;
}

SlippiMatchInfo *SlippiNetplayClient::GetMatchInfo()
{
	return &matchInfo;
}

// Drains the ping accumulator and returns the average ping (in ms) across all measurements taken
// since the last call. Returns 0 if there were no measurements (e.g. a full stall), which callers
// should treat as "no signal" rather than a great connection — the speed ratio check covers that
// case. The two exchanges aren't atomic as a pair, so a sample landing between them can be
// attributed to the wrong interval; at one sample per frame that skews an interval average by a
// negligible amount and is not worth a lock.
double SlippiNetplayClient::GetAndResetAvgPingMs()
{
	u64 sumUs = pingSampleSumUs.exchange(0, std::memory_order_relaxed);
	u64 count = pingSampleCount.exchange(0, std::memory_order_relaxed);
	if (count == 0)
		return 0.0;

	return static_cast<double>(sumUs) / static_cast<double>(count) / 1000.0;
}

// return the smallest time offset among all remote players
s32 SlippiNetplayClient::CalcTimeOffsetUs()
{
	// Skip disconnected peers. Their frameOffsetData buffer freezes at the time of disconnect (writes only
	// happen on pad receive), so including their stale samples would distort time-sync for the rest of the
	// match — particularly bad in 4p where the game continues after one peer drops.
	bool empty = true;
	for (int i = 0; i < m_remotePlayerCount; i++)
	{
		auto remotePlayerIdx = matchInfo.remotePlayerSelections[i].playerIdx;
		if (!playerActive[remotePlayerIdx].load(std::memory_order_acquire))
			continue;

		if (!frameOffsetData[i].buf.empty())
		{
			empty = false;
			break;
		}
	}
	if (empty)
	{
		return 0;
	}

	std::vector<int> offsets;
	for (int i = 0; i < m_remotePlayerCount; i++)
	{
		auto remotePlayerIdx = matchInfo.remotePlayerSelections[i].playerIdx;
		if (!playerActive[remotePlayerIdx].load(std::memory_order_acquire))
			continue;

		if (frameOffsetData[i].buf.empty())
			continue;

		std::vector<s32> buf;
		std::copy(frameOffsetData[i].buf.begin(), frameOffsetData[i].buf.end(), std::back_inserter(buf));

		// TODO: Does this work?
		std::sort(buf.begin(), buf.end());

		int bufSize = (int)buf.size();
		int offset = (int)((1.0f / 3.0f) * bufSize);
		int end = bufSize - offset;

		int sum = 0;
		for (int i = offset; i < end; i++)
		{
			sum += buf[i];
		}

		int count = end - offset;
		if (count <= 0)
		{
			return 0; // What do I return here?
		}

		s32 result = sum / count;
		offsets.push_back(result);
	}

	s32 minOffset = offsets.front();
	for (int i = 1; i < offsets.size(); i++)
	{
		if (offsets[i] < minOffset)
			minOffset = offsets[i];
	}

	// INFO_LOG(SLIPPI_ONLINE, "Time offsets, [0]: %d, [1]: %d, [2]: %d", offsets[0], offsets[1], offsets[2]);
	return minOffset;
}

bool SlippiNetplayClient::IsWaitingForDesyncRecovery()
{
	// If we are not in a desync recovery state, we do not need to wait
	if (!is_desync_recovery)
		return false;

	for (int i = 0; i < m_remotePlayerCount; i++)
	{
		if (local_sync_state.game_index != remote_sync_states[i].game_index)
			return true;

		if (local_sync_state.tiebreak_index != remote_sync_states[i].tiebreak_index)
			return true;
	}

	return false;
}

SlippiDesyncRecoveryResp SlippiNetplayClient::GetDesyncRecoveryState()
{
	SlippiDesyncRecoveryResp result;

	result.is_recovering = is_desync_recovery;
	result.is_waiting = IsWaitingForDesyncRecovery();

	// If we are not recovering or if we are currently waiting, don't need to compute state, just return
	if (!result.is_recovering || result.is_waiting)
		return result;

	result.state = local_sync_state;

	// TODO: Test desyncs once a player has disconnected

	// Here let's try to reconcile all the states into one. This is important to make sure
	// everyone starts at the same percent/stocks because their last synced state might be
	// a slightly different frame. There didn't seem to be an easy way to guarantee they'd
	// all be on exactly the same frame
	for (int i = 0; i < m_remotePlayerCount; i++)
	{
		auto &s = remote_sync_states[i];
		if (abs(static_cast<int>(result.state.seconds_remaining) - static_cast<int>(s.seconds_remaining)) > 1)
		{
			ERROR_LOG(SLIPPI_ONLINE, "Timer values for desync recovery too different: %d, %d",
			          result.state.seconds_remaining, s.seconds_remaining);
			result.is_error = true;
			return result;
		}

		// Use the timer with more time remaining
		if (s.seconds_remaining > result.state.seconds_remaining)
		{
			result.state.seconds_remaining = s.seconds_remaining;
		}

		for (int j = 0; j < 4; j++)
		{
			auto &fighter = result.state.fighters[i];
			auto &iFighter = s.fighters[i];

			if (fighter.stocks_remaining != iFighter.stocks_remaining)
			{
				// This might actually happen sometimes if a desync happens right as someone is KO'd... should be
				// quite rare though in a 1v1 situation.
				ERROR_LOG(SLIPPI_ONLINE, "Stocks remaining for desync recovery do not match: [Player %d] %d, %d", j + 1,
				          fighter.stocks_remaining, iFighter.stocks_remaining);
				result.is_error = true;
				return result;
			}

			if (abs(static_cast<int>(fighter.current_health) - static_cast<int>(iFighter.current_health)) > 25)
			{
				ERROR_LOG(SLIPPI_ONLINE, "Current health for desync recovery too different: [Player %d] %d, %d", j + 1,
				          fighter.current_health, iFighter.current_health);
				result.is_error = true;
				return result;
			}

			// Use the lower health value
			if (iFighter.current_health < fighter.current_health)
			{
				result.state.fighters[i].current_health = iFighter.current_health;
			}
		}
	}

	return result;
}
