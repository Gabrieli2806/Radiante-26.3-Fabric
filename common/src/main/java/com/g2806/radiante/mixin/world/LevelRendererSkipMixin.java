package com.g2806.radiante.mixin.world;

import com.g2806.radiante.client.render.RadianteRenderer;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While Radiante traces the world, LevelRenderer.render is still called every frame and stopped here, so that
 * the per-frame work other mods do at its head keeps running: Not Enough Animations hands its animator the
 * frame's partial tick there, and without it players freeze. The high priority applies this mixin after
 * everyone else's, which places this callback after theirs at the head of the method.
 */
@Mixin(value = LevelRenderer.class, priority = 2000)
public class LevelRendererSkipMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void radiante$skipWhenTracing(GraphicsResourceAllocator resourceAllocator, boolean renderOutline,
        CameraRenderState cameraState, GpuBufferSlice terrainFog, Vector4f fogColor, boolean shouldRenderSky,
        boolean consistentDepthRequired, CallbackInfo ci) {
        if (RadianteRenderer.isTracingLevel()) {
            ci.cancel();
        }
    }
}
