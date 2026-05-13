// Copyright 2008 Dolphin Emulator Project
// Licensed under GPLv2+
// Refer to the license.txt file included.

#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <set>
#include <string>

#include "Common/CommonTypes.h"
#include "Common/MemArena.h"
#include "Common/MsgHandler.h"
#include "Common/StringUtil.h"
#include "Common/Logging/Log.h"

#ifdef _WIN32
#include <windows.h>
#else
#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#ifdef ANDROID
#include <android/log.h>
#include <sys/ioctl.h>
#include <sys/system_properties.h>
#include <linux/ashmem.h>
#endif

#if defined(__APPLE__)
#include <sys/sysctl.h>
#endif
#endif

#ifdef ANDROID
#define ASHMEM_DEVICE "/dev/ashmem"
#define DOLPHIN_MEMMAP_TAG "DolphinMemMap"

#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif

static int AshmemCreateFileMapping(const char* name, size_t size)
{
	int fd, ret;
	fd = open(ASHMEM_DEVICE, O_RDWR);
	if (fd < 0)
		return fd;

	// We don't really care if we can't set the name, it is optional
	ioctl(fd, ASHMEM_SET_NAME, name);

	ret = ioctl(fd, ASHMEM_SET_SIZE, size);
	if (ret < 0)
	{
		close(fd);
		NOTICE_LOG(MEMMAP, "Ashmem returned error: 0x%08x: %s", ret, strerror(errno));
		return ret;
	}
	return fd;
}

static int MemfdCreateFileMapping(const char* name, size_t size)
{
#ifdef __NR_memfd_create
	int fd = static_cast<int>(syscall(__NR_memfd_create, name, MFD_CLOEXEC));
	if (fd < 0)
	{
		NOTICE_LOG(MEMMAP, "memfd_create failed: %s", strerror(errno));
		return fd;
	}

	if (ftruncate(fd, size) < 0)
	{
		NOTICE_LOG(MEMMAP, "memfd ftruncate failed: %s", strerror(errno));
		close(fd);
		return -1;
	}

	return fd;
#else
	return -1;
#endif
}

static bool ShouldForceAlignedMemoryBase()
{
	char value[PROP_VALUE_MAX] = {};
	if (__system_property_get("debug.dolphin.force_aligned_membase", value) <= 0)
		return false;

	return strcmp(value, "1") == 0 || strcasecmp(value, "true") == 0 ||
	       strcasecmp(value, "yes") == 0;
}
#endif

void MemArena::GrabSHMSegment(size_t size)
{
#ifdef _WIN32
	hMemoryMapping = CreateFileMapping(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE, 0, (DWORD)(size), nullptr);
#elif defined(ANDROID)
	fd = AshmemCreateFileMapping("Dolphin-emu", size);
	if (fd < 0)
		fd = MemfdCreateFileMapping("Dolphin-emu", size);
	if (fd < 0)
	{
		NOTICE_LOG(MEMMAP, "Shared memory allocation failed");
		return;
	}
#else
	for (int i = 0; i < 10000; i++)
	{
		std::string file_name = StringFromFormat("/dolphinmem.%d", i);
		fd = shm_open(file_name.c_str(), O_RDWR | O_CREAT | O_EXCL, 0600);
		if (fd != -1)
		{
			shm_unlink(file_name.c_str());
			break;
		}
		else if (errno != EEXIST)
		{
			ERROR_LOG(MEMMAP, "shm_open failed: %s", strerror(errno));
			return;
		}
	}
	if (ftruncate(fd, size) < 0)
		ERROR_LOG(MEMMAP, "Failed to allocate low memory space");
#endif
}


void MemArena::ReleaseSHMSegment()
{
#ifdef _WIN32
	CloseHandle(hMemoryMapping);
	hMemoryMapping = 0;
#else
	if (fd >= 0)
		close(fd);
	fd = -1;
#endif
}


void* MemArena::CreateView(s64 offset, size_t size, void* base)
{
#ifdef _WIN32
	return MapViewOfFileEx(hMemoryMapping, FILE_MAP_ALL_ACCESS, 0, (DWORD)((u64)offset), size, base);
#else
	void* retval = mmap(
		base, size,
		PROT_READ | PROT_WRITE,
		MAP_SHARED | ((base == nullptr) ? 0 : MAP_FIXED),
		fd, offset);

	if (retval == MAP_FAILED)
	{
		NOTICE_LOG(MEMMAP, "mmap failed base=%p size=%zu offset=%lld fd=%d: %s",
		           base, size, static_cast<long long>(offset), fd, strerror(errno));
		return nullptr;
	}
	else
	{
		return retval;
	}
#endif
}


void MemArena::ReleaseView(void* view, size_t size)
{
#ifdef _WIN32
	UnmapViewOfFile(view);
#else
	munmap(view, size);
#endif
}


u8* MemArena::ReserveMemoryRegion(size_t size, size_t alignment, void* fixed_base)
{
#ifdef _WIN32
	return static_cast<u8*>(fixed_base);
#else
	ReleaseMemoryRegion();

	if (size == 0)
		return nullptr;

	const int flags = MAP_ANON | MAP_PRIVATE;

	if (fixed_base)
	{
#ifndef MAP_FIXED_NOREPLACE
#define MAP_FIXED_NOREPLACE 0x100000
#endif
		void* base = mmap(fixed_base, size, PROT_NONE, flags | MAP_FIXED_NOREPLACE, -1, 0);
		if (base == MAP_FAILED)
		{
			NOTICE_LOG(MEMMAP, "Failed to reserve fixed memory region base=%p size=%zu: %s",
			           fixed_base, size, strerror(errno));
			return nullptr;
		}
		if (base != fixed_base)
		{
			NOTICE_LOG(MEMMAP, "Fixed memory reservation returned unexpected base=%p requested=%p",
			           base, fixed_base);
			munmap(base, size);
			return nullptr;
		}

		reserved_base = base;
		reserved_size = size;
		return static_cast<u8*>(base);
	}

	if (alignment <= static_cast<size_t>(getpagesize()))
	{
		void* base = mmap(nullptr, size, PROT_NONE, flags, -1, 0);
		if (base == MAP_FAILED)
		{
			NOTICE_LOG(MEMMAP, "Failed to reserve memory region size=%zu: %s",
			           size, strerror(errno));
			return nullptr;
		}

		reserved_base = base;
		reserved_size = size;
		return static_cast<u8*>(base);
	}

	const size_t allocation_size = size + alignment;
	void* allocation = mmap(nullptr, allocation_size, PROT_NONE, flags, -1, 0);
	if (allocation == MAP_FAILED)
	{
		NOTICE_LOG(MEMMAP, "Failed to reserve aligned memory region size=%zu alignment=%zu: %s",
		           size, alignment, strerror(errno));
		return nullptr;
	}

	const uintptr_t raw = reinterpret_cast<uintptr_t>(allocation);
	const uintptr_t aligned = (raw + alignment - 1) & ~(static_cast<uintptr_t>(alignment) - 1);
	const size_t prefix_size = aligned - raw;
	const uintptr_t allocation_end = raw + allocation_size;
	const uintptr_t aligned_end = aligned + size;
	const size_t suffix_size = allocation_end - aligned_end;

	if (prefix_size != 0)
		munmap(reinterpret_cast<void*>(raw), prefix_size);
	if (suffix_size != 0)
		munmap(reinterpret_cast<void*>(aligned_end), suffix_size);

	reserved_base = reinterpret_cast<void*>(aligned);
	reserved_size = size;
	return static_cast<u8*>(reserved_base);
#endif
}

void MemArena::ReleaseMemoryRegion()
{
#ifndef _WIN32
	if (!reserved_base)
		return;

	munmap(reserved_base, reserved_size);
	reserved_base = nullptr;
	reserved_size = 0;
#endif
}

bool MemArena::HasMemoryRegion() const
{
#ifdef _WIN32
	return false;
#else
	return reserved_base != nullptr;
#endif
}

bool MemArena::HasSHMSegment() const
{
#ifdef _WIN32
	return hMemoryMapping != 0;
#else
	return fd >= 0;
#endif
}


u8* MemArena::FindMemoryBase()
{
    // This code was originally introduced to enable running under Rosetta 2 for M1 devices.
    // However, it turns out it works fine for x64_64 macOS and helps slightly with the 2GB issue
    // by looking to base in a way that works for the OS itself.
#if defined(__APPLE__)
	const size_t memory_size = 0x400000000;
	const int flags = MAP_ANON | MAP_PRIVATE;

	void* base = mmap(nullptr, memory_size, PROT_NONE, flags, -1, 0);
	if (base == MAP_FAILED)
	{
		PanicAlert("Failed to map enough memory space: %s", strerror(errno));
		return nullptr;
	}

	munmap(base, memory_size);
	return static_cast<u8*>(base);
#endif

#if _ARCH_64
#ifdef _WIN32
	// 64 bit
	u8* base = (u8*)VirtualAlloc(0, 0x400000000, MEM_RESERVE, PAGE_READWRITE);
	VirtualFree(base, 0, MEM_RELEASE);
	return base;
#else
	// Very precarious - mmap cannot return an error when trying to map already used pages.
	// This makes the Windows approach above unusable on Linux, so we will simply pray...
	return reinterpret_cast<u8*>(0x2300000000ULL);
#endif

#else // 32 bit
#ifdef ANDROID
	// Android 4.3 changed how mmap works.
	// if we map it private and then munmap it, we can't use the base returned.
	// This may be due to changes in them support a full SELinux implementation.
	const int flags = MAP_ANON | MAP_SHARED;
#else
	const int flags = MAP_ANON | MAP_PRIVATE;
#endif
	const u32 MemSize = 0x31000000;
	void* base = mmap(0, MemSize, PROT_NONE, flags, -1, 0);
	if (base == MAP_FAILED)
	{
		PanicAlert("Failed to map 1 GB of memory space: %s", strerror(errno));
		return 0;
	}
	munmap(base, MemSize);
	return static_cast<u8*>(base);
#endif
}


// yeah, this could also be done in like two bitwise ops...
#define SKIP(a_flags, b_flags) \
	if (!(a_flags & MV_WII_ONLY) && (b_flags & MV_WII_ONLY)) \
		continue; \
	if (!(a_flags & MV_FAKE_VMEM) && (b_flags & MV_FAKE_VMEM)) \
		continue; \

static bool Memory_TryBase(u8* base, MemoryView* views, int num_views, u32 flags, MemArena* arena)
{
	// OK, we know where to find free space. Now grab it!
	// We just mimic the popular BAT setup.

	int i;
	for (i = 0; i < num_views; i++)
	{
		MemoryView* view = &views[i];
		void* view_base;
		bool use_sw_mirror;

		SKIP(flags, view->flags);

#if _ARCH_64
		// On 64-bit, we map the same file position multiple times, so we
		// don't need the software fallback for the mirrors.
		view_base = base + view->virtual_address;
		use_sw_mirror = false;
#else
		// On 32-bit, we don't have the actual address space to store all
		// the mirrors, so we just map the fallbacks somewhere in our address
		// space and use the software fallbacks for mirroring.
		view_base = base + (view->virtual_address & 0x3FFFFFFF);
		use_sw_mirror = true;
#endif

		if (use_sw_mirror && (view->flags & MV_MIRROR_PREVIOUS))
		{
			view->view_ptr = views[i - 1].view_ptr;
		}
		else
		{
			view->mapped_ptr = arena->CreateView(view->shm_position, view->size, view_base);
			view->view_ptr = view->mapped_ptr;
		}

		if (!view->view_ptr)
		{
			// Argh! ERROR! Free what we grabbed so far so we can try again.
			MemoryMap_Shutdown(views, i + 1, flags, arena);
			return false;
		}

		if (view->out_ptr)
			*(view->out_ptr) = (u8*)view->view_ptr;
	}

	return true;
}

static u32 MemoryMap_InitializeViews(MemoryView* views, int num_views, u32 flags)
{
	u32 shm_position = 0;
	u32 last_position = 0;

	for (int i = 0; i < num_views; i++)
	{
		// Zero all the pointers to be sure.
		views[i].mapped_ptr = nullptr;

		SKIP(flags, views[i].flags);

		if (views[i].flags & MV_MIRROR_PREVIOUS)
			shm_position = last_position;
		views[i].shm_position = shm_position;
		last_position = shm_position;
		shm_position += views[i].size;
	}

	return shm_position;
}

u8* MemoryMap_Setup(MemoryView* views, int num_views, u32 flags, MemArena* arena)
{
	u32 total_mem = MemoryMap_InitializeViews(views, num_views, flags);

	arena->GrabSHMSegment(total_mem);
	if (!arena->HasSHMSegment())
	{
		PanicAlert("MemoryMap_Setup: Failed creating shared memory backing store.");
		exit(0);
		return nullptr;
	}

#if defined(ANDROID) && _ARCH_64
	// The ARM64 JIT assumes the emulated memory base is 4 GB aligned, and
	// fastmem relies on the unmapped holes in the 16 GB address window staying
	// reserved so invalid guest accesses fault instead of hitting unrelated
	// process mappings.
	constexpr size_t MEMORY_REGION_SIZE = 0x400000000ULL;
	constexpr size_t MEMORY_BASE_ALIGNMENT = 0x100000000ULL;
	const bool force_aligned_base = ShouldForceAlignedMemoryBase();
	u8* base = nullptr;

	if (!force_aligned_base)
	{
		base = arena->ReserveMemoryRegion(
		    MEMORY_REGION_SIZE, 0, reinterpret_cast<void*>(0x2300000000ULL));
		if (base && Memory_TryBase(base, views, num_views, flags, arena))
		{
			__android_log_print(ANDROID_LOG_INFO, DOLPHIN_MEMMAP_TAG,
			                    "using fixed ARM64 memory base %p", base);
			return base;
		}
	}
	else
	{
		__android_log_print(ANDROID_LOG_INFO, DOLPHIN_MEMMAP_TAG,
		                    "forcing aligned ARM64 memory base fallback");
	}

	base = arena->ReserveMemoryRegion(MEMORY_REGION_SIZE, MEMORY_BASE_ALIGNMENT);
	if (base && Memory_TryBase(base, views, num_views, flags, arena))
	{
		__android_log_print(ANDROID_LOG_INFO, DOLPHIN_MEMMAP_TAG,
		                    "using aligned ARM64 memory base %p", base);
		return base;
	}
#else
	// Now, create views in high memory where there's plenty of space.
	u8* base = MemArena::FindMemoryBase();
	// This really shouldn't fail - in 64-bit, there will always be enough
	// address space.
	if (Memory_TryBase(base, views, num_views, flags, arena))
		return base;
#endif

	PanicAlert("MemoryMap_Setup: Failed finding a memory base.");
	exit(0);
	return nullptr;
}

void MemoryMap_Shutdown(MemoryView* views, int num_views, u32 flags, MemArena* arena)
{
	if (arena->HasMemoryRegion())
	{
		arena->ReleaseMemoryRegion();
		for (int i = 0; i < num_views; i++)
		{
			views[i].mapped_ptr = nullptr;
			views[i].view_ptr = nullptr;
		}
		return;
	}

	std::set<void*> freeset;
	for (int i = 0; i < num_views; i++)
	{
		MemoryView* view = &views[i];
		if (view->mapped_ptr && !freeset.count(view->mapped_ptr))
		{
			arena->ReleaseView(view->mapped_ptr, view->size);
			freeset.insert(view->mapped_ptr);
			view->mapped_ptr = nullptr;
		}
	}
}
