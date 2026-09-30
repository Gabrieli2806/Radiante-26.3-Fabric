// Frame generation without Streamline. The DLSS and FSR providers are ported from Radiance/MCVR 0.1.6
// (dlss_frame_generation.cpp, fsr_frame_generation.cpp, GPL-3.0); the capture and present paths are Radiante's, as
// Minecraft owns the swapchain here.
#include "core/render/framegen/native_frame_generation.hpp"

#include "core/render/buffers.hpp"
#include "core/render/modules/world/dlss/dlss_module.hpp"
#include "core/render/modules/world/dlss/dlss_wrapper.hpp"
#include "core/render/render_framework.hpp"
#include "core/render/renderer.hpp"
#include "core/util/logging.hpp"
#include "core/vulkan/all_core_vulkan.hpp"

#ifdef MCVR_ENABLE_FFX_UPSCALER
#include "core/render/modules/world/fsr_upscaler/fsr_setup.hpp"
#include <ffx_api/ffx_framegeneration.hpp>
#include <ffx_api/vk/ffx_api_vk.hpp>
#endif

#include <nvsdk_ngx_defs_dlssg.h>
#include <nvsdk_ngx_helpers_dlssg_vk.h>
#include <nvsdk_ngx_helpers_vk.h>

#include <algorithm>
#include <string>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <glm/gtc/type_ptr.hpp>

namespace {

std::ostream &fgOut() {
    return radiante::out() << "[Frame Generation] ";
}

// DLSS reports how many frames the GPU can add (RTX 50: up to 5, a 6x multiplier); the cap only guards the arrays.
constexpr uint32_t MAX_GENERATED_FRAMES = 7;
constexpr uint32_t PREPARE_RING = 4;
constexpr uint32_t PRESENT_SLOTS = 8;
constexpr uint32_t VIEW_RETIRE_FRAMES = 8;
// The depth handed to frame generation is rebuilt from the traced hit distance with these planes.
constexpr float CAMERA_NEAR = 0.05f;
constexpr float CAMERA_FAR = 16384.0f;

struct PreparePushConstants {
    float cameraNear;
    float cameraFar;
    uint32_t width;
    uint32_t height;
    float jitterX;
    float jitterY;
};

void imageBarrier(VkCommandBuffer cmd,
                  VkImage image,
                  VkImageLayout oldLayout,
                  VkImageLayout newLayout,
                  VkPipelineStageFlags2 srcStage = VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                  VkAccessFlags2 srcAccess = VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT,
                  VkPipelineStageFlags2 dstStage = VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                  VkAccessFlags2 dstAccess = VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT) {
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

void fullBarrier(VkCommandBuffer cmd) {
    VkMemoryBarrier2 barrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER_2};
    barrier.srcStageMask = VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
    barrier.srcAccessMask = VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT;
    barrier.dstStageMask = VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
    barrier.dstAccessMask = VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT;
    VkDependencyInfo dependency{VK_STRUCTURE_TYPE_DEPENDENCY_INFO};
    dependency.memoryBarrierCount = 1;
    dependency.pMemoryBarriers = &barrier;
    vkCmdPipelineBarrier2(cmd, &dependency);
}

VkImageBlit presentBlit(uint32_t width, uint32_t height, uint32_t swapchainWidth, uint32_t swapchainHeight, bool flipY) {
    int copyWidth = static_cast<int>(std::min(width, swapchainWidth));
    int copyHeight = static_cast<int>(std::min(height, swapchainHeight));
    VkImageBlit blit{};
    blit.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    blit.srcOffsets[0] = {0, 0, 0};
    blit.srcOffsets[1] = {copyWidth, copyHeight, 1};
    blit.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    // Minecraft keeps its images bottom-up and flips them on the way to the window.
    blit.dstOffsets[0] = {0, flipY ? copyHeight : 0, 0};
    blit.dstOffsets[1] = {copyWidth, flipY ? 0 : copyHeight, 1};
    return blit;
}

glm::vec3 safeNormalize(const glm::vec3 &value, const glm::vec3 &fallback) {
    float length = glm::length(value);
    if (!(length > 1e-6f)) return fallback;
    return value / length;
}

} // namespace

namespace framegen {

// ---------------------------------------------------------------------------------------------------------------
// Providers
// ---------------------------------------------------------------------------------------------------------------

struct EvalInputs {
    std::shared_ptr<vk::Image> finalColor; // world plus UI, what the player sees
    std::shared_ptr<vk::Image> hudless;    // the world alone
    std::shared_ptr<vk::Image> depth;
    std::shared_ptr<vk::Image> motionVectors;
    uint32_t displayWidth = 0;
    uint32_t displayHeight = 0;
    uint32_t renderWidth = 0;
    uint32_t renderHeight = 0;
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
    float frameTimeMs = 16.67f;
    bool reset = false;
};

struct NativeFrameGeneration::Provider {
    virtual ~Provider() = default;
    virtual bool evaluate(VkCommandBuffer cmd,
                          const EvalInputs &inputs,
                          const std::shared_ptr<vk::DeviceLocalImage> &output,
                          uint32_t frameCount,
                          uint32_t frameIndex) = 0;
    virtual void release() = 0;
};

namespace {
NVSDK_NGX_Resource_VK ngxResource(const std::shared_ptr<vk::Image> &image, bool readWrite) {
    return NVSDK_NGX_Create_ImageView_Resource_VK(image->vkImageView(), image->vkImage(),
                                                  {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}, image->vkFormat(),
                                                  image->width(), image->height(), readWrite);
}

void copyMatrix(float dst[4][4], const glm::mat4 &src) {
    std::memcpy(dst, glm::value_ptr(src), sizeof(float) * 16);
}
} // namespace

struct NativeFrameGeneration::DlssProvider : NativeFrameGeneration::Provider {
    std::shared_ptr<NgxContext> ngx;
    NVSDK_NGX_Handle *handle = nullptr;
    VkExtent2D display{};
    VkExtent2D render{};
    VkFormat format = VK_FORMAT_UNDEFINED;

    bool ensureFeature(VkCommandBuffer cmd, const EvalInputs &inputs) {
        VkFormat wanted = inputs.finalColor->vkFormat();
        if (handle != nullptr && display.width == inputs.displayWidth && display.height == inputs.displayHeight &&
            render.width == inputs.renderWidth && render.height == inputs.renderHeight && format == wanted) {
            return true;
        }
        release();

        NVSDK_NGX_Parameter *params = ngx->parameters();
        if (params == nullptr) return false;
        NVSDK_NGX_DLSSG_Create_Params create{};
        create.Width = inputs.displayWidth;
        create.Height = inputs.displayHeight;
        create.NativeBackbufferFormat = static_cast<unsigned int>(wanted);
        create.RenderWidth = inputs.renderWidth;
        create.RenderHeight = inputs.renderHeight;
        create.DynamicResolutionScaling = false;
        params->Set(NVSDK_NGX_DLSSG_Parameter_Width, create.Width);
        params->Set(NVSDK_NGX_DLSSG_Parameter_Height, create.Height);
        params->Set(NVSDK_NGX_DLSSG_Parameter_InternalWidth, create.RenderWidth);
        params->Set(NVSDK_NGX_DLSSG_Parameter_InternalHeight, create.RenderHeight);
        params->Set(NVSDK_NGX_DLSSG_Parameter_DynamicResolution, 0u);

        NVSDK_NGX_Result result = checkNgxResult(NGX_VK_CREATE_DLSSG(cmd, 1, 1, &handle, params, &create),
                                                 "NGX_VK_CREATE_DLSSG", __LINE__);
        if (NVSDK_NGX_FAILED(result)) {
            handle = nullptr;
            return false;
        }
        display = {inputs.displayWidth, inputs.displayHeight};
        render = {inputs.renderWidth, inputs.renderHeight};
        format = wanted;
        fgOut() << "DLSS frame generation created for " << display.width << "x" << display.height << std::endl;
        return true;
    }

    bool evaluate(VkCommandBuffer cmd,
                  const EvalInputs &inputs,
                  const std::shared_ptr<vk::DeviceLocalImage> &output,
                  uint32_t frameCount,
                  uint32_t frameIndex) override {
        if (ngx == nullptr || !ensureFeature(cmd, inputs)) return false;

        NVSDK_NGX_Resource_VK backbuffer = ngxResource(inputs.finalColor, false);
        NVSDK_NGX_Resource_VK depth = ngxResource(inputs.depth, false);
        NVSDK_NGX_Resource_VK motion = ngxResource(inputs.motionVectors, false);
        NVSDK_NGX_Resource_VK interpolated = ngxResource(output, true);
        NVSDK_NGX_Resource_VK hudless{};

        NVSDK_NGX_VK_DLSSG_Eval_Params eval{};
        eval.pBackbuffer = &backbuffer;
        eval.pDepth = &depth;
        eval.pMVecs = &motion;
        eval.pOutputInterpFrame = &interpolated;
        if (inputs.hudless != nullptr) {
            hudless = ngxResource(inputs.hudless, false);
            eval.pHudless = &hudless;
        }

        NVSDK_NGX_DLSSG_Opt_Eval_Params opt{};
        opt.multiFrameCount = frameCount;
        opt.multiFrameIndex = frameIndex;
        copyMatrix(opt.cameraViewToClip, inputs.viewToClip);
        copyMatrix(opt.clipToCameraView, inputs.clipToView);
        copyMatrix(opt.clipToLensClip, glm::mat4(1.0f));
        copyMatrix(opt.clipToPrevClip, inputs.clipToPrevClip);
        copyMatrix(opt.prevClipToClip, inputs.prevClipToClip);
        opt.jitterOffset[0] = inputs.jitter.x;
        opt.jitterOffset[1] = inputs.jitter.y;
        // The traced motion vectors are in pixels; DLSS frame generation wants them normalised.
        opt.mvecScale[0] = 1.0f / static_cast<float>(inputs.motionVectors->width());
        opt.mvecScale[1] = 1.0f / static_cast<float>(inputs.motionVectors->height());
        opt.cameraPinholeOffset[0] = 0.0f;
        opt.cameraPinholeOffset[1] = 0.0f;
        std::memcpy(opt.cameraPos, &inputs.cameraPos, sizeof(opt.cameraPos));
        std::memcpy(opt.cameraUp, &inputs.cameraUp, sizeof(opt.cameraUp));
        std::memcpy(opt.cameraRight, &inputs.cameraRight, sizeof(opt.cameraRight));
        std::memcpy(opt.cameraFwd, &inputs.cameraForward, sizeof(opt.cameraFwd));
        opt.cameraNear = CAMERA_NEAR;
        opt.cameraFar = CAMERA_FAR;
        opt.cameraFOV = inputs.fov;
        opt.cameraAspectRatio = inputs.aspect;
        opt.colorBuffersHDR = false;
        opt.depthInverted = false;
        opt.cameraMotionIncluded = true;
        opt.reset = inputs.reset;
        opt.automodeOverrideReset = false;
        opt.notRenderingGameFrames = false;
        opt.orthoProjection = false;
        opt.motionVectorsInvalidValue = 0.0f;
        opt.motionVectorsDilated = false;
        opt.menuDetectionEnabled = true;
        opt.mvecsSubrectSize = {inputs.motionVectors->width(), inputs.motionVectors->height()};
        opt.depthSubrectSize = {inputs.depth->width(), inputs.depth->height()};
        opt.backbufferSubrectSize = {inputs.displayWidth, inputs.displayHeight};
        if (eval.pHudless != nullptr) opt.hudLessSubrectSize = {inputs.displayWidth, inputs.displayHeight};

        NVSDK_NGX_Result result = checkNgxResult(
            NGX_VK_EVALUATE_DLSSG(cmd, handle, ngx->parameters(), &eval, &opt), "NGX_VK_EVALUATE_DLSSG", __LINE__);
        return NVSDK_NGX_SUCCEED(result);
    }

    void release() override {
        if (handle != nullptr) {
            auto framework = Renderer::instance().framework();
            if (framework != nullptr) framework->waitRenderQueueIdle();
            NVSDK_NGX_VULKAN_ReleaseFeature(handle);
            handle = nullptr;
        }
        display = {};
        render = {};
        format = VK_FORMAT_UNDEFINED;
    }

    ~DlssProvider() override {
        release();
    }
};

struct NativeFrameGeneration::FsrProvider : NativeFrameGeneration::Provider {
#ifdef MCVR_ENABLE_FFX_UPSCALER
    ffx::Context context = nullptr;
#endif
    VkExtent2D display{};
    VkExtent2D render{};
    VkFormat format = VK_FORMAT_UNDEFINED;
    VkFormat hudlessFormat = VK_FORMAT_UNDEFINED;
    uint64_t frameId = 0;

#ifdef MCVR_ENABLE_FFX_UPSCALER
    static FfxApiResource resource(const std::shared_ptr<vk::Image> &image, FfxApiResourceState state,
                                   FfxApiResourceUsage usage) {
        FfxApiResource r{};
        r.resource = reinterpret_cast<void *>(image->vkImage());
        r.description.type = FFX_API_RESOURCE_TYPE_TEXTURE2D;
        r.description.format = mcvr::fsr::vkToFfxFormat(image->vkFormat());
        r.description.width = image->width();
        r.description.height = image->height();
        r.description.depth = 1;
        r.description.mipCount = 1;
        r.description.usage = usage;
        r.state = state;
        return r;
    }

    bool ensureContext(const EvalInputs &inputs) {
        VkFormat wanted = inputs.finalColor->vkFormat();
        VkFormat wantedHudless = inputs.hudless != nullptr ? inputs.hudless->vkFormat() : VK_FORMAT_UNDEFINED;
        if (context != nullptr && display.width == inputs.displayWidth && display.height == inputs.displayHeight &&
            render.width == inputs.renderWidth && render.height == inputs.renderHeight && format == wanted &&
            hudlessFormat == wantedHudless) {
            return true;
        }
        release();

        auto framework = Renderer::instance().framework();
        if (framework == nullptr) return false;
        ffx::CreateBackendVKDesc backend{};
        backend.vkDevice = framework->device()->vkDevice();
        backend.vkPhysicalDevice = framework->physicalDevice()->vkPhysicalDevice();
        backend.vkDeviceProcAddr = reinterpret_cast<PFN_vkGetDeviceProcAddr>(mcvr::fsr::customVkGetDeviceProcAddr);

        ffx::CreateContextDescFrameGeneration create{};
        create.displaySize = {inputs.displayWidth, inputs.displayHeight};
        create.maxRenderSize = {std::max(inputs.renderWidth, inputs.displayWidth),
                                std::max(inputs.renderHeight, inputs.displayHeight)};
        create.backBufferFormat = mcvr::fsr::vkToFfxFormat(wanted);
        create.flags = 0;

        ffx::ReturnCode result;
        if (inputs.hudless != nullptr) {
            ffx::CreateContextDescFrameGenerationHudless hudless{};
            hudless.hudlessBackBufferFormat = mcvr::fsr::vkToFfxFormat(wantedHudless);
            result = ffx::CreateContext(context, nullptr, create, hudless, backend);
        } else {
            result = ffx::CreateContext(context, nullptr, create, backend);
        }
        if (result != ffx::ReturnCode::Ok) {
            fgOut() << "FSR frame generation could not be created (" << static_cast<uint32_t>(result) << ")"
                    << std::endl;
            context = nullptr;
            return false;
        }
        display = {inputs.displayWidth, inputs.displayHeight};
        render = {inputs.renderWidth, inputs.renderHeight};
        format = wanted;
        hudlessFormat = wantedHudless;
        fgOut() << "FSR frame generation created for " << display.width << "x" << display.height << std::endl;
        return true;
    }
#endif

    bool evaluate(VkCommandBuffer cmd,
                  const EvalInputs &inputs,
                  const std::shared_ptr<vk::DeviceLocalImage> &output,
                  uint32_t frameCount,
                  uint32_t frameIndex) override {
#ifndef MCVR_ENABLE_FFX_UPSCALER
        return false;
#else
        if (frameCount != 1 || frameIndex != 1 || !ensureContext(inputs)) return false;
        const uint64_t id = ++frameId;

        ffx::ConfigureDescFrameGeneration configure{};
        configure.frameGenerationEnabled = true;
        configure.allowAsyncWorkloads = false;
        configure.flags = FFX_FRAMEGENERATION_FLAG_NO_SWAPCHAIN_CONTEXT_NOTIFY;
        if (inputs.hudless != nullptr) {
            configure.HUDLessColor = resource(inputs.hudless, FFX_API_RESOURCE_STATE_PIXEL_COMPUTE_READ,
                                              FFX_API_RESOURCE_USAGE_READ_ONLY);
        }
        configure.generationRect = {0, 0, static_cast<int32_t>(inputs.displayWidth),
                                    static_cast<int32_t>(inputs.displayHeight)};
        configure.frameID = id;
        if (ffx::Configure(context, configure) != ffx::ReturnCode::Ok) return false;

        ffx::DispatchDescFrameGenerationPrepare prepare{};
        prepare.frameID = id;
        prepare.flags = 0;
        prepare.commandList = cmd;
        prepare.renderSize = {inputs.depth->width(), inputs.depth->height()};
        prepare.jitterOffset = {-inputs.jitter.x, -inputs.jitter.y};
        prepare.motionVectorScale = {1.0f, 1.0f}; // pixel-space, as the FSR upscaler takes them
        prepare.frameTimeDelta = inputs.frameTimeMs;
        prepare.unused_reset = inputs.reset;
        prepare.cameraNear = CAMERA_NEAR;
        prepare.cameraFar = CAMERA_FAR;
        prepare.cameraFovAngleVertical = inputs.fov;
        prepare.viewSpaceToMetersFactor = 1.0f;
        prepare.depth =
            resource(inputs.depth, FFX_API_RESOURCE_STATE_PIXEL_COMPUTE_READ, FFX_API_RESOURCE_USAGE_READ_ONLY);
        prepare.motionVectors = resource(inputs.motionVectors, FFX_API_RESOURCE_STATE_PIXEL_COMPUTE_READ,
                                         FFX_API_RESOURCE_USAGE_READ_ONLY);
        ffx::DispatchDescFrameGenerationPrepareCameraInfo camera{};
        std::memcpy(camera.cameraPosition, &inputs.cameraPos, sizeof(camera.cameraPosition));
        std::memcpy(camera.cameraUp, &inputs.cameraUp, sizeof(camera.cameraUp));
        std::memcpy(camera.cameraRight, &inputs.cameraRight, sizeof(camera.cameraRight));
        std::memcpy(camera.cameraForward, &inputs.cameraForward, sizeof(camera.cameraForward));
        if (ffx::Dispatch(context, prepare, camera) != ffx::ReturnCode::Ok) return false;

        ffx::DispatchDescFrameGeneration dispatch{};
        dispatch.commandList = cmd;
        dispatch.presentColor =
            resource(inputs.finalColor, FFX_API_RESOURCE_STATE_PIXEL_COMPUTE_READ, FFX_API_RESOURCE_USAGE_READ_ONLY);
        dispatch.outputs[0] = resource(output, FFX_API_RESOURCE_STATE_UNORDERED_ACCESS, FFX_API_RESOURCE_USAGE_UAV);
        dispatch.numGeneratedFrames = 1;
        dispatch.reset = inputs.reset;
        dispatch.backbufferTransferFunction = FFX_API_BACKBUFFER_TRANSFER_FUNCTION_SRGB;
        dispatch.minMaxLuminance[0] = 0.0f;
        dispatch.minMaxLuminance[1] = 1000.0f;
        dispatch.generationRect = {0, 0, static_cast<int32_t>(inputs.displayWidth),
                                   static_cast<int32_t>(inputs.displayHeight)};
        dispatch.frameID = id;
        return ffx::Dispatch(context, dispatch) == ffx::ReturnCode::Ok;
#endif
    }

    void release() override {
#ifdef MCVR_ENABLE_FFX_UPSCALER
        if (context != nullptr) {
            auto framework = Renderer::instance().framework();
            if (framework != nullptr) framework->waitRenderQueueIdle();
            ffx::DestroyContext(context);
            context = nullptr;
        }
#endif
        display = {};
        render = {};
        format = VK_FORMAT_UNDEFINED;
        hudlessFormat = VK_FORMAT_UNDEFINED;
        frameId = 0;
    }

    ~FsrProvider() override {
        release();
    }
};

// ---------------------------------------------------------------------------------------------------------------
// Manager
// ---------------------------------------------------------------------------------------------------------------

NativeFrameGeneration &NativeFrameGeneration::instance() {
    // Never destroyed: it holds Vulkan objects that must not be freed after the device is gone at process exit.
    static NativeFrameGeneration *instance = new NativeFrameGeneration();
    return *instance;
}

bool NativeFrameGeneration::probe() {
    if (probed_) return backend_ != Backend::Off;
    if (!Renderer::is_initialized()) return false;
    auto framework = Renderer::instance().framework();
    if (framework == nullptr || framework->device() == nullptr) return false;
    probed_ = true;
    framework_ = framework;

    // Both backends are looked for: the player may pick FSR frame generation on an NVIDIA card, next to DLSS
    // upscaling or anything else (they do not depend on each other).
    auto ngx = DLSSModule::ngxContext();
    dlssAvailable_ = ngx != nullptr && framework->device()->isDlssFrameGenerationDeviceExtensionsCompatible() &&
                     NVSDK_NGX_SUCCEED(ngx->queryDlssFrameGenerationAvailable());
    uint32_t reportedDlssFrames = dlssAvailable_ ? ngx->queryDlssFrameGenerationMaxFrames() : 0;
    dlssMaxFrames_ = dlssAvailable_ ? std::clamp<uint32_t>(reportedDlssFrames, 1, MAX_GENERATED_FRAMES) : 0;
    if (dlssAvailable_) fgOut() << "DLSS reports up to " << reportedDlssFrames << " generated frames" << std::endl;
#ifdef MCVR_ENABLE_FFX_UPSCALER
    fsrAvailable_ = framework->device()->isFsrFrameGenerationDeviceExtensionsCompatible();
#endif
    fgOut() << "available: DLSS " << (dlssAvailable_ ? std::to_string(dlssMaxFrames_ + 1) + "x" : "no") << ", FSR "
            << (fsrAvailable_ ? "2x" : "no") << std::endl;

    const char *forced = std::getenv("RADIANTE_FRAME_GENERATION");
    if (forced != nullptr && std::strcmp(forced, "fsr") == 0) preference_ = Backend::Fsr;
    if (forced != nullptr && std::strcmp(forced, "dlss") == 0) preference_ = Backend::Dlss;
    selectBackend();
    return backend_ != Backend::Off;
}

void NativeFrameGeneration::selectBackend() {
    Backend wanted = Backend::Off;
    if (preference_ == Backend::Fsr && fsrAvailable_) {
        wanted = Backend::Fsr;
    } else if (preference_ == Backend::Dlss && dlssAvailable_) {
        wanted = Backend::Dlss;
    } else if (dlssAvailable_) {
        wanted = Backend::Dlss; // Auto, or the one asked for is missing
    } else if (fsrAvailable_) {
        wanted = Backend::Fsr;
    }
    if (wanted == backend_ && (provider_ != nullptr || wanted == Backend::Off)) return;

    if (provider_ != nullptr) provider_->release();
    provider_ = nullptr;
    backend_ = wanted;
    if (wanted == Backend::Dlss) {
        auto provider = std::make_shared<DlssProvider>();
        provider->ngx = DLSSModule::ngxContext();
        provider_ = provider;
        maxGeneratedFrames_ = dlssMaxFrames_;
    } else if (wanted == Backend::Fsr) {
        provider_ = std::make_shared<FsrProvider>();
        maxGeneratedFrames_ = 1;
    } else {
        maxGeneratedFrames_ = 0;
    }
    reset();
    fgOut() << "using " << (wanted == Backend::Dlss ? "DLSS" : wanted == Backend::Fsr ? "FSR" : "none") << std::endl;
}

void NativeFrameGeneration::setPreference(Backend preference) {
    preference_ = preference;
    if (probed_) selectBackend();
}

uint32_t NativeFrameGeneration::maxGeneratedFramesFor(Backend which) {
    probe();
    if (which == Backend::Dlss) return dlssAvailable_ ? dlssMaxFrames_ : 0;
    if (which == Backend::Fsr) return fsrAvailable_ ? 1 : 0;
    return 0;
}

bool NativeFrameGeneration::available(Backend which) {
    probe();
    return which == Backend::Dlss ? dlssAvailable_ : which == Backend::Fsr ? fsrAvailable_ : false;
}

NativeFrameGeneration::Backend NativeFrameGeneration::backend() {
    probe();
    return backend_;
}

uint32_t NativeFrameGeneration::maxGeneratedFrames() {
    return probe() ? maxGeneratedFrames_ : 0;
}

void NativeFrameGeneration::setGeneratedFrames(uint32_t frames) {
    uint32_t clamped = std::min(frames, MAX_GENERATED_FRAMES);
    if (clamped != generatedFrames_) {
        generatedFrames_ = clamped;
        reset();
    }
}

uint32_t NativeFrameGeneration::generatedFrames() const {
    return generatedFrames_;
}

bool NativeFrameGeneration::active() {
    return generatedFrames_ > 0 && probe();
}

int NativeFrameGeneration::presentedFrameRate() const {
    return presentedPerSecond_;
}

void NativeFrameGeneration::reset() {
    historyValid_ = false;
    hasPrevious_ = false;
    capturedThisFrame_ = false;
    pendingImages_.clear();
    presentedInWindow_ = 0;
    presentedPerSecond_ = 0;
}

void NativeFrameGeneration::captureWorldFrame(std::shared_ptr<Framework> framework,
                                              std::shared_ptr<vk::DeviceLocalImage> hudless,
                                              std::shared_ptr<vk::DeviceLocalImage> linearDepth,
                                              std::shared_ptr<vk::DeviceLocalImage> motionVectors) {
    capturedThisFrame_ = false;
    if (!active() || hudless == nullptr || linearDepth == nullptr || motionVectors == nullptr) return;

    auto buffers = Renderer::instance().buffers();
    if (buffers == nullptr) return;
    auto worldUniformBuffer = buffers->worldUniformBuffer();
    if (worldUniformBuffer == nullptr || worldUniformBuffer->mappedPtr() == nullptr) return;
    auto ubo = static_cast<vk::Data::WorldUBO *>(worldUniformBuffer->mappedPtr());

    glm::mat4 view = ubo->cameraViewMat;
    glm::mat4 projection = ubo->cameraProjMat;
    glm::dvec3 cameraPos = glm::dvec3(ubo->cameraPos);
    glm::mat4 viewInv = ubo->cameraViewMatInv;
    glm::mat4 projectionInv = ubo->cameraProjMatInv;
    if (!hasPrevious_) {
        previousView_ = view;
        previousProjection_ = projection;
        previousCameraPos_ = cameraPos;
    }

    // The renderer traces in camera relative space, so the camera's own movement is the translation between frames.
    glm::mat4 cameraDelta{1.0f};
    cameraDelta[3] = glm::vec4(glm::vec3(previousCameraPos_ - cameraPos), 1.0f);
    Capture capture{};
    capture.hudless = hudless;
    capture.linearDepth = linearDepth;
    capture.motionVectors = motionVectors;
    capture.viewToClip = projection;
    capture.clipToView = projectionInv;
    capture.clipToPrevClip = previousProjection_ * previousView_ * cameraDelta * viewInv * projectionInv;
    capture.prevClipToClip = glm::inverse(capture.clipToPrevClip);
    // Upscaled depth and motion carry no jitter any more.
    bool renderResolution = linearDepth->width() != hudless->width() || linearDepth->height() != hudless->height();
    capture.jitter = renderResolution ? glm::vec2(ubo->cameraJitter) : glm::vec2(0.0f);
    capture.cameraPos = glm::vec3(cameraPos);
    capture.cameraRight = safeNormalize(glm::vec3(viewInv[0]), {1.0f, 0.0f, 0.0f});
    capture.cameraUp = safeNormalize(glm::vec3(viewInv[1]), {0.0f, 1.0f, 0.0f});
    capture.cameraForward = safeNormalize(-glm::vec3(viewInv[2]), {0.0f, 0.0f, -1.0f});
    float focalY = std::abs(projection[1][1]);
    float focalX = std::abs(projection[0][0]);
    capture.fov = focalY > 0.0f ? 2.0f * std::atan(1.0f / focalY) : 1.0f;
    capture.aspect = focalX > 0.0f ? focalY / focalX : 1.0f;
    capture.valid = true;

    previousView_ = view;
    previousProjection_ = projection;
    previousCameraPos_ = cameraPos;
    hasPrevious_ = true;

    framework_ = framework;
    capture_ = capture;
    capturedThisFrame_ = true;
}

std::shared_ptr<vk::ExternalImage>
NativeFrameGeneration::viewOf(VkImage image, VkFormat format, uint32_t width, uint32_t height) {
    auto framework = framework_.lock();
    if (framework == nullptr) return nullptr;
    VkDevice device = framework->device()->vkDevice();
    for (auto it = retiredViews_.begin(); it != retiredViews_.end();) {
        if (++it->second >= VIEW_RETIRE_FRAMES) {
            vkDestroyImageView(device, it->first, nullptr);
            it = retiredViews_.erase(it);
        } else {
            ++it;
        }
    }
    if (finalView_ != nullptr && finalViewImage_ == image && finalView_->width() == width &&
        finalView_->height() == height && finalView_->vkFormat() == format) {
        return finalView_;
    }
    if (finalView_ != nullptr && finalView_->vkImageView(0) != VK_NULL_HANDLE) {
        retiredViews_.emplace_back(finalView_->vkImageView(0), 0u);
    }
    VkImageViewCreateInfo info{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    info.image = image;
    info.viewType = VK_IMAGE_VIEW_TYPE_2D;
    info.format = format;
    info.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    VkImageView view = VK_NULL_HANDLE;
    if (vkCreateImageView(device, &info, nullptr, &view) != VK_SUCCESS) {
        finalView_ = nullptr;
        finalViewImage_ = VK_NULL_HANDLE;
        return nullptr;
    }
    finalView_ = vk::ExternalImage::create(image, width, height, format);
    finalView_->vkImageView(0) = view;
    finalViewImage_ = image;
    return finalView_;
}

bool NativeFrameGeneration::ensureResources(
    uint32_t width, uint32_t height, VkFormat format, uint32_t depthWidth, uint32_t depthHeight) {
    auto framework = framework_.lock();
    if (framework == nullptr) return false;
    auto device = framework->device();
    auto vma = framework->vma();

    auto sized = [](const std::shared_ptr<vk::DeviceLocalImage> &image, uint32_t w, uint32_t h, VkFormat f) {
        return image != nullptr && image->width() == w && image->height() == h && image->vkFormat() == f;
    };
    auto retain = [&](std::shared_ptr<vk::DeviceLocalImage> &image) {
        if (image != nullptr) framework->waitRenderQueueIdle();
        image = nullptr;
    };

    const VkImageUsageFlags colorUsage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT |
                                         VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    if (generated_.size() != MAX_GENERATED_FRAMES) generated_.resize(MAX_GENERATED_FRAMES);
    for (auto &image : generated_) {
        if (sized(image, width, height, format)) continue;
        retain(image);
        image = vk::DeviceLocalImage::create(device, vma, false, width, height, 1, format, colorUsage);
        historyValid_ = false;
    }
    if (!sized(hudlessCopy_, width, height, format)) {
        retain(hudlessCopy_);
        hudlessCopy_ = vk::DeviceLocalImage::create(device, vma, false, width, height, 1, format, colorUsage);
        historyValid_ = false;
    }
    if (!sized(deviceDepth_, depthWidth, depthHeight, VK_FORMAT_R32_SFLOAT)) {
        retain(deviceDepth_);
        deviceDepth_ = vk::DeviceLocalImage::create(device, vma, false, depthWidth, depthHeight, 1,
                                                    VK_FORMAT_R32_SFLOAT,
                                                    VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
        historyValid_ = false;
    }
    if (!sized(motionVectors_, depthWidth, depthHeight, VK_FORMAT_R16G16_SFLOAT)) {
        retain(motionVectors_);
        motionVectors_ = vk::DeviceLocalImage::create(device, vma, false, depthWidth, depthHeight, 1,
                                                      VK_FORMAT_R16G16_SFLOAT,
                                                      VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
        historyValid_ = false;
    }

    if (prepareTables_.empty()) {
        for (uint32_t i = 0; i < PREPARE_RING; i++) {
            auto binding = [](uint32_t index) {
                return VkDescriptorSetLayoutBinding{
                    .binding = index,
                    .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                    .descriptorCount = 1,
                    .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
                };
            };
            prepareTables_.push_back(vk::DescriptorTableBuilder{}
                                         .beginDescriptorLayoutSet()
                                         .beginDescriptorLayoutSetBinding()
                                         .defineDescriptorLayoutSetBinding(binding(0))
                                         .defineDescriptorLayoutSetBinding(binding(1))
                                         .defineDescriptorLayoutSetBinding(binding(2))
                                         .defineDescriptorLayoutSetBinding(binding(3))
                                         .endDescriptorLayoutSetBinding()
                                         .endDescriptorLayoutSet()
                                         .definePushConstant(VkPushConstantRange{
                                             .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
                                             .offset = 0,
                                             .size = sizeof(PreparePushConstants),
                                         })
                                         .build(device));
        }
    }
    if (preparePipeline_ == nullptr) {
        prepareShader_ = vk::Shader::create(
            device, (Renderer::folderPath / "shaders/world/upscaler/linear_to_device_depth_comp.spv").string());
        preparePipeline_ =
            vk::ComputePipelineBuilder{}.defineShader(prepareShader_).definePipelineLayout(prepareTables_[0]).build(
                device);
    }
    return preparePipeline_ != nullptr && hudlessCopy_ != nullptr && deviceDepth_ != nullptr &&
           motionVectors_ != nullptr;
}

bool NativeFrameGeneration::evaluateAndBlit(VkCommandBuffer cmd,
                                            VkImage finalImage,
                                            VkFormat finalFormat,
                                            uint32_t width,
                                            uint32_t height,
                                            VkImage swapchainImage,
                                            uint32_t swapchainWidth,
                                            uint32_t swapchainHeight,
                                            bool flipY) {
    pendingImages_.clear();
    bool captured = capturedThisFrame_;
    capturedThisFrame_ = false;
    if (!active() || !captured || !capture_.valid || cmd == VK_NULL_HANDLE || provider_ == nullptr) {
        historyValid_ = false;
        return false;
    }

    try {
        const Capture &capture = capture_;
        uint32_t frames = std::min(generatedFrames_, maxGeneratedFrames_);
        uint32_t depthWidth = capture.linearDepth->width();
        uint32_t depthHeight = capture.linearDepth->height();
        if (!ensureResources(width, height, finalFormat, depthWidth, depthHeight)) return false;
        auto finalColor = viewOf(finalImage, finalFormat, width, height);
        if (finalColor == nullptr) return false;

        fullBarrier(cmd);

        // The world image without the UI, converted to the backbuffer's format.
        VkImageLayout hudlessLayout = capture.hudless->imageLayout();
        imageBarrier(cmd, capture.hudless->vkImage(), hudlessLayout, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        imageBarrier(cmd, hudlessCopy_->vkImage(), VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        VkImageBlit copy{};
        copy.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        copy.srcOffsets[1] = {static_cast<int>(capture.hudless->width()), static_cast<int>(capture.hudless->height()),
                              1};
        copy.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        copy.dstOffsets[1] = {static_cast<int>(width), static_cast<int>(height), 1};
        vkCmdBlitImage(cmd, capture.hudless->vkImage(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, hudlessCopy_->vkImage(),
                       VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy, VK_FILTER_LINEAR);
        imageBarrier(cmd, capture.hudless->vkImage(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, hudlessLayout);
        imageBarrier(cmd, hudlessCopy_->vkImage(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                     VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        hudlessCopy_->imageLayout() = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

        // Device depth and cleaned motion vectors from the traced hit distance.
        VkImageLayout depthLayout = capture.linearDepth->imageLayout();
        VkImageLayout motionLayout = capture.motionVectors->imageLayout();
        imageBarrier(cmd, capture.linearDepth->vkImage(), depthLayout, VK_IMAGE_LAYOUT_GENERAL);
        imageBarrier(cmd, capture.motionVectors->vkImage(), motionLayout, VK_IMAGE_LAYOUT_GENERAL);
        imageBarrier(cmd, deviceDepth_->vkImage(), VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
        imageBarrier(cmd, motionVectors_->vkImage(), VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
        auto table = prepareTables_[prepareRing_];
        prepareRing_ = (prepareRing_ + 1) % PREPARE_RING;
        table->bindImage(capture.linearDepth, VK_IMAGE_LAYOUT_GENERAL, 0, 0);
        table->bindImage(deviceDepth_, VK_IMAGE_LAYOUT_GENERAL, 0, 1);
        table->bindImage(capture.motionVectors, VK_IMAGE_LAYOUT_GENERAL, 0, 2);
        table->bindImage(motionVectors_, VK_IMAGE_LAYOUT_GENERAL, 0, 3);
        PreparePushConstants push{CAMERA_NEAR, CAMERA_FAR, depthWidth, depthHeight, capture.jitter.x, capture.jitter.y};
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, preparePipeline_->vkPipeline());
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, table->vkPipelineLayout(), 0,
                                static_cast<uint32_t>(table->descriptorSet().size()), table->descriptorSet().data(), 0,
                                nullptr);
        vkCmdPushConstants(cmd, table->vkPipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(push), &push);
        vkCmdDispatch(cmd, (depthWidth + 15) / 16, (depthHeight + 15) / 16, 1);
        imageBarrier(cmd, capture.linearDepth->vkImage(), VK_IMAGE_LAYOUT_GENERAL, depthLayout);
        imageBarrier(cmd, capture.motionVectors->vkImage(), VK_IMAGE_LAYOUT_GENERAL, motionLayout);
        imageBarrier(cmd, deviceDepth_->vkImage(), VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        imageBarrier(cmd, motionVectors_->vkImage(), VK_IMAGE_LAYOUT_GENERAL,
                     VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        deviceDepth_->imageLayout() = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        motionVectors_->imageLayout() = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

        // Minecraft keeps its textures in GENERAL.
        imageBarrier(cmd, finalImage, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        for (uint32_t i = 0; i < frames; i++) {
            imageBarrier(cmd, generated_[i]->vkImage(), VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
            generated_[i]->imageLayout() = VK_IMAGE_LAYOUT_GENERAL;
        }

        auto now = std::chrono::steady_clock::now();
        EvalInputs inputs{};
        inputs.finalColor = finalColor;
        inputs.hudless = hudlessCopy_;
        inputs.depth = deviceDepth_;
        inputs.motionVectors = motionVectors_;
        inputs.displayWidth = width;
        inputs.displayHeight = height;
        inputs.renderWidth = depthWidth;
        inputs.renderHeight = depthHeight;
        inputs.viewToClip = capture.viewToClip;
        inputs.clipToView = capture.clipToView;
        inputs.clipToPrevClip = capture.clipToPrevClip;
        inputs.prevClipToClip = capture.prevClipToClip;
        inputs.jitter = capture.jitter;
        inputs.cameraPos = capture.cameraPos;
        inputs.cameraRight = capture.cameraRight;
        inputs.cameraUp = capture.cameraUp;
        inputs.cameraForward = capture.cameraForward;
        inputs.fov = capture.fov;
        inputs.aspect = capture.aspect;
        inputs.reset = !historyValid_;
        if (historyValid_ && lastEvaluation_.time_since_epoch().count() != 0) {
            inputs.frameTimeMs = std::chrono::duration<float, std::milli>(now - lastEvaluation_).count();
        }
        lastEvaluation_ = now;

        bool evaluated = true;
        for (uint32_t i = 0; i < frames && evaluated; i++) {
            evaluated = provider_->evaluate(cmd, inputs, generated_[i], frames, i + 1);
        }

        fullBarrier(cmd);
        imageBarrier(cmd, finalImage, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL);

        bool presentGenerated = evaluated && historyValid_;
        historyValid_ = evaluated;
        VkImage first = presentGenerated ? generated_[0]->vkImage() : finalImage;
        VkImageBlit blit = presentBlit(width, height, swapchainWidth, swapchainHeight, flipY);
        vkCmdBlitImage(cmd, first, VK_IMAGE_LAYOUT_GENERAL, swapchainImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1,
                       &blit, VK_FILTER_NEAREST);

        if (presentGenerated) {
            for (uint32_t i = 1; i < frames; i++) pendingImages_.push_back(generated_[i]->vkImage());
            // Development: show only generated frames, to judge them on screen.
            static const bool onlyGenerated = std::getenv("RADIANTE_FG_SHOW_GENERATED") != nullptr;
            pendingImages_.push_back(onlyGenerated ? generated_[frames - 1]->vkImage() : finalImage);
            pendingWidth_ = width;
            pendingHeight_ = height;
            pendingFlip_ = flipY;
        }
        return true;
    } catch (const std::exception &e) {
        fgOut() << "evaluation failed: " << e.what() << std::endl;
        historyValid_ = false;
        pendingImages_.clear();
        return false;
    }
}

bool NativeFrameGeneration::ensurePresentResources(VkSwapchainKHR swapchain, size_t imageCount) {
    auto framework = framework_.lock();
    if (framework == nullptr) return false;
    VkDevice device = framework->device()->vkDevice();

    if (presentPool_ == VK_NULL_HANDLE) {
        VkCommandPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        poolInfo.queueFamilyIndex = framework->physicalDevice()->mainQueueIndex();
        if (vkCreateCommandPool(device, &poolInfo, nullptr, &presentPool_) != VK_SUCCESS) return false;
        presentSlots_.resize(PRESENT_SLOTS);
        for (auto &slot : presentSlots_) {
            VkCommandBufferAllocateInfo alloc{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
            alloc.commandPool = presentPool_;
            alloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
            alloc.commandBufferCount = 1;
            vkAllocateCommandBuffers(device, &alloc, &slot.cmd);
            VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
            fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
            vkCreateFence(device, &fenceInfo, nullptr, &slot.fence);
            VkSemaphoreCreateInfo semaphoreInfo{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
            vkCreateSemaphore(device, &semaphoreInfo, nullptr, &slot.acquire);
        }
    }

    if (presentSwapchain_ != swapchain || presentSemaphores_.size() != imageCount) {
        if (!presentSemaphores_.empty()) {
            framework->waitRenderQueueIdle();
            for (VkSemaphore semaphore : presentSemaphores_) vkDestroySemaphore(device, semaphore, nullptr);
        }
        presentSemaphores_.assign(imageCount, VK_NULL_HANDLE);
        for (auto &semaphore : presentSemaphores_) {
            VkSemaphoreCreateInfo semaphoreInfo{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
            vkCreateSemaphore(device, &semaphoreInfo, nullptr, &semaphore);
        }
        presentSwapchain_ = swapchain;
    }
    return true;
}

int NativeFrameGeneration::presentOne(VkSwapchainKHR swapchain,
                                      const std::vector<VkImage> &swapchainImages,
                                      VkQueue queue,
                                      VkImage source,
                                      uint32_t width,
                                      uint32_t height,
                                      uint32_t swapchainWidth,
                                      uint32_t swapchainHeight,
                                      bool flipY) {
    auto framework = framework_.lock();
    if (framework == nullptr) return -1;
    VkDevice device = framework->device()->vkDevice();
    PresentSlot &slot = presentSlots_[presentSlot_];
    presentSlot_ = (presentSlot_ + 1) % PRESENT_SLOTS;

    vkWaitForFences(device, 1, &slot.fence, VK_TRUE, UINT64_MAX);
    uint32_t imageIndex = 0;
    VkResult acquired = vkAcquireNextImageKHR(device, swapchain, 5000000000ull, slot.acquire, VK_NULL_HANDLE,
                                              &imageIndex);
    if (acquired == VK_ERROR_OUT_OF_DATE_KHR || acquired < 0 || imageIndex >= swapchainImages.size()) return -1;
    vkResetFences(device, 1, &slot.fence);

    VkImage target = swapchainImages[imageIndex];
    vkResetCommandBuffer(slot.cmd, 0);
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(slot.cmd, &begin);
    fullBarrier(slot.cmd);
    imageBarrier(slot.cmd, target, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                 VK_PIPELINE_STAGE_2_TRANSFER_BIT, 0, VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                 VK_ACCESS_2_TRANSFER_WRITE_BIT);
    VkImageBlit blit = presentBlit(width, height, swapchainWidth, swapchainHeight, flipY);
    vkCmdBlitImage(slot.cmd, source, VK_IMAGE_LAYOUT_GENERAL, target, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit,
                   VK_FILTER_NEAREST);
    imageBarrier(slot.cmd, target, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                 VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
                 VK_PIPELINE_STAGE_2_BOTTOM_OF_PIPE_BIT, 0);
    vkEndCommandBuffer(slot.cmd);

    VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.waitSemaphoreCount = 1;
    submit.pWaitSemaphores = &slot.acquire;
    submit.pWaitDstStageMask = &waitStage;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &slot.cmd;
    submit.signalSemaphoreCount = 1;
    submit.pSignalSemaphores = &presentSemaphores_[imageIndex];
    if (vkQueueSubmit(queue, 1, &submit, slot.fence) != VK_SUCCESS) return -1;

    VkPresentInfoKHR present{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
    present.waitSemaphoreCount = 1;
    present.pWaitSemaphores = &presentSemaphores_[imageIndex];
    present.swapchainCount = 1;
    present.pSwapchains = &swapchain;
    present.pImageIndices = &imageIndex;
    VkResult result = vkQueuePresentKHR(queue, &present);
    if (result == VK_ERROR_OUT_OF_DATE_KHR || result < 0) return -1;
    return (result == VK_SUBOPTIMAL_KHR || acquired == VK_SUBOPTIMAL_KHR) ? 1 : 0;
}

void NativeFrameGeneration::countPresented(uint32_t frames) {
    presentedInWindow_ += frames;
    auto now = std::chrono::steady_clock::now();
    auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(now - windowStart_).count();
    if (elapsed >= 1000) {
        presentedPerSecond_ = static_cast<int>(presentedInWindow_ * 1000 / elapsed);
        presentedInWindow_ = 0;
        windowStart_ = now;
    }
}

int NativeFrameGeneration::presentPending(VkSwapchainKHR swapchain,
                                          const std::vector<VkImage> &swapchainImages,
                                          VkQueue queue,
                                          uint32_t swapchainWidth,
                                          uint32_t swapchainHeight) {
    if (!active()) {
        pendingImages_.clear();
        presentedPerSecond_ = 0;
        return 0;
    }
    if (pendingImages_.empty()) {
        countPresented(1);
        return 0;
    }
    std::vector<VkImage> pending;
    pending.swap(pendingImages_);
    if (!ensurePresentResources(swapchain, swapchainImages.size())) return 0;

    int status = 0;
    uint32_t presented = 1;
    for (VkImage image : pending) {
        int result = presentOne(swapchain, swapchainImages, queue, image, pendingWidth_, pendingHeight_,
                                swapchainWidth, swapchainHeight, pendingFlip_);
        if (result < 0) {
            static int reported = 0;
            if (reported++ < 5) fgOut() << "a generated frame could not be presented; the swapchain is rebuilt" << std::endl;
            historyValid_ = false;
            status = -1;
            break;
        }
        status = std::max(status, result);
        presented++;
    }
    countPresented(presented);
    return status;
}

void NativeFrameGeneration::release() {
    auto framework = framework_.lock();
    if (framework != nullptr) {
        framework->waitRenderQueueIdle();
        VkDevice device = framework->device()->vkDevice();
        for (auto &slot : presentSlots_) {
            if (slot.fence != VK_NULL_HANDLE) vkDestroyFence(device, slot.fence, nullptr);
            if (slot.acquire != VK_NULL_HANDLE) vkDestroySemaphore(device, slot.acquire, nullptr);
        }
        for (VkSemaphore semaphore : presentSemaphores_) vkDestroySemaphore(device, semaphore, nullptr);
        if (presentPool_ != VK_NULL_HANDLE) vkDestroyCommandPool(device, presentPool_, nullptr);
        for (auto &[view, age] : retiredViews_) vkDestroyImageView(device, view, nullptr);
        if (finalView_ != nullptr && finalView_->vkImageView(0) != VK_NULL_HANDLE) {
            vkDestroyImageView(device, finalView_->vkImageView(0), nullptr);
        }
    }
    if (provider_ != nullptr) provider_->release();
    provider_ = nullptr;
    presentSlots_.clear();
    presentSemaphores_.clear();
    presentPool_ = VK_NULL_HANDLE;
    presentSwapchain_ = VK_NULL_HANDLE;
    retiredViews_.clear();
    finalView_ = nullptr;
    finalViewImage_ = VK_NULL_HANDLE;
    generated_.clear();
    hudlessCopy_ = nullptr;
    deviceDepth_ = nullptr;
    motionVectors_ = nullptr;
    prepareTables_.clear();
    preparePipeline_ = nullptr;
    prepareShader_ = nullptr;
    capture_ = Capture{};
    backend_ = Backend::Off;
    maxGeneratedFrames_ = 0;
    dlssAvailable_ = false;
    fsrAvailable_ = false;
    dlssMaxFrames_ = 0;
    probed_ = false;
    reset();
}

} // namespace framegen
