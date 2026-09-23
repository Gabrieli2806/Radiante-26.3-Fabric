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
import java.util.Properties;

public class Options {

    public static final String OPTION_PROPERTIES = "options.properties";

    public static final String CATEGORY_GAMEPLAY = "options.video.category.gameplay";
    public static final String CATEGORY_WINDOW = "options.video.category.window";
    public static final String CATEGORY_DLSS = "options.video.category.dlss";
    public static final String CATEGORY_RAY_TRACING = "options.video.category.ray_tracing";
    public static final String CATEGORY_UPSCALER = "options.video.category.upscaler";
    public static final String CATEGORY_TERRAIN = "options.video.category.terrain";
    public static final String CATEGORY_PIPELINE = "options.video.category.pipeline";

    public static final String DLSS_MODE_PERFORMANCE_TOOLTIP = "options.video.dlss_mode.performance.tooltip";
    public static final String DLSS_MODE_BALANCED_TOOLTIP = "options.video.dlss_mode.balanced.tooltip";
    public static final String DLSS_MODE_QUALITY_TOOLTIP = "options.video.dlss_mode.quality.tooltip";
    public static final String DLSS_MODE_DLAA_TOOLTIP = "options.video.dlss_mode.dlaa.tooltip";

    public static final String DLSS_MODE_PERFORMANCE = "options.video.dlss_mode.performance";
    public static final String DLSS_MODE_BALANCED = "options.video.dlss_mode.balanced";
    public static final String DLSS_MODE_QUALITY = "options.video.dlss_mode.quality";
    public static final String DLSS_MODE_DLAA = "options.video.dlss_mode.dlaa";

    public static final String DLSS_MODE_KEY = "options.video.dlss_mode";
    public static final String UPSCALER_TYPE_KEY = "options.video.upscaler_type";
    public static final String UPSCALER_QUALITY_KEY = "options.video.upscaler_quality";
    public static final String DENOISER_MODE_KEY = "options.video.denoiser_mode";
    public static final String RAY_BOUNCES_KEY = "options.video.ray_bounces";
    public static final String CHUNK_BUILDING_BATCH_SIZE_KEY = "options.video.chunk_building_batch_size";
    public static final String CHUNK_BUILDING_TOTAL_BATCHES_KEY = "options.video.chunk_building_total_batches";
    public static final String CHUNK_BUILDING_THREADS_KEY = "options.video.chunk_building_threads";
    public static final String COLLECT_CHUNK_EMISSION_KEY = "options.video.collect_chunk_emission";
    public static final String SHADER_PACK_SETUP_KEY = "options.video.shader_pack_setup";
    public static final String PIPELINE_SETUP_KEY = "options.video.pipeline_setup";

    public static final String UPSCALER_TYPE_NATIVE = "options.video.upscaler_type.native";
    public static final String UPSCALER_TYPE_FSR3 = "options.video.upscaler_type.fsr3";

    public static final String UPSCALER_QUALITY_NATIVEAA = "options.video.upscaler_quality.nativeaa";
    public static final String UPSCALER_QUALITY_QUALITY = "options.video.upscaler_quality.quality";
    public static final String UPSCALER_QUALITY_BALANCED = "options.video.upscaler_quality.balanced";
    public static final String UPSCALER_QUALITY_PERFORMANCE = "options.video.upscaler_quality.performance";
    public static final String DENOISER_MODE_DLSS = "options.video.denoiser_mode.dlss";
    public static final String DENOISER_MODE_SVGF = "options.video.denoiser_mode.svgf";
    public static final String DENOISER_MODE_NRD = "options.video.denoiser_mode.nrd";
    public static final String DENOISER_MODE_TEMPORAL = "options.video.denoiser_mode.temporal";
    public static int maxFps = 260;
    public static int inactivityFpsLimit = 260;
    public static boolean vsync = true;
    public static int dlssMode = 1;
    public static int upscalerType = 1;
    public static int upscalerQuality = 1;
    public static int denoiserMode = 1;
    public static int rayBounces = 4;
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
    public static boolean vanillaSunPath = false;
    /** Sun and moon sprites keep vanilla's fixed orientation instead of turning as they cross the sky. */
    public static boolean vanillaCelestialOrientation = false;
    /** How thick the biome haze is, in percent of the tuned values. */
    public static int biomeFogStrength = 100;
    /** Loading Streamline replaces Minecraft's Vulkan loader, so it only happens when the player asks for it. */
    public static boolean frameGeneration = false;
    /** Frames DLSS generates per rendered frame: 0 is off, 1 is 2x, up to 5 for 6x. */
    public static int generatedFrames = 1;
    /** NVIDIA Reflex low latency mode. Loads Streamline at startup, so turning it on takes a restart. */
    public static boolean reflex = false;

    /** Ray tracing can be switched off with a key, which hands the world back to Minecraft's own renderer. */
    public static boolean rayTracingEnabled = true;
    /** Set when the player chooses to run on OpenGL, where this renderer cannot work at all. */
    public static boolean useOpenGl = false;

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

    private static int getDefaultChunkBuildingThreads() {
        return clampChunkBuildingThreads(Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() / 4)));
    }

    public static void readOptions() {
        Path path = RadianteClient.radianceDir.resolve(OPTION_PROPERTIES);
        if (!Files.exists(path)) {
//            System.out.println("Generating default options...");
            overwriteConfig();
            return;
        }

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);

            setMaxFps(Integer.parseInt(props.getProperty("maxFps", String.valueOf(maxFps))), false);
            setInactivityFpsLimit(Integer.parseInt(
                    props.getProperty("inactivityFpsLimit", String.valueOf(inactivityFpsLimit))),
                false);
            setVsync(Boolean.parseBoolean(props.getProperty("vsync", String.valueOf(vsync))),
                false);
            setChunkBuildingBatchSize(Integer.parseInt(props.getProperty("chunkBuildingBatchSize",
                    String.valueOf(chunkBuildingBatchSize))),
                false);
            setChunkBuildingTotalBatches(
                Integer.parseInt(props.getProperty("chunkBuildingTotalBatches",
                    String.valueOf(chunkBuildingTotalBatches))), false);
            setChunkBuildingThreads(
                Integer.parseInt(props.getProperty("chunkBuildingThreads",
                    String.valueOf(chunkBuildingThreads))), false);
            setDebugLogging(Boolean.parseBoolean(props.getProperty("debugLogging",
                    String.valueOf(debugLogging))), false);
            biomeFog = Boolean.parseBoolean(props.getProperty("biomeFog", String.valueOf(biomeFog)));
            firstPersonShadow = Boolean.parseBoolean(
                props.getProperty("firstPersonShadow", String.valueOf(firstPersonShadow)));
            vanillaSunPath = Boolean.parseBoolean(
                props.getProperty("vanillaSunPath", String.valueOf(vanillaSunPath)));
            vanillaCelestialOrientation = Boolean.parseBoolean(
                props.getProperty("vanillaCelestialOrientation", String.valueOf(vanillaCelestialOrientation)));
            biomeFogStrength = Math.max(0, Math.min(400, Integer.parseInt(
                props.getProperty("biomeFogStrength", String.valueOf(biomeFogStrength)))));
            setCollectChunkEmission(Boolean.parseBoolean(props.getProperty("collectChunkEmission",
                    String.valueOf(collectChunkEmission))),
                false);
            rayTracingEnabled = Boolean.parseBoolean(props.getProperty("rayTracingEnabled",
                String.valueOf(rayTracingEnabled)));
            useOpenGl = Boolean.parseBoolean(props.getProperty("useOpenGl", String.valueOf(useOpenGl)));
            frameGeneration = Boolean.parseBoolean(props.getProperty("frameGeneration",
                String.valueOf(frameGeneration)));
            generatedFrames = Integer.parseInt(props.getProperty("generatedFrames",
                String.valueOf(generatedFrames)));
            reflex = Boolean.parseBoolean(props.getProperty("reflex", String.valueOf(reflex)));

            overwriteConfig();
//            System.out.println("Successfully read options: " + path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void overwriteConfig() {
        Path path = RadianteClient.radianceDir.resolve(OPTION_PROPERTIES);
        Properties props = new Properties();
        props.setProperty("maxFps", String.valueOf(maxFps));
        props.setProperty("inactivityFpsLimit", String.valueOf(inactivityFpsLimit));
        props.setProperty("vsync", String.valueOf(vsync));
        props.setProperty("dlssMode", String.valueOf(dlssMode));
        props.setProperty("upscalerType", String.valueOf(upscalerType));
        props.setProperty("upscalerQuality", String.valueOf(upscalerQuality));
        props.setProperty("denoiserMode", String.valueOf(denoiserMode));
        props.setProperty("rayBounces", String.valueOf(rayBounces));
        props.setProperty("chunkBuildingBatchSize", String.valueOf(chunkBuildingBatchSize));
        props.setProperty("chunkBuildingTotalBatches", String.valueOf(chunkBuildingTotalBatches));
        props.setProperty("chunkBuildingThreads", String.valueOf(chunkBuildingThreads));
        props.setProperty("collectChunkEmission", String.valueOf(collectChunkEmission));
        props.setProperty("debugLogging", String.valueOf(debugLogging));
        props.setProperty("biomeFog", String.valueOf(biomeFog));
        props.setProperty("firstPersonShadow", String.valueOf(firstPersonShadow));
        props.setProperty("vanillaSunPath", String.valueOf(vanillaSunPath));
        props.setProperty("vanillaCelestialOrientation", String.valueOf(vanillaCelestialOrientation));
        props.setProperty("biomeFogStrength", String.valueOf(biomeFogStrength));
        props.setProperty("rayTracingEnabled", String.valueOf(rayTracingEnabled));
        props.setProperty("useOpenGl", String.valueOf(useOpenGl));
        props.setProperty("frameGeneration", String.valueOf(frameGeneration));
        props.setProperty("generatedFrames", String.valueOf(generatedFrames));
        props.setProperty("reflex", String.valueOf(reflex));

        try {
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        try (OutputStream out = Files.newOutputStream(path)) {
            props.store(out, "Options");
//            System.out.println("Options written to: " + path);
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
