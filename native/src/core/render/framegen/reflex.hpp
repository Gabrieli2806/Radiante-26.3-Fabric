#pragma once

#include "core/all_extern.hpp"

namespace framegen {

// NVIDIA Reflex through VK_NV_low_latency2, straight on the driver: no Streamline, and the same code on Windows and
// Linux. Minecraft owns the swapchain, so its creation and present are extended from Java (ReflexSurfaceMixin) with
// the structures built here. Every call is a no-op where the extension is missing (non-NVIDIA GPUs, old drivers).
class Reflex {
  public:
    // Device extensions to request when the GPU has them, and whether they were enabled; set while creating the
    // device (vk::Device::createMerged).
    static bool deviceSupports(VkPhysicalDevice physicalDevice);
    static void setDevice(VkDevice device, bool enabled);
    static bool isSupported();

    // The player's choice; applied on the next frame.
    static void setEnabled(bool enabled);
    static bool isActive();

    // Chains VkSwapchainLatencyCreateInfoNV into Minecraft's VkSwapchainCreateInfoKHR (address of the struct).
    static void chainSwapchainCreate(void *createInfo);
    // The swapchain Minecraft presents to, or VK_NULL_HANDLE while it is being rebuilt.
    static void setSwapchain(VkSwapchainKHR swapchain);
    // Chains VkPresentIdKHR (this frame's id) into Minecraft's VkPresentInfoKHR (address of the struct).
    static void chainPresent(void *presentInfo);

    // Once per client frame before input is read: sleeps just long enough that the GPU never queues frames ahead.
    static void beginFrame();
    // VkLatencyMarkerNV for this frame (simulation, render submit and present, start and end).
    static void marker(int marker);
};

} // namespace framegen
