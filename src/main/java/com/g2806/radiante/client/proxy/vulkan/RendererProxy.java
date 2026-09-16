package com.g2806.radiante.client.proxy.vulkan;

public class RendererProxy {

    public static native void initFolderPath(String folderPath);

    /** Creates the shared VkInstance with the renderer's extra extensions. Returns a VkResult. */
    public static native int createInstance(long createInfo, long allocator, long outInstance);

    /** Creates the shared VkDevice with ray tracing features enabled. Returns a VkResult. */
    public static native int createDevice(long physicalDevice, long createInfo, long allocator, long outDevice);

    public static native boolean isRayTracingCapable(long physicalDevice);

    public static native boolean initRenderer(long instance, long physicalDevice, long device, long mainQueue,
        int mainQueueFamily, long secondaryQueue, int secondaryQueueFamily, int width, int height);

    public static native int maxSupportedTextureSize();

    /**
     * Records this frame's world rendering into {@code targetImage} and writes the resulting command buffer
     * handles, in execution order, into {@code outCommandBuffers}. Returns how many were written.
     */
    public static native int renderFrame(long targetImage, int width, int height, int vkFormat,
        long[] outCommandBuffers);

    /** Records the Minecraft submit these command buffers belong to, then opens the next frame slot. */
    public static native void markSubmitted(long timelineSemaphore, long timelineValue);

    public static native void flushTextureUploads();

    public static native void lockQueue();

    public static native void unlockQueue();

    public static native void close();

    public static native void shouldRenderWorld(boolean renderWorld);
}
