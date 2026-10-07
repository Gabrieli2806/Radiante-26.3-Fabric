package com.g2806.radiante.mixin.world;

import com.g2806.radiante.client.render.RadianteRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft's terrain draws, stopped while its level render runs over the traced world (LevelRendererSkipMixin). It
 * has no terrain of its own then, but mods hang draws of world objects on the end of these - Physics Mod its debris
 * - and Radiante traces those itself; drawn here as well they would show twice.
 */
@Mixin(value = ChunkSectionsToRender.class, priority = 500)
public class ChunkSectionsToRenderMixin {

    @Inject(method = "renderGroup", at = @At("HEAD"), cancellable = true)
    private void radiante$noTerrainOverTrace(CallbackInfo ci) {
        if (RadianteRenderer.isOverlayFrame()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderLayers", at = @At("HEAD"), cancellable = true)
    private void radiante$noTranslucentTerrainOverTrace(CallbackInfo ci) {
        if (RadianteRenderer.isOverlayFrame()) {
            ci.cancel();
        }
    }
}
