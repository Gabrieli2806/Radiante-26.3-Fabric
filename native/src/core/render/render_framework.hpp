#pragma once

#include "common/shared.hpp"
#include "common/singleton.hpp"
#include "core/all_extern.hpp"
#include "core/render/modules/world/dlss/dlss_wrapper.hpp"
#include "core/render/pipeline.hpp"
#include "core/vulkan/all_core_vulkan.hpp"

#include <chrono>
#include <map>
#include <mutex>

class Framework;

class FrameResourceRetainer : public SharedObject<FrameResourceRetainer> {
  public:
    FrameResourceRetainer(std::shared_ptr<Framework> framework);

    template <typename T>
    void retain(std::shared_ptr<T> resource);

    void beginFrame(uint32_t frameIndex);

  private:
    std::vector<std::vector<std::shared_ptr<void>>> retainedResourcesByFrame_;
    uint32_t currentFrameIndex_ = 0;
    std::recursive_mutex mtx_;
};

struct FrameworkContext : public SharedObject<FrameworkContext> {
    std::weak_ptr<Framework> framework;

    uint32_t frameIndex;

    std::shared_ptr<vk::Instance> instance;
    std::shared_ptr<vk::PhysicalDevice> physicalDevice;
    std::shared_ptr<vk::Device> device;
    std::shared_ptr<vk::VMA> vma;
    std::shared_ptr<vk::Swapchain> swapchain;
    std::shared_ptr<vk::CommandPool> commandPool;

    std::shared_ptr<vk::CommandBuffer> uploadCommandBuffer;
    std::shared_ptr<vk::CommandBuffer> worldCommandBuffer;
    std::shared_ptr<vk::CommandBuffer> fuseCommandBuffer;

    // Minecraft's submit timeline semaphore and the value that marks the submit these command
    // buffers were executed in. Zero means the buffers were never handed to Minecraft.
    VkSemaphore submitTimeline = VK_NULL_HANDLE;
    uint64_t submitTimelineValue = 0;
    bool recording = false;

    FrameworkContext(std::shared_ptr<Framework> framework, uint32_t frame_index);
    ~FrameworkContext();

    void fuseInto(std::shared_ptr<vk::ExternalImage> target);
    void waitForPreviousSubmit();
};

struct SharedDeviceHandles {
    VkInstance instance;
    VkPhysicalDevice physicalDevice;
    VkDevice device;
    VkQueue mainQueue;
    uint32_t mainQueueFamily;
    VkQueue secondaryQueue;
    uint32_t secondaryQueueFamily;
    uint32_t width;
    uint32_t height;
};

class Framework : public SharedObject<Framework> {
    friend FrameworkContext;
    friend FrameResourceRetainer;

  public:
    Framework();
    ~Framework();

    void init(const SharedDeviceHandles &handles);

    // Opens the next frame slot: waits for its previous GPU work and begins its command buffers.
    void acquireContext();
    // Records uploads, the world pipeline and the blit into `target`, ends the command buffers and
    // returns them in execution order. The next slot is opened before returning.
    std::vector<VkCommandBuffer> renderFrame(VkImage target, uint32_t width, uint32_t height, VkFormat format);
    void markSubmitted(VkSemaphore timeline, uint64_t value);

    void recreate();
    void waitDeviceIdle();
    void waitRenderQueueIdle();
    void waitBackendQueueIdle();
    void close();
    bool isRunning();

    std::recursive_mutex &recreateMtx();

    std::shared_ptr<vk::Instance> instance();
    std::shared_ptr<vk::PhysicalDevice> physicalDevice();
    std::shared_ptr<vk::Device> device();
    std::shared_ptr<vk::VMA> vma();
    std::shared_ptr<vk::Swapchain> swapchain();
    std::shared_ptr<vk::CommandPool> mainCommandPool();
    std::shared_ptr<vk::CommandPool> asyncCommandPool();

    std::shared_ptr<vk::CommandBuffer> worldAsyncCommandBuffer();

    std::vector<std::shared_ptr<FrameworkContext>> &contexts();
    std::shared_ptr<FrameworkContext> safeAcquireCurrentContext();

    std::shared_ptr<Pipeline> pipeline();

    FrameResourceRetainer &frameResourceRetainer();

  private:
    void createContexts();

    std::shared_ptr<vk::Instance> instance_;
    std::shared_ptr<vk::PhysicalDevice> physicalDevice_;
    std::shared_ptr<vk::Device> device_;
    std::shared_ptr<vk::VMA> vma_;
    std::shared_ptr<vk::Swapchain> swapchain_;
    std::shared_ptr<vk::CommandPool> mainCommandPool_;
    std::shared_ptr<vk::CommandPool> asyncCommandPool_;

    std::shared_ptr<vk::CommandBuffer> worldAsyncCommandBuffer_;

    std::shared_ptr<Pipeline> pipeline_;

    std::vector<std::shared_ptr<FrameworkContext>> contexts_;
    std::shared_ptr<FrameworkContext> currentContext_ = nullptr;
    std::shared_ptr<FrameworkContext> lastRenderedContext_ = nullptr;
    uint32_t nextContextIndex_ = 0;

    std::recursive_mutex recreateMtx_;
    bool running_ = true;

    std::shared_ptr<FrameResourceRetainer> frameResourceRetainer_;
};

template <typename T>
void FrameResourceRetainer::retain(std::shared_ptr<T> resource) {
    std::unique_lock<std::recursive_mutex> lck(mtx_);

    if (resource != nullptr) { retainedResourcesByFrame_[currentFrameIndex_].push_back(resource); }
}
