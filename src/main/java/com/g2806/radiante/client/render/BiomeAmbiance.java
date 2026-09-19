package com.g2806.radiante.client.render;

import com.g2806.radiante.client.option.Options;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;

/**
 * Per-biome haze for the overworld, in the spirit of Bedrock RTX's per-biome fog: deserts read warm and dusty, swamps
 * green and thick, snowy biomes cool and bright. The shaders light the haze with the sky, so it follows the time of
 * day on its own; this class only decides its tint and how thick it is.
 *
 * <p>The biomes around the camera are averaged and the result eased in over a couple of seconds, so walking across a
 * border fades from one to the other instead of switching. The haze also thins out with the sky light at the camera:
 * it is weather, and it has no business filling a cave.
 */
public final class BiomeAmbiance {

    /** Tint (linear, roughly unit luminance) and extinction per block. */
    private record Haze(float r, float g, float b, float density) {
    }

    private static final Haze DEFAULT = new Haze(0.92f, 0.96f, 1.00f, 0.0030f);
    private static final Haze CLEAR = new Haze(0.92f, 0.96f, 1.02f, 0.0018f);
    private static final Haze NONE = new Haze(1.0f, 1.0f, 1.0f, 0.0f);
    private static final Haze DESERT = new Haze(1.00f, 0.78f, 0.52f, 0.0120f);
    private static final Haze BADLANDS = new Haze(1.00f, 0.66f, 0.44f, 0.0110f);
    private static final Haze SAVANNA = new Haze(1.00f, 0.86f, 0.62f, 0.0080f);
    private static final Haze SWAMP = new Haze(0.66f, 0.80f, 0.52f, 0.0260f);
    private static final Haze MANGROVE = new Haze(0.68f, 0.84f, 0.58f, 0.0280f);
    private static final Haze JUNGLE = new Haze(0.78f, 1.00f, 0.78f, 0.0150f);
    private static final Haze SPARSE_JUNGLE = new Haze(0.84f, 1.00f, 0.84f, 0.0100f);
    private static final Haze DARK_FOREST = new Haze(0.76f, 0.84f, 0.76f, 0.0170f);
    private static final Haze PALE_GARDEN = new Haze(0.90f, 0.90f, 0.90f, 0.0300f);
    private static final Haze SNOWY = new Haze(0.84f, 0.92f, 1.08f, 0.0090f);
    private static final Haze TAIGA = new Haze(0.84f, 0.92f, 0.96f, 0.0070f);
    private static final Haze CHERRY = new Haze(1.00f, 0.84f, 0.92f, 0.0070f);
    private static final Haze MUSHROOM = new Haze(0.90f, 0.78f, 1.00f, 0.0100f);
    private static final Haze OCEAN = new Haze(0.80f, 0.92f, 1.08f, 0.0050f);
    private static final Haze WARM_OCEAN = new Haze(0.78f, 0.96f, 1.02f, 0.0050f);
    private static final Haze RIVER = new Haze(0.90f, 0.95f, 1.00f, 0.0040f);

    private static final Map<ResourceKey<Biome>, Haze> BY_BIOME = new HashMap<>();

    static {
        put(DESERT, Biomes.DESERT);
        put(BADLANDS, Biomes.BADLANDS, Biomes.ERODED_BADLANDS, Biomes.WOODED_BADLANDS);
        put(SAVANNA, Biomes.SAVANNA, Biomes.SAVANNA_PLATEAU, Biomes.WINDSWEPT_SAVANNA);
        put(SWAMP, Biomes.SWAMP);
        put(MANGROVE, Biomes.MANGROVE_SWAMP);
        put(JUNGLE, Biomes.JUNGLE, Biomes.BAMBOO_JUNGLE);
        put(SPARSE_JUNGLE, Biomes.SPARSE_JUNGLE);
        put(DARK_FOREST, Biomes.DARK_FOREST);
        put(PALE_GARDEN, Biomes.PALE_GARDEN);
        put(SNOWY, Biomes.SNOWY_PLAINS, Biomes.ICE_SPIKES, Biomes.SNOWY_TAIGA, Biomes.SNOWY_SLOPES, Biomes.GROVE,
            Biomes.FROZEN_PEAKS, Biomes.SNOWY_BEACH, Biomes.FROZEN_RIVER, Biomes.FROZEN_OCEAN,
            Biomes.DEEP_FROZEN_OCEAN);
        put(TAIGA, Biomes.TAIGA, Biomes.OLD_GROWTH_PINE_TAIGA, Biomes.OLD_GROWTH_SPRUCE_TAIGA);
        put(CLEAR, Biomes.JAGGED_PEAKS, Biomes.STONY_PEAKS, Biomes.WINDSWEPT_HILLS, Biomes.WINDSWEPT_GRAVELLY_HILLS,
            Biomes.WINDSWEPT_FOREST, Biomes.MEADOW, Biomes.STONY_SHORE);
        put(CHERRY, Biomes.CHERRY_GROVE);
        put(MUSHROOM, Biomes.MUSHROOM_FIELDS);
        put(OCEAN, Biomes.OCEAN, Biomes.DEEP_OCEAN, Biomes.COLD_OCEAN, Biomes.DEEP_COLD_OCEAN);
        put(WARM_OCEAN, Biomes.WARM_OCEAN, Biomes.LUKEWARM_OCEAN, Biomes.DEEP_LUKEWARM_OCEAN);
        put(RIVER, Biomes.RIVER, Biomes.BEACH);
        // Cave biomes: the sky light check already keeps the haze out, this keeps their colour out of the average.
        put(NONE, Biomes.LUSH_CAVES, Biomes.DRIPSTONE_CAVES, Biomes.DEEP_DARK, Biomes.SULFUR_CAVES);
    }

    @SafeVarargs
    private static void put(Haze haze, ResourceKey<Biome>... biomes) {
        for (ResourceKey<Biome> biome : biomes) {
            BY_BIOME.put(biome, haze);
        }
    }

    /** Samples around the camera, in blocks; wide enough that a border fades in rather than snapping. */
    private static final int SAMPLE_SPACING = 12;
    /** Seconds for the blend to cover most of the way to a new biome, and for the sky light to catch up. */
    private static final float BLEND_SECONDS = 2.0f;
    /** Rain thickens the haze by up to this factor. */
    private static final float RAIN_THICKENING = 1.5f;
    private static final int OVERWORLD_SKY = 1;

    private static final Vector4f current = new Vector4f();
    private static float currentExposure;
    private static boolean hasCurrent;
    private static long lastNanos;
    private static long lastLogNanos;

    private BiomeAmbiance() {
    }

    /**
     * The value handed to the shaders: tint in xyz, extinction per block in w. Zero when the option is off or the
     * camera is not under the overworld sky.
     */
    public static Vector4f update(Minecraft minecraft, Vec3 cameraPos, int skyType, float rain) {
        ClientLevel level = minecraft.level;
        if (!Options.biomeFog || level == null || skyType != OVERWORLD_SKY) {
            hasCurrent = false;
            return new Vector4f(1.0f, 1.0f, 1.0f, 0.0f);
        }

        float r = 0.0f;
        float g = 0.0f;
        float b = 0.0f;
        float density = 0.0f;
        int samples = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int cx = Mth.floor(cameraPos.x);
        int cy = Mth.floor(cameraPos.y);
        int cz = Mth.floor(cameraPos.z);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                pos.set(cx + dx * SAMPLE_SPACING, cy, cz + dz * SAMPLE_SPACING);
                Haze haze = hazeOf(level.getBiome(pos));
                r += haze.r();
                g += haze.g();
                b += haze.b();
                density += haze.density();
                samples++;
            }
        }
        r /= samples;
        g /= samples;
        b /= samples;
        density /= samples;
        density *= 1.0f + RAIN_THICKENING * Mth.clamp(rain, 0.0f, 1.0f);

        float exposure = level.getBrightness(LightLayer.SKY, pos.set(cx, cy, cz)) / 15.0f;

        long now = System.nanoTime();
        if (!hasCurrent) {
            current.set(r, g, b, density);
            currentExposure = exposure;
            hasCurrent = true;
        } else {
            float seconds = Math.min((now - lastNanos) / 1.0e9f, 1.0f);
            float blend = 1.0f - (float) Math.exp(-seconds * 3.0f / BLEND_SECONDS);
            current.lerp(new Vector4f(r, g, b, density), blend);
            currentExposure += (exposure - currentExposure) * blend;
        }
        lastNanos = now;

        if (Options.debugLogging && now - lastLogNanos > 5_000_000_000L) {
            lastLogNanos = now;
            RadianteRenderer.LOGGER.info("biome fog: biome={} tint=({}, {}, {}) density={} sky exposure={}",
                level.getBiome(pos.set(cx, cy, cz)).unwrapKey().map(key -> key.identifier().toString()).orElse("?"),
                current.x, current.y, current.z, current.w, currentExposure);
        }

        return new Vector4f(current.x, current.y, current.z, current.w * currentExposure);
    }

    private static Haze hazeOf(Holder<Biome> biome) {
        Haze known = biome.unwrapKey().map(BY_BIOME::get).orElse(null);
        if (known != null) {
            return known;
        }

        // Modded biomes: go by the tags they share with vanilla ones, then by climate.
        if (biome.is(BiomeTags.IS_OCEAN)) {
            return OCEAN;
        }
        if (biome.is(BiomeTags.IS_JUNGLE)) {
            return JUNGLE;
        }
        if (biome.is(BiomeTags.IS_BADLANDS)) {
            return BADLANDS;
        }
        if (biome.is(BiomeTags.IS_SAVANNA)) {
            return SAVANNA;
        }
        if (biome.is(BiomeTags.IS_TAIGA)) {
            return TAIGA;
        }
        if (biome.is(BiomeTags.IS_MOUNTAIN)) {
            return CLEAR;
        }
        if (biome.is(BiomeTags.IS_RIVER) || biome.is(BiomeTags.IS_BEACH)) {
            return RIVER;
        }
        float temperature = biome.value().getBaseTemperature();
        if (temperature >= 1.5f && !biome.value().hasPrecipitation()) {
            return DESERT;
        }
        if (temperature < 0.15f) {
            return SNOWY;
        }
        return DEFAULT;
    }
}
