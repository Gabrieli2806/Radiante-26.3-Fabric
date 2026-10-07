package com.g2806.radiante.mixin.framegen;

import com.g2806.radiante.client.proxy.vulkan.RendererProxy;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NVIDIA Reflex (VK_NV_low_latency2) on Minecraft's swapchain: its creation and every present carry the structures
 * the renderer builds (framegen::Reflex), and the renderer is told which swapchain to pace. Only the addresses of
 * Minecraft's own structures cross over, so a newer Minecraft only needs these injection points to still match.
 */
@Mixin(VulkanGpuSurface.class)
public abstract class ReflexSurfaceMixin {

    @Shadow
    private long swapchain;

    @ModifyArg(method = "configure", index = 1, at = @At(value = "INVOKE",
        target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkCreateSwapchainKHR(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
    private VkSwapchainCreateInfoKHR radiante$reflexSwapchain(VkSwapchainCreateInfoKHR createInfo) {
        RendererProxy.reflexChainSwapchainCreate(createInfo.address());
        return createInfo;
    }

    @Inject(method = "configure", at = @At("RETURN"))
    private void radiante$reflexSwapchainCreated(GpuSurface.Configuration configuration, CallbackInfo ci) {
        RendererProxy.reflexSetSwapchain(this.swapchain);
    }

    @Inject(method = "destroySwapchain", at = @At("HEAD"))
    private void radiante$reflexSwapchainDestroyed(CallbackInfo ci) {
        RendererProxy.reflexSetSwapchain(0L);
    }

    @ModifyArg(method = "present", index = 1, at = @At(value = "INVOKE",
        target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkQueuePresentKHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkPresentInfoKHR;)I"))
    private VkPresentInfoKHR radiante$reflexPresent(VkPresentInfoKHR presentInfo) {
        RendererProxy.reflexChainPresent(presentInfo.address());
        return presentInfo;
    }
}
