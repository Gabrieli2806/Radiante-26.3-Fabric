#pragma once

#include "core/all_extern.hpp"

namespace vk {
// Presentation is owned by Minecraft. This only tracks the frame slot count and the size of the
// target the world is rendered into, which the render modules size their resources from.
class Swapchain : public SharedObject<Swapchain> {
  public:
    static constexpr uint32_t FRAME_SLOTS = 3;

    Swapchain(uint32_t width, uint32_t height) : extent_{width, height} {}

    void reconstruct(uint32_t width, uint32_t height) {
        extent_ = {width, height};
    }

    VkExtent2D &vkExtent() {
        return extent_;
    }

    uint32_t imageCount() {
        return FRAME_SLOTS;
    }

  private:
    VkExtent2D extent_;
};
} // namespace vk
