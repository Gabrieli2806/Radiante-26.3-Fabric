#pragma once

#include "core/all_extern.hpp"

#include <atomic>
#include <cstdint>
#include <mutex>

namespace vk {
// Queues are shared with Minecraft's Vulkan backend, which submits from Java. Every queue
// operation on either side goes through this mutex.
std::recursive_mutex &queueMutex();

// Diagnostics: the queue operation currently running under queueMutex() and when it started.
std::atomic<const char *> &queueOp();
std::atomic<int64_t> &queueOpStart();

// Replaces volk's queue-level function pointers with wrappers that take queueMutex().
// Call after volkLoadDevice().
void installQueueLockHooks();
} // namespace vk
