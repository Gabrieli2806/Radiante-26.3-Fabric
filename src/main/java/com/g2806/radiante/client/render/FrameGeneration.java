package com.g2806.radiante.client.render;

import com.g2806.radiante.client.RadianteClient;
import com.g2806.radiante.client.option.Options;
import com.g2806.radiante.client.proxy.vulkan.RendererProxy;

/**
 * Frame generation needs to know where each part of a frame begins and ends, because Reflex paces the generated
 * frames from those markers.
 */
public final class FrameGeneration {

    public static final int SIMULATION_START = 0;
    public static final int SIMULATION_END = 1;
    public static final int RENDER_SUBMIT_START = 2;
    public static final int RENDER_SUBMIT_END = 3;
    public static final int PRESENT_START = 4;
    public static final int PRESENT_END = 5;

    private static boolean active;

    private FrameGeneration() {
    }

    /** True once Streamline is loaded and the player picked a multiplier. */
    public static boolean isActive() {
        return active;
    }

    public static void setGeneratedFrames(int generatedFrames) {
        if (!RadianteClient.streamlineLoaded()) {
            active = false;
            return;
        }
        // Zero has to reach the renderer as well: that is how frame generation is switched off without a restart.
        int frames = Options.frameGeneration ? generatedFrames : 0;
        RendererProxy.setGeneratedFrames(frames);
        active = frames > 0;
    }

    /** Frames reaching the display each second, real plus generated; 0 while frame generation is off. */
    public static int presentedFrameRate() {
        return active ? RendererProxy.presentedFrameRate() : 0;
    }

    /** How many frames are shown per rendered one, so 6 while generating five. */
    public static int multiplier() {
        return active ? Options.generatedFrames + 1 : 1;
    }

    public static void beginClientFrame() {
        if (active) {
            RendererProxy.beginFrameGenerationFrame();
        }
    }

    public static void marker(int marker) {
        if (active) {
            RendererProxy.frameGenerationMarker(marker);
        }
    }
}
