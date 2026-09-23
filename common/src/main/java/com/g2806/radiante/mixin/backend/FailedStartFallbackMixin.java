package com.g2806.radiante.mixin.backend;

import com.g2806.radiante.client.option.Options;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * After a start that never finished, Minecraft switches the preferred graphics API to OpenGL, and that choice is
 * saved with the other options on exit. This mod reads a saved OpenGL as the player's own choice, so one crash left
 * ray tracing off for good. The fallback still applies for the session it protects, but is no longer saved.
 */
@Mixin(Minecraft.class)
public class FailedStartFallbackMixin {

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/OptionInstance;set(Ljava/lang/Object;)V"))
    private void radiante$fallBackForThisSessionOnly(OptionInstance<?> option, Object value,
        Operation<Void> original) {
        if (value == PreferredGraphicsApi.OPENGL) {
            Options.openGlAfterFailedStart = true;
            return;
        }
        original.call(option, value);
    }
}
