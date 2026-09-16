#pragma once

#include "core/all_extern.hpp"

#include <glm/glm.hpp>
#include <memory>

namespace vk {
class DeviceLocalImage;
}

namespace framegen {

// Feeds DLSS Frame Generation with what it needs every frame: the camera, the depth and motion vectors the ray
// tracer produced, and the world image before Minecraft draws its interface on top.
class FrameGeneration {
  public:
    // Pipeline image slots for the depth and motion vectors of the frame that is about to be presented. The mod
    // resolves them from the pipeline graph, so upscaled variants are used when an upscaler is active.
    static void setImageSlots(int depthSlot, int motionVectorSlot);

    // Opens a new Streamline frame; call once per client frame before anything else.
    static void beginClientFrame();
    // Latency markers Reflex needs while frame generation runs (see sl::PCLMarker).
    static void marker(int marker);

    // Called once per rendered frame while the world command buffer is being recorded.
    static void beginFrame(std::shared_ptr<vk::DeviceLocalImage> color,
                           std::shared_ptr<vk::DeviceLocalImage> depth,
                           std::shared_ptr<vk::DeviceLocalImage> motionVectors,
                           VkCommandBuffer commandBuffer);

    // Frames actually put on screen per second, real plus generated. Zero while frame generation is off.
    static int presentedFrameRate();

    static int depthSlot();
    static int motionVectorSlot();

    // Forgets the previous camera, so the next frame is treated as a cut.
    static void reset();
};

} // namespace framegen
