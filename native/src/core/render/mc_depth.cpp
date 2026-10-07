#include "core/render/mc_depth.hpp"

#include "core/render/render_framework.hpp"
#include "core/render/renderer.hpp"

void McDepthWriter::reset() {
    sized_.clear();
    current_ = nullptr;
    pipeline_ = nullptr;
}

void McDepthWriter::ensure(std::shared_ptr<Framework> framework, uint32_t width, uint32_t height, uint32_t frameCount) {
    for (auto &entry : sized_) {
        if (entry.width == width && entry.height == height && entry.tables.size() == frameCount) {
            current_ = &entry;
            return;
        }
    }

    // Least recently added out first; whatever a frame in flight still uses is kept until it completes.
    auto &retainer = framework->frameResourceRetainer();
    if (sized_.size() >= MAX_SIZES) {
        Sized &old = sized_.front();
        retainer.retain(old.deviceDepth);
        retainer.retain(old.transfer);
        for (auto &table : old.tables) retainer.retain(table);
        sized_.erase(sized_.begin());
    }
    sized_.reserve(MAX_SIZES);
    sized_.emplace_back();
    Sized &entry = sized_.back();
    current_ = &entry;

    entry.width = width;
    entry.height = height;
    entry.deviceDepth = vk::DeviceLocalImage::create(
        framework->device(), framework->vma(), false, width, height, 1, VK_FORMAT_R32_SFLOAT,
        VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
    entry.transfer = vk::DeviceLocalBuffer::create(framework->vma(), framework->device(), false,
                                              static_cast<size_t>(width) * height * sizeof(float),
                                              VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);

    entry.tables.assign(frameCount, nullptr);
    for (uint32_t i = 0; i < frameCount; i++) {
        entry.tables[i] = vk::DescriptorTableBuilder{}
                         .beginDescriptorLayoutSet()
                         .beginDescriptorLayoutSetBinding()
                         .defineDescriptorLayoutSetBinding({
                             .binding = 0,
                             .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                             .descriptorCount = 1,
                             .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
                         })
                         .defineDescriptorLayoutSetBinding({
                             .binding = 1,
                             .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                             .descriptorCount = 1,
                             .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
                         })
                         .endDescriptorLayoutSetBinding()
                         .endDescriptorLayoutSet()
                         .definePushConstant({
                             .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
                             .offset = 0,
                             .size = sizeof(PushConstants),
                         })
                         .build(framework->device());
    }
    if (pipeline_ == nullptr) {
        auto shader = vk::Shader::create(framework->device(),
                                         (Renderer::folderPath / "shaders/world/overlay/mc_depth_comp.spv").string());
        pipeline_ = vk::ComputePipelineBuilder{}
                        .defineShader(shader)
                        .definePipelineLayout(entry.tables[0])
                        .build(framework->device());
    }
}

void McDepthWriter::write(std::shared_ptr<Framework> framework,
                          std::shared_ptr<vk::CommandBuffer> commandBuffer,
                          std::shared_ptr<vk::DeviceLocalImage> linearDepth,
                          const Target &target,
                          uint32_t width,
                          uint32_t height,
                          uint32_t frameIndex) {
    // Only the depth format Minecraft uses for its main target; another would need its own copy rules.
    if (target.image == VK_NULL_HANDLE || target.format != VK_FORMAT_D32_SFLOAT || linearDepth == nullptr) return;
    uint32_t frameCount = framework->swapchain()->imageCount();
    if (frameIndex >= frameCount) return;
    ensure(framework, width, height, frameCount);

    auto mainQueueIndex = framework->physicalDevice()->mainQueueIndex();
    VkCommandBuffer cmd = commandBuffer->vkCommandBuffer();
    auto table = current_->tables[frameIndex];
    auto deviceDepth = current_->deviceDepth;
    auto transfer = current_->transfer;

    commandBuffer->barriersBufferImage(
        {},
        {{.srcStageMask = VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
          .srcAccessMask = VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT,
          .dstStageMask = VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
          .dstAccessMask = VK_ACCESS_2_SHADER_READ_BIT,
          .oldLayout = linearDepth->imageLayout(),
          .newLayout = VK_IMAGE_LAYOUT_GENERAL,
          .srcQueueFamilyIndex = mainQueueIndex,
          .dstQueueFamilyIndex = mainQueueIndex,
          .image = linearDepth,
          .subresourceRange = vk::wholeColorSubresourceRange},
         {.srcStageMask = VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
          .srcAccessMask = VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT,
          .dstStageMask = VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
          .dstAccessMask = VK_ACCESS_2_SHADER_WRITE_BIT,
          .oldLayout = deviceDepth->imageLayout(),
          .newLayout = VK_IMAGE_LAYOUT_GENERAL,
          .srcQueueFamilyIndex = mainQueueIndex,
          .dstQueueFamilyIndex = mainQueueIndex,
          .image = deviceDepth,
          .subresourceRange = vk::wholeColorSubresourceRange}});
    linearDepth->imageLayout() = VK_IMAGE_LAYOUT_GENERAL;
    deviceDepth->imageLayout() = VK_IMAGE_LAYOUT_GENERAL;

    table->bindImage(linearDepth, VK_IMAGE_LAYOUT_GENERAL, 0, 0);
    table->bindImage(deviceDepth, VK_IMAGE_LAYOUT_GENERAL, 0, 1);

    PushConstants pushConstants{
        target.projection[0], target.projection[1], target.projection[2], target.projection[3],
        linearDepth->width(), linearDepth->height(), width,                height,
    };
    commandBuffer->bindDescriptorTable(table, VK_PIPELINE_BIND_POINT_COMPUTE)->bindComputePipeline(pipeline_);
    vkCmdPushConstants(cmd, table->vkPipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pushConstants),
                       &pushConstants);
    vkCmdDispatch(cmd, (width + 15) / 16, (height + 15) / 16, 1);

    // A colour image cannot be copied into a depth image, but a buffer can: the values go through one.
    auto barrier = [&](VkPipelineStageFlags srcStage, VkAccessFlags srcAccess, VkPipelineStageFlags dstStage,
                       VkAccessFlags dstAccess) {
        VkMemoryBarrier memory{};
        memory.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
        memory.srcAccessMask = srcAccess;
        memory.dstAccessMask = dstAccess;
        vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, 1, &memory, 0, nullptr, 0, nullptr);
    };
    barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT);

    VkBufferImageCopy region{};
    region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    region.imageExtent = {width, height, 1};
    vkCmdCopyImageToBuffer(cmd, deviceDepth->vkImage(), VK_IMAGE_LAYOUT_GENERAL, transfer->vkBuffer(), 1, &region);

    barrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT);

    // Minecraft keeps its textures in GENERAL layout for their whole lifetime.
    region.imageSubresource = {VK_IMAGE_ASPECT_DEPTH_BIT, 0, 0, 1};
    vkCmdCopyBufferToImage(cmd, transfer->vkBuffer(), target.image, VK_IMAGE_LAYOUT_GENERAL, 1, &region);

    barrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
            VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT |
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT |
                VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT);
}
