package com.g2806.radiante.mixin.compat;

import com.g2806.radiante.client.render.RadianteRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Distant Horizons draws its far terrain, clouds and beacon beams from hooks in the level render of Minecraft. That
 * render runs over the traced world for the sake of other mods (LevelRendererSkipMixin), and Radiante already traces
 * the far terrain and beams itself from the data Distant Horizons keeps (LodTerrain, DhBeacons): drawn here as well
 * they would lie over the traced ones, and Distant Horizons would start changing the game's cloud and transparency
 * settings as it does when it renders. Skipped when Distant Horizons is not installed.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.api.internal.ClientApi", remap = false)
public class DistantHorizonsRenderSkipMixin {

    @Inject(method = {"renderLods", "renderDeferredLodsForShaders", "renderFadeOpaque", "renderFadeTransparent"},
        at = @At("HEAD"), cancellable = true, require = 0)
    private void radiante$skipWhileTracing(CallbackInfo ci) {
        if (RadianteRenderer.isTracingLevel()) {
            ci.cancel();
        }
    }
}
