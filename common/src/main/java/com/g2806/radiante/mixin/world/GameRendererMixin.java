package com.g2806.radiante.mixin.world;

import com.g2806.radiante.client.render.ChunkManager;
import com.g2806.radiante.client.render.RadianteRenderer;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    /** Frames of traced world before the world's picture is worth taking; the loading screen is gone by then. */
    private static final int WORLD_ICON_MIN_FRAMES = 120;

    @Shadow
    public abstract net.minecraft.client.renderer.state.GameRenderState gameRenderState();

    @Shadow
    @org.spongepowered.asm.mixin.Final
    private com.mojang.blaze3d.resource.CrossFrameResourcePool resourcePool;

    @Shadow
    @org.spongepowered.asm.mixin.Final
    private net.minecraft.client.renderer.fog.FogRenderer fogRenderer;

    @Shadow
    @org.spongepowered.asm.mixin.Final
    private Minecraft minecraft;

    @Shadow
    protected abstract boolean shouldRenderBlockOutline();

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V"))
    private void radiante$renderLevel(GameRenderer gameRenderer, Operation<Void> original) {
        if (!RadianteRenderer.isRayTracingEnabled()) {
            original.call(gameRenderer);
            return;
        }

        // Vanilla's level render is called as it would be and stopped at its head (LevelRendererSkipMixin), so
        // that the per-frame hooks other mods put there still run on every loader.
        var cameraState = this.gameRenderState().levelRenderState.cameraRenderState;
        this.fogRenderer.updateBuffer(cameraState.fogData);
        RadianteRenderer.setTracingLevel(true);
        try {
            this.minecraft.levelRenderer.render(this.resourcePool, this.shouldRenderBlockOutline(), cameraState,
                this.fogRenderer.getBuffer(net.minecraft.client.renderer.fog.FogRenderer.FogMode.WORLD),
                cameraState.fogData.color, true, false);
        } finally {
            RadianteRenderer.setTracingLevel(false);
        }
        RadianteRenderer.renderLevel(gameRenderer, this.gameRenderState().levelRenderState);
    }

    /**
     * The picture a world shows in the world list is taken once, a second or so after joining, and only when
     * Minecraft has drawn more than ten sections of terrain itself. With ray tracing on it draws none of them -
     * Radiante has the terrain - so the count stayed at zero, the picture was never taken and the world was left
     * without one. The renderer's own section count answers for it instead.
     */
    @ModifyExpressionValue(method = "takeAutoScreenshot", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;countRenderedSections()I"))
    private int radiante$countSectionsForWorldIcon(int original) {
        if (!RadianteRenderer.isRayTracingEnabled()) {
            return original;
        }

        // Only once the world has actually been on screen for a moment: the first frames after joining still show
        // the loading screen over it, and a picture taken then is a flat grey square.
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui.screen() != null || RadianteRenderer.levelFrames() < WORLD_ICON_MIN_FRAMES) {
            return original;
        }

        return Math.max(original, ChunkManager.compiledSectionCount());
    }

    @ModifyExpressionValue(method = "takeAutoScreenshot", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/LevelRenderer;hasRenderedAllSections()Z"))
    private boolean radiante$builtAllSectionsForWorldIcon(boolean original) {
        return RadianteRenderer.isRayTracingEnabled() ? ChunkManager.hasBuiltEverything() : original;
    }
}
