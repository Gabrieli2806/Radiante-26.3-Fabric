package com.g2806.radiante.mixin.world;

import com.g2806.radiante.client.render.ChunkManager;
import com.g2806.radiante.client.render.RadianteRenderer;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
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
    @Final
    private net.minecraft.client.renderer.DebugCrosshairRenderer debugCrosshairRenderer;

    @Shadow
    @Final
    private net.minecraft.client.renderer.fog.FogRenderer fogRenderer;

    @Shadow
    @Final
    private com.mojang.blaze3d.pipeline.RenderTarget mainRenderTarget;

    @Shadow
    @Final
    private net.minecraft.client.renderer.Projection hudProjection;

    @Shadow
    @Final
    private net.minecraft.client.renderer.ProjectionMatrixBuffer hud3dProjectionMatrixBuffer;

    /**
     * The three coloured axes F3 puts at the crosshair. Minecraft draws them at the end of the pass that draws the
     * hand, which is skipped while tracing because the hand is traced; they are drawn here the same way, over the
     * traced picture.
     */
    private void radiante$debugCrosshair() {
        net.minecraft.client.renderer.state.GameRenderState state = this.gameRenderState();
        if (!state.levelRenderState.render3dCrosshair || !state.optionsRenderState.cameraType.isFirstPerson()
            || state.guiRenderState.isHudHidden || this.mainRenderTarget.getDepthTexture() == null) {
            return;
        }
        net.minecraft.client.renderer.state.level.CameraRenderState cameraState =
            state.levelRenderState.cameraRenderState;
        this.hudProjection.setupPerspective(0.05F, cameraState.depthFar, cameraState.hudFov,
            state.windowRenderState.width, state.windowRenderState.height);
        com.mojang.blaze3d.systems.RenderSystem.setProjectionMatrix(
            this.hud3dProjectionMatrixBuffer.getBuffer(this.hudProjection),
            com.mojang.blaze3d.ProjectionType.PERSPECTIVE);
        // In front of everything, as in vanilla, where the hand pass starts from a cleared depth.
        com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder()
            .clearDepthTexture(this.mainRenderTarget.getDepthTexture(), 0.0);
        this.debugCrosshairRenderer.render(cameraState, state.windowRenderState.guiScale,
            this.mainRenderTarget.getColorTextureView(), this.mainRenderTarget.getDepthTextureView());
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V"))
    private void radiante$renderLevel(GameRenderer gameRenderer, Operation<Void> original) {
        if (!RadianteRenderer.isRayTracingEnabled()) {
            original.call(gameRenderer);
            return;
        }

        // Vanilla's level render still runs, so the per-frame hooks other mods put on it (at its start and end,
        // and at the start of LevelRenderer.render) run on every loader as they would without Radiante. What it
        // would draw is stopped: the level (LevelRendererSkipMixin) and the hand and screen effects (below), all
        // of which the tracer draws instead.
        RadianteRenderer.setTracingLevel(true);
        try {
            original.call(gameRenderer);
        } finally {
            RadianteRenderer.setTracingLevel(false);
        }
        // With mod overlays on the frame was traced from inside that level render (LevelRendererSkipMixin), which
        // then went on over the traced picture; otherwise it is traced now.
        if (!RadianteRenderer.wasFrameTraced()) {
            RadianteRenderer.renderLevel(gameRenderer, this.gameRenderState().levelRenderState);
            radiante$debugCrosshair();
        }
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V"))
    private void radiante$skipHudWhenTracing(GameRenderer gameRenderer,
        net.minecraft.client.renderer.state.level.CameraRenderState cameraState,
        net.minecraft.client.renderer.state.level.PlayerRenderState playerState,
        net.minecraft.client.renderer.state.OptionsRenderState optionsState, boolean consistentDepthRequired,
        Operation<Void> original) {
        if (!RadianteRenderer.isTracingLevel()) {
            original.call(gameRenderer, cameraState, playerState, optionsState, consistentDepthRequired);
        } else {
            // What this pass does besides drawing: it ends the world fog. Minecraft level render switches that fog
            // on when it runs over the traced picture, and left on it reached the screens drawn afterwards - item
            // icons in an inventory came out as flat shapes in the fog colour.
            com.mojang.blaze3d.systems.RenderSystem.setShaderFog(this.fogRenderer.getBuffer(
                net.minecraft.client.renderer.fog.FogRenderer.FogMode.NONE));
            if (RadianteRenderer.wasFrameTraced()) {
                // The traced picture is already in the target; without the overlay pass it is not yet (see above).
                radiante$debugCrosshair();
            }
        }
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
