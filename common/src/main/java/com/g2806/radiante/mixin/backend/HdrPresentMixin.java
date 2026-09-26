package com.g2806.radiante.mixin.backend;

import com.g2806.radiante.client.hdr.HdrDisplay;
import com.g2806.radiante.client.option.Options;
import com.g2806.radiante.client.proxy.vulkan.HdrProxy;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * HDR display output: the window's swapchain format, and the present. Minecraft presents by blitting its main
 * render target into the swapchain image; with an HDR swapchain that blit becomes the native HDR composition
 * (HdrProxy.compose), which writes the same image, brighter where the traced world is. See HdrDisplay.
 */
@Mixin(VulkanGpuSurface.class)
public abstract class HdrPresentMixin {

    @Shadow
    private int swapchainWidth;
    @Shadow
    private int swapchainHeight;

    /** The texture being presented this call, for the redirect below. */
    @Unique
    private GpuTextureView radiante$presented;

    @Inject(method = "pickSwapchainSurfaceFormat", at = @At("HEAD"), cancellable = true)
    private void radiante$pickHdrFormat(VkSurfaceFormatKHR.Buffer formats,
        CallbackInfoReturnable<VkSurfaceFormatKHR> cir) {
        VkSurfaceFormatKHR hdr = HdrDisplay.pick(formats);
        if (hdr != null) {
            cir.setReturnValue(hdr);
        }
    }

    @Inject(method = "blitFromTexture", at = @At("HEAD"))
    private void radiante$notePresented(CommandEncoderBackend encoder, GpuTextureView view, CallbackInfo ci) {
        this.radiante$presented = view;
    }

    @Redirect(method = "blitFromTexture", at = @At(value = "INVOKE",
        target = "Lorg/lwjgl/vulkan/VK12;vkCmdBlitImage(Lorg/lwjgl/vulkan/VkCommandBuffer;JIJILorg/lwjgl/vulkan/VkImageBlit$Buffer;I)V"))
    private void radiante$composeHdr(VkCommandBuffer commandBuffer, long srcImage, int srcLayout, long dstImage,
        int dstLayout, VkImageBlit.Buffer regions, int filter) {
        GpuTextureView view = this.radiante$presented;
        this.radiante$presented = null;
        if (HdrDisplay.isActive() && view != null && view.texture() instanceof VulkanGpuTexture texture) {
            int width = view.getWidth(0);
            int height = view.getHeight(0);
            if (HdrProxy.compose(commandBuffer.address(), srcImage, srcLayout,
                VulkanConst.toVk(texture.getFormat()), width, height, dstImage, this.swapchainWidth,
                this.swapchainHeight, Options.hdrPaperWhiteNits, Options.hdrPeakNits)) {
                return;
            }
        }
        VK12.vkCmdBlitImage(commandBuffer, srcImage, srcLayout, dstImage, dstLayout, regions, filter);
    }
}
