package com.g2806.radiante.client.render;

import java.util.function.Consumer;
import net.minecraft.client.SectionUpdateTracker;

/** Implemented on {@link SectionUpdateTracker} by a mixin so the grid can be walked directly. */
public interface SectionTrackerAccess {

    void radiante$forEachSection(Consumer<SectionUpdateTracker.SectionDirtyState> action);

    int radiante$radius();

    int radiante$minSectionY();

    int radiante$maxSectionY();
}
