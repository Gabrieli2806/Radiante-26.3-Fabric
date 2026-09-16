#pragma once

#include "core/all_extern.hpp"

namespace vk {
class Instance;

class PhysicalDevice : public SharedObject<PhysicalDevice> {
  public:
    PhysicalDevice(std::shared_ptr<Instance> instance,
                   VkPhysicalDevice physicalDevice,
                   uint32_t mainQueueFamily,
                   uint32_t secondaryQueueFamily);
    ~PhysicalDevice();

    VkPhysicalDevice &vkPhysicalDevice();
    uint32_t mainQueueIndex();
    uint32_t secondaryQueueIndex();

    VkPhysicalDeviceProperties properties();
    VkPhysicalDeviceRayTracingPipelinePropertiesKHR rayTracingProperties();
    VkPhysicalDeviceAccelerationStructurePropertiesKHR accelerationStructProperties();

    static bool isRayTracingCapable(VkPhysicalDevice device);

  private:
    std::shared_ptr<Instance> instance_;

    VkPhysicalDevice physicalDevice_ = VK_NULL_HANDLE;
    uint32_t mainQueueIndex_ = -1;
    uint32_t secondaryQueueIndex_ = -1;

    VkPhysicalDeviceProperties properties_;
    VkPhysicalDeviceRayTracingPipelinePropertiesKHR rayTracingProperties_;
    VkPhysicalDeviceAccelerationStructurePropertiesKHR accelerationStructProperties_;
};
} // namespace vk
