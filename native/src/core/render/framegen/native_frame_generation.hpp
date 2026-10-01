#pragma once

#include "core/all_extern.hpp"

#include <glm/glm.hpp>

#include <chrono>
#include <condition_variable>
#include <mutex>
#include <thread>
#include <memory>
#include <vector>

class Framework;

namespace vk {
class DeviceLocalImage;
class ExternalImage;
class DescriptorTable;
class ComputePipeline;
class Shader;
} // namespace vk

namespace framegen {

/**
 * Frame generation without Streamline, ported from Radiance/MCVR 0.1.6 (DLSS through NGX, FSR through the
 * FidelityFX API) and fitted to Minecraft owning the swapchain.
 *
 * Each frame: the world pass hands over its finished image, depth and motion vectors (captureWorldFrame). When
 * Minecraft blits its final image (world plus UI) into the swapchain, the generated frames are evaluated in that same
 * command buffer and the first of them goes to the swapchain in place of the real frame (evaluateAndBlit). After
 * Minecraft presents it, the remaining generated frames and then the real one are presented on further swapchain
 * images (presentPending). Presenting in FIFO paces them one refresh apart.
 */
class NativeFrameGeneration {
  public:
    enum class Backend : uint32_t { Off = 0, Dlss = 1, Fsr = 2 };

    static NativeFrameGeneration &instance();

    /** Which backend this GPU gets, and how many frames it can generate per rendered one (0 = none). */
    Backend backend();
    uint32_t maxGeneratedFrames();
    /** Which backend the player asked for; Off means automatic (DLSS where it runs, FSR otherwise). */
    void setPreference(Backend preference);
    bool available(Backend which);
    uint32_t maxGeneratedFramesFor(Backend which);

    void setGeneratedFrames(uint32_t frames);
    uint32_t generatedFrames() const;
    bool active();

    /** Frames reaching the display per second, real plus generated. */
    int presentedFrameRate() const;

    void captureWorldFrame(std::shared_ptr<Framework> framework,
                           std::shared_ptr<vk::DeviceLocalImage> hudless,
                           std::shared_ptr<vk::DeviceLocalImage> linearDepth,
                           std::shared_ptr<vk::DeviceLocalImage> motionVectors);

    bool evaluateAndBlit(VkCommandBuffer cmd,
                         VkImage finalImage,
                         VkFormat finalFormat,
                         uint32_t width,
                         uint32_t height,
                         VkImage swapchainImage,
                         uint32_t swapchainWidth,
                         uint32_t swapchainHeight,
                         bool flipY);

    /** 0 ok (or nothing pending), 1 suboptimal, -1 out of date. */
    int presentPending(VkSwapchainKHR swapchain,
                       const std::vector<VkImage> &swapchainImages,
                       VkQueue queue,
                       uint32_t swapchainWidth,
                       uint32_t swapchainHeight);

    void reset();
    void release();

    /** Waits until the frames handed to the present thread are all shown. */
    void waitPresentIdle();

  private:
    struct Provider;
    struct DlssProvider;
    struct FsrProvider;

    struct Capture {
        std::shared_ptr<vk::DeviceLocalImage> hudless;
        std::shared_ptr<vk::DeviceLocalImage> linearDepth;
        std::shared_ptr<vk::DeviceLocalImage> motionVectors;
        glm::mat4 viewToClip{1.0f};
        glm::mat4 clipToView{1.0f};
        glm::mat4 clipToPrevClip{1.0f};
        glm::mat4 prevClipToClip{1.0f};
        glm::vec2 jitter{0.0f};
        glm::vec3 cameraPos{0.0f};
        glm::vec3 cameraRight{1.0f, 0.0f, 0.0f};
        glm::vec3 cameraUp{0.0f, 1.0f, 0.0f};
        glm::vec3 cameraForward{0.0f, 0.0f, -1.0f};
        float fov = 1.0f;
        float aspect = 1.0f;
        bool valid = false;
    };

    NativeFrameGeneration() = default;

    bool probe();
    void selectBackend();
    bool ensureResources(uint32_t width, uint32_t height, VkFormat format, uint32_t depthWidth, uint32_t depthHeight);
    std::shared_ptr<vk::ExternalImage> viewOf(VkImage image, VkFormat format, uint32_t width, uint32_t height);
    bool ensurePresentResources(VkSwapchainKHR swapchain, size_t imageCount);
    int presentOne(VkSwapchainKHR swapchain,
                   const std::vector<VkImage> &swapchainImages,
                   VkQueue queue,
                   VkImage source,
                   uint32_t width,
                   uint32_t height,
                   uint32_t swapchainWidth,
                   uint32_t swapchainHeight,
                   bool flipY);
    void countPresented(uint32_t frames);
    void presentWorker();
    void stopPresentWorker();

    struct PresentJob {
        std::vector<VkImage> images;
        VkSwapchainKHR swapchain = VK_NULL_HANDLE;
        std::vector<VkImage> swapchainImages;
        VkQueue queue = VK_NULL_HANDLE;
        uint32_t width = 0, height = 0, swapchainWidth = 0, swapchainHeight = 0;
        bool flipY = true;
        std::chrono::steady_clock::time_point start{};
        std::chrono::duration<double> spacing{0.0};
    };
    std::thread worker_;
    std::mutex jobMutex_;
    std::condition_variable jobCv_;
    PresentJob job_;
    bool jobPending_ = false;
    bool stopWorker_ = false;
    int lastStatus_ = 0;
    uint32_t presentedByWorker_ = 0;
    std::chrono::steady_clock::time_point lastMinecraftPresent_{};
    double frameInterval_ = 0.0;

    std::weak_ptr<Framework> framework_;
    std::shared_ptr<Provider> provider_;
    Backend backend_ = Backend::Off;
    Backend preference_ = Backend::Off;
    bool dlssAvailable_ = false;
    bool fsrAvailable_ = false;
    uint32_t dlssMaxFrames_ = 0;
    uint32_t maxGeneratedFrames_ = 0;
    bool probed_ = false;
    uint32_t generatedFrames_ = 0;

    Capture capture_{};
    bool capturedThisFrame_ = false;
    bool hasPrevious_ = false;
    glm::mat4 previousView_{1.0f};
    glm::mat4 previousProjection_{1.0f};
    glm::dvec3 previousCameraPos_{0.0};
    std::chrono::steady_clock::time_point lastEvaluation_{};
    bool historyValid_ = false;

    // Evaluation resources.
    std::vector<std::shared_ptr<vk::DeviceLocalImage>> generated_;
    std::vector<std::shared_ptr<vk::DeviceLocalImage>> generatedSpare_;
    std::shared_ptr<vk::DeviceLocalImage> realCopy_;
    std::shared_ptr<vk::DeviceLocalImage> realCopySpare_;
    std::shared_ptr<vk::DeviceLocalImage> hudlessCopy_;
    std::shared_ptr<vk::DeviceLocalImage> deviceDepth_;
    std::shared_ptr<vk::DeviceLocalImage> motionVectors_;
    std::vector<std::shared_ptr<vk::DescriptorTable>> prepareTables_;
    uint32_t prepareRing_ = 0;
    std::shared_ptr<vk::Shader> prepareShader_;
    std::shared_ptr<vk::ComputePipeline> preparePipeline_;
    std::shared_ptr<vk::ExternalImage> finalView_;
    VkImage finalViewImage_ = VK_NULL_HANDLE;
    std::vector<std::pair<VkImageView, uint32_t>> retiredViews_;

    // What is still to be presented after Minecraft's own present, in order.
    std::vector<VkImage> pendingImages_;
    uint32_t pendingWidth_ = 0;
    uint32_t pendingHeight_ = 0;
    bool pendingFlip_ = true;

    // Presenting resources.
    VkCommandPool presentPool_ = VK_NULL_HANDLE;
    struct PresentSlot {
        VkCommandBuffer cmd = VK_NULL_HANDLE;
        VkFence fence = VK_NULL_HANDLE;
        VkSemaphore acquire = VK_NULL_HANDLE;
    };
    std::vector<PresentSlot> presentSlots_;
    uint32_t presentSlot_ = 0;
    VkSwapchainKHR presentSwapchain_ = VK_NULL_HANDLE;
    std::vector<VkSemaphore> presentSemaphores_;

    uint32_t presentedInWindow_ = 0;
    int presentedPerSecond_ = 0;
    std::chrono::steady_clock::time_point windowStart_ = std::chrono::steady_clock::now();
};

} // namespace framegen
