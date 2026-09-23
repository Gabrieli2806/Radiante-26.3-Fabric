package com.g2806.radiante.mixin.world;

import com.g2806.radiante.client.render.ChunkManager;
import com.g2806.radiante.client.render.LevelExtractorAccess;
import com.g2806.radiante.client.render.RadianteRenderer;
import com.g2806.radiante.client.render.SectionTrackerAccess;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
public class LevelExtractorMixin implements LevelExtractorAccess {

    @Shadow
    private @Nullable SectionUpdateTracker sectionUpdateTracker;

    @Override
    public SectionUpdateTracker radiante$sectionUpdateTracker() {
        return this.sectionUpdateTracker;
    }

    @Inject(method = "allChanged", at = @At("TAIL"))
    private void radiante$onSectionGridCreated(CallbackInfo ci) {
        if (!RadianteRenderer.isActive() || this.sectionUpdateTracker == null) {
            return;
        }

        SectionTrackerAccess access = (SectionTrackerAccess) this.sectionUpdateTracker;
        ChunkManager.onTrackerCreated(this.sectionUpdateTracker, access.radiante$radius(),
            access.radiante$minSectionY(), access.radiante$maxSectionY());
    }
}
