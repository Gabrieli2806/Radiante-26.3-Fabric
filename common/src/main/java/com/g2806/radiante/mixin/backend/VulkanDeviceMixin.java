package com.g2806.radiante.mixin.backend;

import com.g2806.radiante.client.render.RadianteRenderer;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanDevice.class)
public class VulkanDeviceMixin {

    @Inject(method = "<init>", at = @At("TAIL"))
    private void radiante$initRenderer(CallbackInfo ci) {
        RadianteRenderer.onDeviceCreated((VulkanDevice) (Object) this);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void radiante$closeRenderer(CallbackInfo ci) {
        RadianteRenderer.close();
    }
}
