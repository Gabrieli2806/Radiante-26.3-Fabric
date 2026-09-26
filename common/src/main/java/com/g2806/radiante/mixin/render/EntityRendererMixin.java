package com.g2806.radiante.mixin.render;

import com.g2806.radiante.client.render.EntityIdHolder;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Notes on each render state which entity it was taken from; see EntityIdHolder. */
@Mixin(EntityRenderer.class)
public class EntityRendererMixin {

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void radiante$noteEntity(Entity entity, EntityRenderState state, float partialTicks, CallbackInfo ci) {
        ((EntityIdHolder) state).radiante$setEntityId(entity.getId());
    }
}
