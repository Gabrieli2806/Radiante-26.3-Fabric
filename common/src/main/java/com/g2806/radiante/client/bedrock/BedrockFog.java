package com.g2806.radiante.client.bedrock;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A Bedrock pack's per-biome volumetric fog, as Radiante's biome haze reads it
 * ({@link com.g2806.radiante.client.render.BiomeAmbiance}). Bedrock gives each biome a fog through its client biome
 * file, and each fog an air medium: scattering and absorption coefficients per colour channel, scaled by a density
 * that is full up to one height and fades out by another. Per Java biome this keeps the extinction per channel, the
 * share of it that scatters, those two heights and how much thicker the fog gets in rain.
 */
final class BedrockFog {

    static final String PATH = "assets/radiante/bedrock_fog.json";

    /** Bedrock biome names that differ from Java's. The rest are the same on both sides. */
    private static final Map<String, String> JAVA_NAMES = new HashMap<>();

    static {
        JAVA_NAMES.put("birch_forest_mutated", "old_growth_birch_forest");
        JAVA_NAMES.put("cold_beach", "snowy_beach");
        JAVA_NAMES.put("cold_taiga", "snowy_taiga");
        JAVA_NAMES.put("extreme_hills", "windswept_hills");
        JAVA_NAMES.put("extreme_hills_mutated", "windswept_gravelly_hills");
        JAVA_NAMES.put("extreme_hills_plus_trees", "windswept_forest");
        JAVA_NAMES.put("hell", "nether_wastes");
        JAVA_NAMES.put("ice_plains", "snowy_plains");
        JAVA_NAMES.put("ice_plains_spikes", "ice_spikes");
        JAVA_NAMES.put("jungle_edge", "sparse_jungle");
        JAVA_NAMES.put("mega_taiga", "old_growth_pine_taiga");
        JAVA_NAMES.put("redwood_taiga_mutated", "old_growth_spruce_taiga");
        JAVA_NAMES.put("mesa", "badlands");
        JAVA_NAMES.put("mesa_bryce", "eroded_badlands");
        JAVA_NAMES.put("mesa_plateau_stone", "wooded_badlands");
        JAVA_NAMES.put("mushroom_island", "mushroom_fields");
        JAVA_NAMES.put("roofed_forest", "dark_forest");
        JAVA_NAMES.put("savanna_mutated", "windswept_savanna");
        JAVA_NAMES.put("soulsand_valley", "soul_sand_valley");
        JAVA_NAMES.put("stone_beach", "stony_shore");
        JAVA_NAMES.put("swampland", "swamp");
    }

    private BedrockFog() {
    }

    /** The fog file for the converted pack, or null when the pack has no volumetric fog. */
    static byte[] convert(ZipFile zip, String root) throws IOException {
        Map<String, JsonObject> fogsById = new HashMap<>();
        Map<String, String> fogIdByBiome = new HashMap<>();
        for (var entries = zip.entries(); entries.hasMoreElements(); ) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (!name.startsWith(root)) {
                continue;
            }
            String path = name.substring(root.length());
            try {
                if (path.startsWith("fogs/") && path.endsWith(".json")) {
                    JsonObject settings = read(zip, entry).getAsJsonObject("minecraft:fog_settings");
                    if (settings != null && settings.has("description")) {
                        fogsById.put(settings.getAsJsonObject("description").get("identifier").getAsString(), settings);
                    }
                } else if (path.startsWith("biomes/") && path.endsWith(".json")) {
                    JsonObject biome = read(zip, entry).getAsJsonObject("minecraft:client_biome");
                    JsonObject fog = biome == null ? null
                        : biome.getAsJsonObject("components").getAsJsonObject("minecraft:fog_appearance");
                    if (fog != null) {
                        String id = biome.getAsJsonObject("description").get("identifier").getAsString();
                        fogIdByBiome.put(id.substring(id.indexOf(':') + 1), fog.get("fog_identifier").getAsString());
                    }
                }
            } catch (RuntimeException e) {
                // One malformed file only loses that biome.
            }
        }

        JsonObject biomes = new JsonObject();
        for (Map.Entry<String, String> entry : fogIdByBiome.entrySet()) {
            JsonObject settings = fogsById.get(entry.getValue());
            JsonObject haze = settings == null ? null : haze(settings);
            if (haze == null) {
                continue;
            }
            String javaName = JAVA_NAMES.getOrDefault(entry.getKey(), entry.getKey());
            // Several Bedrock biomes (hills, edges, mutated variants) fold into one Java biome; the plain one wins.
            if (!biomes.has("minecraft:" + javaName) || entry.getKey().equals(javaName)) {
                biomes.add("minecraft:" + javaName, haze);
            }
        }
        if (biomes.isEmpty()) {
            return null;
        }
        JsonObject out = new JsonObject();
        out.addProperty("_comment", "Per-biome fog converted from a Bedrock pack by Radiante: extinction per block, "
            + "scattering albedo, full density below full_below and none from zero_at up, rain_scale in rain; the "
            + "same for the water, per block.");
        out.add("biomes", biomes);
        return new GsonBuilder().setPrettyPrinting().create().toJson(out).getBytes(StandardCharsets.UTF_8);
    }

    private static JsonObject haze(JsonObject settings) {
        JsonObject volumetric = settings.getAsJsonObject("volumetric");
        if (volumetric == null || !volumetric.has("density") || !volumetric.has("media_coefficients")) {
            return null;
        }
        JsonObject air = volumetric.getAsJsonObject("density").getAsJsonObject("air");
        JsonObject media = volumetric.getAsJsonObject("media_coefficients").getAsJsonObject("air");
        if (air == null || media == null) {
            return null;
        }
        float density = air.has("max_density") ? air.get("max_density").getAsFloat() : 0.0f;
        float[] scattering = coefficients(media.get("scattering"));
        float[] absorption = coefficients(media.get("absorption"));

        JsonArray extinction = new JsonArray();
        JsonArray albedo = new JsonArray();
        for (int i = 0; i < 3; i++) {
            float total = scattering[i] + absorption[i];
            extinction.add(density * total);
            albedo.add(total > 0.0f ? scattering[i] / total : 1.0f);
        }
        JsonObject haze = new JsonObject();
        haze.add("extinction", extinction);
        haze.add("albedo", albedo);
        boolean uniform = air.has("uniform") && air.get("uniform").getAsBoolean();
        if (!uniform && air.has("max_density_height") && air.has("zero_density_height")) {
            haze.addProperty("full_below", air.get("max_density_height").getAsFloat());
            haze.addProperty("zero_at", air.get("zero_density_height").getAsFloat());
        }
        JsonObject water = volumetric.getAsJsonObject("media_coefficients").getAsJsonObject("water");
        if (water != null) {
            // Water has no density of its own in Bedrock: the coefficients are per block as they stand.
            float[] waterScattering = coefficients(water.get("scattering"));
            float[] waterAbsorption = coefficients(water.get("absorption"));
            JsonArray waterExtinction = new JsonArray();
            JsonArray waterAlbedo = new JsonArray();
            for (int i = 0; i < 3; i++) {
                float total = waterScattering[i] + waterAbsorption[i];
                waterExtinction.add(total);
                waterAlbedo.add(total > 0.0f ? waterScattering[i] / total : 0.0f);
            }
            JsonObject waterOut = new JsonObject();
            waterOut.add("extinction", waterExtinction);
            waterOut.add("albedo", waterAlbedo);
            haze.add("water", waterOut);
        }
        JsonObject weather = volumetric.getAsJsonObject("density").getAsJsonObject("weather");
        if (weather != null && weather.has("max_density") && density > 0.0f) {
            haze.addProperty("rain_scale", weather.get("max_density").getAsFloat() / density);
        }
        return haze;
    }

    /** A coefficient triple, written either as numbers or as a colour, "#rrggbb" meaning 0 to 1 per channel. */
    private static float[] coefficients(JsonElement value) {
        float[] out = new float[3];
        if (value == null) {
            return out;
        }
        if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            for (int i = 0; i < 3 && i < array.size(); i++) {
                out[i] = array.get(i).getAsFloat();
            }
        } else if (value.isJsonPrimitive() && value.getAsString().startsWith("#")) {
            String hex = value.getAsString().substring(1);
            for (int i = 0; i < 3 && i * 2 + 2 <= hex.length(); i++) {
                out[i] = Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16) / 255.0f;
            }
        }
        return out;
    }

    private static JsonObject read(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            // Bedrock tolerates comments in its JSON; Gson's lenient parser does too.
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
