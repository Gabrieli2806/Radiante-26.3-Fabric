#pragma once

#include "core/all_extern.hpp"

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
};
}; // namespace vk
