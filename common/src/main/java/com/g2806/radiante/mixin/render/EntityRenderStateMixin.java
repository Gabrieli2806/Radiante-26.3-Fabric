package com.g2806.radiante.mixin.render;

import com.g2806.radiante.client.render.EntityIdHolder;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EntityRenderState.class)
public class EntityRenderStateMixin implements EntityIdHolder {

    @Unique
    private int radiante$entityId = Integer.MIN_VALUE;

    @Override
    public int radiante$entityId() {
        return this.radiante$entityId;
    }

    @Override
    public void radiante$setEntityId(int id) {
        this.radiante$entityId = id;
    }
}
