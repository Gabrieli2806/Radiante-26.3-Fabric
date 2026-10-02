package com.g2806.radiante.client.render;

import com.g2806.radiante.client.RadianteClient;
import com.g2806.radiante.client.hdr.HdrDisplay;
import com.g2806.radiante.client.option.Options;
import com.g2806.radiante.client.proxy.vulkan.FrameGenerationProxy;
import com.g2806.radiante.client.proxy.vulkan.RendererProxy;

/**
 * Frame generation runs in the renderer itself (DLSS through NGX on NVIDIA, FSR elsewhere), so it needs no
 * Streamline and no restart. Streamline is still what provides Reflex, which paces frames from the markers below.
 */
public final class FrameGeneration {

    public static final int SIMULATION_START = 0;
    public static final int SIMULATION_END = 1;
    public static final int RENDER_SUBMIT_START = 2;
    public static final int RENDER_SUBMIT_END = 3;
    public static final int PRESENT_START = 4;
    public static final int PRESENT_END = 5;

    private static boolean active;
    /** Whether the swapchain was last configured with frame generation on; a change asks for a new one. */
    private static boolean configuredActive;
    private static boolean configuredOnce;

    private FrameGeneration() {
    }

    /** True while frames are being generated. Off with HDR output, which presents its own way. */
    public static boolean isActive() {
        return active && !HdrDisplay.isActive();
    }

    /** How many frames this GPU can generate per rendered one; 0 when it cannot. */
    public static int maxGeneratedFrames() {
        try {
            return RendererProxy.maxGeneratedFrames();
        } catch (UnsatisfiedLinkError e) {
            return 0;
        }
    }

    /** "DLSS", "FSR" or null, for the settings and the debug screen. */
    public static String backendName() {
        try {
            return switch (FrameGenerationProxy.backend()) {
                case FrameGenerationProxy.BACKEND_DLSS -> "DLSS";
                case FrameGenerationProxy.BACKEND_FSR -> "FSR";
                default -> null;
            };
        } catch (UnsatisfiedLinkError e) {
            return null;
        }
    }

    /** Hands the player's choice of backend over; the frame count is clamped to what that backend allows. */
    public static void applyBackend() {
        try {
            FrameGenerationProxy.setPreference(Options.frameGenerationBackend);
        } catch (UnsatisfiedLinkError ignored) {
            // No renderer.
        }
    }

    /** Waits for the present thread to show the frames it still holds; nothing to wait for without a renderer. */
    public static void waitPresentIdle() {
        try {
            FrameGenerationProxy.waitPresentIdle();
        } catch (UnsatisfiedLinkError ignored) {
            // No renderer.
        }
    }

    /** DLSS frame generation's limit on this GPU (it may not be the backend in use); 0 without it. */
    public static int dlssMaxGeneratedFrames() {
        try {
            return FrameGenerationProxy.maxGeneratedFramesFor(FrameGenerationProxy.BACKEND_DLSS);
        } catch (UnsatisfiedLinkError e) {
            return 0;
        }
    }

    /** Whether both DLSS and FSR frame generation run here, so there is a choice to offer. */
    public static boolean hasBackendChoice() {
        try {
            return FrameGenerationProxy.isAvailable(FrameGenerationProxy.BACKEND_DLSS)
                && FrameGenerationProxy.isAvailable(FrameGenerationProxy.BACKEND_FSR);
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    public static void setGeneratedFrames(int generatedFrames) {
        applyBackend();
        int frames = Options.frameGeneration ? Math.max(0, Math.min(generatedFrames, maxGeneratedFrames())) : 0;
        RendererProxy.setGeneratedFrames(frames);
        active = frames > 0;
    }

    /** Called when the swapchain is (re)built. */
    public static void noteSwapchainConfigured() {
        configuredActive = isActive();
        configuredOnce = true;
        if (configuredActive) {
            RadianteClient.LOGGER.info("Frame generation on: a present thread spreads the generated frames over one rendered frame");
        }
    }

    /** True once after frame generation was switched on or off, until the swapchain follows. */
    public static boolean needsReconfigure() {
        return configuredOnce && configuredActive != isActive();
    }

    /** Frames reaching the display each second, real plus generated; 0 while frame generation is off. */
    public static int presentedFrameRate() {
        return isActive() ? RendererProxy.presentedFrameRate() : 0;
    }

    /** How many frames are shown per rendered one, so 4 while generating three. */
    public static int multiplier() {
        return isActive() ? Math.min(Options.generatedFrames, Math.max(1, maxGeneratedFrames())) + 1 : 1;
    }

    public static void beginClientFrame() {
        if (reflexActive()) {
            RendererProxy.beginFrameGenerationFrame();
        }
    }

    public static void marker(int marker) {
        if (reflexActive()) {
            RendererProxy.frameGenerationMarker(marker);
        }
    }

    /** Reflex paces frames from the markers above (VK_NV_low_latency2, on NVIDIA GPUs). */
    private static boolean reflexActive() {
        return Options.reflex && RadianteRenderer.isActive() && RendererProxy.isReflexSupported();
    }

    /** Whether this GPU has NVIDIA Reflex; false before the renderer is up. */
    public static boolean isReflexSupported() {
        return RadianteRenderer.isActive() && RendererProxy.isReflexSupported();
    }

    /** Hands the player's Reflex choice to the renderer; it takes effect on the next frame. */
    public static void applyReflex() {
        if (RadianteRenderer.isActive()) {
            RendererProxy.setReflexEnabled(Options.reflex);
        }
    }
}
