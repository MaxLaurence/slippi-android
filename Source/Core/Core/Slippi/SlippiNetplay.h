// Copyright 2010 Dolphin Emulator Project
// Licensed under GPLv2+
// Refer to the license.txt file included.

#pragma once

#include "Common/CommonTypes.h"
#include "Common/Event.h"
#include "Common/FifoQueue.h"
#include "Common/Timer.h"
#include "Common/TraversalClient.h"
#include "Core/NetPlayProto.h"
#include "Core/Slippi/SlippiPad.h"
#include "InputCommon/GCPadStatus.h"
#include <SlippiLib/SlippiGame.h>
#include <SFML/Network/Packet.hpp>
#include <array>
#include <atomic>
#include <climits>
#include <deque>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>
#ifdef _WIN32
#include <Qos2.h>
#endif

#define ROLLBACK_MAX_FRAMES 7
#define SLIPPI_ONLINE_LOCKSTEP_INTERVAL 30 // Number of frames to wait before attempting to time-sync
#define SLIPPI_PING_DISPLAY_INTERVAL 60
#define SLIPPI_REMOTE_PLAYER_MAX 3
#define SLIPPI_REMOTE_PLAYER_COUNT 3
#define SLIPPI_PLAYER_COUNT_MAX (SLIPPI_REMOTE_PLAYER_MAX + 1)
#define SLIPPI_PAD_RING_CAPACITY 192

struct SlippiRemotePadOutput
{
	s32 latestFrame = 0;
	s32 checksumFrame = 0;
	u32 checksum = 0;
	u8 playerIdx = 0;
	bool isDisconnected = false;
	size_t dataLen = 0;
	std::array<u8, SLIPPI_PAD_FULL_SIZE * ROLLBACK_MAX_FRAMES> data = {};
};

struct SlippiGamePrepStepResults
{
	u8 step_idx;
	u8 char_selection;
	u8 char_color_selection;
	u8 stage_selections[2];
};

struct SlippiSyncedFighterState
{
	u8 stocks_remaining = 4;
	u16 current_health = 0;
};

struct SlippiSyncedGameState
{
	std::string match_id = "";
	u32 game_index = 0;
	u32 tiebreak_index = 0;
	u32 seconds_remaining = 480;
	SlippiSyncedFighterState fighters[4];
};

struct SlippiDesyncRecoveryResp
{
	bool is_recovering = false;
	bool is_waiting = false;
	bool is_error = false;
	SlippiSyncedGameState state;
};

class SlippiPlayerSelections
{
  public:
	u8 playerIdx = 0;
	u8 characterId = 0;
	u8 characterColor = 0;
	u8 teamId = 0;

	bool isCharacterSelected = false;

	u16 stageId = 0;
	bool isStageSelected = false;
	u8 alt_stage_mode{};

	u32 rngOffset = 0;

	int messageId = 0;
	bool error = false;

	void Merge(SlippiPlayerSelections &s)
	{
		this->rngOffset = s.rngOffset;

		if (s.isStageSelected)
		{
			this->stageId = s.stageId;
			this->isStageSelected = true;
			this->alt_stage_mode = s.alt_stage_mode;
		}

		if (s.isCharacterSelected)
		{
			this->characterId = s.characterId;
			this->characterColor = s.characterColor;
			this->teamId = s.teamId;
			this->isCharacterSelected = true;
		}
	}

	void Reset()
	{
		characterId = 0;
		characterColor = 0;
		isCharacterSelected = false;
		teamId = 0;

		stageId = 0;
		isStageSelected = false;

		rngOffset = 0;
	}
};

struct ChecksumEntry
{
	s32 frame;
	u32 value;
};

class SlippiMatchInfo
{
  public:
	SlippiPlayerSelections localPlayerSelections;
	SlippiPlayerSelections remotePlayerSelections[SLIPPI_REMOTE_PLAYER_MAX];

	void Reset()
	{
		localPlayerSelections.Reset();
		for (int i = 0; i < SLIPPI_REMOTE_PLAYER_MAX; i++)
		{
			remotePlayerSelections[i].Reset();
		}
	}
};

class OnlinePlayMode;
class SlippiNetplayClient
{
  public:
	void ThreadFunc();
	void SendAsync(std::unique_ptr<sf::Packet> packet);

	SlippiNetplayClient(bool isDecider); // Make a dummy client
	SlippiNetplayClient(std::vector<std::string> addrs, std::vector<u16> ports, const u8 remotePlayerCount,
	                    const u16 localPort, bool isDecider, u8 playerIdx);
	~SlippiNetplayClient();

	// Slippi Online
	enum class SlippiConnectStatus
	{
		NET_CONNECT_STATUS_UNSET,
		NET_CONNECT_STATUS_INITIATED,
		NET_CONNECT_STATUS_CONNECTED,
		NET_CONNECT_STATUS_FAILED,
		NET_CONNECT_STATUS_DISCONNECTED,
	};

	bool IsDecider();
	bool IsConnectionSelected();
	u8 LocalPlayerPort();
	SlippiConnectStatus GetSlippiConnectStatus();
	std::vector<int> GetFailedConnections();
	void StartSlippiGame();
	void SendConnectionSelected();
	void SendSlippiPad(const SlippiPad *pad);
	void SetMatchSelections(SlippiPlayerSelections &s);
	void SendGamePrepStep(SlippiGamePrepStepResults &s);
	void SendSyncedGameState(SlippiSyncedGameState &s);
	bool GetGamePrepResults(u8 stepIdx, SlippiGamePrepStepResults &res);
	SlippiRemotePadOutput GetFakePadOutput(int frame);
	SlippiRemotePadOutput GetSlippiRemotePad(int index, int maxFrameCount);
	void DropOldRemoteInputs(int32_t finalizedFrame);
	std::unordered_map<u8, bool> GetActivePlayerIndices();
	void ForceDisconnectPlayer(u8 playerIdx);
	SlippiMatchInfo *GetMatchInfo();
	int32_t GetSlippiLatestRemoteFrame(int maxFrameCount);
	SlippiPlayerSelections GetSlippiRemoteChatMessage(bool isChatEnabled);
	u8 GetSlippiRemoteSentChatMessage(bool isChatEnabled);
	s32 CalcTimeOffsetUs();
	bool IsWaitingForDesyncRecovery();
	SlippiDesyncRecoveryResp GetDesyncRecoveryState();

	void WriteChatMessageToPacket(sf::Packet &packet, int messageId, u8 playerIdx);
	std::unique_ptr<SlippiPlayerSelections> ReadChatMessageFromPacket(sf::Packet &packet);

	std::unique_ptr<SlippiPlayerSelections> remoteChatMessageSelection =
	    nullptr;                    // most recent chat message player selection (message + player index)
	u8 remoteSentChatMessageId = 0; // most recent chat message id that current player sent

  protected:
	struct
	{
		std::recursive_mutex game;
		// lock order
		std::recursive_mutex players;
		std::recursive_mutex async_queue_write;
	} m_crit;

	Common::FifoQueue<std::unique_ptr<sf::Packet>, false> m_async_queue;

	ENetHost *m_client = nullptr;
	std::vector<ENetPeer *> m_server;
	std::thread m_thread;
	u8 m_remotePlayerCount = 0;

	std::string m_selected_game;
	Common::Flag m_is_running{false};
	Common::Flag m_do_loop{true};

	unsigned int m_minimum_buffer_size = 6;

	u32 m_current_game = 0;

	// Slippi Stuff
	struct FrameTiming
	{
		int32_t frame;
		u64 timeUs;
	};

	struct FrameOffsetData
	{
		// TODO: Should the buffer size be dynamic based on time sync interval or not?
		int idx;
		std::vector<s32> buf;
	};

	bool isConnectionSelected = false;
	bool isDecider = false;
	bool hasGameStarted = false;
	u8 playerIdx = 0;

	struct ActiveConnectionInfo
	{
		u8 playerIdx;
		bool isDisconnected = false;
	};

	// Owned by the network thread (constructor + ThreadFunc + Send/OnData which run on the
	// network thread via the SendAsync queue). Do not read from other threads — use the
	// playerActive atomics below for cross-thread checks of liveness.
	std::unordered_map<std::string, std::map<ENetPeer *, ActiveConnectionInfo>> activeConnections;

	// Lock-free view of which global player indices still have at least one live peer.
	// Written by the network thread when activeConnections changes, and by the EXI
	// thread via ForceDisconnectPlayer. Read from any thread (notably the main/EXI
	// thread via GetActivePlayerIndices). The network thread also uses this to drive
	// per-peer ENet disconnects for players force-dropped from the EXI side.
	std::atomic<bool> playerActive[SLIPPI_PLAYER_COUNT_MAX] = {};

	struct PadRingSlot
	{
		std::atomic<u32> sequence{0};
		std::atomic<u32> generation{0};
		SlippiPad pad;
	};

	class PadRing
	{
	  public:
		void Clear();
		void Push(const SlippiPad &pad);
		void DropBefore(s32 frame);
		bool TryGetFrame(s32 frame, SlippiPad *out) const;
		s32 LatestFrame() const;
		size_t CopyNewestFirst(s32 minFrameInclusive, size_t maxCount,
		                       std::array<SlippiPad, SLIPPI_PAD_RING_CAPACITY> *out) const;
		size_t CopyNewestFirst(size_t maxCount, u8 *out, size_t outCapacity, s32 *latestFrame) const;

	  private:
		std::array<PadRingSlot, SLIPPI_PAD_RING_CAPACITY> m_slots;
		std::atomic<u32> m_generation{0};
		std::atomic<s32> m_latestFrame{INT_MIN};
		std::atomic<s32> m_readFloor{INT_MIN};
	};

	PadRing localPadRing;
	PadRing remotePadRings[SLIPPI_REMOTE_PLAYER_MAX];

	static constexpr size_t SLIPPI_PAD_PACKET_QUEUE_CAPACITY = 512;
	struct QueuedPadPacket
	{
		s32 frame = 0;
		u8 playerIdx = 0;
		s32 checksumFrame = 0;
		u32 checksum = 0;
		size_t padCount = 0;
		std::array<u8, SLIPPI_PAD_DATA_SIZE * SLIPPI_PAD_RING_CAPACITY> padBytes = {};
	};

	std::array<QueuedPadPacket, SLIPPI_PAD_PACKET_QUEUE_CAPACITY> m_pad_packet_queue;
	std::atomic<size_t> m_pad_packet_head{0};
	std::atomic<size_t> m_pad_packet_tail{0};

	bool is_desync_recovery = false;
	std::atomic<s32> remoteChecksumFrame[SLIPPI_REMOTE_PLAYER_MAX] = {};
	std::atomic<u32> remoteChecksumValue[SLIPPI_REMOTE_PLAYER_MAX] = {};
	SlippiSyncedGameState remote_sync_states[SLIPPI_REMOTE_PLAYER_MAX];
	SlippiSyncedGameState local_sync_state;

	std::deque<SlippiGamePrepStepResults> gamePrepStepQueue;

	u64 pingUs[SLIPPI_REMOTE_PLAYER_MAX];
	int32_t lastFrameAcked[SLIPPI_REMOTE_PLAYER_MAX];
	FrameOffsetData frameOffsetData[SLIPPI_REMOTE_PLAYER_MAX];
	FrameTiming lastFrameTiming[SLIPPI_REMOTE_PLAYER_MAX];
	std::array<Common::FifoQueue<FrameTiming, false>, SLIPPI_REMOTE_PLAYER_MAX> ackTimers;
	std::atomic<int> m_async_queue_depth{0};
#ifdef __ANDROID__
	uint64_t m_pad_send_count = 0;
	uint64_t m_total_pad_override_age_us = 0;
	uint64_t m_max_pad_override_age_us = 0;
	uint64_t m_ack_count = 0;
	uint64_t m_total_ack_us = 0;
	uint64_t m_max_ack_us = 0;
#endif

	std::atomic<SlippiConnectStatus> slippiConnectStatus{SlippiConnectStatus::NET_CONNECT_STATUS_UNSET};
	std::vector<int> failedConnections;
	SlippiMatchInfo matchInfo;

	bool m_is_recording = false;

	void writeToPacket(sf::Packet &packet, SlippiPlayerSelections &s);
	std::unique_ptr<SlippiPlayerSelections> readSelectionsFromPacket(sf::Packet &packet);

  private:
	u8 PlayerIdxFromPort(u8 port);
	unsigned int OnData(sf::Packet &packet, ENetPeer *peer);
	void Send(sf::Packet &packet);
	void SendSlippiPadBatchAsync(const std::array<SlippiPad, SLIPPI_PAD_RING_CAPACITY> &pads,
	                             size_t padCount);
	void DrainQueuedPadPackets();
	void SendQueuedPadPacket(const QueuedPadPacket &packet);
	void Disconnect();
	// Network-thread only — call from inside ThreadFunc.
	bool AreAllConnectionsDisconnected();
	bool AreAllPeersDisconnectedForKey(const std::string &key);

	bool m_is_connected = false;

#ifdef _WIN32
	HANDLE m_qos_handle;
	QOS_FLOWID m_qos_flow_id;
#endif

	u32 m_timebase_frame = 0;
};
extern SlippiNetplayClient *SLIPPI_NETPLAY; // singleton static pointer

static bool IsOnline()
{
	return SLIPPI_NETPLAY != nullptr;
}
