package com.g2806.radiante.client.render;

import com.g2806.radiante.client.option.Options;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.Resource;
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
 *
 * <p>A Bedrock RTX pack converted by {@link com.g2806.radiante.client.bedrock.BedrockPackConverter} brings its own
 * per-biome fog, which then takes the place of these values for the biomes it covers: how much of the light it
 * scatters rather than absorbs, which colours it absorbs most, how thick it is and the heights it thins out between.
 */
public final class BiomeAmbiance {

    /**
     * Tint - the share of the extinction that scatters - and extinction per block, with how each channel's extinction
     * departs from it (chroma), the heights the haze thins out between and how much rain thickens it.
     */
    private record Haze(float r, float g, float b, float density, float chromaR, float chromaG, float chromaB,
        float fullBelow, float zeroAt, float rainScale) {

        /** Radiante's own haze: grey extinction, the same thickness at every height. */
        Haze(float r, float g, float b, float density) {
            this(r, g, b, density, 1.0f, 1.0f, 1.0f, NO_THINNING, NO_THINNING + 1.0f, 1.0f + RAIN_THICKENING);
        }
    }

    /** A Bedrock pack's water: extinction per block per channel, and the share of it that scatters. */
    private record Water(float extinctionR, float extinctionG, float extinctionB, float albedoR, float albedoG,
        float albedoB) {
    }

    /** Far above any world: haze that never thins out with height. */
    private static final float NO_THINNING = 100_000.0f;
    /** Rain thickens the haze by up to this factor. */
    private static final float RAIN_THICKENING = 1.5f;
    /** Written into converted Bedrock packs; the topmost pack that has one decides the fog. */
    private static final Identifier PACK_FOG = Identifier.fromNamespaceAndPath("radiante", "bedrock_fog.json");

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

    // Nether and End: rgb multiplies the biome's vanilla fog colour, which already carries each biome's hue (red in
    // the crimson forest, teal in the warped forest, and so on) and is what the haze is drawn in.
    private static final Haze NETHER_WASTES = new Haze(1.00f, 0.92f, 0.86f, 0.0100f);
    private static final Haze CRIMSON_FOREST = new Haze(1.10f, 0.86f, 0.86f, 0.0160f);
    private static final Haze WARPED_FOREST = new Haze(0.90f, 1.00f, 1.10f, 0.0130f);
    private static final Haze SOUL_SAND_VALLEY = new Haze(0.90f, 1.00f, 1.12f, 0.0180f);
    private static final Haze BASALT_DELTAS = new Haze(1.00f, 1.00f, 1.00f, 0.0280f);
    private static final Haze END_CENTER = new Haze(1.00f, 1.00f, 1.00f, 0.0040f);
    private static final Haze END_OUTER = new Haze(1.00f, 1.00f, 1.00f, 0.0060f);
    /** Vanilla's End fog is close to black, which as haze would only darken; this is the least it is lifted to. */
    private static final float END_FLOOR_R = 0.10f;
    private static final float END_FLOOR_G = 0.07f;
    private static final float END_FLOOR_B = 0.15f;

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
        put(NETHER_WASTES, Biomes.NETHER_WASTES);
        put(CRIMSON_FOREST, Biomes.CRIMSON_FOREST);
        put(WARPED_FOREST, Biomes.WARPED_FOREST);
        put(SOUL_SAND_VALLEY, Biomes.SOUL_SAND_VALLEY);
        put(BASALT_DELTAS, Biomes.BASALT_DELTAS);
        put(END_CENTER, Biomes.THE_END);
        put(END_OUTER, Biomes.END_HIGHLANDS, Biomes.END_MIDLANDS, Biomes.SMALL_END_ISLANDS, Biomes.END_BARRENS);
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
    private static final int OVERWORLD_SKY = 1;
    private static final int END_SKY = 2;

    /** A converted Bedrock pack's fog by biome id, empty without one. */
    private static final Map<String, Haze> PACK_BY_BIOME = new HashMap<>();
    private static final Map<String, Water> PACK_WATER_BY_BIOME = new HashMap<>();
    private static final Vector4f waterExtinction = new Vector4f();
    private static final Vector4f waterAlbedo = new Vector4f();
    private static Object packFogLoadedFor;

    private static final Vector4f current = new Vector4f();
    private static final Vector4f currentChroma = new Vector4f(1.0f, 1.0f, 1.0f, 0.0f);
    private static final Vector4f currentHeights = new Vector4f(NO_THINNING, NO_THINNING + 1.0f, 0.0f, 0.0f);
    private static float currentExposure;
    private static boolean hasCurrent;
    private static int currentSkyType = -1;
    private static long lastNanos;
    private static long lastLogNanos;

    private BiomeAmbiance() {
    }

    /** How each channel's extinction compares with the density handed over by {@link #update}. */
    public static Vector4f chroma() {
        return new Vector4f(currentChroma);
    }

    /** Water extinction per block around the camera in xyz, w 1 once there is a world to take it from. */
    public static Vector4f waterExtinction() {
        return new Vector4f(waterExtinction);
    }

    /** The share of the water's extinction that scatters. */
    public static Vector4f waterAlbedo() {
        return new Vector4f(waterAlbedo);
    }

    /** The haze is at full density below x and gone from y up, in world heights. */
    public static Vector4f heights() {
        return new Vector4f(currentHeights);
    }

    /** Reads a converted Bedrock pack's fog, once per resource reload (each one rebuilds the block atlas). */
    public static void reloadPackFogIfNeeded(Minecraft minecraft) {
        TextureAtlas atlas = minecraft.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
        Object key = TextureTracker.gpuTextureOrNull(atlas);
        if (key == null || key == packFogLoadedFor) {
            return;
        }
        packFogLoadedFor = key;
        PACK_BY_BIOME.clear();
        PACK_WATER_BY_BIOME.clear();
        hasCurrent = false;
        Optional<Resource> resource = minecraft.getResourceManager().getResource(PACK_FOG);
        if (resource.isEmpty()) {
            return;
        }
        try (Reader reader = resource.get().openAsReader()) {
            JsonObject biomes = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("biomes");
            for (Map.Entry<String, JsonElement> entry : biomes.entrySet()) {
                JsonObject biome = entry.getValue().getAsJsonObject();
                PACK_BY_BIOME.put(entry.getKey(), packHaze(biome));
                if (biome.has("water")) {
                    JsonObject water = biome.getAsJsonObject("water");
                    float[] extinction = vec3(water.getAsJsonArray("extinction"));
                    float[] albedo = vec3(water.getAsJsonArray("albedo"));
                    PACK_WATER_BY_BIOME.put(entry.getKey(), new Water(extinction[0], extinction[1], extinction[2],
                        albedo[0], albedo[1], albedo[2]));
                }
            }
            RadianteRenderer.LOGGER.info("Using the Bedrock fog of {} for {} biomes", resource.get().sourcePackId(),
                PACK_BY_BIOME.size());
        } catch (Exception e) {
            PACK_BY_BIOME.clear();
            PACK_WATER_BY_BIOME.clear();
            RadianteRenderer.LOGGER.warn("Could not read the Bedrock fog of {}", resource.get().sourcePackId(), e);
        }
    }

    private static Haze packHaze(JsonObject fog) {
        float[] albedo = vec3(fog.getAsJsonArray("albedo"));
        float[] extinction = vec3(fog.getAsJsonArray("extinction"));
        float density = (extinction[0] + extinction[1] + extinction[2]) / 3.0f;
        if (density <= 0.0f) {
            return NONE;
        }
        boolean thins = fog.has("full_below") && fog.has("zero_at");
        float fullBelow = thins ? fog.get("full_below").getAsFloat() : NO_THINNING;
        float zeroAt = thins ? Math.max(fog.get("zero_at").getAsFloat(), fullBelow + 1.0f) : NO_THINNING + 1.0f;
        float rainScale = fog.has("rain_scale") ? fog.get("rain_scale").getAsFloat() : 1.0f;
        return new Haze(albedo[0], albedo[1], albedo[2], density, extinction[0] / density, extinction[1] / density,
            extinction[2] / density, fullBelow, zeroAt, rainScale);
    }

    private static float[] vec3(JsonArray array) {
        return new float[] {array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat()};
    }

    /**
     * The value handed to the shaders: extinction per block in w, zero when the option is off. In the overworld xyz
     * is a tint for sky-lit haze; in the Nether and the End it is the haze colour itself.
     */
    public static Vector4f update(Minecraft minecraft, Vec3 cameraPos, int skyType, float rain,
        org.joml.Vector4fc vanillaFogColor) {
        ClientLevel level = minecraft.level;
        updateWater(level, cameraPos);
        if (!Options.biomeFog || level == null || Options.biomeFogStrength <= 0) {
            hasCurrent = false;
            return new Vector4f(1.0f, 1.0f, 1.0f, 0.0f);
        }
        // The colour means something different in each kind of dimension, so never blend across a change.
        if (skyType != currentSkyType) {
            hasCurrent = false;
            currentSkyType = skyType;
        }
        boolean overworld = skyType == OVERWORLD_SKY;

        float r = 0.0f;
        float g = 0.0f;
        float b = 0.0f;
        float density = 0.0f;
        Vector4f chroma = new Vector4f();
        Vector4f heights = new Vector4f();
        float rainScale = 0.0f;
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
                chroma.add(haze.chromaR(), haze.chromaG(), haze.chromaB(), 0.0f);
                heights.add(haze.fullBelow(), haze.zeroAt(), 0.0f, 0.0f);
                rainScale += haze.rainScale();
                samples++;
            }
        }
        r /= samples;
        g /= samples;
        b /= samples;
        density /= samples;
        chroma.div(samples);
        heights.div(samples);
        rainScale /= samples;
        float exposure = 1.0f;
        if (overworld) {
            density *= Mth.lerp(Mth.clamp(rain, 0.0f, 1.0f), 1.0f, rainScale);
            exposure = level.getBrightness(LightLayer.SKY, pos.set(cx, cy, cz)) / 15.0f;
        } else {
            float fogR = vanillaFogColor.x();
            float fogG = vanillaFogColor.y();
            float fogB = vanillaFogColor.z();
            if (skyType == END_SKY) {
                fogR = Math.max(fogR, END_FLOOR_R);
                fogG = Math.max(fogG, END_FLOOR_G);
                fogB = Math.max(fogB, END_FLOOR_B);
            }
            r *= fogR;
            g *= fogG;
            b *= fogB;
        }
        density *= Options.biomeFogStrength / 100.0f;

        long now = System.nanoTime();
        if (!hasCurrent) {
            current.set(r, g, b, density);
            currentChroma.set(chroma);
            currentHeights.set(heights);
            currentExposure = exposure;
            hasCurrent = true;
        } else {
            float seconds = Math.min((now - lastNanos) / 1.0e9f, 1.0f);
            float blend = 1.0f - (float) Math.exp(-seconds * 3.0f / BLEND_SECONDS);
            current.lerp(new Vector4f(r, g, b, density), blend);
            currentChroma.lerp(chroma, blend);
            currentHeights.lerp(heights, blend);
            currentExposure += (exposure - currentExposure) * blend;
        }
        lastNanos = now;

        if (Options.debugLogging && now - lastLogNanos > 5_000_000_000L) {
            lastLogNanos = now;
            RadianteRenderer.LOGGER.info("biome fog: biome={} tint=({}, {}, {}) density={} chroma={} heights={} sky "
                    + "exposure={}",
                level.getBiome(pos.set(cx, cy, cz)).unwrapKey().map(key -> key.identifier().toString()).orElse("?"),
                current.x, current.y, current.z, current.w, currentChroma, currentHeights, currentExposure);
        }

        return new Vector4f(current.x, current.y, current.z, current.w * currentExposure);
    }

    /**
     * The water around the camera, averaged like the haze: a Bedrock pack's where it has one, and otherwise one worked
     * out from the biome's vanilla water colour, so every biome's water keeps its own tone as it gets deeper.
     */
    private static void updateWater(ClientLevel level, Vec3 cameraPos) {
        waterExtinction.set(0.0f);
        waterAlbedo.set(0.0f);
        if (level == null) {
            return;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int found = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                pos.set(Mth.floor(cameraPos.x) + dx * SAMPLE_SPACING, Mth.floor(cameraPos.y),
                    Mth.floor(cameraPos.z) + dz * SAMPLE_SPACING);
                Holder<Biome> biome = level.getBiome(pos);
                Water water = biome.unwrapKey()
                    .map(key -> PACK_WATER_BY_BIOME.get(key.identifier().toString())).orElse(null);
                if (water == null) {
                    water = vanillaWater(biome.value().getWaterColor());
                }
                waterExtinction.add(water.extinctionR(), water.extinctionG(), water.extinctionB(), 0.0f);
                waterAlbedo.add(water.albedoR(), water.albedoG(), water.albedoB(), 0.0f);
                found++;
            }
        }
        if (found > 0) {
            waterExtinction.div(found).w = 1.0f;
            waterAlbedo.div(found);
        }
    }

    /** Per block, how much of the colours the biome's water is not gets absorbed. */
    private static final float VANILLA_WATER_ABSORPTION = 0.22f;
    /** Floor of the extinction, so even the palest water fades with depth. */
    private static final float VANILLA_WATER_MIN_EXTINCTION = 0.02f;
    /** How much of the vanilla water's extinction scatters back in its own colour. */
    private static final float VANILLA_WATER_SCATTER = 0.45f;

    /** Water that looks like the vanilla tint: it keeps its colour and absorbs the rest. */
    private static Water vanillaWater(int rgb) {
        float r = (rgb >> 16 & 0xFF) / 255.0f;
        float g = (rgb >> 8 & 0xFF) / 255.0f;
        float b = (rgb & 0xFF) / 255.0f;
        return new Water(VANILLA_WATER_MIN_EXTINCTION + (1.0f - r) * VANILLA_WATER_ABSORPTION,
            VANILLA_WATER_MIN_EXTINCTION + (1.0f - g) * VANILLA_WATER_ABSORPTION,
            VANILLA_WATER_MIN_EXTINCTION + (1.0f - b) * VANILLA_WATER_ABSORPTION,
            r * VANILLA_WATER_SCATTER, g * VANILLA_WATER_SCATTER, b * VANILLA_WATER_SCATTER);
    }

    private static Haze hazeOf(Holder<Biome> biome) {
        if (!PACK_BY_BIOME.isEmpty()) {
            Haze pack = biome.unwrapKey().map(key -> PACK_BY_BIOME.get(key.identifier().toString())).orElse(null);
            if (pack != null) {
                return pack;
            }
        }
        Haze known = biome.unwrapKey().map(BY_BIOME::get).orElse(null);
        if (known != null) {
            return known;
        }

        // Modded biomes: go by the tags they share with vanilla ones, then by climate.
        if (biome.is(BiomeTags.IS_NETHER)) {
            return NETHER_WASTES;
        }
        if (biome.is(BiomeTags.IS_END)) {
            return END_OUTER;
        }
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
