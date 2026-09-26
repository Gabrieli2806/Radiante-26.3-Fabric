#include <jni.h>

#include "core/all_extern.hpp"
#include "core/render/hdr_output.hpp"
#include "core/util/logging.hpp"

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_g2806_radiante_client_proxy_vulkan_HdrProxy_compose(JNIEnv *,
                                                                                         jclass,
                                                                                         jlong commandBuffer,
                                                                                         jlong minecraftImage,
                                                                                         jint minecraftLayout,
                                                                                         jint minecraftFormat,
                                                                                         jint width,
                                                                                         jint height,
                                                                                         jlong swapchainImage,
                                                                                         jint swapchainWidth,
                                                                                         jint swapchainHeight,
                                                                                         jfloat paperWhiteNits,
                                                                                         jfloat peakNits) {
    try {
        return HdrOutput::instance().compose(
                   reinterpret_cast<VkCommandBuffer>(commandBuffer), reinterpret_cast<VkImage>(minecraftImage),
                   static_cast<VkImageLayout>(minecraftLayout), static_cast<VkFormat>(minecraftFormat),
                   static_cast<uint32_t>(width), static_cast<uint32_t>(height),
                   reinterpret_cast<VkImage>(swapchainImage), static_cast<uint32_t>(swapchainWidth),
                   static_cast<uint32_t>(swapchainHeight), paperWhiteNits, peakNits) ?
                   JNI_TRUE :
                   JNI_FALSE;
    } catch (const std::exception &e) {
        radiante::err() << "[HDR] compose failed: " << e.what() << std::endl;
    } catch (...) { radiante::err() << "[HDR] compose failed with unknown exception" << std::endl; }
    return JNI_FALSE;
}

} // extern "C"
