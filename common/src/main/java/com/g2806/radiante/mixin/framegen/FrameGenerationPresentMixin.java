package com.g2806.radiante.mixin.framegen;

import com.g2806.radiante.client.proxy.vulkan.FrameGenerationProxy;
import com.g2806.radiante.client.render.FrameGeneration;
import com.mojang.blaze3d.systems.SurfaceException;
import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import it.unimi.dsi.fastutil.longs.LongList;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VkQueue;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Frame generation owns the rest of the present: after Minecraft presents the first generated frame (swapped in at
 * the blit, see HdrPresentMixin), the remaining generated frames and then the real one are presented on swapchain
 * images of their own. A native present thread shows them spread over the time one rendered frame takes, so pacing
 * does not depend on the present mode and vsync stays the player's choice.
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

    @Inject(method = "configure", at = @At("HEAD"))
    private void radiante$configureAfterGenerated(CallbackInfo ci) {
        // The present thread still holds images of the old swapchain until it is done with them.
        FrameGeneration.waitPresentIdle();
        FrameGeneration.noteSwapchainConfigured();
    }

    @Inject(method = "blitFromTexture", at = @At("HEAD"))
    private void radiante$blitAfterGenerated(CallbackInfo ci) {
        // Minecraft's present that follows must not overtake the previous frame's generated ones.
        FrameGeneration.waitPresentIdle();
    }

    @Inject(method = "present", at = @At("TAIL"))
    private void radiante$presentGenerated(CallbackInfo ci) {
        if (FrameGeneration.needsReconfigure()) {
            // Switched on or off: have Minecraft rebuild the swapchain.
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
