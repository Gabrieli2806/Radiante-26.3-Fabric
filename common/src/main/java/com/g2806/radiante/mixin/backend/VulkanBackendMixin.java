package com.g2806.radiante.mixin.backend;

import com.g2806.radiante.client.proxy.vulkan.RendererProxy;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import java.util.List;
import java.util.Set;
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

    @Redirect(method = "createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;",
        at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkCreateDevice(Lorg/lwjgl/vulkan/VkPhysicalDevice;Lorg/lwjgl/vulkan/VkDeviceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I"))
    private static int radiante$createMergedDevice(VkPhysicalDevice physicalDevice, VkDeviceCreateInfo createInfo,
        VkAllocationCallbacks allocator, PointerBuffer device) {
        return RendererProxy.createDevice(physicalDevice.address(), createInfo.address(),
            allocator == null ? 0L : allocator.address(), device.address());
    }

    /** Devices without hardware ray tracing are rejected so a capable GPU gets picked. */
    @Inject(method = "checkDeviceSuitability", at = @At("RETURN"), cancellable = true)
    private static void radiante$requireRayTracing(VkPhysicalDevice device, Set<FeatureSet> required,
        Set<FeatureSet> requiredIfAvailable, CallbackInfoReturnable<BackendCreationException> cir) {
        if (cir.getReturnValue() == null && !RendererProxy.isRayTracingCapable(device.address())) {
            cir.setReturnValue(new BackendCreationException("Device does not support hardware ray tracing",
                BackendCreationException.Reason.VULKAN_MISSING_EXTENSION, List.of("VK_KHR_ray_tracing_pipeline")));
        }
    }
}
