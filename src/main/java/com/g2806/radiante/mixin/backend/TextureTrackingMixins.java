package com.g2806.radiante.mixin.backend;

import com.g2806.radiante.client.render.TextureTracker;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import java.nio.ByteBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

public class TextureTrackingMixins {

    @Mixin(VulkanDevice.class)
    public static class DeviceMixin {

        @Inject(method = "createTexture(Ljava/lang/String;ILcom/mojang/renderpearl/api/GpuFormat;IIII)Lcom/mojang/renderpearl/api/textures/GpuTexture;",
            at = @At("RETURN"))
        private void radiante$trackTexture(CallbackInfoReturnable<GpuTexture> cir) {
            TextureTracker.onCreated(cir.getReturnValue());
        }
    }

    @Mixin(VulkanGpuTexture.class)
    public static class TextureMixin {

        @Inject(method = "close", at = @At("HEAD"))
        private void radiante$untrackTexture(CallbackInfo ci) {
            if (!((VulkanGpuTexture) (Object) this).isClosed()) {
                TextureTracker.onClosed((GpuTexture) (Object) this);
            }
        }
    }

    @Mixin(VulkanCommandEncoder.class)
    public static class EncoderMixin {

        @Inject(method = "writeToTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;Ljava/nio/ByteBuffer;IIIIII)V",
            at = @At("HEAD"))
        private void radiante$mirrorWrite(GpuTexture destination, ByteBuffer source, int mipLevel, int depthOrLayer,
            int destX, int destY, int width, int height, CallbackInfo ci) {
            TextureTracker.onWrite(destination, source, mipLevel, destX, destY, width, height);
        }

        @Inject(method = "copyBufferToTexture(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;IIIILcom/mojang/renderpearl/api/textures/GpuTexture;IIIIII)V",
            at = @At("HEAD"))
        private void radiante$mirrorBufferCopy(GpuBufferSlice source, int sourceX, int sourceY, int sourceWidth,
            int sourceHeight, GpuTexture destination, int destinationX, int destinationY, int copyWidth,
            int copyHeight, int mipLevel, int arrayLayer, CallbackInfo ci) {
            TextureTracker.onBufferCopy(destination, source, sourceX, sourceY, sourceWidth, destinationX,
                destinationY, copyWidth, copyHeight, mipLevel);
        }
    }
}
