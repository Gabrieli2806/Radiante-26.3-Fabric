#include "core/render/framegen/reflex.hpp"

#include <cstring>
#include <mutex>
#include <vector>

#include "core/util/logging.hpp"

namespace {

std::recursive_mutex g_mutex;
VkDevice g_device = VK_NULL_HANDLE;
bool g_supported = false;
bool g_enabled = false;
VkSwapchainKHR g_swapchain = VK_NULL_HANDLE;
// Mode last given to the swapchain; -1 forces it to be set again (new swapchain).
int g_appliedMode = -1;
VkSemaphore g_sleepSemaphore = VK_NULL_HANDLE;
uint64_t g_sleepValue = 0;
// Present id of the frame being made: markers and the present carry it so the driver can tie them together.
uint64_t g_frameId = 0;

// Live until the structures they are chained into are consumed (the next create or present on this thread).
VkSwapchainLatencyCreateInfoNV g_swapchainLatency{VK_STRUCTURE_TYPE_SWAPCHAIN_LATENCY_CREATE_INFO_NV};
VkPresentIdKHR g_presentId{VK_STRUCTURE_TYPE_PRESENT_ID_KHR};
uint64_t g_presentIdValue = 0;

std::ostream &reflexOut() {
    return radiante::out() << "[Reflex] ";
}

bool usable() {
    return g_supported && g_device != VK_NULL_HANDLE && vkSetLatencySleepModeNV != nullptr &&
           vkLatencySleepNV != nullptr && vkSetLatencyMarkerNV != nullptr;
}

} // namespace

bool framegen::Reflex::deviceSupports(VkPhysicalDevice physicalDevice) {
    uint32_t count = 0;
    vkEnumerateDeviceExtensionProperties(physicalDevice, nullptr, &count, nullptr);
    std::vector<VkExtensionProperties> extensions(count);
    vkEnumerateDeviceExtensionProperties(physicalDevice, nullptr, &count, extensions.data());
    bool lowLatency = false, presentId = false;
    for (const auto &ext : extensions) {
        lowLatency |= std::strcmp(ext.extensionName, VK_NV_LOW_LATENCY_2_EXTENSION_NAME) == 0;
        presentId |= std::strcmp(ext.extensionName, VK_KHR_PRESENT_ID_EXTENSION_NAME) == 0;
    }
    return lowLatency && presentId;
}

void framegen::Reflex::setDevice(VkDevice device, bool enabled) {
    std::scoped_lock lock(g_mutex);
    g_device = device;
    g_supported = enabled;
    g_swapchain = VK_NULL_HANDLE;
    g_appliedMode = -1;
    g_sleepSemaphore = VK_NULL_HANDLE;
    g_sleepValue = 0;
    reflexOut() << (enabled ? "available (VK_NV_low_latency2)" : "not supported here") << std::endl;
}

bool framegen::Reflex::isSupported() {
    std::scoped_lock lock(g_mutex);
    return usable();
}

void framegen::Reflex::setEnabled(bool enabled) {
    std::scoped_lock lock(g_mutex);
    g_enabled = enabled;
}

bool framegen::Reflex::isActive() {
    std::scoped_lock lock(g_mutex);
    return g_enabled && usable() && g_swapchain != VK_NULL_HANDLE;
}

void framegen::Reflex::chainSwapchainCreate(void *createInfo) {
    std::scoped_lock lock(g_mutex);
    if (!usable() || createInfo == nullptr) return;
    // Always on when supported: switching Reflex is then only a sleep mode, never a new swapchain.
    auto *info = static_cast<VkSwapchainCreateInfoKHR *>(createInfo);
    g_swapchainLatency.latencyModeEnable = VK_TRUE;
    g_swapchainLatency.pNext = info->pNext;
    info->pNext = &g_swapchainLatency;
}

void framegen::Reflex::setSwapchain(VkSwapchainKHR swapchain) {
    std::scoped_lock lock(g_mutex);
    g_swapchain = swapchain;
    g_appliedMode = -1;
}

void framegen::Reflex::chainPresent(void *presentInfo) {
    std::scoped_lock lock(g_mutex);
    if (!g_enabled || !usable() || g_swapchain == VK_NULL_HANDLE || presentInfo == nullptr || g_frameId == 0) return;
    auto *info = static_cast<VkPresentInfoKHR *>(presentInfo);
    if (info->swapchainCount != 1) return;
    g_presentIdValue = g_frameId;
    g_presentId.swapchainCount = 1;
    g_presentId.pPresentIds = &g_presentIdValue;
    g_presentId.pNext = info->pNext;
    info->pNext = &g_presentId;
}

void framegen::Reflex::beginFrame() {
    std::scoped_lock lock(g_mutex);
    if (!usable() || g_swapchain == VK_NULL_HANDLE) return;

    int mode = g_enabled ? 1 : 0;
    if (mode != g_appliedMode) {
        VkLatencySleepModeInfoNV sleepMode{VK_STRUCTURE_TYPE_LATENCY_SLEEP_MODE_INFO_NV};
        sleepMode.lowLatencyMode = g_enabled ? VK_TRUE : VK_FALSE;
        sleepMode.lowLatencyBoost = g_enabled ? VK_TRUE : VK_FALSE;
        sleepMode.minimumIntervalUs = 0;
        if (vkSetLatencySleepModeNV(g_device, g_swapchain, &sleepMode) == VK_SUCCESS) {
            g_appliedMode = mode;
            reflexOut() << (g_enabled ? "low latency" : "off") << std::endl;
        }
    }
    if (!g_enabled) return;

    if (g_sleepSemaphore == VK_NULL_HANDLE) {
        VkSemaphoreTypeCreateInfo type{VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO};
        type.semaphoreType = VK_SEMAPHORE_TYPE_TIMELINE;
        type.initialValue = 0;
        VkSemaphoreCreateInfo create{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO, &type};
        if (vkCreateSemaphore(g_device, &create, nullptr, &g_sleepSemaphore) != VK_SUCCESS) {
            g_sleepSemaphore = VK_NULL_HANDLE;
            return;
        }
        g_sleepValue = 0;
    }

    g_frameId++;
    // The sleep is what lowers latency: the driver signals the semaphore when the CPU should start the frame, so
    // input is read as late as the GPU allows instead of frames queueing ahead of it.
    VkLatencySleepInfoNV sleep{VK_STRUCTURE_TYPE_LATENCY_SLEEP_INFO_NV};
    sleep.signalSemaphore = g_sleepSemaphore;
    sleep.value = ++g_sleepValue;
    if (vkLatencySleepNV(g_device, g_swapchain, &sleep) != VK_SUCCESS) return;
    VkSemaphoreWaitInfo wait{VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO};
    wait.semaphoreCount = 1;
    wait.pSemaphores = &g_sleepSemaphore;
    wait.pValues = &g_sleepValue;
    // Bounded: a lost signal (swapchain rebuilt mid-frame) must never stall the game.
    vkWaitSemaphores(g_device, &wait, 100'000'000ull);
}

void framegen::Reflex::marker(int marker) {
    std::scoped_lock lock(g_mutex);
    if (!g_enabled || !usable() || g_swapchain == VK_NULL_HANDLE || g_frameId == 0) return;
    if (marker < VK_LATENCY_MARKER_SIMULATION_START_NV || marker > VK_LATENCY_MARKER_TRIGGER_FLASH_NV) return;
    VkSetLatencyMarkerInfoNV info{VK_STRUCTURE_TYPE_SET_LATENCY_MARKER_INFO_NV};
    info.presentID = g_frameId;
    info.marker = static_cast<VkLatencyMarkerNV>(marker);
    vkSetLatencyMarkerNV(g_device, g_swapchain, &info);
}
