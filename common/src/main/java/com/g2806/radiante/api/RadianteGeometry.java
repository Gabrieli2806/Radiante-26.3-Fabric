package com.g2806.radiante.api;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * For mods that draw into the world with their own render passes (physics debris, custom effects, anything that
 * does not go through entities, block entities or particles). While Radiante traces the world Minecraft's level
 * passes do not run, so such drawing never reaches the screen; a provider registered here hands the same geometry
 * to the ray tracer instead, every frame, and it is lit, shadowed and reflected like the rest of the world.
 *
 * <p>Nothing here needs Radiante's internals: a provider writes quads to an ordinary {@link VertexConsumer}.
 */
public final class RadianteGeometry {

    /** How a texture's alpha is read. */
    public enum Alpha {
        /** Alpha ignored; the cheapest to trace. */
        OPAQUE,
        /** Texels are there or not (leaves, grates). */
        CUTOUT,
        /** See-through (stained glass, ice). */
        TRANSLUCENT
    }

    /** Called once a frame, on the render thread, while the world is being traced. */
    @FunctionalInterface
    public interface Provider {
        void provide(Sink sink);
    }

    /** Where a provider writes its geometry for the frame. */
    public interface Sink {

        /** The camera's position in the world. Vertex positions are given relative to it. */
        Vec3 camera();

        /** How far the frame is between two ticks, for interpolating movement. */
        float partialTick();

        /**
         * Quads drawn with one texture. For each vertex: {@code addVertex} (relative to {@link #camera()}),
         * {@code setColor}, {@code setUv}; four vertices make a quad, and a triangle is a quad whose last vertex is
         * repeated. Normals are worked out from the corners. The consumer is only good for this frame.
         */
        VertexConsumer quads(GpuTexture texture, Alpha alpha);

        /** As {@link #quads(GpuTexture, Alpha)}, for a texture known by name (an atlas, an entity texture). */
        VertexConsumer quads(Identifier texture, Alpha alpha);
    }

    private static final Map<String, Provider> PROVIDERS = new LinkedHashMap<>();

    private RadianteGeometry() {
    }

    /** Registers a provider under a name of the mod's own ("mymod:debris"); a second one of that name replaces it. */
    public static synchronized void register(String name, Provider provider) {
        PROVIDERS.put(name, provider);
    }

    public static synchronized void unregister(String name) {
        PROVIDERS.remove(name);
    }

    /** The providers by name, for the renderer. */
    public static synchronized Map<String, Provider> providers() {
        return new LinkedHashMap<>(PROVIDERS);
    }
}
