package com.g2806.radiante.mixin.world;

import com.g2806.radiante.client.render.ChunkManager;
import com.g2806.radiante.client.render.LevelRendererGizmoAccess;
import com.g2806.radiante.client.render.RadianteRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.SimpleGizmoCollector;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin implements LevelRendererGizmoAccess {

    @Shadow
    private @Nullable SkyRenderer skyRenderer;

    @Shadow
    @Final
    private TextureManager textureManager;

    @Shadow
    @Final
    private AtlasManager atlasManager;

    @Shadow
    @Final
    private GameRenderer gameRenderer;

    @Shadow
    @Final
    private SimpleGizmoCollector renderThreadGizmos;

    @Override
    public SimpleGizmoCollector radiante$renderThreadGizmos() {
        return this.renderThreadGizmos;
    }

    @Shadow
    @Final
    private net.minecraft.client.renderer.CloudRenderer cloudRenderer;

    @Override
    public net.minecraft.client.renderer.CloudRenderer radiante$cloudRenderer() {
        return this.cloudRenderer;
    }

    /**
     * Vanilla creates the sky renderer inside its own render pass, which the ray tracer replaces. It is
     * still needed because its extraction fills the sky state the sky shader reads.
     */
    @Inject(method = "skyRenderer", at = @At("HEAD"), cancellable = true)
    private void radiante$ensureSkyRenderer(CallbackInfoReturnable<SkyRenderer> cir) {
        if (RadianteRenderer.isActive() && this.skyRenderer == null) {
            this.skyRenderer = new SkyRenderer(this.textureManager, this.atlasManager,
                this.gameRenderer.mainRenderTarget());
            cir.setReturnValue(this.skyRenderer);
        }
    }

    /**
     * Entity and block entity extraction ask this; answer from the renderer's own sections. With ray tracing
     * switched off the world is Minecraft's to draw again, and answering for it would leave it convinced that
     * sections it never compiled are ready, so almost nothing would be drawn.
     */
    @Inject(method = "isSectionCompiledAndVisible", at = @At("HEAD"), cancellable = true)
    private void radiante$sectionReady(BlockPos pos, long fadeDuration, CallbackInfoReturnable<Boolean> cir) {
        if (RadianteRenderer.isRayTracingEnabled()) {
            cir.setReturnValue(ChunkManager.isSectionReady(pos));
        }
    }
}
