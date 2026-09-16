#pragma once

#include "core/all_extern.hpp"

namespace vk {
// Wraps the VkInstance owned by Minecraft's Vulkan backend. The instance is created through
// createMerged() so the renderer's extension requirements (DLSS, XeSS) are enabled on it.
class Instance : public SharedObject<Instance> {
  public:
    explicit Instance(VkInstance instance);
    ~Instance();

    VkInstance &vkInstance();
    uint32_t apiVersion() const;
    bool isDlssInstanceExtensionsCompatible() const;
    bool isXessInstanceExtensionsCompatible() const;

    static VkResult createMerged(const VkInstanceCreateInfo *baseInfo,
                                 const VkAllocationCallbacks *allocator,
                                 VkInstance *outInstance);
    static VkInstance lastCreatedInstance();

  private:
    VkInstance instance_;
};
} // namespace vk
