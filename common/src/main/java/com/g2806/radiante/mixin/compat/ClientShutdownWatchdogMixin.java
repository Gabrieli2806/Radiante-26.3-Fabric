package com.g2806.radiante.mixin.compat;

import com.g2806.radiante.client.compat.distanthorizons.DistantHorizonsCompat;
import com.mojang.blaze3d.platform.ClientShutdownWatchdog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The game has finished and is waiting for its last threads; see DistantHorizonsCompat.afterGameExit. */
@Mixin(ClientShutdownWatchdog.class)
public class ClientShutdownWatchdogMixin {

    @Inject(method = "startShutdownWatchdog", at = @At("HEAD"))
    private static void radiante$afterGameExit(String callsite, boolean forceShutdown,
        net.minecraft.client.Minecraft minecraft, net.minecraft.client.main.GameConfig gameConfig, long mainThreadId,
        CallbackInfo ci) {
        if ("post-main".equals(callsite)) {
            DistantHorizonsCompat.afterGameExit();
        }
    }
}
