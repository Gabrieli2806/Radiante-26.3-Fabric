package com.g2806.radiante.mixin.backend;

import com.g2806.radiante.client.RadianteClient;
import com.g2806.radiante.client.proxy.vulkan.RendererProxy;
import com.mojang.renderpearl.backend.vulkan.VulkanInstance;
import org.lwjgl.PointerBuffer;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(VulkanInstance.class)
public class VulkanInstanceMixin {

    @Redirect(method = "<init>", at = @At(value = "INVOKE",
        target = "Lorg/lwjgl/vulkan/VK12;vkCreateInstance(Lorg/lwjgl/vulkan/VkInstanceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I"))
    private int radiante$createMergedInstance(VkInstanceCreateInfo createInfo, VkAllocationCallbacks allocator,
        PointerBuffer instance) {
        RadianteClient.ensureNativeLoaded();
        return RendererProxy.createInstance(createInfo.address(), allocator == null ? 0L : allocator.address(),
            instance.address());
    }
}
