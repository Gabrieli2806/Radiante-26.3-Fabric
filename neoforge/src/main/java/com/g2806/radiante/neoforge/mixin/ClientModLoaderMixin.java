package com.g2806.radiante.neoforge.mixin;

import com.g2806.radiante.neoforge.EarlyWindowState;
import net.neoforged.fml.loading.EarlyLoadingScreenController;
import net.neoforged.neoforge.client.loading.ClientModLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The early loading screen keeps drawing with OpenGL on Minecraft's window until mods finish loading. Once that
 * window has been replaced for Vulkan there is no OpenGL context left to draw with, so the screen is let go.
 */
@Mixin(value = ClientModLoader.class, remap = false)
public class ClientModLoaderMixin {

    @Shadow
    private static EarlyLoadingScreenController earlyLoadingScreen;

    @Inject(method = "finish", at = @At("HEAD"))
    private static void radiante$dropReplacedEarlyScreen(CallbackInfo ci) {
        if (EarlyWindowState.replaced) {
            earlyLoadingScreen = null;
        }
    }
}
