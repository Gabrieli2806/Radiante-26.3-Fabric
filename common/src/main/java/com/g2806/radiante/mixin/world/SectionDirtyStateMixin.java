package com.g2806.radiante.mixin.world;

import com.g2806.radiante.client.render.ChunkManager;
import net.minecraft.client.SectionUpdateTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells the chunk manager which sections changed, so it can react to exactly those instead of walking the whole
 * tracker every frame. At a 32 chunk render distance that walk covered about a hundred thousand sections a frame.
 */
@Mixin(SectionUpdateTracker.SectionDirtyState.class)
public class SectionDirtyStateMixin {

    @Shadow
    private long sectionNode;

    @Inject(method = "setDirty", at = @At("TAIL"))
    private void radiante$onDirty(boolean fromPlayer, CallbackInfo ci) {
        ChunkManager.onSectionChanged(this.sectionNode);
    }

    /** A new section scrolled into this slot of the tracker's rotating grid; it starts out dirty. */
    @Inject(method = "setSectionNode", at = @At("TAIL"))
    private void radiante$onMoved(long node, CallbackInfo ci) {
        ChunkManager.onSectionChanged(this.sectionNode);
    }
}
