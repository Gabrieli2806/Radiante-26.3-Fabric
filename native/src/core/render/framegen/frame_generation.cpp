#include "core/render/framegen/frame_generation.hpp"

#include "core/render/framegen/streamline.hpp"

#ifdef MCVR_ENABLE_STREAMLINE

#include "core/render/buffers.hpp"
#include "core/render/render_framework.hpp"
#include "core/render/renderer.hpp"
#include "core/vulkan/image.hpp"

#include <sl.h>
#include <sl_consts.h>
#include <sl_core_api.h>
#include <sl_dlss_g.h>
#include <sl_pcl.h>
#include <sl_reflex.h>

#include <glm/gtc/matrix_inverse.hpp>

#include <chrono>
#include <cmath>
#include <cstring>
#include <iostream>
#include "core/util/logging.hpp"

namespace {

int g_depthSlot = -1;
int g_motionVectorSlot = -1;

bool g_hasPrevious = false;
bool g_enabled = false;
uint32_t g_presentedInWindow = 0;
int g_presentedPerSecond = 0;
std::chrono::steady_clock::time_point g_windowStart = std::chrono::steady_clock::now();
glm::mat4 g_previousView{1.0f};
glm::mat4 g_previousProjection{1.0f};
glm::dvec3 g_previousCameraPos{0.0};

sl::float4x4 toSl(const glm::mat4 &matrix) {
    // Streamline matrices are row major, glm stores columns.
    glm::mat4 transposed = glm::transpose(matrix);
    sl::float4x4 result{};
    std::memcpy(&result, &transposed, sizeof(float) * 16);
    return result;
}

sl::Resource toResource(const std::shared_ptr<vk::DeviceLocalImage> &image) {
    sl::Resource resource(sl::ResourceType::eTex2d, image->vkImage(), nullptr, image->vkImageView(),
                          static_cast<uint32_t>(image->imageLayout()));
    resource.width = image->width();
    resource.height = image->height();
    resource.nativeFormat = static_cast<uint32_t>(image->vkFormat());
    resource.mipLevels = 1;
    resource.arrayLayers = 1;
    return resource;
}

sl::FrameToken *g_frame = nullptr;

struct SlApi {
    PFun_slGetNewFrameToken *getNewFrameToken = nullptr;
    PFun_slPCLSetMarker *setMarker = nullptr;
    PFun_slReflexSetOptions *setReflexOptions = nullptr;
    PFun_slSetConstants *setConstants = nullptr;
    PFun_slSetTagForFrame *setTagForFrame = nullptr;
    PFun_slDLSSGSetOptions *setOptions = nullptr;
    PFun_slDLSSGGetState *getState = nullptr;
    bool resolved = false;
    bool usable = false;
};

SlApi g_sl;

bool resolveApi() {
    if (g_sl.resolved) return g_sl.usable;
    g_sl.resolved = true;

    g_sl.getNewFrameToken =
        reinterpret_cast<PFun_slGetNewFrameToken *>(framegen::Streamline::procAddress("slGetNewFrameToken"));
    g_sl.setConstants = reinterpret_cast<PFun_slSetConstants *>(framegen::Streamline::procAddress("slSetConstants"));
    g_sl.setTagForFrame =
        reinterpret_cast<PFun_slSetTagForFrame *>(framegen::Streamline::procAddress("slSetTagForFrame"));
    g_sl.setOptions = reinterpret_cast<PFun_slDLSSGSetOptions *>(
        framegen::Streamline::featureFunction(sl::kFeatureDLSS_G, "slDLSSGSetOptions"));
    g_sl.getState = reinterpret_cast<PFun_slDLSSGGetState *>(
        framegen::Streamline::featureFunction(sl::kFeatureDLSS_G, "slDLSSGGetState"));
    g_sl.setMarker =
        reinterpret_cast<PFun_slPCLSetMarker *>(framegen::Streamline::featureFunction(sl::kFeaturePCL, "slPCLSetMarker"));
    g_sl.setReflexOptions = reinterpret_cast<PFun_slReflexSetOptions *>(
        framegen::Streamline::featureFunction(sl::kFeatureReflex, "slReflexSetOptions"));

    g_sl.usable = g_sl.getNewFrameToken != nullptr && g_sl.setConstants != nullptr &&
                  g_sl.setTagForFrame != nullptr && g_sl.setOptions != nullptr;
    if (!g_sl.usable) {
        radiante::out() << "[Streamline] frame generation entry points are missing; staying off" << std::endl;
    }
    return g_sl.usable;
}

} // namespace

void framegen::FrameGeneration::setImageSlots(int depthSlot, int motionVectorSlot) {
    g_depthSlot = depthSlot;
    g_motionVectorSlot = motionVectorSlot;
}

int framegen::FrameGeneration::presentedFrameRate() {
    return g_presentedPerSecond;
}

int framegen::FrameGeneration::depthSlot() {
    return g_depthSlot;
}

int framegen::FrameGeneration::motionVectorSlot() {
    return g_motionVectorSlot;
}

void framegen::FrameGeneration::reset() {
    g_hasPrevious = false;
}

void framegen::FrameGeneration::beginClientFrame() {
    if (!Streamline::isSupported() || Streamline::generatedFrames() == 0 || !resolveApi()) {
        g_frame = nullptr;
        return;
    }

    // Frame generation needs Reflex to pace the frames it inserts.
    if (g_sl.setReflexOptions != nullptr) {
        sl::ReflexOptions reflex{};
        reflex.mode = sl::ReflexMode::eLowLatency;
        g_sl.setReflexOptions(reflex);
    }

    sl::FrameToken *frame = nullptr;
    if (g_sl.getNewFrameToken(frame, nullptr) != sl::Result::eOk) {
        g_frame = nullptr;
        return;
    }
    g_frame = frame;
}

void framegen::FrameGeneration::marker(int marker) {
    if (g_frame == nullptr || g_sl.setMarker == nullptr) return;
    g_sl.setMarker(static_cast<sl::PCLMarker>(marker), *g_frame);
}

void framegen::FrameGeneration::beginFrame(std::shared_ptr<vk::DeviceLocalImage> color,
                                           std::shared_ptr<vk::DeviceLocalImage> depth,
                                           std::shared_ptr<vk::DeviceLocalImage> motionVectors,
                                           VkCommandBuffer commandBuffer) {
    if (!Streamline::isSupported()) return;

    if (Streamline::generatedFrames() == 0) {
        // Streamline keeps generating until it is told to stop, so switching the option off has to reach it.
        if (g_enabled && resolveApi()) {
            sl::DLSSGOptions off{};
            off.mode = sl::DLSSGMode::eOff;
            g_sl.setOptions(sl::ViewportHandle(0), off);
            radiante::out() << "[Streamline] frame generation turned off" << std::endl;
        }
        g_enabled = false;
        g_presentedPerSecond = 0;
        g_presentedInWindow = 0;
        return;
    }
    if (color == nullptr || depth == nullptr || motionVectors == nullptr) return;

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

    if (!g_hasPrevious) {
        g_previousView = view;
        g_previousProjection = projection;
        g_previousCameraPos = cameraPos;
    }

    // The renderer traces in camera relative space, so the movement of the camera itself is the translation
    // between the two frames.
    glm::mat4 cameraDelta{1.0f};
    cameraDelta[3] = glm::vec4(glm::vec3(g_previousCameraPos - cameraPos), 1.0f);
    glm::mat4 clipToPrevClip = g_previousProjection * g_previousView * cameraDelta * viewInv * projectionInv;
    glm::mat4 prevClipToClip = glm::inverse(clipToPrevClip);

    sl::Constants constants{};
    constants.cameraViewToClip = toSl(projection);
    constants.clipToCameraView = toSl(projectionInv);
    constants.clipToPrevClip = toSl(clipToPrevClip);
    constants.prevClipToClip = toSl(prevClipToClip);
    constants.jitterOffset = {ubo->cameraJitter.x, ubo->cameraJitter.y};
    constants.cameraPinholeOffset = {0.0f, 0.0f};
    // Streamline wants the lens described separately from the matrix, so it is read back out of the projection.
    float focalY = std::abs(projection[1][1]);
    float focalX = std::abs(projection[0][0]);
    constants.cameraFOV = focalY > 0.0f ? 2.0f * std::atan(1.0f / focalY) : 0.0f;
    constants.cameraAspectRatio = focalX > 0.0f ? focalY / focalX : 1.0f;
    constants.mvecScale = {1.0f, 1.0f};
    constants.cameraPos = {static_cast<float>(cameraPos.x), static_cast<float>(cameraPos.y),
                           static_cast<float>(cameraPos.z)};
    constants.cameraRight = {viewInv[0][0], viewInv[0][1], viewInv[0][2]};
    constants.cameraUp = {viewInv[1][0], viewInv[1][1], viewInv[1][2]};
    constants.cameraFwd = {-viewInv[2][0], -viewInv[2][1], -viewInv[2][2]};
    constants.cameraNear = 0.05f;
    constants.cameraFar = 1024.0f;
    constants.depthInverted = sl::Boolean::eFalse;
    constants.cameraMotionIncluded = sl::Boolean::eTrue;
    constants.motionVectors3D = sl::Boolean::eFalse;
    constants.reset = g_hasPrevious ? sl::Boolean::eFalse : sl::Boolean::eTrue;
    constants.orthographicProjection = sl::Boolean::eFalse;
    constants.motionVectorsDilated = sl::Boolean::eFalse;
    constants.motionVectorsJittered = sl::Boolean::eFalse;

    g_previousView = view;
    g_previousProjection = projection;
    g_previousCameraPos = cameraPos;
    g_hasPrevious = true;

    if (!resolveApi() || g_frame == nullptr) return;

    sl::FrameToken *frame = g_frame;
    sl::ViewportHandle viewport(0);
    g_sl.setConstants(constants, *frame, viewport);

    sl::Resource colorResource = toResource(color);
    sl::Resource depthResource = toResource(depth);
    sl::Resource motionResource = toResource(motionVectors);
    sl::ResourceTag tags[] = {
        sl::ResourceTag(&colorResource, sl::kBufferTypeHUDLessColor, sl::ResourceLifecycle::eValidUntilPresent),
        sl::ResourceTag(&depthResource, sl::kBufferTypeDepth, sl::ResourceLifecycle::eValidUntilPresent),
        sl::ResourceTag(&motionResource, sl::kBufferTypeMotionVectors, sl::ResourceLifecycle::eValidUntilPresent),
    };
    g_sl.setTagForFrame(*frame, viewport, tags, static_cast<uint32_t>(std::size(tags)),
                        reinterpret_cast<sl::CommandBuffer *>(commandBuffer));

    sl::DLSSGOptions options{};
    options.mode = sl::DLSSGMode::eOn;
    options.numFramesToGenerate = Streamline::generatedFrames();
    // Without these Streamline guesses the sizes from the window and warns about a zero sized back buffer.
    options.colorWidth = color->width();
    options.colorHeight = color->height();
    options.colorBufferFormat = static_cast<uint32_t>(color->vkFormat());
    options.hudLessBufferFormat = static_cast<uint32_t>(color->vkFormat());
    options.mvecDepthWidth = depth->width();
    options.mvecDepthHeight = depth->height();
    options.depthBufferFormat = static_cast<uint32_t>(depth->vkFormat());
    options.mvecBufferFormat = static_cast<uint32_t>(motionVectors->vkFormat());
    sl::Result optionsResult = g_sl.setOptions(viewport, options);
    g_enabled = true;

    if (g_sl.getState == nullptr) return;

    // How many frames really reached the screen, which is what tells generated frames apart from rendered ones.
    sl::DLSSGState state{};
    sl::Result stateResult = g_sl.getState(viewport, state, &options);
    g_presentedInWindow += state.numFramesActuallyPresented;
    auto now = std::chrono::steady_clock::now();
    auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(now - g_windowStart).count();
    if (elapsed >= 1000) {
        g_presentedPerSecond = static_cast<int>(g_presentedInWindow * 1000 / elapsed);
        g_presentedInWindow = 0;
        g_windowStart = now;
    }

    // Periodic report, because frame generation only reaches its steady state after a few hundred frames.
    static int frames = 0;
    if (frames++ % 600 == 0) {
        radiante::out() << "[Streamline] frame generation state: options=" << static_cast<int>(optionsResult)
                  << " query=" << static_cast<int>(stateResult) << " status=" << static_cast<int>(state.status)
                  << " generated=" << options.numFramesToGenerate << " presented="
                  << state.numFramesActuallyPresented << " perSecond=" << g_presentedPerSecond << std::endl;
    }
}

#else

void framegen::FrameGeneration::setImageSlots(int, int) {}

int framegen::FrameGeneration::presentedFrameRate() {
    return 0;
}

int framegen::FrameGeneration::depthSlot() {
    return -1;
}

int framegen::FrameGeneration::motionVectorSlot() {
    return -1;
}

void framegen::FrameGeneration::reset() {}

void framegen::FrameGeneration::beginClientFrame() {}

void framegen::FrameGeneration::marker(int) {}

void framegen::FrameGeneration::beginFrame(std::shared_ptr<vk::DeviceLocalImage>,
                                           std::shared_ptr<vk::DeviceLocalImage>,
                                           std::shared_ptr<vk::DeviceLocalImage>,
                                           VkCommandBuffer) {}

#endif
