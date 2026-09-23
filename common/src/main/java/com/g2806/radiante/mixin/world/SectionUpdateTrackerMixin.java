package com.g2806.radiante.mixin.world;

import com.g2806.radiante.client.render.SectionTrackerAccess;
import java.util.function.Consumer;
import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.client.SectionUpdateTracker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(SectionUpdateTracker.class)
public class SectionUpdateTrackerMixin implements SectionTrackerAccess {

    @Shadow
    @Final
    private RotatingSectionStorage<SectionUpdateTracker.SectionDirtyState> storage;

    @Override
    public void radiante$forEachSection(Consumer<SectionUpdateTracker.SectionDirtyState> action) {
        this.storage.forEach(action);
    }

    @Override
    public int radiante$radius() {
        return this.storage.radius();
    }

    @Override
    public int radiante$minSectionY() {
        return this.storage.minY();
    }

    @Override
    public int radiante$maxSectionY() {
        return this.storage.maxY();
    }
}
