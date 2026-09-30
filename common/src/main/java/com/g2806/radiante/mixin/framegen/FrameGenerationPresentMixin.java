package com.g2806.radiante.mixin.framegen;

import com.g2806.radiante.client.proxy.vulkan.FrameGenerationProxy;
import com.g2806.radiante.client.render.FrameGeneration;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import it.unimi.dsi.fastutil.longs.LongList;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VkQueue;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Frame generation owns the rest of the present: after Minecraft presents the first generated frame (swapped in at
 * the blit, see HdrPresentMixin), the remaining generated frames and then the real one are presented on swapchain
 * images of their own. They are paced by the display, so while frame generation is on the swapchain uses FIFO.
 */
@Mixin(VulkanGpuSurface.class)
public abstract class FrameGenerationPresentMixin {

    @Shadow
    @Final
    private VkQueue presentQueue;
    @Shadow
    private long swapchain;
    @Shadow
    private int swapchainWidth;
    @Shadow
    private int swapchainHeight;
    @Shadow
    @Final
    private LongList swapchainImages;
    @Shadow
    private boolean swapchainSuboptimal;
    @Shadow
    private boolean swapchainOutOfDate;
    @Shadow
    private @Nullable SurfaceException eatenException;

    @Redirect(method = "configure", at = @At(value = "INVOKE",
        target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanConst;toVk(Lcom/mojang/renderpearl/api/device/GpuSurface$PresentMode;)I"))
    private int radiante$pacedPresentMode(GpuSurface.PresentMode mode) {
        FrameGeneration.noteSwapchainConfigured();
        // Generated frames presented back to back only show for a refresh each when the display paces them.
        return FrameGeneration.isActive() ? VulkanConst.toVk(GpuSurface.PresentMode.FIFO) : VulkanConst.toVk(mode);
    }

    @Inject(method = "present", at = @At("TAIL"))
    private void radiante$presentGenerated(CallbackInfo ci) {
        if (FrameGeneration.needsReconfigure()) {
            // Switched on or off: have Minecraft rebuild the swapchain with the matching present mode.
            this.swapchainSuboptimal = true;
        }
        if (!FrameGeneration.isActive() || this.swapchain == 0L || this.swapchainOutOfDate) {
            return;
        }
        long[] images = this.swapchainImages.toLongArray();
        int result = FrameGenerationProxy.presentPending(this.swapchain, images, this.presentQueue.address(),
            this.swapchainWidth, this.swapchainHeight);
        if (result < 0) {
            this.swapchainSuboptimal = true;
            this.swapchainOutOfDate = true;
            if (this.eatenException == null) {
                this.eatenException = new SurfaceException("Failed to present generated frame, swapchain out of date");
            }
        } else if (result > 0) {
            this.swapchainSuboptimal = true;
        }
    }
}
