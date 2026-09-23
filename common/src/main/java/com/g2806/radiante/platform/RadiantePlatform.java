package com.g2806.radiante.platform;

import com.g2806.radiante.client.render.EntityCollector;
import java.nio.file.Path;
import java.util.ServiceLoader;

/**
 * The few things Radiante needs from the mod loader it runs on. Everything else is plain Minecraft and lives in the
 * common module; each loader module provides one implementation, registered under META-INF/services.
 */
public interface RadiantePlatform {

    RadiantePlatform INSTANCE = load();

    /** Loader name for logs: "Fabric", "NeoForge" or "Forge". */
    String loaderName();

    /** The game directory; Radiante keeps its native files and settings in a "radiante" folder inside it. */
    Path gameDir();

    /**
     * Whether NVIDIA Streamline (frame generation and Reflex) can be loaded. It has to hook the Vulkan library before
     * LWJGL loads it; on Forge and NeoForge that has not worked yet and device creation crashes, so they opt out.
     */
    default boolean supportsStreamline() {
        return true;
    }

    /**
     * The collector entity and block submissions are expanded into. A loader that adds submission methods of its
     * own (Fabric's rendering API submits meshes) returns a subclass that handles them.
     */
    default EntityCollector createEntityCollector() {
        return new EntityCollector();
    }

    private static RadiantePlatform load() {
        return ServiceLoader.load(RadiantePlatform.class, RadiantePlatform.class.getClassLoader()).findFirst()
            .orElseThrow(() -> new IllegalStateException("No RadiantePlatform implementation on the classpath"));
    }
}
