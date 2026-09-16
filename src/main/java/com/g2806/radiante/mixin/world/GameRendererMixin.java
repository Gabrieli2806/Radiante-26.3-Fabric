package com.g2806.radiante.mixin.world;

import com.g2806.radiante.client.render.RadianteRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Shadow
    public abstract net.minecraft.client.renderer.state.GameRenderState gameRenderState();

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V"))
    private void radiante$renderLevel(GameRenderer gameRenderer, Operation<Void> original) {
        if (!RadianteRenderer.isActive()) {
            original.call(gameRenderer);
            return;
        }

        RadianteRenderer.renderLevel(gameRenderer, this.gameRenderState().levelRenderState);
    }
}
