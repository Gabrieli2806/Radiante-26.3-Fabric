#pragma once

#include "core/all_extern.hpp"

#include <filesystem>
#include <mutex>

namespace vk {
class Instance;
class PhysicalDevice;

// Wraps the VkDevice owned by Minecraft's Vulkan backend. The device is created through
// createMerged() so ray tracing, DLSS and XeSS requirements are enabled alongside vanilla's.
class Device : public SharedObject<Device> {
  public:
    Device(std::shared_ptr<Instance> instance,
           std::shared_ptr<PhysicalDevice> physicalDevice,
           VkDevice device,
           VkQueue mainQueue,
           VkQueue secondaryQueue);
    ~Device();

    VkDevice &vkDevice();
    VkQueue &mainVkQueue();
    VkQueue &secondaryQueue();

    bool hasExtendedDynamicState2LogicOp() const;
    bool isDlssDeviceExtensionsCompatible() const;
    bool isXessDeviceExtensionsCompatible() const;
    bool isDlssFrameGenerationDeviceExtensionsCompatible() const;
    bool isFsrFrameGenerationDeviceExtensionsCompatible() const;
    bool isShaderExecutionReorderingEnabled() const;

    // One VkPipelineCache for every pipeline the renderer builds, kept on disk between runs: a pipeline whose shaders
    // were compiled before is rebuilt from the driver's saved code instead of compiled again. Made on first use,
    // loaded from the file set with setPipelineCacheFile (the driver ignores data from another GPU or driver).
    VkPipelineCache pipelineCache();
    // Writes the cache to its file; called when a pipeline build ends and when the device goes.
    void savePipelineCache();
    static void setPipelineCacheFile(const std::filesystem::path &file);

    static VkResult createMerged(VkPhysicalDevice physicalDevice,
                                 const VkDeviceCreateInfo *baseInfo,
                                 const VkAllocationCallbacks *allocator,
                                 VkDevice *outDevice);

  private:
    std::shared_ptr<Instance> instance_;
    std::shared_ptr<PhysicalDevice> physicalDevice_;

    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue mainQueue_ = VK_NULL_HANDLE;
    VkQueue secondaryQueue_ = VK_NULL_HANDLE;

    VkPipelineCache pipelineCache_ = VK_NULL_HANDLE;
    std::mutex pipelineCacheMutex_;
    static std::filesystem::path pipelineCacheFile_;
};
}; // namespace vk
