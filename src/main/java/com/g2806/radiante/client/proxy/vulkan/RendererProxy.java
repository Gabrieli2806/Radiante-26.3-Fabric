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

    /** Turns the renderer's own diagnostic output on or off; it is off unless the player asks for it. */
    public static native void setLoggingEnabled(boolean enabled);

    public static native void shouldRenderWorld(boolean renderWorld);

    /** Loads NVIDIA Streamline from `folder`; must run before Minecraft creates its Vulkan instance. */
    public static native boolean initFrameGeneration(String folder);

    /** Frames DLSS can generate per rendered frame, 0 when frame generation is unavailable. */
    public static native int maxGeneratedFrames();

    public static native void setGeneratedFrames(int frames);

    /** Frames put on screen per second, real plus generated; 0 while frame generation is off. */
    public static native int presentedFrameRate();

    /** Pipeline image slots holding the depth and motion vectors frame generation reads. */
    public static native void setFrameGenerationImages(int depthSlot, int motionVectorSlot);

    /** Opens the Streamline frame; called once per client frame. */
    public static native void beginFrameGenerationFrame();

    /** Latency marker for Reflex (see FrameGeneration.Marker). */
    public static native void frameGenerationMarker(int marker);

    /** NVIDIA Reflex low latency; only has an effect where Streamline is loaded and the GPU supports it. */
    public static native void setReflexEnabled(boolean enabled);

    public static native boolean isReflexSupported();
}
