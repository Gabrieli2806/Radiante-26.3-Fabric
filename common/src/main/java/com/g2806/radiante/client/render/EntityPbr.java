package com.g2806.radiante.client.render;

import com.g2806.radiante.client.option.Options;
import com.g2806.radiante.mixin.texture.TextureAtlasAccessor;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.system.MemoryUtil;

/**
 * LabPBR maps for everything that is not a block: entities, block entities and held items. A pack ships them the
 * same way as for blocks, "_s" and "_n" next to the texture ({@code textures/entity/minecart_s.png}), and the ray
 * tracer finds them through the same texture mapping, keyed by the renderer id of the texture the geometry samples.
 *
 * <p>Two kinds of textures are covered. Standalone ones (a minecart, most mobs) are picked up the first time a
 * render type samples them, and their maps are uploaded whole. Atlases (chests, shulker boxes, decorated pots,
 * banner and shield patterns, items) get maps stitched sprite by sprite, like the block atlas. Everything is thrown
 * away and built again after a resource reload.
 */
public final class EntityPbr {

    /** Renderer ids of one texture's maps; -1 where the pack has none. */
    private record Maps(int albedoId, int specular, int normal) {
    }

    private static final Maps NONE = new Maps(0, -1, -1);

    /** Atlases whose sprites are drawn as entity geometry. The block atlas has its own maps (PbrAtlases). */
    private static final List<Identifier> ATLASES = List.of(AtlasIds.CHESTS, AtlasIds.SHULKER_BOXES,
        AtlasIds.DECORATED_POT, AtlasIds.BANNER_PATTERNS, AtlasIds.SHIELD_PATTERNS, AtlasIds.ITEMS);

    /** Written on the render thread, read from whichever thread collects entities. */
    private static final Map<Identifier, Maps> STANDALONE = new ConcurrentHashMap<>();
    private static final Set<Identifier> PENDING = ConcurrentHashMap.newKeySet();
    private static final Map<Identifier, Maps> ATLAS_MAPS = new HashMap<>();
    /** Atlases still to look at: one whose image the renderer has not mirrored yet is tried again next frame. */
    private static final Set<Identifier> ATLASES_PENDING = new java.util.HashSet<>();
    /** GPU images are kept per map across reloads and filled again, since the renderer has no way to free one. */
    private static final Map<Identifier, Integer> MAP_TEXTURE_IDS = new HashMap<>();
    private static Object builtFor;

    private EntityPbr() {
    }

    /** Called for every texture a render type samples; queues the ones not looked at since the last reload. */
    public static void note(Identifier texture, int albedoId) {
        if (texture == null || albedoId <= 0) {
            return;
        }
        Maps known = STANDALONE.get(texture);
        if (known == null || (known != NONE && known.albedoId() != albedoId)) {
            PENDING.add(texture);
        }
    }

    /** Loads what was queued, and rebuilds after a resource reload. Render thread, before the mapping is uploaded. */
    public static void update(Minecraft minecraft) {
        TextureAtlas blocks = minecraft.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
        Object key = TextureTracker.gpuTextureOrNull(blocks);
        if (key == null) {
            return;
        }
        ResourceManager resources = minecraft.getResourceManager();
        if (key != builtFor) {
            builtFor = key;
            STANDALONE.clear();
            ATLAS_MAPS.clear();
            ATLASES_PENDING.clear();
            ATLASES_PENDING.addAll(ATLASES);
        }
        if (!ATLASES_PENDING.isEmpty()) {
            ATLASES_PENDING.removeIf(atlasId -> buildAtlas(minecraft, resources, atlasId));
        }
        if (PENDING.isEmpty()) {
            return;
        }
        for (Identifier texture : List.copyOf(PENDING)) {
            PENDING.remove(texture);
            int albedoId = TextureTracker.idOf(texture);
            if (albedoId <= 0) {
                continue;
            }
            int specular = loadWhole(resources, texture, "_s");
            int normal = loadWhole(resources, texture, "_n");
            STANDALONE.put(texture, specular < 0 && normal < 0 ? NONE : new Maps(albedoId, specular, normal));
        }
    }

    /** Adds the maps to the texture mapping: three ints per albedo id (specular, normal, flags). */
    public static void writeMapping(ByteBuffer mapping, int entries) {
        for (Maps maps : STANDALONE.values()) {
            put(mapping, entries, maps);
        }
        for (Maps maps : ATLAS_MAPS.values()) {
            put(mapping, entries, maps);
        }
    }

    private static void put(ByteBuffer mapping, int entries, Maps maps) {
        if (maps == NONE || maps.albedoId() <= 0 || maps.albedoId() >= entries) {
            return;
        }
        int entry = maps.albedoId() * 3 * Integer.BYTES;
        if (maps.specular() >= 0) {
            mapping.putInt(entry, maps.specular());
        }
        if (maps.normal() >= 0) {
            mapping.putInt(entry + Integer.BYTES, maps.normal());
        }
    }

    /** A standalone texture's map, uploaded as it is; -1 when the pack has none. */
    private static int loadWhole(ResourceManager resources, Identifier texture, String suffix) {
        Identifier mapId = mapOf(texture, suffix);
        Optional<Resource> resource = resources.getResource(mapId);
        if (resource.isEmpty()) {
            return -1;
        }
        try (InputStream stream = resource.get().open(); NativeImage image = NativeImage.read(stream)) {
            int width = image.getWidth();
            int height = image.getHeight();
            ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
            try {
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        pixels.putInt((y * width + x) * 4, PbrAtlases.toRgba(image.getPixel(x, y)));
                    }
                }
                return PbrAtlases.upload(textureIdFor(mapId), pixels, width, height, 1);
            } finally {
                MemoryUtil.memFree(pixels);
            }
        } catch (Exception e) {
            RadianteRenderer.LOGGER.warn("Could not read PBR map {}", mapId, e);
            return -1;
        }
    }

    /**
     * Stitches the maps of an atlas's sprites, if the pack has any for them. False while the atlas is not ready to be
     * looked at yet, so it is tried again.
     */
    private static boolean buildAtlas(Minecraft minecraft, ResourceManager resources, Identifier atlasId) {
        TextureAtlas atlas;
        try {
            atlas = minecraft.getAtlasManager().getAtlasOrThrow(atlasId);
        } catch (RuntimeException missing) {
            return true;
        }
        int albedoId = TextureTracker.idOf(TextureTracker.gpuTextureOrNull(atlas));
        Map<Identifier, TextureAtlasSprite> sprites = ((TextureAtlasAccessor) (Object) atlas).radiante$texturesByName();
        if (albedoId <= 0 || sprites.isEmpty()) {
            return false;
        }
        // Most packs cover blocks only; an atlas nothing is authored for costs no memory at all.
        boolean anySpecular = false;
        boolean anyNormal = false;
        for (Identifier spriteId : sprites.keySet()) {
            if (PbrAtlases.isPackMap(sprites, spriteId)) {
                continue;
            }
            anySpecular |= resources.getResource(spriteMapOf(spriteId, "_s")).isPresent();
            anyNormal |= resources.getResource(spriteMapOf(spriteId, "_n")).isPresent();
        }
        if (!anySpecular && !anyNormal) {
            return true;
        }

        int width = atlas.getTexture().getWidth(0);
        int height = atlas.getTexture().getHeight(0);
        int mipLevels = atlas.getTexture().getMipLevels();
        int specular = -1;
        int normal = -1;
        if (anySpecular) {
            specular = stitchAtlas(resources, sprites, atlasId, "_s", 0, width, height, mipLevels);
        }
        if (anyNormal) {
            normal = stitchAtlas(resources, sprites, atlasId, "_n", PbrAtlases.NORMAL_DEFAULT, width, height,
                mipLevels);
        }
        ATLAS_MAPS.put(atlasId, new Maps(albedoId, specular, normal));
        if (Options.debugLogging) {
            RadianteRenderer.LOGGER.info("Stitched PBR maps for the {} atlas", atlasId);
        }
        return true;
    }

    private static int stitchAtlas(ResourceManager resources, Map<Identifier, TextureAtlasSprite> sprites,
        Identifier atlasId, String suffix, int neutral, int width, int height, int mipLevels) {
        ByteBuffer target = PbrAtlases.fill(width, height, neutral);
        try {
            for (Map.Entry<Identifier, TextureAtlasSprite> entry : sprites.entrySet()) {
                if (!PbrAtlases.isPackMap(sprites, entry.getKey())) {
                    PbrAtlases.stitch(resources, entry.getKey(), entry.getValue(), suffix, target, width, height);
                }
            }
            Identifier mapId = Identifier.fromNamespaceAndPath(atlasId.getNamespace(),
                "radiante_atlas_" + atlasId.getPath() + suffix);
            return PbrAtlases.upload(textureIdFor(mapId), target, width, height, mipLevels);
        } finally {
            MemoryUtil.memFree(target);
        }
    }

    private static int textureIdFor(Identifier mapId) {
        return MAP_TEXTURE_IDS.computeIfAbsent(mapId,
            id -> com.g2806.radiante.client.proxy.vulkan.TextureProxy.generateTextureId());
    }

    /** "textures/entity/minecart.png" to "textures/entity/minecart_s.png". */
    private static Identifier mapOf(Identifier texture, String suffix) {
        String path = texture.getPath();
        String base = path.endsWith(".png") ? path.substring(0, path.length() - 4) : path;
        return Identifier.fromNamespaceAndPath(texture.getNamespace(), base + suffix + ".png");
    }

    /** A sprite "entity/chest/normal" has its map at "textures/entity/chest/normal_s.png", as PbrAtlases reads it. */
    private static Identifier spriteMapOf(Identifier spriteId, String suffix) {
        return Identifier.fromNamespaceAndPath(spriteId.getNamespace(),
            "textures/" + spriteId.getPath() + suffix + ".png");
    }
}
