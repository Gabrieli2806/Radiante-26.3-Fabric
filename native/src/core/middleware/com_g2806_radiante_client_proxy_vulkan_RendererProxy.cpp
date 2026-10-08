#include <chrono>
#include <jni.h>

#include "core/all_extern.hpp"
#include "core/render/buffers.hpp"
#include "core/render/pipeline.hpp"
#include "core/render/render_framework.hpp"
#include "core/render/framegen/frame_generation.hpp"
#include "core/render/framegen/native_frame_generation.hpp"
#include "core/render/framegen/reflex.hpp"
#include "core/render/renderer.hpp"
#include "core/render/textures.hpp"
#include "core/render/world.hpp"
#include "core/vulkan/device.hpp"
#include "core/vulkan/instance.hpp"
#include "core/vulkan/physical_device.hpp"
#include "core/vulkan/queue_lock.hpp"

#include <iostream>
#include <string>
#include "core/util/logging.hpp"

namespace {
std::u16string toU16(JNIEnv *env, jstring jstr) {
    if (!jstr) return {};
    const jchar *chars = env->GetStringChars(jstr, nullptr);
    jsize len = env->GetStringLength(jstr);
    std::u16string u16(reinterpret_cast<const char16_t *>(chars), reinterpret_cast<const char16_t *>(chars) + len);
    env->ReleaseStringChars(jstr, chars);
    return u16;
}

template <typename Fn>
auto guarded(const char *name, Fn &&fn, decltype(fn()) fallback) -> decltype(fn()) {
    try {
        return fn();
    } catch (const std::exception &e) {
        radiante::err() << "[Radiante] " << name << " failed: " << e.what() << std::endl;
    } catch (...) { radiante::err() << "[Radiante] " << name << " failed with unknown exception" << std::endl; }
    return fallback;
}
} // namespace

extern "C" {
JNIEXPORT void JNICALL
Java_com_g2806_radiante_client_proxy_vulkan_RendererProxy_initFolderPath(JNIEnv *env, jclass, jstring folderPath) {
    if (folderPath == nullptr) return;
    Renderer::folderPath = toU16(env, folderPath);
    vk::Device::setPipelineCacheFile(Renderer::folderPath / "cache" / "pipeline_cache.bin");
}

JNIEXPORT jint JNICALL Java_com_g2806_radiante_client_proxy_vulkan_RendererProxy_createInstance(
    JNIEnv *, jclass, jlong createInfo, jlong allocator, jlong outInstance) {
    return guarded(
        "createInstance",
        [&]() {
            return static_cast<jint>(vk::Instance::createMerged(
                reinterpret_cast<const VkInstanceCreateInfo *>(createInfo),
                reinterpret_cast<const VkAllocationCallbacks *>(allocator), reinterpret_cast<VkInstance *>(outInstance)));
        },
        static_cast<jint>(VK_ERROR_INITIALIZATION_FAILED));
}

JNIEXPORT jint JNICALL Java_com_g2806_radiante_client_proxy_vulkan_RendererProxy_createDevice(
    JNIEnv *, jclass, jlong physicalDevice, jlong createInfo, jlong allocator, jlong outDevice) {
    return guarded(
        "createDevice",
        [&]() {
            return static_cast<jint>(vk::Device::createMerged(
                reinterpret_cast<VkPhysicalDevice>(physicalDevice),
                reinterpret_cast<const VkDeviceCreateInfo *>(createInfo),
                reinterpret_cast<const VkAllocationCallbacks *>(allocator), reinterpret_cast<VkDevice *>(outDevice)));
        },
        static_cast<jint>(VK_ERROR_INITIALIZATION_FAILED));
}

JNIEXPORT jboolean JNICALL
Java_com_g2806_radiante_client_proxy_vulkan_RendererProxy_isRayTracingCapable(JNIEnv *, jclass, jlong physicalDevice) {
    return vk::PhysicalDevice::isRayTracingCapable(reinterpret_cast<VkPhysicalDevice>(physicalDevice)) ? JNI_TRUE :
                                                                                                      JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_g2806_radiante_client_proxy_vulkan_RendererProxy_initRenderer(JNIEnv *,
                                                                                                jclass,
                                                                                                jlong instance,
                                                                                                jlong physicalDevice,
                                                                                                jlong device,
                                                                                                jlong mainQueue,
                                                                                                jint mainQueueFamily,
                                                                                                jlong secondaryQueue,
                                                                                                jint secondaryQueueFamily,
                                                                                                jint width,
                                                                                                jint height) {
    return guarded(
        "initRenderer",
        [&]() {
            SharedDeviceHandles handles{
                .instance = reinterpret_cast<VkInstance>(instance),
                .physicalDevice = reinterpret_cast<VkPhysicalDevice>(physicalDevice),
                .device = reinterpret_cast<VkDevice>(device),
                .mainQueue = reinterpret_cast<VkQueue>(mainQueue),
                .mainQueueFamily = static_cast<uint32_t>(mainQueueFamily),
                .secondaryQueue = reinterpret_cast<VkQueue>(secondaryQueue),
                .secondaryQueueFamily = static_cast<uint32_t>(secondaryQueueFamily),
                .width = static_cast<uint32_t>(std::max(width, 1)),
                .height = static_cast<uint32_t>(std::max(height, 1)),
            };
            Renderer::init(handles);
            Renderer::instance().framework()->acquireContext();
            return static_cast<jboolean>(JNI_TRUE);
        },
        static_cast<jboolean>(JNI_FALSE));
}

JNIEXPORT jint JNICALL Java_com_g2806_radiante_client_proxy_vulkan_RendererProxy_renderFrame(
    JNIEnv *env, jclass, jlong target, jint width, jint height, jint traceWidth, jint traceHeight, jint format,
    jlongArray outCommandBuffers) {
    if (!Renderer::is_initialized()) return 0;
    auto framework = Renderer::instance().framework();
    if (framework == nullptr) return 0;

    auto buffers = guarded(
        "renderFrame",
        [&]() {
            return framework->renderFrame(reinterpret_cast<VkImage>(target), static_cast<uint32_t>(width),
                                          static_cast<uint32_t>(height), static_cast<uint32_t>(std::max(traceWidth, 1)),
                                          static_cast<uint32_t>(std::max(traceHeight, 1)), static_cast<VkFormat>(format));
        },
        std::vector<VkCommandBuffer>{});

    jsize capacity = env->GetArrayLength(outCommandBuffers);
    jsize count = std::min<jsize>(capacity, static_cast<jsize>(buffers.size()));
    std::vector<jlong> handles(count);
    for (jsize i = 0; i < count; i++) handles[i] = reinterpret_cast<jlong>(buffers[i]);
    env->SetLongArrayRegion(outCommandBuffers, 0, count, handles.data());
    return count;
}

JNIEXPORT void JNICALL Java_com_g2806_radiante_client_proxy_vulkan_RendererProxy_markSubmitted(JNIEnv *,
                                                                                             jclass,
                                                                                             jlong semaphore,
                                                                                             jlong value) {
    if (!Renderer::is_initialized()) return;
    guarded(
        "markSubmitted",
        [&]() {
            Renderer::instance().framework()->markSubmitted(reinterpret_cast<VkSemaphore>(semaphore),
                                                            static_cast<uint64_t>(value));
            return 0;
        },
        0);
}

JNIEXPORT void JNICALL Java_com_g2806_radiante_client_proxy_vulkan_RendererProxy_flushTextureUploads(JNIEnv *, jclass) {
    if (!Renderer::is_initialized()) return;
    auto textures = Renderer::instance().textures();
    if (textures != nullptr) textures->performQueuedUpload();
}

JNIEXPORT void JNICALL Java_com_g2806_radiante_client_proxy_vulkan_RendererProxy_lockQueue(JNIEnv *, jclass) {
    vk::queueMutex().lock();
    vk::queueOp().store("minecraft queue op");
    vk::queueOpStart().store(std::chrono::steady_clock::now().time_since_epoch().count());
}

JNIEXPORT void JNICALL Java_com_g2806_radiante_client_proxy_vulkan_RendererProxy_unlockQueue(JNIEnv *, jclass) {
    vk::queueOp().store(nullptr);
    vk::queueMutex().unlock();
}

JNIEXPORT void JNICALL Java_com_g2806_radiante_client_proxy_vulkan_RendererProxy_close(JNIEnv *, jclass) {
    if (!Renderer::is_initialized()) return;
    Renderer::instance().close();
}

JNIEXPORT void JNICALL Java_com_g2806_radiante_client_proxy_vulkan_RendererProxy_setLoggingEnabled(
    JNIEnv *, jclass, jboolean enabled) {
    radiante::setLoggingEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_g2806_radiante_client_proxy_vulkan_RendererProxy_shouldRenderWorld(
    JNIEnv *, jclass, jboolean shouldRenderWorld) {
    if (!Renderer::is_initialized()) return;
    auto world = Renderer::instance().world();
    if (world == nullptr) return;
    world->shouldRender() = shouldRenderWorld;
}

JNIEXPORT jint JNICALL Java_com_g2806_radiante_client_proxy_vulkan_RendererProxy_maxGeneratedFrames(JNIEnv *, jclass) {
    return static_cast<jint>(framegen::NativeFrameGeneration::instance().maxGeneratedFrames());
}

JNIEXPORT void JNICALL Java_com_g2806_radiante_client_proxy_vulkan_RendererProxy_setGeneratedFrames(JNIEnv *,
                                                                                                   jclass,
                                                                                                   jint frames) {
    framegen::NativeFrameGeneration::instance().setGeneratedFrames(static_cast<uint32_t>(frames < 0 ? 0 : frames));
}

JNIEXPORT void JNICALL Java_com_g2806_radiante_client_proxy_vulkan_RendererProxy_setReflexEnabled(JNIEnv *,
                                                                                                 jclass,
                                                                                                 jboolean enabled) {
    framegen::Reflex::setEnabled(enabled == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL Java_com_g2806_radiante_client_proxy_vulkan_RendererProxy_isReflexSupported(JNIEnv *, jclass) {
    return framegen::Reflex::isSupported() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_g2806_radiante_client_proxy_vulkan_RendererProxy_reflexChainSwapchainCreate(
    JNIEnv *, jclass, jlong createInfo) {
    framegen::Reflex::chainSwapchainCreate(reinterpret_cast<void *>(createInfo));
}

JNIEXPORT void JNICALL Java_com_g2806_radiante_client_proxy_vulkan_RendererProxy_reflexSetSwapchain(JNIEnv *,
                                                                                                  jclass,
                                                                                                  jlong swapchain) {
    framegen::Reflex::setSwapchain(reinterpret_cast<VkSwapchainKHR>(swapchain));
}

JNIEXPORT void JNICALL Java_com_g2806_radiante_client_proxy_vulkan_RendererProxy_reflexChainPresent(JNIEnv *,
                                                                                                  jclass,
                                                                                                  jlong presentInfo) {
    framegen::Reflex::chainPresent(reinterpret_cast<void *>(presentInfo));
}

JNIEXPORT jint JNICALL Java_com_g2806_radiante_client_proxy_vulkan_RendererProxy_presentedFrameRate(JNIEnv *, jclass) {
    return static_cast<jint>(framegen::NativeFrameGeneration::instance().presentedFrameRate());
}

JNIEXPORT void JNICALL Java_com_g2806_radiante_client_proxy_vulkan_RendererProxy_setOverlayDepthTarget(
    JNIEnv *, jclass, jlong image, jint format, jfloat zScale, jfloat zOffset, jfloat wScale, jfloat wOffset) {
    if (!Renderer::is_initialized()) return;
    auto framework = Renderer::instance().framework();
    if (framework == nullptr) return;
    const float projection[4] = {zScale, zOffset, wScale, wOffset};
    framework->setOverlayDepthTarget(reinterpret_cast<VkImage>(image), static_cast<VkFormat>(format), projection);
}

JNIEXPORT void JNICALL Java_com_g2806_radiante_client_proxy_vulkan_RendererProxy_setFrameGenerationImages(
    JNIEnv *, jclass, jint depthSlot, jint motionVectorSlot) {
    framegen::FrameGeneration::setImageSlots(depthSlot, motionVectorSlot);
}

JNIEXPORT void JNICALL Java_com_g2806_radiante_client_proxy_vulkan_RendererProxy_beginFrameGenerationFrame(JNIEnv *,
                                                                                                          jclass) {
    framegen::FrameGeneration::beginClientFrame();
}

JNIEXPORT void JNICALL Java_com_g2806_radiante_client_proxy_vulkan_RendererProxy_frameGenerationMarker(JNIEnv *,
                                                                                                      jclass,
                                                                                                      jint marker) {
    framegen::FrameGeneration::marker(marker);
}

JNIEXPORT jstring JNICALL Java_com_g2806_radiante_client_proxy_vulkan_RendererProxy_drainNativeErrors(JNIEnv *env, jclass) {
    std::string errors = radiante::drainErrors();
    return errors.empty() ? nullptr : env->NewStringUTF(errors.c_str());
}

JNIEXPORT jstring JNICALL Java_com_g2806_radiante_client_proxy_vulkan_RendererProxy_drainNativeInfo(JNIEnv *env, jclass) {
    std::string info = radiante::drainInfo();
    return info.empty() ? nullptr : env->NewStringUTF(info.c_str());
}

JNIEXPORT void JNICALL Java_com_g2806_radiante_client_proxy_vulkan_RendererProxy_rebuildPendingPipeline(JNIEnv *env, jclass) {
    try {
        if (Renderer::is_initialized()) Renderer::instance().framework()->recreatePending();
    } catch (const std::exception &e) {
        radiante::err() << "[Radiante] pipeline rebuild failed: " << e.what() << std::endl;
    } catch (...) { radiante::err() << "[Radiante] pipeline rebuild failed" << std::endl; }
}

JNIEXPORT jint JNICALL Java_com_g2806_radiante_client_proxy_vulkan_RendererProxy_rebuildProgress(JNIEnv *, jclass) {
    // 0-100: compiling shaders; 1000-1100: creating the passes' pipelines, which follows.
    uint32_t passes = Pipeline::rebuildPassesTotal.load();
    if (passes > 0) {
        return 1000 + static_cast<jint>(std::min<uint64_t>(100, 100ull * Pipeline::rebuildPassesDone.load() / passes));
    }
    uint32_t total = Pipeline::rebuildShadersTotal.load();
    if (total == 0) return 0;
    return static_cast<jint>(std::min<uint64_t>(100, 100ull * Pipeline::rebuildShadersDone.load() / total));
}
}
