#pragma once

#include "core/all_extern.hpp"
#include "core/vulkan/all_core_vulkan.hpp"

#include <cstdint>
#include <memory>
#include <vector>

class FrameworkContext;
struct WorldPipelineContext;

/**
 * HDR display output. Minecraft presents by blitting its main render target (8-bit, SDR) into the window's
 * swapchain; with HDR on, the swapchain is 16-bit float scRGB instead, and that blit is replaced by compose():
 *
 * - Pixels Minecraft left as the traced world (nothing drawn over them since the world was fused in) are rebuilt
 *   from the world's HDR radiance with an HDR tone curve, so highlights go up to the display's peak brightness.
 * - Everything else - menus, text, the hotbar, anything translucent over the world, the whole screen when the
 *   world is not being traced - is Minecraft's SDR image shown at the paper white brightness.
 *
 * The world pipeline is not changed at all: SDR output, screenshots and every module keep working as before. The
 * only inputs are images the pipeline already keeps (the tone mapping module's input, its output and its exposure,
 * and the world image fused into Minecraft's target), noted here each frame by Framework::renderFrame.
 */
class HdrOutput {
  public:
    static HdrOutput &instance();

    /** A world frame was traced and fused into Minecraft's target; compose() may use it until the next one. */
    void noteWorldFrame(const std::shared_ptr<WorldPipelineContext> &worldContext);

    /**
     * Records the HDR composition of Minecraft's final image into `swapchainImage` on Minecraft's present
     * command buffer, in place of its own blit. The swapchain image is in TRANSFER_DST_OPTIMAL, the Minecraft
     * image in `minecraftLayout`. Returns false when it could not (the caller then blits as usual).
     */
    bool compose(VkCommandBuffer commandBuffer,
                 VkImage minecraftImage,
                 VkImageLayout minecraftLayout,
                 VkFormat minecraftFormat,
                 uint32_t width,
                 uint32_t height,
                 VkImage swapchainImage,
                 uint32_t swapchainWidth,
                 uint32_t swapchainHeight,
                 float paperWhiteNits,
                 float peakNits);

    /** Drops every Vulkan object; called when the renderer shuts down. */
    void release();

  private:
    struct WorldFrame {
        std::shared_ptr<vk::DeviceLocalImage> finalImage;    // the world as fused into Minecraft's target
        std::shared_ptr<vk::DeviceLocalImage> mappedImage;   // tone mapped, before the post render passes
        std::shared_ptr<vk::DeviceLocalImage> radianceImage; // HDR radiance the tone mapping read
        std::shared_ptr<vk::DeviceLocalBuffer> exposureBuffer;
        float exposureBias = 0.0f;
        float saturation = 1.0f;
        float manualExposure = 1.0f;
        bool autoExposure = true;
    };

    bool ensureResources(uint32_t width, uint32_t height);
    std::shared_ptr<vk::ExternalImage> viewOf(VkImage image, VkFormat format, uint32_t width, uint32_t height);

    // Descriptor sets are rewritten every frame, and a set may still be in use by a frame in flight: a small ring.
    static constexpr uint32_t RING_SIZE = 4;

    bool initialised_ = false;
    bool failed_ = false;
    std::shared_ptr<vk::Device> device_;
    std::shared_ptr<vk::VMA> vma_;
    std::vector<std::shared_ptr<vk::DescriptorTable>> tables_;
    uint32_t ringIndex_ = 0;
    std::shared_ptr<vk::Shader> shader_;
    std::shared_ptr<vk::ComputePipeline> pipeline_;
    std::shared_ptr<vk::Sampler> sampler_;
    std::shared_ptr<vk::DeviceLocalImage> composedImage_;
    std::shared_ptr<vk::DeviceLocalImage> placeholderImage_;
    std::shared_ptr<vk::DeviceLocalBuffer> placeholderBuffer_;

    // A view of Minecraft's image, recreated when it changes (on a resize); old views are destroyed a few frames
    // later, once no frame in flight can still read them.
    std::shared_ptr<vk::ExternalImage> minecraftView_;
    VkImage minecraftViewImage_ = VK_NULL_HANDLE;
    std::vector<std::pair<VkImageView, uint32_t>> retiredViews_;

    WorldFrame world_;
    bool worldFresh_ = false;
};
