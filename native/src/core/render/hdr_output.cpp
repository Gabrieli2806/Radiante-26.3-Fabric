#include "core/render/hdr_output.hpp"

#include "core/render/modules/world/tone_mapping/tone_mapping_module.hpp"
#include "core/render/pipeline.hpp"
#include "core/render/render_framework.hpp"
#include "core/render/renderer.hpp"
#include "core/util/logging.hpp"

#include <algorithm>

namespace {

std::ostream &hdrErr() {
    return radiante::err() << "[HDR] ";
}

/** Matches the push constants of shader/display/hdr_compose.comp. */
struct HdrComposePushConstant {
    float paperWhite;   // scRGB units: 1.0 is 80 nits
    float peak;         // the display's peak, in paper whites
    float exposureBias; // stops, as the tone mapping module applies them
    float saturation;
    float manualExposure;
    int autoExposure;
    int worldValid;
    int padding;
};

constexpr uint32_t VIEW_RETIRE_FRAMES = 8;

void imageBarrier(VkCommandBuffer cmd,
                  VkImage image,
                  VkImageLayout oldLayout,
                  VkImageLayout newLayout,
                  VkPipelineStageFlags2 srcStage,
                  VkAccessFlags2 srcAccess,
                  VkPipelineStageFlags2 dstStage,
                  VkAccessFlags2 dstAccess) {
    VkImageMemoryBarrier2 barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2};
    barrier.srcStageMask = srcStage;
    barrier.srcAccessMask = srcAccess;
    barrier.dstStageMask = dstStage;
    barrier.dstAccessMask = dstAccess;
    barrier.oldLayout = oldLayout;
    barrier.newLayout = newLayout;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = image;
    barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    VkDependencyInfo dependency{VK_STRUCTURE_TYPE_DEPENDENCY_INFO};
    dependency.imageMemoryBarrierCount = 1;
    dependency.pImageMemoryBarriers = &barrier;
    vkCmdPipelineBarrier2(cmd, &dependency);
}

/** Everything written before, by anyone, visible to the compose pass. */
void memoryBarrierBefore(VkCommandBuffer cmd) {
    VkMemoryBarrier2 barrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER_2};
    barrier.srcStageMask = VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
    barrier.srcAccessMask = VK_ACCESS_2_MEMORY_WRITE_BIT;
    barrier.dstStageMask = VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;
    barrier.dstAccessMask = VK_ACCESS_2_SHADER_READ_BIT | VK_ACCESS_2_SHADER_WRITE_BIT;
    VkDependencyInfo dependency{VK_STRUCTURE_TYPE_DEPENDENCY_INFO};
    dependency.memoryBarrierCount = 1;
    dependency.pMemoryBarriers = &barrier;
    vkCmdPipelineBarrier2(cmd, &dependency);
}

bool sameSize(const std::shared_ptr<vk::DeviceLocalImage> &image, uint32_t width, uint32_t height) {
    return image != nullptr && image->width() == width && image->height() == height;
}

} // namespace

HdrOutput &HdrOutput::instance() {
    static HdrOutput output;
    return output;
}

void HdrOutput::noteWorldFrame(const std::shared_ptr<WorldPipelineContext> &worldContext) {
    world_ = WorldFrame{};
    worldFresh_ = false;
    if (worldContext == nullptr || worldContext->outputImage == nullptr) return;

    for (auto &moduleContext : worldContext->worldModuleContexts) {
        auto toneMapping = std::dynamic_pointer_cast<ToneMappingModuleContext>(moduleContext);
        if (toneMapping == nullptr) continue;
        auto module = toneMapping->toneMappingModule.lock();
        if (module == nullptr) return;

        world_.finalImage = worldContext->outputImage;
        world_.mappedImage = toneMapping->ldrImage;
        world_.radianceImage = toneMapping->hdrImage;
        world_.exposureBuffer = module->exposureBuffer();
        world_.exposureBias = module->exposureBias();
        world_.saturation = module->saturation();
        world_.manualExposure = module->manualExposure();
        world_.autoExposure = module->isAutoExposureEnabled();
        worldFresh_ = world_.mappedImage != nullptr && world_.radianceImage != nullptr &&
                      world_.exposureBuffer != nullptr;
        return;
    }
}

std::shared_ptr<vk::ExternalImage>
HdrOutput::viewOf(VkImage image, VkFormat format, uint32_t width, uint32_t height) {
    for (auto it = retiredViews_.begin(); it != retiredViews_.end();) {
        if (++it->second >= VIEW_RETIRE_FRAMES) {
            vkDestroyImageView(device_->vkDevice(), it->first, nullptr);
            it = retiredViews_.erase(it);
        } else {
            ++it;
        }
    }

    if (minecraftView_ != nullptr && minecraftViewImage_ == image && minecraftView_->width() == width &&
        minecraftView_->height() == height && minecraftView_->vkFormat() == format) {
        return minecraftView_;
    }
    if (minecraftView_ != nullptr && minecraftView_->vkImageView(0) != VK_NULL_HANDLE) {
        retiredViews_.emplace_back(minecraftView_->vkImageView(0), 0u);
    }

    VkImageViewCreateInfo info{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    info.image = image;
    info.viewType = VK_IMAGE_VIEW_TYPE_2D;
    info.format = format;
    info.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    VkImageView view = VK_NULL_HANDLE;
    if (vkCreateImageView(device_->vkDevice(), &info, nullptr, &view) != VK_SUCCESS) {
        minecraftView_ = nullptr;
        minecraftViewImage_ = VK_NULL_HANDLE;
        return nullptr;
    }
    minecraftView_ = vk::ExternalImage::create(image, width, height, format);
    minecraftView_->vkImageView(0) = view;
    minecraftViewImage_ = image;
    return minecraftView_;
}

bool HdrOutput::ensureResources(uint32_t width, uint32_t height) {
    if (failed_) return false;
    if (!initialised_) {
        if (!Renderer::is_initialized()) return false;
        auto framework = Renderer::instance().framework();
        if (framework == nullptr) return false;
        try {
            device_ = framework->device();
            vma_ = framework->vma();

            auto binding = [](uint32_t index, VkDescriptorType type) {
                return VkDescriptorSetLayoutBinding{
                    .binding = index,
                    .descriptorType = type,
                    .descriptorCount = 1,
                    .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
                };
            };
            for (uint32_t i = 0; i < RING_SIZE; i++) {
                tables_.push_back(vk::DescriptorTableBuilder{}
                                      .beginDescriptorLayoutSet()
                                      .beginDescriptorLayoutSetBinding()
                                      .defineDescriptorLayoutSetBinding(
                                          binding(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER))
                                      .defineDescriptorLayoutSetBinding(
                                          binding(1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER))
                                      .defineDescriptorLayoutSetBinding(
                                          binding(2, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER))
                                      .defineDescriptorLayoutSetBinding(
                                          binding(3, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER))
                                      .defineDescriptorLayoutSetBinding(binding(4, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER))
                                      .defineDescriptorLayoutSetBinding(binding(5, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE))
                                      .endDescriptorLayoutSetBinding()
                                      .endDescriptorLayoutSet()
                                      .definePushConstant(VkPushConstantRange{
                                          .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
                                          .offset = 0,
                                          .size = sizeof(HdrComposePushConstant),
                                      })
                                      .build(device_));
            }
            shader_ = vk::Shader::create(device_,
                                         (Renderer::folderPath / "shaders/display/hdr_compose_comp.spv").string());
            pipeline_ = vk::ComputePipelineBuilder{}.defineShader(shader_).definePipelineLayout(tables_[0]).build(
                device_);
            sampler_ = vk::Sampler::create(device_, VK_FILTER_NEAREST, VK_SAMPLER_MIPMAP_MODE_NEAREST,
                                           VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            // Stands in for the exposure buffer while there is no traced world.
            placeholderBuffer_ = vk::DeviceLocalBuffer::create(vma_, device_, 16, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            initialised_ = true;
        } catch (const std::exception &e) {
            hdrErr() << "could not set up the HDR output, falling back to SDR: " << e.what() << std::endl;
            failed_ = true;
            return false;
        }
    }

    if (composedImage_ == nullptr || composedImage_->width() != width || composedImage_->height() != height) {
        composedImage_ = vk::DeviceLocalImage::create(device_, vma_, false, width, height, 1,
                                                      VK_FORMAT_R16G16B16A16_SFLOAT,
                                                      VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        composedImage_->imageLayout() = VK_IMAGE_LAYOUT_UNDEFINED;
    }
    return true;
}

bool HdrOutput::compose(VkCommandBuffer cmd,
                        VkImage minecraftImage,
                        VkImageLayout minecraftLayout,
                        VkFormat minecraftFormat,
                        uint32_t width,
                        uint32_t height,
                        VkImage swapchainImage,
                        uint32_t swapchainWidth,
                        uint32_t swapchainHeight,
                        float paperWhiteNits,
                        float peakNits) {
    if (cmd == VK_NULL_HANDLE || width == 0 || height == 0) return false;
    if (!ensureResources(width, height)) return false;

    auto minecraft = viewOf(minecraftImage, minecraftFormat, width, height);
    if (minecraft == nullptr) return false;

    bool worldValid = worldFresh_ && sameSize(world_.finalImage, width, height) &&
                      sameSize(world_.mappedImage, width, height) && sameSize(world_.radianceImage, width, height);
    // A world frame is shown once; if Minecraft presents again without tracing one (a menu over a paused game
    // that stopped rendering the level), what it shows is its own image.
    worldFresh_ = false;

    const VkPipelineStageFlags2 anyStage = VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
    const VkAccessFlags2 anyAccess = VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT;
    const VkPipelineStageFlags2 compute = VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;

    memoryBarrierBefore(cmd);

    // Every image the pass samples is read in GENERAL and put back afterwards as it was, so neither Minecraft
    // nor the world pipeline sees a layout it did not leave.
    std::vector<std::pair<VkImage, VkImageLayout>> restore;
    auto toGeneral = [&](VkImage image, VkImageLayout layout) {
        if (layout == VK_IMAGE_LAYOUT_GENERAL) return;
        imageBarrier(cmd, image, layout, VK_IMAGE_LAYOUT_GENERAL, anyStage, anyAccess, compute,
                     VK_ACCESS_2_SHADER_READ_BIT);
        restore.emplace_back(image, layout);
    };
    toGeneral(minecraftImage, minecraftLayout);
    if (worldValid) {
        for (auto &image : {world_.finalImage, world_.mappedImage, world_.radianceImage}) {
            bool seen = std::any_of(restore.begin(), restore.end(),
                                    [&](const auto &entry) { return entry.first == image->vkImage(); });
            if (!seen) toGeneral(image->vkImage(), image->imageLayout());
        }
    }
    imageBarrier(cmd, composedImage_->vkImage(), VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, anyStage,
                 anyAccess, compute, VK_ACCESS_2_SHADER_WRITE_BIT);

    auto table = tables_[ringIndex_];
    ringIndex_ = (ringIndex_ + 1) % RING_SIZE;
    std::shared_ptr<vk::Image> finalImage = worldValid ? std::static_pointer_cast<vk::Image>(world_.finalImage) :
                                                         std::static_pointer_cast<vk::Image>(minecraft);
    std::shared_ptr<vk::Image> mappedImage = worldValid ? std::static_pointer_cast<vk::Image>(world_.mappedImage) :
                                                          std::static_pointer_cast<vk::Image>(minecraft);
    std::shared_ptr<vk::Image> radianceImage =
        worldValid ? std::static_pointer_cast<vk::Image>(world_.radianceImage) :
                     std::static_pointer_cast<vk::Image>(minecraft);
    table->bindSamplerImage(sampler_, minecraft, VK_IMAGE_LAYOUT_GENERAL, 0, 0, 0);
    table->bindSamplerImage(sampler_, finalImage, VK_IMAGE_LAYOUT_GENERAL, 0, 1, 0);
    table->bindSamplerImage(sampler_, mappedImage, VK_IMAGE_LAYOUT_GENERAL, 0, 2, 0);
    table->bindSamplerImage(sampler_, radianceImage, VK_IMAGE_LAYOUT_GENERAL, 0, 3, 0);
    table->bindBuffer(worldValid ? world_.exposureBuffer : placeholderBuffer_, 0, 4);
    table->bindImage(composedImage_, VK_IMAGE_LAYOUT_GENERAL, 0, 5);

    HdrComposePushConstant pc{};
    pc.paperWhite = std::clamp(paperWhiteNits, 40.0f, 1000.0f) / 80.0f;
    pc.peak = std::max(peakNits, paperWhiteNits) / std::max(paperWhiteNits, 1.0f);
    pc.exposureBias = world_.exposureBias;
    pc.saturation = world_.saturation;
    pc.manualExposure = world_.manualExposure;
    pc.autoExposure = world_.autoExposure ? 1 : 0;
    pc.worldValid = worldValid ? 1 : 0;

    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_->vkPipeline());
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, table->vkPipelineLayout(), 0,
                            static_cast<uint32_t>(table->descriptorSet().size()), table->descriptorSet().data(), 0,
                            nullptr);
    vkCmdPushConstants(cmd, table->vkPipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pc), &pc);
    vkCmdDispatch(cmd, (width + 15) / 16, (height + 15) / 16, 1);

    imageBarrier(cmd, composedImage_->vkImage(), VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                 compute, VK_ACCESS_2_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                 VK_ACCESS_2_TRANSFER_READ_BIT);
    for (auto &[image, layout] : restore) {
        imageBarrier(cmd, image, VK_IMAGE_LAYOUT_GENERAL, layout, compute, VK_ACCESS_2_SHADER_READ_BIT, anyStage,
                     anyAccess);
    }

    VkImageBlit blit{};
    blit.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    blit.srcOffsets[1] = {static_cast<int>(width), static_cast<int>(height), 1};
    blit.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    blit.dstOffsets[1] = {static_cast<int>(std::min(width, swapchainWidth)),
                          static_cast<int>(std::min(height, swapchainHeight)), 1};
    blit.srcOffsets[1].x = blit.dstOffsets[1].x;
    blit.srcOffsets[1].y = blit.dstOffsets[1].y;
    vkCmdBlitImage(cmd, composedImage_->vkImage(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, swapchainImage,
                   VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_NEAREST);
    composedImage_->imageLayout() = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    return true;
}

void HdrOutput::release() {
    if (device_ != nullptr) {
        vkDeviceWaitIdle(device_->vkDevice());
        for (auto &[view, age] : retiredViews_) vkDestroyImageView(device_->vkDevice(), view, nullptr);
        if (minecraftView_ != nullptr && minecraftView_->vkImageView(0) != VK_NULL_HANDLE) {
            vkDestroyImageView(device_->vkDevice(), minecraftView_->vkImageView(0), nullptr);
        }
    }
    retiredViews_.clear();
    minecraftView_ = nullptr;
    minecraftViewImage_ = VK_NULL_HANDLE;
    world_ = WorldFrame{};
    worldFresh_ = false;
    composedImage_ = nullptr;
    placeholderBuffer_ = nullptr;
    sampler_ = nullptr;
    pipeline_ = nullptr;
    shader_ = nullptr;
    tables_.clear();
    vma_ = nullptr;
    device_ = nullptr;
    initialised_ = false;
}
