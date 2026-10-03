package com.g2806.radiante.mixin.gui;

import com.g2806.radiante.client.Showcase;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Answers {@code /radiante showcase} on the client. The three loaders register client commands differently, so the
 * command is caught where every typed command leaves for the server instead.
 */
@Mixin(ClientPacketListener.class)
public abstract class ShowcaseCommandMixin {

    @Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
    private void radiante$showcase(String command, CallbackInfo ci) {
        if (Showcase.handle(command)) {
            ci.cancel();
        }
    }
}
