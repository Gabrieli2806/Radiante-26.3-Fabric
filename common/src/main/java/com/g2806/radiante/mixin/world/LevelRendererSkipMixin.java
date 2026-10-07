package com.g2806.radiante.mixin.world;

import com.g2806.radiante.client.render.RadianteRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ChunkLoadingRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While Radiante traces the world, LevelRenderer.render is still called every frame (GameRendererMixin), so that the
 * per-frame work other mods do at its head keeps running: Not Enough Animations hands its animator the frame's
 * partial tick there, and without it players freeze. The high priority applies this mixin after everyone else's,
 * which places this callback after theirs at the head of the method.
 *
 * <p>From there it goes one of two ways. With mod overlays on (the default) the tracer's work for the frame is done
 * right here, and Minecraft's level render then goes on with nothing of its own left to draw - no sky, terrain,
 * entities, particles, clouds or weather - over the traced picture and against the traced depth. Its passes are
 * where other mods draw into the world (schematics, selection boxes, waypoint beams, guides), so all of that lands
 * on the traced world, hidden where the world is in front. With mod overlays off the level render is stopped
 * outright, and nothing but the traced world shows.
 */
@Mixin(value = LevelRenderer.class, priority = 2000)
public class LevelRendererSkipMixin {

    @Shadow
    @Final
    private GameRenderer gameRenderer;

    @Shadow
    @Final
    private LevelRenderState levelRenderState;

    @Shadow
    @Final
    private LevelTargetBundle targets;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void radiante$skipWhenTracing(GraphicsResourceAllocator resourceAllocator,
        net.minecraft.client.DeltaTracker deltaTracker, boolean renderOutline, CameraRenderState cameraState,
        org.joml.Matrix4fc modelView, GpuBufferSlice terrainFog, Vector4f fogColor, boolean shouldRenderSky,
        CallbackInfo ci) {
        if (!RadianteRenderer.isTracingLevel()) {
            return;
        }
        if (!RadianteRenderer.usesOverlayPass() || !RadianteRenderer.prepareFrame(this.gameRenderer,
            this.levelRenderState)) {
            ci.cancel();
            return;
        }
        // The tracer has read all of these; emptied, Minecraft submits and draws none of them again.
        this.levelRenderState.entityRenderStates.clear();
        this.levelRenderState.blockEntityRenderStates.clear();
        this.levelRenderState.blockBreakingRenderStates.clear();
        this.levelRenderState.weatherRenderState.reset();
        this.levelRenderState.cloudColor = 0;
    }

    /** No block outline of Minecraft's: the tracer draws its own. */
    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private boolean radiante$noOutlineOverTrace(boolean renderOutline) {
        return RadianteRenderer.isOverlayFrame() ? false : renderOutline;
    }

    /** No sky of Minecraft's: it would be drawn over the traced one. */
    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private boolean radiante$noSkyOverTrace(boolean shouldRenderSky) {
        return RadianteRenderer.isOverlayFrame() ? false : shouldRenderSky;
    }

    /**
     * The traced picture and its depth go into Minecraft's target here: after Minecraft's own clear of it and before
     * its main pass, which is where everything other mods add is drawn.
     */
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/LevelRenderer;addMainPass(Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/util/profiling/ProfilerFiller;Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;)V"))
    private void radiante$traceBeforeMainPass(LevelRenderer levelRenderer, FrameGraphBuilder frame,
        FeatureRenderDispatcher.PreparedFrame featureFrame, GpuBufferSlice terrainFog, LevelRenderState state,
        net.minecraft.util.profiling.ProfilerFiller profiler, ChunkSectionsToRender chunkSectionsToRender,
        Operation<Void> original) {
        if (RadianteRenderer.isOverlayFrame()) {
            FramePass pass = frame.addPass("radiante");
            this.targets.main = pass.readsAndWrites(this.targets.main);
            pass.executes(RadianteRenderer::submitFrame);
        }
        original.call(levelRenderer, frame, featureFrame, terrainFog, state, profiler, chunkSectionsToRender);
    }

    /** Minecraft compiles no terrain of its own while the tracer has it. */
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/LevelRenderer;compileSections(Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"))
    private void radiante$noTerrainCompile(LevelRenderer levelRenderer, CameraRenderState cameraState,
        Operation<Void> original) {
        if (!RadianteRenderer.isOverlayFrame()) {
            original.call(levelRenderer, cameraState);
        }
    }

    /** Nor does it work out which of its sections are in view; the tracer keeps that graph fed itself. */
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/SectionOcclusionGraph;update(Lnet/minecraft/client/renderer/state/level/CameraRenderState;ILnet/minecraft/client/renderer/state/level/ChunkLoadingRenderState;)V"))
    private void radiante$noOcclusionUpdate(SectionOcclusionGraph graph, CameraRenderState cameraState, int fov,
        ChunkLoadingRenderState chunkLoading, Operation<Void> original) {
        if (!RadianteRenderer.isOverlayFrame()) {
            original.call(graph, cameraState, fov, chunkLoading);
        }
    }

    /** Particles are traced. */
    @WrapOperation(method = "submitFeatures", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/state/level/ParticlesRenderState;submit(Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"))
    private void radiante$noParticlesOverTrace(ParticlesRenderState particles, SubmitNodeCollector collector,
        CameraRenderState cameraState, Operation<Void> original) {
        if (!RadianteRenderer.isOverlayFrame()) {
            original.call(particles, collector, cameraState);
        }
    }

    /** Debug gizmos (hitboxes, chunk borders) are traced too; see OverlayLines. */
    @WrapOperation(method = "submitFeatures", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/gizmos/DrawableGizmoPrimitives;submit(Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;Z)V"))
    private void radiante$noGizmosOverTrace(DrawableGizmoPrimitives primitives, SubmitNodeCollector collector,
        CameraRenderState cameraState, boolean alwaysOnTop, Operation<Void> original) {
        if (!RadianteRenderer.isOverlayFrame()) {
            original.call(primitives, collector, cameraState, alwaysOnTop);
        }
    }
}
