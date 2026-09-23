package com.g2806.radiante.mixin.backend;

import com.g2806.radiante.client.proxy.vulkan.RendererProxy;
import com.mojang.blaze3d.platform.NativeLibrariesBootstrap;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import org.lwjgl.PointerBuffer;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VulkanBackend.class)
public class VulkanBackendMixin {

    @Redirect(method = "createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;",
        at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkCreateDevice(Lorg/lwjgl/vulkan/VkPhysicalDevice;Lorg/lwjgl/vulkan/VkDeviceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I"))
    private static int radiante$createMergedDevice(VkPhysicalDevice physicalDevice, VkDeviceCreateInfo createInfo,
        VkAllocationCallbacks allocator, PointerBuffer device) {
        return RendererProxy.createDevice(physicalDevice.address(), createInfo.address(),
            allocator == null ? 0L : allocator.address(), device.address());
    }

    /** Devices without hardware ray tracing are rejected so a capable GPU gets picked. */
    @Inject(method = "isDeviceSuitable", at = @At("RETURN"), cancellable = true)
    private static void radiante$requireRayTracing(VkPhysicalDevice device, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && !RendererProxy.isRayTracingCapable(device.address())) {
            cir.setReturnValue(false);
        }
    }

    @Mixin(NativeLibrariesBootstrap.class)
    public static class BootstrapMixin {

        /**
         * Streamline has to be in place before LWJGL loads the Vulkan library. 26.2 loads it once, at startup,
         * with the rest of the natives - before Forge and NeoForge run any mod code.
         */
        @Inject(method = "tryLoadingVulkan", at = @At("HEAD"))
        private static void radiante$loadStreamline(CallbackInfoReturnable<Boolean> cir) {
            com.g2806.radiante.client.StreamlineBootstrap.run();
        }
    }
}
