#include "core/vulkan/queue_lock.hpp"
#include <thread>
#include <chrono>
#include <atomic>
#include "core/render/render_framework.hpp"

#include "core/render/framegen/frame_generation.hpp"
#include "core/render/framegen/streamline.hpp"

#include "common/shared.hpp"
#include "core/render/buffers.hpp"
#include "core/render/chunks.hpp"
#include "core/render/entities.hpp"
#include "core/render/pipeline.hpp"
#include "core/render/renderer.hpp"
#include "core/render/textures.hpp"
#include "core/render/world.hpp"

#include <iostream>
#include "core/util/logging.hpp"

namespace {
std::ostream &renderFrameworkCerr() {
    return radiante::err() << "[Render Framework] ";
}

VkImageLayout worldOutputRestingLayout() {
#ifdef USE_AMD
    return VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
#else
    return VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
#endif
}
} // namespace

FrameworkContext::FrameworkContext(std::shared_ptr<Framework> framework, uint32_t frameIndex)
    : framework(framework),
      frameIndex(frameIndex),
      instance(framework->instance_),
      physicalDevice(framework->physicalDevice_),
      device(framework->device_),
      vma(framework->vma_),
      swapchain(framework->swapchain_),
      commandPool(framework->mainCommandPool_),
      uploadCommandBuffer(vk::CommandBuffer::create(framework->device_, framework->mainCommandPool_)),
      worldCommandBuffer(vk::CommandBuffer::create(framework->device_, framework->mainCommandPool_)),
      fuseCommandBuffer(vk::CommandBuffer::create(framework->device_, framework->mainCommandPool_)) {}

FrameworkContext::~FrameworkContext() {}

void FrameworkContext::waitForPreviousSubmit() {
    if (submitTimeline == VK_NULL_HANDLE || submitTimelineValue == 0) return;

    VkSemaphoreWaitInfo waitInfo{};
    waitInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO;
    waitInfo.semaphoreCount = 1;
    waitInfo.pSemaphores = &submitTimeline;
    waitInfo.pValues = &submitTimelineValue;
    VkResult result = vkWaitSemaphores(device->vkDevice(), &waitInfo, UINT64_MAX);
    if (result != VK_SUCCESS) {
        renderFrameworkCerr() << "vkWaitSemaphores failed: " << result << std::endl;
    }
    submitTimelineValue = 0;
}

void FrameworkContext::fuseInto(std::shared_ptr<vk::ExternalImage> target) {
    auto f = framework.lock();
    if (!f->isRunning()) return;

    auto pipelineContext = f->pipeline_->acquirePipelineContext(shared_from_this());
    auto worldContext = pipelineContext->worldPipelineContext;
    if (worldContext == nullptr || worldContext->outputImage == nullptr) return;

    auto source = worldContext->outputImage;
    auto mainQueueIndex = physicalDevice->mainQueueIndex();

    fuseCommandBuffer->barriersBufferImage(
        {}, {{
                .srcStageMask = VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                .srcAccessMask = VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT,
                .dstStageMask = VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                .dstAccessMask = VK_ACCESS_2_TRANSFER_READ_BIT,
                .oldLayout = source->imageLayout(),
                .newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                .srcQueueFamilyIndex = mainQueueIndex,
                .dstQueueFamilyIndex = mainQueueIndex,
                .image = source,
                .subresourceRange = vk::wholeColorSubresourceRange,
            }});
    source->imageLayout() = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;

    VkImageBlit blit{};
    blit.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    blit.srcOffsets[1] = {static_cast<int>(source->width()), static_cast<int>(source->height()), 1};
    blit.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    blit.dstOffsets[1] = {static_cast<int>(target->width()), static_cast<int>(target->height()), 1};

    // Minecraft keeps its textures in GENERAL layout for their whole lifetime. Vulkan's
    // framebuffer origin is top-left for both images, so no flip is needed.
    vkCmdBlitImage(fuseCommandBuffer->vkCommandBuffer(), source->vkImage(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                   target->vkImage(), VK_IMAGE_LAYOUT_GENERAL, 1, &blit, VK_FILTER_LINEAR);

    fuseCommandBuffer->barriersBufferImage(
        {}, {{
                .srcStageMask = VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                .srcAccessMask = VK_ACCESS_2_TRANSFER_READ_BIT,
                .dstStageMask = VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                .dstAccessMask = VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT,
                .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                .newLayout = worldOutputRestingLayout(),
                .srcQueueFamilyIndex = mainQueueIndex,
                .dstQueueFamilyIndex = mainQueueIndex,
                .image = source,
                .subresourceRange = vk::wholeColorSubresourceRange,
            }});
    source->imageLayout() = worldOutputRestingLayout();
}

Framework::Framework() {}

Framework::~Framework() {}

void Framework::init(const SharedDeviceHandles &handles) {
    instance_ = vk::Instance::create(handles.instance);
    physicalDevice_ = vk::PhysicalDevice::create(instance_, handles.physicalDevice, handles.mainQueueFamily,
                                                 handles.secondaryQueueFamily);
    device_ =
        vk::Device::create(instance_, physicalDevice_, handles.device, handles.mainQueue, handles.secondaryQueue);
    vma_ = vk::VMA::create(instance_, physicalDevice_, device_);
    swapchain_ = vk::Swapchain::create(handles.width, handles.height);
    mainCommandPool_ = vk::CommandPool::create(physicalDevice_, device_);
    asyncCommandPool_ = vk::CommandPool::create(physicalDevice_, device_, physicalDevice_->secondaryQueueIndex());
    frameResourceRetainer_ = FrameResourceRetainer::create(shared_from_this());
    worldAsyncCommandBuffer_ = vk::CommandBuffer::create(device_, asyncCommandPool_);

    framegen::Streamline::setVulkanInfo(handles.instance, handles.physicalDevice, handles.device,
                                        handles.mainQueueFamily, 0, handles.secondaryQueueFamily, 0);

    createContexts();
    pipeline_ = Pipeline::create(shared_from_this());
}

void Framework::createContexts() {
    contexts_.clear();
    for (uint32_t i = 0; i < swapchain_->imageCount(); i++) {
        contexts_.push_back(FrameworkContext::create(shared_from_this(), i));
    }
    currentContext_ = nullptr;
    lastRenderedContext_ = nullptr;
    nextContextIndex_ = 0;
}

void Framework::acquireContext() {
    if (!running_) return;
    std::unique_lock<std::recursive_mutex> lck(recreateMtx_);

    auto context = contexts_[nextContextIndex_];
    nextContextIndex_ = (nextContextIndex_ + 1) % contexts_.size();

    context->waitForPreviousSubmit();
    currentContext_ = context;
    frameResourceRetainer_->beginFrame(context->frameIndex);

    // Flush texture uploads queued while no frame was in flight; resetFrame drops the queue.
    Renderer::instance().textures()->performQueuedUpload();

    context->uploadCommandBuffer->begin();
    context->worldCommandBuffer->begin();
    context->fuseCommandBuffer->begin();
    context->recording = true;

    Renderer::instance().buffers()->resetFrame();
    Renderer::instance().textures()->resetFrame();
    Renderer::instance().world()->resetFrame();
    Renderer::instance().world()->chunks()->resetFrame();
    Renderer::instance().world()->entities()->resetFrame();
}

std::vector<VkCommandBuffer> Framework::renderFrame(VkImage target, uint32_t width, uint32_t height, VkFormat format) {
    std::unique_lock<std::recursive_mutex> lck(recreateMtx_);
    if (!running_) return {};

    auto extent = swapchain_->vkExtent();
    if (extent.width != width || extent.height != height || pipeline_->isRecreationNeeded ||
        Renderer::options.needRecreate) {
        swapchain_->reconstruct(width, height);
        recreate();
    }

    auto context = safeAcquireCurrentContext();

    Renderer::instance().textures()->performQueuedUpload();
    Renderer::instance().buffers()->performQueuedUpload();

    auto pipelineContext = pipeline_->acquirePipelineContext(context);
    if (Renderer::instance().world()->shouldRender() && pipelineContext->worldPipelineContext != nullptr) {
        pipelineContext->worldPipelineContext->render();

        // Frame generation reads the finished world image plus the depth and motion vectors behind it.
        auto worldPipeline = pipeline_->worldPipeline();
        if (worldPipeline != nullptr) {
            int depthSlot = framegen::FrameGeneration::depthSlot();
            int motionSlot = framegen::FrameGeneration::motionVectorSlot();
            if (depthSlot >= 0 && motionSlot >= 0) {
                framegen::FrameGeneration::beginFrame(
                    pipelineContext->worldPipelineContext->outputImage,
                    worldPipeline->sharedImage(context->frameIndex, static_cast<uint32_t>(depthSlot)),
                    worldPipeline->sharedImage(context->frameIndex, static_cast<uint32_t>(motionSlot)),
                    context->worldCommandBuffer->vkCommandBuffer());
            }
        }

        context->fuseInto(vk::ExternalImage::create(target, width, height, format));
    }

    context->uploadCommandBuffer->end();
    context->worldCommandBuffer->end();
    context->fuseCommandBuffer->end();
    context->recording = false;
    lastRenderedContext_ = context;

    std::vector<VkCommandBuffer> result = {
        context->uploadCommandBuffer->vkCommandBuffer(),
        context->worldCommandBuffer->vkCommandBuffer(),
        context->fuseCommandBuffer->vkCommandBuffer(),
    };

    currentContext_ = nullptr;
    return result;
}

namespace {
// Diagnostics for frames that never complete: a watchdog reports what the queue is doing when Minecraft stops
// handing frames to the renderer.
std::atomic<VkSemaphore> g_watchTimeline{VK_NULL_HANDLE};
std::atomic<uint64_t> g_watchValue{0};
std::atomic<int64_t> g_watchTime{0};
std::atomic<bool> g_watchdogStarted{false};

int64_t nowTicks() {
    return std::chrono::steady_clock::now().time_since_epoch().count();
}

void startWatchdog(VkDevice device) {
    if (g_watchdogStarted.exchange(true)) return;
    std::thread([device]() {
        bool reported = false;
        while (true) {
            std::this_thread::sleep_for(std::chrono::milliseconds(500));
            int64_t last = g_watchTime.load();
            if (last == 0) continue;
            double stalledMs = std::chrono::duration<double, std::milli>(
                                   std::chrono::steady_clock::duration(nowTicks() - last))
                                   .count();
            if (stalledMs < 2500.0) {
                reported = false;
                continue;
            }
            if (reported) continue;
            reported = true;

            uint64_t counter = 0;
            VkSemaphore timeline = g_watchTimeline.load();
            VkResult result = timeline != VK_NULL_HANDLE ?
                                  vkGetSemaphoreCounterValue(device, timeline, &counter) :
                                  VK_ERROR_UNKNOWN;
            const char *op = vk::queueOp().load();
            double opMs = std::chrono::duration<double, std::milli>(
                              std::chrono::steady_clock::duration(nowTicks() - vk::queueOpStart().load()))
                              .count();
            radiante::out() << "[Radiante-Watchdog] no frame for " << stalledMs << " ms; last submitted timeline value "
                      << g_watchValue.load() << ", completed " << counter << " (result " << result
                      << "); queue op " << (op != nullptr ? op : "none")
                      << (op != nullptr ? " running for " + std::to_string(opMs) + " ms" : std::string()) << std::endl;
            radiante::out().flush();
        }
    }).detach();
}
} // namespace

void Framework::markSubmitted(VkSemaphore timeline, uint64_t value) {
    g_watchTimeline.store(timeline);
    g_watchValue.store(value);
    g_watchTime.store(nowTicks());
    startWatchdog(device_->vkDevice());
    std::unique_lock<std::recursive_mutex> lck(recreateMtx_);
    if (lastRenderedContext_ == nullptr) return;
    lastRenderedContext_->submitTimeline = timeline;
    lastRenderedContext_->submitTimelineValue = value;
    acquireContext();
}

void Framework::recreate() {
    if (!running_) return;

    std::unique_lock<std::recursive_mutex> lck(recreateMtx_);
    const bool reportNativeProgress = Pipeline::nativeRebuildActive();

    try {
        Renderer::options.needRecreate = false;
        pipeline_->isRecreationNeeded = false;

        waitRenderQueueIdle();

        createContexts();
        pipeline_->recreate(shared_from_this());
        Renderer::instance().textures()->bindAllTextures();
        acquireContext();

        if (reportNativeProgress) { Pipeline::endNativeRebuild(); }
    } catch (...) {
        if (reportNativeProgress) { Pipeline::endNativeRebuild(); }
        throw;
    }
}

void Framework::waitDeviceIdle() {
    vkDeviceWaitIdle(device_->vkDevice());
}

void Framework::waitRenderQueueIdle() {
    vkQueueWaitIdle(device_->mainVkQueue());
}

void Framework::waitBackendQueueIdle() {
    vkQueueWaitIdle(device_->secondaryQueue());
}

void Framework::close() {
    if (running_ && pipeline_ != nullptr) { pipeline_->close(); }
    running_ = false;
}

bool Framework::isRunning() {
    return running_;
}

std::recursive_mutex &Framework::recreateMtx() {
    return recreateMtx_;
}

std::shared_ptr<vk::Instance> Framework::instance() {
    return instance_;
}

std::shared_ptr<vk::PhysicalDevice> Framework::physicalDevice() {
    return physicalDevice_;
}

std::shared_ptr<vk::Device> Framework::device() {
    return device_;
}

std::shared_ptr<vk::VMA> Framework::vma() {
    return vma_;
}

std::shared_ptr<vk::Swapchain> Framework::swapchain() {
    return swapchain_;
}

std::shared_ptr<vk::CommandPool> Framework::mainCommandPool() {
    return mainCommandPool_;
}

std::shared_ptr<vk::CommandPool> Framework::asyncCommandPool() {
    return asyncCommandPool_;
}

std::shared_ptr<vk::CommandBuffer> Framework::worldAsyncCommandBuffer() {
    return worldAsyncCommandBuffer_;
}

std::vector<std::shared_ptr<FrameworkContext>> &Framework::contexts() {
    return contexts_;
}

std::shared_ptr<FrameworkContext> Framework::safeAcquireCurrentContext() {
    std::unique_lock<std::recursive_mutex> lck(recreateMtx_);
    if (currentContext_ == nullptr) { acquireContext(); }
    return currentContext_;
}

std::shared_ptr<Pipeline> Framework::pipeline() {
    return pipeline_;
}

FrameResourceRetainer &Framework::frameResourceRetainer() {
    return *frameResourceRetainer_;
}

FrameResourceRetainer::FrameResourceRetainer(std::shared_ptr<Framework> framework) {
    retainedResourcesByFrame_.resize(framework->swapchain_->imageCount());
}

void FrameResourceRetainer::beginFrame(uint32_t frameIndex) {
    std::unique_lock<std::recursive_mutex> lck(mtx_);

    currentFrameIndex_ = frameIndex;
    retainedResourcesByFrame_[currentFrameIndex_].clear();
}
