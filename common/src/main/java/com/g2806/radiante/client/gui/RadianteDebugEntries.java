package com.g2806.radiante.client.gui;

import com.g2806.radiante.client.RadianteClient;
import com.g2806.radiante.client.option.Options;
import com.g2806.radiante.client.pipeline.Pipeline;
import com.g2806.radiante.client.render.FrameGeneration;
import com.g2806.radiante.client.render.RadianteRenderer;
import com.g2806.radiante.mixin.debug.DebugScreenEntriesInvoker;
import com.g2806.radiante.platform.RadiantePlatform;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/**
 * Radiante's lines on the F3 screen, each its own debug entry so it can be hidden from the debug options screen
 * (F3 + F6) like any of Minecraft's.
 */
public final class RadianteDebugEntries {

    public static final Identifier UPSCALER = Identifier.fromNamespaceAndPath("radiante", "upscaler");
    public static final Identifier REFLEX = Identifier.fromNamespaceAndPath("radiante", "reflex");
    public static final Identifier FRAME_GENERATION = Identifier.fromNamespaceAndPath("radiante", "frame_generation");
    /** Shown in the overlay until the player hides them. */
    public static final List<Identifier> ALL = List.of(UPSCALER, REFLEX, FRAME_GENERATION);

    private RadianteDebugEntries() {
    }

    public static void register() {
        DebugScreenEntriesInvoker.radiante$register(UPSCALER, new Entry() {
            @Override
            void lines(DebugScreenDisplayer displayer) {
                displayer.addLine("Upscaler: " + Pipeline.describeUpscaler());
            }
        });
        DebugScreenEntriesInvoker.radiante$register(REFLEX, new Entry() {
            @Override
            void lines(DebugScreenDisplayer displayer) {
                String reflex;
                if (!RadiantePlatform.INSTANCE.supportsStreamline()) {
                    reflex = "unavailable";
                } else if (!Options.reflex) {
                    reflex = "Off";
                } else {
                    reflex = RadianteClient.streamlineLoaded() ? "On" : "On (restart to apply)";
                }
                displayer.addLine("NVIDIA Reflex: " + reflex);
            }
        });
        DebugScreenEntriesInvoker.radiante$register(FRAME_GENERATION, new Entry() {
            @Override
            void lines(DebugScreenDisplayer displayer) {
                // Minecraft's own counter only sees the frames it rendered; with frame generation on the screen
                // refreshes several times faster.
                int presented = FrameGeneration.presentedFrameRate();
                if (presented <= 0) {
                    return;
                }
                int rendered = Minecraft.getInstance().getFps();
                int generated = Math.max(0, presented - rendered);
                displayer.addLine(String.format(Locale.ROOT, "%d fps on screen (%d generated, %dx)", presented,
                    generated, FrameGeneration.multiplier()));
            }
        });
    }

    private abstract static class Entry implements DebugScreenEntry {

        abstract void lines(DebugScreenDisplayer displayer);

        @Override
        public void display(DebugScreenDisplayer displayer, @Nullable Level serverOrClientLevel,
            @Nullable LevelChunk clientChunk, @Nullable LevelChunk serverChunk) {
            if (RadianteRenderer.isRayTracingEnabled()) {
                this.lines(displayer);
            }
        }
    }
}
