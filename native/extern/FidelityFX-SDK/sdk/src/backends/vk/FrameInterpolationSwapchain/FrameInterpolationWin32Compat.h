// Lightweight Win32 compatibility layer for non-Windows platforms.
// Provides minimal threading, event, and timing primitives used by the
// FrameInterpolation swapchain implementation so it can run on Linux.
//
// This is not a full Win32 emulation; it only covers the subset of APIs
// actually used in the FidelityFX frame interpolation backend.

#pragma once

#ifndef _WIN32

#include <cstdint>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <chrono>

using UINT64 = std::uint64_t;
using DWORD  = std::uint32_t;
using LPVOID = void*;
using BOOL   = int;

// Calling convention macro used in Win32 thread entrypoints.
#ifndef WINAPI
#define WINAPI
#endif

// Thread priority constants used by SetThreadPriority.
#ifndef THREAD_PRIORITY_HIGHEST
#define THREAD_PRIORITY_HIGHEST 2
#endif

#ifndef TRUE
#define TRUE 1
#endif

#ifndef FALSE
#define FALSE 0
#endif

#ifndef INFINITE
#define INFINITE 0xFFFFFFFFu
#endif

// We ignore the distinction between ANSI/WIDE here – the name is unused.
#ifndef TEXT
#define TEXT(x) x
#endif

struct LARGE_INTEGER
{
    long long QuadPart;
};

// Simple critical section backed by std::mutex.
struct CRITICAL_SECTION
{
    std::mutex m;
};

inline void InitializeCriticalSection(CRITICAL_SECTION* cs)
{
    (void)cs;
}

inline void EnterCriticalSection(CRITICAL_SECTION* cs)
{
    if (cs)
    {
        cs->m.lock();
    }
}

inline void LeaveCriticalSection(CRITICAL_SECTION* cs)
{
    if (cs)
    {
        cs->m.unlock();
    }
}

inline void DeleteCriticalSection(CRITICAL_SECTION* cs)
{
    (void)cs;
}

// Minimal HANDLE abstraction that can represent either an event or a thread.
struct Win32Handle
{
    enum class Type
    {
        Event,
        Thread
    };

    explicit Win32Handle(Type t)
        : type(t)
    {
    }

    Type                     type;
    std::thread              thread;
    std::mutex               m;
    std::condition_variable  cv;
    bool                     signaled     = false;
    bool                     manualReset  = false;
    bool                     joined       = false;
};

using HANDLE = Win32Handle*;

using LPTHREAD_START_ROUTINE = DWORD (*)(LPVOID);

// Event creation: security attributes and name are ignored.
inline HANDLE CreateEvent(void*, BOOL manualReset, BOOL initialState, const char*)
{
    auto handle        = new Win32Handle(Win32Handle::Type::Event);
    handle->manualReset = (manualReset != 0);
    handle->signaled    = (initialState != 0);
    return handle;
}

// Waits until the event is signaled or the thread exits. Timeout handling
// other than INFINITE is not implemented – the existing FI code only
// uses INFINITE.
inline DWORD WaitForSingleObject(HANDLE handle, DWORD /*milliseconds*/)
{
    if (!handle)
        return 0;

    if (handle->type == Win32Handle::Type::Thread)
    {
        if (handle->thread.joinable() && !handle->joined)
        {
            handle->thread.join();
            handle->joined = true;
        }
        return 0;
    }

    std::unique_lock<std::mutex> lock(handle->m);
    handle->cv.wait(lock, [&] { return handle->signaled; });
    if (!handle->manualReset)
    {
        handle->signaled = false;
    }
    return 0;
}

inline BOOL SetEvent(HANDLE handle)
{
    if (!handle)
        return FALSE;

    {
        std::lock_guard<std::mutex> lock(handle->m);
        handle->signaled = true;
    }

    if (handle->manualReset)
    {
        handle->cv.notify_all();
    }
    else
    {
        handle->cv.notify_one();
    }

    return TRUE;
}

inline BOOL CloseHandle(HANDLE handle)
{
    if (!handle)
        return FALSE;

    if (handle->type == Win32Handle::Type::Thread)
    {
        // Ensure the underlying std::thread has finished before destroying
        // the handle. The code that uses thread HANDLEs always waits on them
        // via WaitForSingleObject before closing, so this should normally
        // be a no-op here.
        if (handle->thread.joinable() && !handle->joined)
        {
            handle->thread.join();
            handle->joined = true;
        }
    }

    delete handle;
    return TRUE;
}

// Priority / description helpers are no-ops on non-Windows.
inline BOOL SetThreadPriority(HANDLE /*handle*/, int /*priority*/)
{
    return TRUE;
}

inline void SetThreadDescription(HANDLE /*handle*/, const wchar_t* /*desc*/)
{
}

inline HANDLE CreateThread(void*,
                           std::size_t,
                           LPTHREAD_START_ROUTINE startAddress,
                           LPVOID                 param,
                           DWORD,
                           DWORD*)
{
    auto handle = new Win32Handle(Win32Handle::Type::Thread);

    handle->thread = std::thread([handle, startAddress, param]() {
        if (startAddress)
        {
            startAddress(param);
        }

        {
            std::lock_guard<std::mutex> lock(handle->m);
            handle->signaled = true;
        }
        handle->cv.notify_all();
    });

    return handle;
}

inline BOOL QueryPerformanceCounter(LARGE_INTEGER* li)
{
    if (!li)
        return FALSE;

    auto now = std::chrono::steady_clock::now().time_since_epoch();
    li->QuadPart =
        std::chrono::duration_cast<std::chrono::nanoseconds>(now).count();

    return TRUE;
}

inline BOOL QueryPerformanceFrequency(LARGE_INTEGER* li)
{
    if (!li)
        return FALSE;

    // steady_clock is not guaranteed to be nanosecond-based, but we just need
    // a consistent scale for the pacing math. Use 1e9 ticks per second.
    li->QuadPart = 1000000000ll;
    return TRUE;
}

#endif // !_WIN32
