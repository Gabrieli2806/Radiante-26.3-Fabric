package com.g2806.radiante.mixin.backend;

import com.g2806.radiante.client.option.Options;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.backend.opengl.GlBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The renderer shares Minecraft's Vulkan device, and Minecraft's own default prefers OpenGL, so left alone the mod
 * would never run. Vulkan is therefore asked for when the player has expressed no preference - but a player who
 * picked OpenGL on purpose, whether in Minecraft's video settings or because their card cannot ray trace, gets
 * OpenGL and the mod simply stays out of the way.
 */
@Mixin(PreferredGraphicsApi.class)
public class PreferredGraphicsApiMixin {

    @Inject(method = "getBackendsToTry", at = @At("HEAD"), cancellable = true)
    private void radiante$preferVulkan(CallbackInfoReturnable<GpuBackend[]> cir) {
        PreferredGraphicsApi preference = (PreferredGraphicsApi) (Object) this;
        if (preference == PreferredGraphicsApi.OPENGL || Options.useOpenGl || Options.openGlAfterFailedStart) {
            cir.setReturnValue(new GpuBackend[]{new GlBackend()});
            return;
        }

        cir.setReturnValue(new GpuBackend[]{new VulkanBackend()});
    }
}
