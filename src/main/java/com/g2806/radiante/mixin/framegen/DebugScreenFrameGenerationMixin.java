package com.g2806.radiante.mixin.framegen;

import com.g2806.radiante.client.render.FrameGeneration;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugEntryFps;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft's own counter only sees the frames it rendered, so with frame generation on it barely moves while the
 * screen refreshes several times faster. This puts the frames that actually reach the display next to it.
 */
@Mixin(DebugEntryFps.class)
public class DebugScreenFrameGenerationMixin {

    @Inject(method = "display", at = @At("TAIL"))
    private void radiante$showGeneratedFrames(DebugScreenDisplayer displayer, @Nullable Level serverOrClientLevel,
        @Nullable LevelChunk clientChunk, @Nullable LevelChunk serverChunk, CallbackInfo callbackInfo) {
        int presented = FrameGeneration.presentedFrameRate();
        if (presented <= 0) {
            return;
        }

        int rendered = Minecraft.getInstance().getFps();
        int generated = Math.max(0, presented - rendered);
        displayer.addPriorityLine(String.format(Locale.ROOT, "%d fps on screen (%d generated, %dx)", presented,
            generated, FrameGeneration.multiplier()));
    }
}
