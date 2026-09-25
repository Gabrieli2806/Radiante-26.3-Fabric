package com.g2806.radiante.mixin.compat;

import com.g2806.radiante.client.compat.distanthorizons.DistantHorizonsCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Distant Horizons turns vanilla's clouds off in the game's options when its "override vanilla graphics settings"
 * toggle is on, and never back. The setting it replaces is noted here so it can be put back once that toggle is
 * off again; see DistantHorizonsCompat.tick. Skipped when Distant Horizons is not installed.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftClientWrapper", remap = false)
public class DistantHorizonsCloudsMixin {

    @Inject(method = "disableVanillaClouds", at = @At("HEAD"), require = 0)
    private void radiante$rememberClouds(CallbackInfo ci) {
        DistantHorizonsCompat.onCloudsDisabled();
    }
}
