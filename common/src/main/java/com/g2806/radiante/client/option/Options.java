package com.g2806.radiante.client.option;

import com.g2806.radiante.client.RadianteClient;
import com.g2806.radiante.client.pipeline.Pipeline;
import com.g2806.radiante.client.proxy.vulkan.TextureProxy;
import com.g2806.radiante.client.proxy.world.ChunkProxy;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public class Options {

    public static final String OPTION_PROPERTIES = "options.properties";

    public static int maxFps = 260;
    public static int inactivityFpsLimit = 260;
    public static boolean vsync = true;
    public static int chunkBuildingBatchSize = 12;
    public static int chunkBuildingTotalBatches = 12;
    public static int chunkBuildingThreads = getDefaultChunkBuildingThreads();
    public static boolean collectChunkEmission = true;
    /**
     * Everything Radiante writes to the log is diagnostic. Off by default so a player's log stays theirs; turned
     * on when someone is reporting a problem and the detail is worth having.
     */
    public static boolean debugLogging = false;
    /** Per-biome haze in the overworld: warm dust over deserts, thick green air over swamps, and so on. */
    public static boolean biomeFog = true;
    /**
     * Whether the player casts a shadow (and shows up in reflections) while the camera is in first person.
     * Minecraft does not draw the player at all then, so nothing of them would reach the world without this.
     */
    public static boolean firstPersonShadow = true;
    /**
     * Sun and moon cross the sky exactly as in vanilla, straight overhead from east to west. Off, their path leans
     * ten degrees to the south, so noon shadows are not all straight down.
     */
    public static boolean vanillaSunPath = true;
    /** Sun and moon sprites keep vanilla's fixed orientation instead of turning as they cross the sky. */
    public static boolean vanillaCelestialOrientation = true;
    /**
     * Surfaces pick a nearby light block and trace one shadow ray to it, instead of waiting for a random bounce to
     * find it. Much less noise from torches, lava and lamps; needs Block Emission for the list of lights.
     */
    public static boolean blockLightSampling = true;
    /** A torch, lantern or other light held in either hand lights up the surroundings. */
    public static boolean heldItemLight = true;
    /** The outline around the block under the crosshair. */
    public static boolean blockOutline = false;
    /** Carved (parallax) resource pack faces are see-through at the outer edges of their block. */
    public static boolean parallaxTransparentEdges = false;
    /** Light and shadow snap to the texture's pixels, a retro blocky look. Off: smooth, as vanilla-like. */
    public static boolean pixelLighting = false;
    /** Brightness of sunlight and the daytime sky, in percent of the shader pack's own. */
    public static int dayBrightness = 25;
    /** Brightness of moonlight and the night sky, in percent of the shader pack's own. */
    public static int nightBrightness = 35;
    /** Brightness of light emitting blocks and held lights, in percent. */
    public static int emissionBrightness = 12;
    /** Brightness of what the player holds - its glow and the light it casts - in percent. */
    public static int heldLightBrightness = 12;
    /** How thick the volumetric fog (the air that shows light shafts) is, in percent of the tuned values. */
    public static int volumetricFogStrength = 100;
    /** How thick the biome haze is, in percent of the tuned values. */
    public static int biomeFogStrength = 100;
    /**
     * HDR display output: the window becomes a 16-bit float scRGB swapchain and the traced world keeps its
     * highlights above SDR white. Needs HDR on in the operating system; the window format is picked at startup,
     * so a change takes effect after a restart.
     */
    public static boolean hdrOutput = false;
    /** HDR: the brightness of the display's brightest white, in nits. */
    public static int hdrPeakNits = 1000;
    /** HDR: the brightness SDR white (menus, text, the world's midtones) is shown at, in nits. */
    public static int hdrPaperWhiteNits = 200;
    /** Loading Streamline replaces Minecraft's Vulkan loader, so it only happens when the player asks for it. */
    public static boolean frameGeneration = false;
    /** Frames DLSS generates per rendered frame: 0 is off, 1 is 2x, up to 5 for 6x. */
    public static int generatedFrames = 1;
    /** NVIDIA Reflex low latency mode. Loads Streamline at startup, so turning it on takes a restart. */
    public static boolean reflex = false;

    /**
     * The cloud setting Distant Horizons switched off, kept until its override toggle is off again and the setting
     * is put back; empty when there is nothing to put back. See DistantHorizonsCompat.
     */
    public static String cloudsBeforeDistantHorizons = "";

    /** The look and lighting settings back to a fresh install's; pipeline and restart-bound ones are the screen's. */
    public static void resetVisualDefaults() {
        blockLightSampling = true;
        heldItemLight = true;
        blockOutline = false;
        parallaxTransparentEdges = false;
        pixelLighting = false;
        dayBrightness = 25;
        nightBrightness = 35;
        emissionBrightness = 12;
        heldLightBrightness = 12;
        volumetricFogStrength = 100;
        vanillaSunPath = true;
        vanillaCelestialOrientation = true;
    }

    /** Ray tracing can be switched off with a key, which hands the world back to Minecraft's own renderer. */
    public static boolean rayTracingEnabled = true;
    /** Set when the player chooses to run on OpenGL, where this renderer cannot work at all. */
    public static boolean useOpenGl = false;
    /** OpenGL for this session only, after Minecraft detected a failed start; never saved. */
    public static boolean openGlAfterFailedStart = false;

    /**
     * Half the logical cores. Section building shares the machine with the render thread and, in singleplayer,
     * the integrated server generating those same chunks; measured on a 24-thread CPU, 20 builders dropped the frame
     * rate from 120 to about 65 for several seconds after every teleport, while 4 held it above 110 and loaded the
     * area as quickly. More threads than this only take time away from rendering.
     */
    public static int getMaxChunkBuildingThreads() {
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        boolean is64Bits = System.getProperty("os.arch", "").contains("64");
        return Math.max(1, is64Bits ? availableProcessors / 2 : Math.min(availableProcessors, 4));
    }

    public static int clampChunkBuildingThreads(int chunkBuildingThreads) {
        return Math.max(1, Math.min(chunkBuildingThreads, getMaxChunkBuildingThreads()));
    }

    public static int getDefaultChunkBuildingThreads() {
        return clampChunkBuildingThreads(Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() / 4)));
    }

    /** One saved setting: its key in options.properties, how to write it out and how to take it back. */
    private record Entry(String key, Supplier<String> value, Consumer<String> load) {
    }

    private static Entry bool(String key, BooleanSupplier value, Consumer<Boolean> load) {
        return new Entry(key, () -> String.valueOf(value.getAsBoolean()), text -> load.accept(Boolean.parseBoolean(text)));
    }

    private static Entry number(String key, IntSupplier value, IntConsumer load) {
        return new Entry(key, () -> String.valueOf(value.getAsInt()), text -> load.accept(Integer.parseInt(text.trim())));
    }

    /**
     * Every saved setting, in file order. Reading and writing both go through this list, so a setting is either
     * in it - and round-trips - or not saved at all; nothing can be written and never read back again.
     */
    private static final List<Entry> ENTRIES = List.of(
        number("maxFps", () -> maxFps, v -> setMaxFps(v, false)),
        number("inactivityFpsLimit", () -> inactivityFpsLimit, v -> setInactivityFpsLimit(v, false)),
        bool("vsync", () -> vsync, v -> setVsync(v, false)),
        number("chunkBuildingBatchSize", () -> chunkBuildingBatchSize, v -> setChunkBuildingBatchSize(v, false)),
        number("chunkBuildingTotalBatches", () -> chunkBuildingTotalBatches,
            v -> setChunkBuildingTotalBatches(v, false)),
        number("chunkBuildingThreads", () -> chunkBuildingThreads, v -> setChunkBuildingThreads(v, false)),
        bool("collectChunkEmission", () -> collectChunkEmission, v -> setCollectChunkEmission(v, false)),
        bool("debugLogging", () -> debugLogging, v -> setDebugLogging(v, false)),
        bool("biomeFog", () -> biomeFog, v -> biomeFog = v),
        number("volumetricFogStrength", () -> volumetricFogStrength,
            v -> volumetricFogStrength = Math.max(0, Math.min(400, v))),
        number("biomeFogStrength", () -> biomeFogStrength, v -> biomeFogStrength = Math.max(0, Math.min(400, v))),
        bool("firstPersonShadow", () -> firstPersonShadow, v -> firstPersonShadow = v),
        bool("heldItemLight", () -> heldItemLight, v -> heldItemLight = v),
        bool("blockOutline", () -> blockOutline, v -> blockOutline = v),
        bool("parallaxTransparentEdges", () -> parallaxTransparentEdges, v -> parallaxTransparentEdges = v),
        bool("pixelLighting", () -> pixelLighting, v -> pixelLighting = v),
        number("dayBrightness", () -> dayBrightness, v -> dayBrightness = v),
        number("nightBrightness", () -> nightBrightness, v -> nightBrightness = v),
        number("emissionBrightness", () -> emissionBrightness, v -> emissionBrightness = v),
        number("heldLightBrightness", () -> heldLightBrightness, v -> heldLightBrightness = v),
        bool("vanillaSunPath", () -> vanillaSunPath, v -> vanillaSunPath = v),
        bool("vanillaCelestialOrientation", () -> vanillaCelestialOrientation,
            v -> vanillaCelestialOrientation = v),
        bool("blockLightSampling", () -> blockLightSampling, v -> blockLightSampling = v),
        bool("rayTracingEnabled", () -> rayTracingEnabled, v -> rayTracingEnabled = v),
        new Entry("cloudsBeforeDistantHorizons", () -> cloudsBeforeDistantHorizons,
            v -> cloudsBeforeDistantHorizons = v),
        bool("useOpenGl", () -> useOpenGl, v -> useOpenGl = v),
        bool("frameGeneration", () -> frameGeneration, v -> frameGeneration = v),
        number("generatedFrames", () -> generatedFrames, v -> generatedFrames = v),
        bool("reflex", () -> reflex, v -> reflex = v),
        bool("hdrOutput", () -> hdrOutput, v -> hdrOutput = v),
        number("hdrPeakNits", () -> hdrPeakNits, v -> hdrPeakNits = Math.max(200, Math.min(10000, v))),
        number("hdrPaperWhiteNits", () -> hdrPaperWhiteNits,
            v -> hdrPaperWhiteNits = Math.max(80, Math.min(500, v))));

    public static void readOptions() {
        Path path = RadianteClient.radianceDir.resolve(OPTION_PROPERTIES);
        if (!Files.exists(path)) {
            overwriteConfig();
            return;
        }

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        for (Entry entry : ENTRIES) {
            String text = props.getProperty(entry.key());
            if (text == null) {
                continue;
            }
            try {
                entry.load().accept(text);
            } catch (NumberFormatException badValue) {
                // A hand-edited or damaged value keeps its default rather than stopping the game from starting.
                RadianteClient.LOGGER.warn("Ignoring option {}={}", entry.key(), text);
            }
        }
        // Held lights used to follow the block setting; a config from before keeps them where they were.
        if (!props.containsKey("heldLightBrightness")) {
            heldLightBrightness = emissionBrightness;
        }
        overwriteConfig();
    }

    public static void overwriteConfig() {
        Path path = RadianteClient.radianceDir.resolve(OPTION_PROPERTIES);
        Properties props = new Properties();
        for (Entry entry : ENTRIES) {
            props.setProperty(entry.key(), entry.value().get());
        }

        try {
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        try (OutputStream out = Files.newOutputStream(path)) {
            props.store(out, "Options");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public native static void nativeSetMaxFps(int maxFps, boolean write);

    public static void setMaxFps(int maxFps, boolean write) {
        Options.maxFps = maxFps;
        nativeSetMaxFps(maxFps, write);
        if (write) {
            overwriteConfig();
        }
    }

    public native static void nativeSetInactivityFpsLimit(int inactivityFpsLimit, boolean write);

    public static void setInactivityFpsLimit(int inactivityFpsLimit, boolean write) {
        Options.inactivityFpsLimit = inactivityFpsLimit;
        nativeSetInactivityFpsLimit(inactivityFpsLimit, write);
        if (write) {
            overwriteConfig();
        }
    }

    public native static void nativeSetVsync(boolean vsync, boolean write);

    public static void setVsync(boolean vsync, boolean write) {
        Options.vsync = vsync;
        nativeSetVsync(vsync, write);
        if (write) {
            overwriteConfig();
        }
    }

    public native static void nativeSetChunkBuildingBatchSize(int chunkBuildingBatchSize,
        boolean write);

    public static void setChunkBuildingBatchSize(int chunkBuildingBatchSize, boolean write) {
        Options.chunkBuildingBatchSize = chunkBuildingBatchSize;
        nativeSetChunkBuildingBatchSize(chunkBuildingBatchSize, write);
        if (write) {
            overwriteConfig();
        }
    }

    public native static void nativeSetChunkBuildingTotalBatches(int chunkBuildingTotalBatches,
        boolean write);

    public static void setChunkBuildingTotalBatches(int chunkBuildingTotalBatches, boolean write) {
        Options.chunkBuildingTotalBatches = chunkBuildingTotalBatches;
        nativeSetChunkBuildingTotalBatches(chunkBuildingTotalBatches, write);
        if (write) {
            overwriteConfig();
        }
    }

    public static void setChunkBuildingThreads(int chunkBuildingThreads, boolean write) {
        Options.chunkBuildingThreads = clampChunkBuildingThreads(chunkBuildingThreads);
        if (write) {
            overwriteConfig();
        }
    }

    /** Applies the setting to the renderer too, whose own output is the noisier half of it. */
    public static void setDebugLogging(boolean enabled, boolean write) {
        Options.debugLogging = enabled;
        try {
            com.g2806.radiante.client.proxy.vulkan.RendererProxy.setLoggingEnabled(enabled);
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            // The native renderer is not loaded yet; it reads the flag when it is.
        }
        if (write) {
            overwriteConfig();
        }
    }

    public native static void nativeSetCollectChunkEmission(boolean collectChunkEmission,
        boolean write);

    public static void setCollectChunkEmission(boolean collectChunkEmission, boolean write) {
        boolean changed = Options.collectChunkEmission != collectChunkEmission;
        Options.collectChunkEmission = collectChunkEmission;
        nativeSetCollectChunkEmission(collectChunkEmission, write);

        if (changed) {
            if (collectChunkEmission) {
                TextureProxy.flushEmissionTiles();
                ChunkProxy.rebuildAll();
            } else if (write) {
                Pipeline.ensureSelectedShaderPackAvailable();
            }
        }

        if (write) {
            overwriteConfig();
        }
    }
}
