package com.g2806.radiante.mixin.compat;

import com.g2806.radiante.client.render.RadianteRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While ray tracing, Distant Horizons' own render buffers are never drawn: Radiante reads its terrain data and meshes
 * the far terrain itself (LodTerrain). Distant Horizons still built them, on its render loader threads, for every
 * section it loaded: about a third of the CPU time the game used standing still in a pre-generated world, and a good
 * part of why the CPU ran hot. The build is skipped while ray tracing is on; Distant Horizons asks again (its sections
 * stay dirty) and builds them as usual once ray tracing is switched off. Skipped when Distant Horizons is not installed.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.render.QuadTree.LodRenderSection", remap = false)
public class DistantHorizonsRenderBuildMixin {

    @Inject(method = "uploadRenderDataToGpuAsync", at = @At("HEAD"), cancellable = true, require = 0)
    private void radiante$skipWhileTracing(CallbackInfoReturnable<Boolean> cir) {
        if (RadianteRenderer.isRayTracingEnabled()) {
            cir.setReturnValue(false);
        }
    }
}
