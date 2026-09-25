package com.g2806.radiante.client.bedrock;

import com.g2806.radiante.client.RadianteClient;
import com.g2806.radiante.platform.RadiantePlatform;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

/**
 * Turns Bedrock RTX resource packs ({@code .mcpack}) dropped into the resource pack folder into LabPBR packs the
 * renderer reads; the pack list shows the .mcpack itself (see PackDetectorMixin) and loads the conversion. Bedrock
 * describes each texture with a texture set: a colour texture, a MER(S) map (metalness,
 * emission, roughness, subsurface) and a height or normal map, under Bedrock's own texture names. Each Java block
 * texture that has a Bedrock counterpart (see texture_map.json) gets the pack's colour, a LabPBR specular map and a
 * LabPBR normal map built from them, so the pack keeps its look: its colours, how rough, metallic and glowing
 * things are, and the relief of its surfaces. The pack's per-biome volumetric fog comes along too (see
 * {@link BedrockFog}).
 */
public final class BedrockPackConverter {

    /** Under Radiante's folder in the game directory: the converted packs, one zip per .mcpack. */
    private static final String CACHE_FOLDER = "bedrock_packs";
    /** Stored as the zip comment; a pack converted by another version of the converter is converted again. */
    private static final String CONVERTER_VERSION = "radiante-bedrock-converter 6";
    /** Resource pack format of Minecraft 26.3. */
    private static final int PACK_FORMAT = 97;
    /** Slope of normals built from a height map: height units per texel. */
    private static final float HEIGHT_TO_NORMAL = 2.0f;
    /**
     * LabPBR height of every texel: the surface itself. Bedrock only shades with its height maps and never displaces
     * by them, and they are authored for that - mortar lines drop from white to black in one texel - so parallax
     * would dig trenches the pack never had.
     */
    private static final int FLAT_HEIGHT = 255;
    /** LabPBR F0 of non-metals (reflectance 0.04), stored in the specular map's green channel. */
    private static final int DIELECTRIC_F0 = 10;
    /** LabPBR green channel for a metal that reflects its own colour. */
    private static final int ALBEDO_METAL = 255;
    /**
     * Bedrock multiplies emission by a strong constant of its own: RTX packs give full light sources (torches,
     * glowstone, sea lanterns) about a quarter of the range. Radiante lights them at full strength, so the same
     * ratio lands those at the top of LabPBR's range.
     */
    private static final int EMISSION_SCALE = 4;

    private BedrockPackConverter() {
    }

    /**
     * The LabPBR pack for a Bedrock .mcpack, converted the first time it is asked for and kept in Radiante's own folder
     * (not beside the .mcpack, where it would show up in the pack list a second time), or null if it cannot be.
     */
    public static synchronized Path converted(Path mcpack) {
        String name = mcpack.getFileName().toString();
        String base = name.substring(0, name.length() - ".mcpack".length());
        Path out = RadiantePlatform.INSTANCE.gameDir().resolve("radiante").resolve(CACHE_FOLDER).resolve(base + ".zip");
        try {
            if (isUpToDate(out, mcpack)) {
                return out;
            }
            JsonObject textureMap = loadTextureMap();
            if (textureMap == null) {
                return null;
            }
            Files.createDirectories(out.getParent());
            long start = System.nanoTime();
            int converted = convert(mcpack, out, textureMap);
            RadianteClient.LOGGER.info("Converted Bedrock pack {} ({} textures) in {} ms", name, converted,
                (System.nanoTime() - start) / 1_000_000);
            return out;
        } catch (Exception e) {
            RadianteClient.LOGGER.warn("Could not convert Bedrock pack {}", name, e);
            return null;
        }
    }

    public static boolean isBedrockPack(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mcpack");
    }

    private static boolean isUpToDate(Path out, Path pack) throws IOException {
        if (!Files.exists(out) || Files.getLastModifiedTime(out).compareTo(Files.getLastModifiedTime(pack)) < 0) {
            return false;
        }
        try (ZipFile zip = new ZipFile(out.toFile())) {
            return CONVERTER_VERSION.equals(zip.getComment());
        } catch (IOException e) {
            return false;
        }
    }

    private static JsonObject loadTextureMap() {
        try (InputStream in =
                 BedrockPackConverter.class.getResourceAsStream("/assets/radiante/bedrock/texture_map.json")) {
            if (in == null) {
                RadianteClient.LOGGER.warn("Bedrock texture map missing from the Radiante jar");
                return null;
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject()
                .getAsJsonObject("textures");
        } catch (IOException e) {
            RadianteClient.LOGGER.warn("Could not read the Bedrock texture map", e);
            return null;
        }
    }

    private static int convert(Path source, Path out, JsonObject textureMap) throws IOException {
        Path temp = out.resolveSibling(out.getFileName() + ".tmp");
        int converted = 0;
        try (ZipFile zip = new ZipFile(source.toFile());
             ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(temp))) {
            zos.setComment(CONVERTER_VERSION);
            String root = findPackRoot(zip);
            String name = packName(zip, root, source);

            write(zos, "pack.mcmeta", ("{\n  \"pack\": {\n    \"description\": \"" + escape(name)
                + " - converted from Bedrock by Radiante\",\n    \"min_format\": [" + PACK_FORMAT
                + ", 0],\n    \"max_format\": [" + PACK_FORMAT + ", 999]\n  }\n}\n").getBytes(StandardCharsets.UTF_8));
            byte[] icon = read(zip, root + "pack_icon.png");
            if (icon != null) {
                write(zos, "pack.png", icon);
            }

            for (Map.Entry<String, JsonElement> entry : textureMap.entrySet()) {
                JsonObject info = entry.getValue().getAsJsonObject();
                String bedrockPath = info.get("bedrock").getAsString();
                boolean colorCompatible = info.get("color").getAsBoolean();
                try {
                    if (convertTexture(zip, root, entry.getKey(), bedrockPath, colorCompatible, zos)) {
                        converted++;
                    }
                } catch (Exception e) {
                    RadianteClient.LOGGER.debug("Skipped Bedrock texture {}: {}", bedrockPath, e.toString());
                }
            }

            byte[] fog = BedrockFog.convert(zip, root);
            if (fog != null) {
                write(zos, BedrockFog.PATH, fog);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        Files.move(temp, out, StandardCopyOption.REPLACE_EXISTING);
        return converted;
    }

    /** One Java texture from its Bedrock texture set; false when the pack has no set for it. */
    private static boolean convertTexture(ZipFile zip, String root, String javaName, String bedrockPath,
        boolean colorCompatible, ZipOutputStream zos) throws IOException {
        byte[] setBytes = read(zip, root + bedrockPath + ".texture_set.json");
        if (setBytes == null) {
            return false;
        }
        JsonObject set = JsonParser.parseString(new String(setBytes, StandardCharsets.UTF_8)).getAsJsonObject()
            .getAsJsonObject("minecraft:texture_set");
        if (set == null) {
            return false;
        }
        String dir = bedrockPath.contains("/") ? bedrockPath.substring(0, bedrockPath.lastIndexOf('/') + 1) : "";

        TgaReader.Image color = image(zip, root + dir, set.get("color"));
        JsonElement merSource = set.has("metalness_emissive_roughness_subsurface")
            ? set.get("metalness_emissive_roughness_subsurface") : set.get("metalness_emissive_roughness");
        boolean hasSubsurface = set.has("metalness_emissive_roughness_subsurface");
        TgaReader.Image mer = image(zip, root + dir, merSource);
        TgaReader.Image height = image(zip, root + dir, set.get("heightmap"));
        TgaReader.Image normal = image(zip, root + dir, set.get("normal"));

        int width = color != null ? color.width() : mer != null ? mer.width() : 16;
        int heightPx = color != null ? color.height() : mer != null ? mer.height() : 16;
        String target = "assets/minecraft/textures/block/" + javaName;

        if (color != null && colorCompatible) {
            write(zos, target + ".png", png(color));
        }

        int[] merUniform = uniform(merSource);
        if (mer != null || merUniform != null) {
            TgaReader.Image merImage = mer != null ? mer.resized(width, heightPx) : null;
            int[] specular = new int[width * heightPx];
            for (int i = 0; i < specular.length; i++) {
                int m, e, r, s;
                if (merImage != null) {
                    int p = merImage.argb()[i];
                    m = p >> 16 & 0xFF;
                    e = p >> 8 & 0xFF;
                    r = p & 0xFF;
                    s = hasSubsurface ? p >>> 24 : 0;
                } else {
                    m = merUniform[0];
                    e = merUniform[1];
                    r = merUniform[2];
                    s = hasSubsurface && merUniform.length > 3 ? merUniform[3] : 0;
                }
                specular[i] = labPbrSpecular(m, e, r, s);
            }
            write(zos, target + "_s.png", png(new TgaReader.Image(width, heightPx, specular)));
        }

        if (normal != null) {
            write(zos, target + "_n.png", png(labPbrFromNormal(normal.resized(width, heightPx))));
        } else if (height != null) {
            write(zos, target + "_n.png", png(labPbrFromHeight(height.resized(width, heightPx))));
        }
        return true;
    }

    /** LabPBR specular texel from Bedrock metalness, emission, roughness and subsurface (all 0-255). */
    static int labPbrSpecular(int metalness, int emission, int roughness, int subsurface) {
        // Bedrock roughness is perceptual, LabPBR stores perceptual smoothness.
        int smoothness = 255 - roughness;
        // LabPBR has no partial metals: the metal reflects its colour, anything else is a 4 % dielectric.
        int f0 = metalness >= 128 ? ALBEDO_METAL : DIELECTRIC_F0;
        int porositySss = subsurface > 0 ? 65 + subsurface * 190 / 255 : 0;
        int emissive = emission > 0 ? Math.min(254, emission * EMISSION_SCALE) : 255;
        return emissive << 24 | smoothness << 16 | f0 << 8 | porositySss;
    }

    /** LabPBR normal map from a Bedrock height map: normal in red and green, full AO in blue, flat height in alpha. */
    static TgaReader.Image labPbrFromHeight(TgaReader.Image height) {
        int w = height.width();
        int h = height.height();
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float du = (grey(height.get(x + 1, y)) - grey(height.get(x - 1, y))) * 0.5f;
                // Texture v runs up, image rows run down.
                float dv = (grey(height.get(x, y - 1)) - grey(height.get(x, y + 1))) * 0.5f;
                out[y * w + x] = encodeNormal(-du * HEIGHT_TO_NORMAL, -dv * HEIGHT_TO_NORMAL, 1.0f);
            }
        }
        return new TgaReader.Image(w, h, out);
    }

    /** A Bedrock normal map re-encoded for LabPBR. */
    static TgaReader.Image labPbrFromNormal(TgaReader.Image normal) {
        int[] out = new int[normal.argb().length];
        for (int i = 0; i < out.length; i++) {
            int p = normal.argb()[i];
            float nx = (p >> 16 & 0xFF) / 127.5f - 1.0f;
            float ny = (p >> 8 & 0xFF) / 127.5f - 1.0f;
            float nz = (p & 0xFF) / 127.5f - 1.0f;
            out[i] = encodeNormal(nx, ny, Math.max(nz, 0.05f));
        }
        return new TgaReader.Image(normal.width(), normal.height(), out);
    }

    private static int encodeNormal(float x, float y, float z) {
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        int r = Math.round((x / length * 0.5f + 0.5f) * 255.0f);
        int g = Math.round((y / length * 0.5f + 0.5f) * 255.0f);
        return FLAT_HEIGHT << 24 | r << 16 | g << 8 | 255;
    }

    private static float grey(int argb) {
        return ((argb >> 16 & 0xFF) + (argb >> 8 & 0xFF) + (argb & 0xFF)) / (3.0f * 255.0f);
    }

    /** A texture set layer: an image by name next to the set, or null for a missing or uniform layer. */
    private static TgaReader.Image image(ZipFile zip, String dir, JsonElement layer) throws IOException {
        if (layer == null || !layer.isJsonPrimitive() || !layer.getAsJsonPrimitive().isString()) {
            return null;
        }
        String value = layer.getAsString();
        if (value.startsWith("#")) {
            return null;
        }
        byte[] tga = read(zip, dir + value + ".tga");
        if (tga != null) {
            return TgaReader.read(tga);
        }
        byte[] png = read(zip, dir + value + ".png");
        if (png == null) {
            png = read(zip, dir + value + ".jpg");
        }
        if (png == null) {
            return null;
        }
        BufferedImage buffered = ImageIO.read(new ByteArrayInputStream(png));
        if (buffered == null) {
            return null;
        }
        int w = buffered.getWidth();
        int h = buffered.getHeight();
        return new TgaReader.Image(w, h, buffered.getRGB(0, 0, w, h, null, 0, w));
    }

    /** A layer given as one value for the whole texture: [r, g, b(, a)] or "#rrggbb(aa)". */
    private static int[] uniform(JsonElement layer) {
        if (layer == null) {
            return null;
        }
        if (layer.isJsonArray()) {
            JsonArray array = layer.getAsJsonArray();
            int[] values = new int[array.size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = Math.clamp(Math.round(array.get(i).getAsFloat()), 0, 255);
            }
            return values.length >= 3 ? values : null;
        }
        if (layer.isJsonPrimitive() && layer.getAsString().startsWith("#")) {
            String hex = layer.getAsString().substring(1);
            int[] values = new int[hex.length() / 2];
            for (int i = 0; i < values.length; i++) {
                values[i] = Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
            return values.length >= 3 ? values : null;
        }
        return null;
    }

    private static byte[] png(TgaReader.Image image) throws IOException {
        BufferedImage buffered = new BufferedImage(image.width(), image.height(), BufferedImage.TYPE_INT_ARGB);
        buffered.setRGB(0, 0, image.width(), image.height(), image.argb(), 0, image.width());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(buffered, "png", out);
        return out.toByteArray();
    }

    /** The folder the pack's manifest is in: some .mcpack files wrap the pack in a folder of its own. */
    private static String findPackRoot(ZipFile zip) {
        String best = null;
        for (var entries = zip.entries(); entries.hasMoreElements(); ) {
            String name = entries.nextElement().getName();
            if (name.endsWith("manifest.json")) {
                String root = name.substring(0, name.length() - "manifest.json".length());
                if (best == null || root.length() < best.length()) {
                    best = root;
                }
            }
        }
        return best == null ? "" : best;
    }

    private static String packName(ZipFile zip, String root, Path source) {
        try {
            byte[] manifest = read(zip, root + "manifest.json");
            if (manifest != null) {
                JsonObject header = JsonParser.parseString(new String(manifest, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("header");
                if (header != null && header.has("name")) {
                    return header.get("name").getAsString().replaceAll("§.", "");
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // A manifest Bedrock itself would reject still leaves a usable pack; name it after the file.
        }
        String file = source.getFileName().toString();
        return file.substring(0, file.length() - ".mcpack".length());
    }

    private static byte[] read(ZipFile zip, String path) throws IOException {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    private static void write(ZipOutputStream zos, String path, byte[] data) throws IOException {
        zos.putNextEntry(new ZipEntry(path));
        zos.write(data);
        zos.closeEntry();
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
