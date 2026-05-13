// Copyright 2008 Dolphin Emulator Project
// Licensed under GPLv2+
// Refer to the license.txt file included.

#pragma once

#include <cstddef>

#ifdef _WIN32
#include <windows.h>
#endif

#include "Common/CommonTypes.h"

// This class lets you create a block of anonymous RAM, and then arbitrarily map views into it.
// Multiple views can mirror the same section of the block, which makes it very convenient for emulating
// memory mirrors.

class MemArena
{
public:
	void GrabSHMSegment(size_t size);
	void ReleaseSHMSegment();
	void* CreateView(s64 offset, size_t size, void* base = nullptr);
	void ReleaseView(void* view, size_t size);
	u8* ReserveMemoryRegion(size_t size, size_t alignment = 0, void* fixed_base = nullptr);
	void ReleaseMemoryRegion();
	bool HasMemoryRegion() const;
	bool HasSHMSegment() const;

	// This finds 1 GB in 32-bit, 16 GB in 64-bit.
	static u8* FindMemoryBase();
private:

#ifdef _WIN32
	HANDLE hMemoryMapping = 0;
#else
	int fd = -1;
	void* reserved_base = nullptr;
	size_t reserved_size = 0;
#endif
};

enum
{
	MV_MIRROR_PREVIOUS = 1,
	MV_FAKE_VMEM = 2,
	MV_WII_ONLY = 4,
};

struct MemoryView
{
	u8** out_ptr;
	u64 virtual_address;
	u32 size;
	u32 flags;
	void* mapped_ptr;
	void* view_ptr;
	u32 shm_position;
};

// Uses a memory arena to set up an emulator-friendly memory map according to
// a passed-in list of MemoryView structures.
u8* MemoryMap_Setup(MemoryView* views, int num_views, u32 flags, MemArena* arena);
void MemoryMap_Shutdown(MemoryView* views, int num_views, u32 flags, MemArena* arena);
