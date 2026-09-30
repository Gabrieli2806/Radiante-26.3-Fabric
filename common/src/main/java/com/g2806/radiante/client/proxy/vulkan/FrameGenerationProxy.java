package com.g2806.radiante.client.proxy.vulkan;

/** Native frame generation (core/render/framegen/native_frame_generation.cpp): DLSS or FSR, without Streamline. */
public final class FrameGenerationProxy {

    public static final int BACKEND_NONE = 0;
    public static final int BACKEND_DLSS = 1;
    public static final int BACKEND_FSR = 2;

    private FrameGenerationProxy() {
    }

    /** Which frame generation this GPU gets: {@link #BACKEND_DLSS}, {@link #BACKEND_FSR} or none. */
    public static native int backend();

    /** The backend the player picked: {@link #BACKEND_NONE} for automatic, or DLSS / FSR. */
    public static native void setPreference(int backend);

    /** Whether this GPU can run the given backend. */
    public static native boolean isAvailable(int backend);

    /** How many frames the given backend can generate per rendered one here; 0 when it cannot run. */
    public static native int maxGeneratedFramesFor(int backend);

    /**
     * Evaluates the generated frames on Minecraft's present command buffer and blits the first one (or, without
     * history yet, the real frame) into the swapchain image, in place of Minecraft's blit. The swapchain image must
     * be in TRANSFER_DST_OPTIMAL and Minecraft's final image in GENERAL. False when nothing was recorded, and the
     * caller blits as usual.
     */
    public static native boolean evaluateAndBlit(long commandBuffer, long finalImage, int finalFormat, int width,
        int height, long swapchainImage, int swapchainWidth, int swapchainHeight, boolean flipY);

    /**
     * After Minecraft's present: presents the remaining generated frames and then the real one, each on a
     * swapchain image of its own. 0 when fine, 1 suboptimal, -1 out of date.
     */
    public static native int presentPending(long swapchain, long[] swapchainImages, long queue, int swapchainWidth,
        int swapchainHeight);
}
