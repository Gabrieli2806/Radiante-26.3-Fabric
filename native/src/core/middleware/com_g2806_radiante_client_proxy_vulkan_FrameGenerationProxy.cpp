#include <jni.h>

#include "core/all_extern.hpp"
#include "core/render/framegen/native_frame_generation.hpp"
#include "core/util/logging.hpp"

#include <vector>

extern "C" {

JNIEXPORT jint JNICALL Java_com_g2806_radiante_client_proxy_vulkan_FrameGenerationProxy_backend(JNIEnv *, jclass) {
    try {
        return static_cast<jint>(framegen::NativeFrameGeneration::instance().backend());
    } catch (...) { return 0; }
}

JNIEXPORT void JNICALL Java_com_g2806_radiante_client_proxy_vulkan_FrameGenerationProxy_setPreference(JNIEnv *,
                                                                                               jclass,
                                                                                               jint backend) {
    try {
        framegen::NativeFrameGeneration::instance().setPreference(
            static_cast<framegen::NativeFrameGeneration::Backend>(backend < 0 || backend > 2 ? 0 : backend));
    } catch (...) {}
}

JNIEXPORT jboolean JNICALL Java_com_g2806_radiante_client_proxy_vulkan_FrameGenerationProxy_isAvailable(JNIEnv *,
                                                                                                    jclass,
                                                                                                    jint backend) {
    try {
        return framegen::NativeFrameGeneration::instance().available(
                   static_cast<framegen::NativeFrameGeneration::Backend>(backend)) ?
                   JNI_TRUE :
                   JNI_FALSE;
    } catch (...) { return JNI_FALSE; }
}

JNIEXPORT jint JNICALL Java_com_g2806_radiante_client_proxy_vulkan_FrameGenerationProxy_maxGeneratedFramesFor(
    JNIEnv *, jclass, jint backend) {
    try {
        return static_cast<jint>(framegen::NativeFrameGeneration::instance().maxGeneratedFramesFor(
            static_cast<framegen::NativeFrameGeneration::Backend>(backend)));
    } catch (...) { return 0; }
}

JNIEXPORT jboolean JNICALL Java_com_g2806_radiante_client_proxy_vulkan_FrameGenerationProxy_evaluateAndBlit(
    JNIEnv *,
    jclass,
    jlong commandBuffer,
    jlong finalImage,
    jint finalFormat,
    jint width,
    jint height,
    jlong swapchainImage,
    jint swapchainWidth,
    jint swapchainHeight,
    jboolean flipY) {
    try {
        return framegen::NativeFrameGeneration::instance().evaluateAndBlit(
                   reinterpret_cast<VkCommandBuffer>(commandBuffer), reinterpret_cast<VkImage>(finalImage),
                   static_cast<VkFormat>(finalFormat), static_cast<uint32_t>(width), static_cast<uint32_t>(height),
                   reinterpret_cast<VkImage>(swapchainImage), static_cast<uint32_t>(swapchainWidth),
                   static_cast<uint32_t>(swapchainHeight), flipY == JNI_TRUE) ?
                   JNI_TRUE :
                   JNI_FALSE;
    } catch (const std::exception &e) {
        radiante::err() << "[Frame Generation] evaluation failed: " << e.what() << std::endl;
    } catch (...) { radiante::err() << "[Frame Generation] evaluation failed" << std::endl; }
    return JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_com_g2806_radiante_client_proxy_vulkan_FrameGenerationProxy_presentPending(
    JNIEnv *env,
    jclass,
    jlong swapchain,
    jlongArray swapchainImages,
    jlong queue,
    jint swapchainWidth,
    jint swapchainHeight) {
    try {
        std::vector<VkImage> images;
        if (swapchainImages != nullptr) {
            jsize count = env->GetArrayLength(swapchainImages);
            std::vector<jlong> raw(static_cast<size_t>(count));
            env->GetLongArrayRegion(swapchainImages, 0, count, raw.data());
            for (jlong handle : raw) images.push_back(reinterpret_cast<VkImage>(handle));
        }
        return framegen::NativeFrameGeneration::instance().presentPending(
            reinterpret_cast<VkSwapchainKHR>(swapchain), images, reinterpret_cast<VkQueue>(queue),
            static_cast<uint32_t>(swapchainWidth), static_cast<uint32_t>(swapchainHeight));
    } catch (const std::exception &e) {
        radiante::err() << "[Frame Generation] present failed: " << e.what() << std::endl;
    } catch (...) { radiante::err() << "[Frame Generation] present failed" << std::endl; }
    return 0;
}

} // extern "C"
