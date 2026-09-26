package com.g2806.radiante.client.render;

/**
 * Implemented on {@code EntityRenderState} by a mixin: the id of the entity the state was taken from. Minecraft
 * builds a new state object every frame, so the object itself cannot tell one frame's entity from the next.
 */
public interface EntityIdHolder {

    /** The entity's id, or {@link Integer#MIN_VALUE} for a state no entity filled in. */
    int radiante$entityId();

    void radiante$setEntityId(int id);
}
