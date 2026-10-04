#pragma once

#include "core/all_extern.hpp"
#include "core/vulkan/all_core_vulkan.hpp"

#include <memory>
#include <vector>

class Framework;

// Writes the traced world's depth into Minecraft's depth texture, in Minecraft's own depth convention. Minecraft's
// level passes run after the tracer for the sake of other mods' overlays (schematics, selection boxes, markers);
// they depth test against that texture, and without the traced depth in it they would draw through walls.
class McDepthWriter {
  public:
    struct Target {
        VkImage image = VK_NULL_HANDLE;
        VkFormat format = VK_FORMAT_UNDEFINED;
        // zScale, zOffset, wScale, wOffset of the projection; see mc_depth.comp.
        float projection[4] = {0.0f, 0.0f, 0.0f, 0.0f};
    };

    // Recorded into commandBuffer. linearDepth is the tracer's first hit depth, at any resolution.
    void write(std::shared_ptr<Framework> framework,
               std::shared_ptr<vk::CommandBuffer> commandBuffer,
               std::shared_ptr<vk::DeviceLocalImage> linearDepth,
               const Target &target,
               uint32_t width,
               uint32_t height,
               uint32_t frameIndex);

    void reset();

  private:
    void ensure(std::shared_ptr<Framework> framework, uint32_t width, uint32_t height, uint32_t frameCount);

    struct PushConstants {
        float zScale;
        float zOffset;
        float wScale;
        float wOffset;
        uint32_t srcWidth;
        uint32_t srcHeight;
        uint32_t dstWidth;
        uint32_t dstHeight;
    };

    uint32_t width_ = 0;
    uint32_t height_ = 0;
    std::shared_ptr<vk::DeviceLocalImage> deviceDepth_;
    std::shared_ptr<vk::DeviceLocalBuffer> transfer_;
    std::vector<std::shared_ptr<vk::DescriptorTable>> tables_;
    std::shared_ptr<vk::ComputePipeline> pipeline_;
};
