package com.g2806.radiante.client.proxy.vulkan;

/** The native HDR display output (core/render/hdr_output.cpp). */
public final class HdrProxy {

    private HdrProxy() {
    }

    /**
     * Records the HDR composition of Minecraft's final image into the swapchain image on Minecraft's present command
     * buffer, in place of its blit. The swapchain image must be in TRANSFER_DST_OPTIMAL. Returns false when it could
     * not, and the caller blits as usual.
     */
    public static native boolean compose(long commandBuffer, long minecraftImage, int minecraftLayout,
        int minecraftFormat, int width, int height, long swapchainImage, int swapchainWidth, int swapchainHeight,
        float paperWhiteNits, float peakNits);
}
