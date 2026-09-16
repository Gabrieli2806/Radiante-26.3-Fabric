#include "core/vulkan/queue_lock.hpp"

#include <atomic>
#include <chrono>
#include <iostream>
#include <thread>

namespace {
struct QueueOpScope {
    const char *previous;
    explicit QueueOpScope(const char *op) : previous(vk::queueOp().load()) {
        vk::queueOp().store(op);
        vk::queueOpStart().store(std::chrono::steady_clock::now().time_since_epoch().count());
    }
    ~QueueOpScope() {
        vk::queueOp().store(previous);
    }
};
decltype(vkQueueSubmit) g_queueSubmit = nullptr;
decltype(vkQueueSubmit2) g_queueSubmit2 = nullptr;
decltype(vkQueueSubmit2KHR) g_queueSubmit2KHR = nullptr;
decltype(vkQueueWaitIdle) g_queueWaitIdle = nullptr;
decltype(vkDeviceWaitIdle) g_deviceWaitIdle = nullptr;
decltype(vkQueuePresentKHR) g_queuePresent = nullptr;

VkResult VKAPI_PTR lockedQueueSubmit(VkQueue queue, uint32_t count, const VkSubmitInfo *infos, VkFence fence) {
    std::scoped_lock lock(vk::queueMutex());
    QueueOpScope scope("native vkQueueSubmit");
    return g_queueSubmit(queue, count, infos, fence);
}

VkResult VKAPI_PTR lockedQueueSubmit2(VkQueue queue, uint32_t count, const VkSubmitInfo2 *infos, VkFence fence) {
    std::scoped_lock lock(vk::queueMutex());
    QueueOpScope scope("native vkQueueSubmit2");
    return g_queueSubmit2(queue, count, infos, fence);
}

VkResult VKAPI_PTR lockedQueueSubmit2KHR(VkQueue queue, uint32_t count, const VkSubmitInfo2 *infos, VkFence fence) {
    std::scoped_lock lock(vk::queueMutex());
    QueueOpScope scope("native vkQueueSubmit2KHR");
    return g_queueSubmit2KHR(queue, count, infos, fence);
}

VkResult VKAPI_PTR lockedQueueWaitIdle(VkQueue queue) {
    std::scoped_lock lock(vk::queueMutex());
    QueueOpScope scope("native vkQueueWaitIdle");
    return g_queueWaitIdle(queue);
}

VkResult VKAPI_PTR lockedDeviceWaitIdle(VkDevice device) {
    std::scoped_lock lock(vk::queueMutex());
    QueueOpScope scope("native vkDeviceWaitIdle");
    return g_deviceWaitIdle(device);
}

VkResult VKAPI_PTR lockedQueuePresent(VkQueue queue, const VkPresentInfoKHR *info) {
    std::scoped_lock lock(vk::queueMutex());
    QueueOpScope scope("native vkQueuePresentKHR");
    return g_queuePresent(queue, info);
}

template <typename Fn>
void hook(Fn &slot, Fn &original, Fn replacement) {
    if (slot == nullptr || slot == replacement) return;
    original = slot;
    slot = replacement;
}
} // namespace

std::atomic<const char *> &vk::queueOp() {
    static std::atomic<const char *> op{nullptr};
    return op;
}

std::atomic<int64_t> &vk::queueOpStart() {
    static std::atomic<int64_t> start{0};
    return start;
}

std::recursive_mutex &vk::queueMutex() {
    static std::recursive_mutex mutex;
    return mutex;
}

void vk::installQueueLockHooks() {
    hook(vkQueueSubmit, g_queueSubmit, &lockedQueueSubmit);
    hook(vkQueueSubmit2, g_queueSubmit2, &lockedQueueSubmit2);
    hook(vkQueueSubmit2KHR, g_queueSubmit2KHR, &lockedQueueSubmit2KHR);
    hook(vkQueueWaitIdle, g_queueWaitIdle, &lockedQueueWaitIdle);
    hook(vkDeviceWaitIdle, g_deviceWaitIdle, &lockedDeviceWaitIdle);
    hook(vkQueuePresentKHR, g_queuePresent, &lockedQueuePresent);
}
