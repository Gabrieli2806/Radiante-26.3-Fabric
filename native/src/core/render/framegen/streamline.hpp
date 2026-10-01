#pragma once

#include "core/all_extern.hpp"

#include <string>
#include <vector>

namespace framegen {

// NVIDIA Streamline, loaded at runtime from the folder the mod extracts it into, for NVIDIA Reflex only: frame
// generation runs through NGX / FidelityFX directly (NativeFrameGeneration). Everything stays optional: when the
// DLLs are missing every call here is a no-op.
class Streamline {
  public:
    // Loads sl.interposer.dll from `folder` and initialises Streamline. Must run before Vulkan is created.
    static bool init(const std::string &folder);
    static void shutdown();

    static bool isLoaded();
    // Folder the interposer and its plugins were loaded from, empty when Streamline is not loaded.
    static const std::string &folder();

    // NVIDIA Reflex low latency mode. Supported is decided once the device is known; enabled is the player's choice
    // and only takes effect where it is supported.
    static void setReflexEnabled(bool enabled);
    static bool reflexEnabled();

    // Hands Streamline the shared Vulkan device; call right after Minecraft's device is created.
    static void setVulkanInfo(VkInstance instance,
                              VkPhysicalDevice physicalDevice,
                              VkDevice device,
                              uint32_t graphicsQueueFamily,
                              uint32_t graphicsQueueIndex,
                              uint32_t computeQueueFamily,
                              uint32_t computeQueueIndex);

    // Extensions Reflex needs, to merge into Minecraft's instance and device.
    static std::vector<std::string> requiredInstanceExtensions();
    static std::vector<std::string> requiredDeviceExtensions();

    // Vulkan entry points from Streamline's loader. Creating Minecraft's instance and device through these is what
    // lets Streamline follow the swapchain and presentation it later drives.
    static PFN_vkCreateInstance createInstanceProxy();
    static PFN_vkCreateDevice createDeviceProxy(VkInstance instance);

    // Raw entry points from the loader, so the frame code can use the Streamline API without this header
    // depending on its types.
    static void *procAddress(const char *name);
    static void *featureFunction(uint32_t feature, const char *name);

};

} // namespace framegen
