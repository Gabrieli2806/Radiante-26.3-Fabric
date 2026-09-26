package com.g2806.radiante.client.render;

import com.g2806.radiante.client.proxy.vulkan.TextureProxy;
import com.g2806.radiante.mixin.texture.TextureAtlasAccessor;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.system.MemoryUtil;

/**
 * Stitches the PBR maps a resource pack ships next to its textures onto atlases that match the block atlas, so the
 * ray tracer can read roughness, metalness, emission and surface normals per texel. This is the LabPBR layout every
 * shader pack uses: "_s" carries roughness, metalness and emission, "_n" carries the normal plus ambient occlusion
 * and height. Textures without these files keep their neutral defaults and look exactly as before.
 */
public final class PbrAtlases {

    /** A normal map of all zeroes reads as "no map"; the alpha keeps the height at its neutral value. */
    static final int NORMAL_DEFAULT = 0xFF000000;
    private static final int SPECULAR_DEFAULT = 0x00000000;

    private static int specularTextureId = -1;
    private static int normalTextureId = -1;
    private static int atlasId = -1;
    private static int spriteCount;

    private PbrAtlases() {
    }

    public static void invalidate() {
        atlasId = -1;
        spriteCount = 0;
    }

    /** The specular map stitched for this atlas, or -1 when the pack ships none. */
    public static int specularTextureFor(int albedoTextureId) {
        return albedoTextureId == atlasId && spriteCount > 0 ? specularTextureId : -1;
    }

    /** The normal map stitched for this atlas, or -1 when the pack ships none. */
    public static int normalTextureFor(int albedoTextureId) {
        return albedoTextureId == atlasId && spriteCount > 0 ? normalTextureId : -1;
    }

    /**
     * Builds both maps for an atlas. Returns the number of sprites a map was found for, zero when the pack ships
     * none, in which case nothing is uploaded and the renderer keeps its own generated emission.
     */
    public static int build(Minecraft minecraft, TextureAtlas atlas, int albedoTextureId,
        @org.jetbrains.annotations.Nullable ByteBuffer specularSeed) {
        Map<Identifier, TextureAtlasSprite> sprites = ((TextureAtlasAccessor) (Object) atlas).radiante$texturesByName();
        if (sprites.isEmpty()) {
            return 0;
        }

        int width = atlas.getTexture().getWidth(0);
        int height = atlas.getTexture().getHeight(0);
        int mipLevels = atlas.getTexture().getMipLevels();
        ResourceManager resources = minecraft.getResourceManager();

        // Emission Radiante derived from the albedo comes in as the seed, so a block whose map nobody authored -
        // a lit redstone lamp, say - keeps the glow that was worked out for it, and an authored map simply writes
        // over its own texels. Without this a single authored map anywhere turned the derived emission off for
        // every block at once, which is what left redstone lamps dark with vanilla textures.
        ByteBuffer specular = specularSeed != null ? specularSeed : fill(width, height, SPECULAR_DEFAULT);
        boolean ownsSpecular = specularSeed == null;
        ByteBuffer normal = fill(width, height, NORMAL_DEFAULT);
        int found = 0;
        try {
            for (Map.Entry<Identifier, TextureAtlasSprite> entry : sprites.entrySet()) {
                if (isPackMap(sprites, entry.getKey())) {
                    continue;
                }
                boolean any = stitch(resources, entry.getKey(), entry.getValue(), "_s", specular, width, height);
                any |= stitch(resources, entry.getKey(), entry.getValue(), "_n", normal, width, height);
                if (any) {
                    found++;
                }
            }

            if (found == 0) {
                return 0;
            }

            specularTextureId = upload(specularTextureId, specular, width, height, mipLevels);
            normalTextureId = upload(normalTextureId, normal, width, height, mipLevels);
        } finally {
            if (ownsSpecular) {
                MemoryUtil.memFree(specular);
            }
            MemoryUtil.memFree(normal);
        }

        atlasId = albedoTextureId;
        spriteCount = found;
        if (com.g2806.radiante.client.option.Options.debugLogging) {
            RadianteRenderer.LOGGER.info("Stitched PBR maps for {} of {} sprites", found, sprites.size());
        }
        return found;
    }

    /**
     * True for a sprite that is itself one of a pack's PBR maps. Minecraft 26.3 builds the block atlas from a
     * directory source, so every PNG under textures/block - "stone_s.png" and "stone_n.png" included - is stitched
     * in as a sprite of its own. Those have to be skipped as bases, but they must not be refused as maps: an
     * earlier version rejected any map that was also a sprite, and with a directory source that is every map a
     * pack ships, so no PBR pack worked at all. Vanilla has no texture named after another with these suffixes.
     */
    static boolean isPackMap(Map<Identifier, TextureAtlasSprite> sprites, Identifier spriteId) {
        String path = spriteId.getPath();
        if (!path.endsWith("_s") && !path.endsWith("_n")) {
            return false;
        }
        Identifier base = Identifier.fromNamespaceAndPath(spriteId.getNamespace(),
            path.substring(0, path.length() - 2));
        return sprites.containsKey(base);
    }

    static ByteBuffer fill(int width, int height, int value) {
        ByteBuffer buffer = MemoryUtil.memAlloc(width * height * 4);
        for (int i = 0; i < width * height; i++) {
            buffer.putInt(i * 4, value);
        }
        return buffer;
    }

    /**
     * Copies one sprite's map into the atlas. Packs are free to author these at a different resolution than the
     * base texture, and animated textures stack their frames, so only the first frame is read and it is point
     * sampled to the size the sprite occupies.
     */
    static boolean stitch(ResourceManager resources, Identifier spriteId, TextureAtlasSprite sprite,
        String suffix, ByteBuffer target, int width, int height) {
        Identifier mapId = Identifier.fromNamespaceAndPath(spriteId.getNamespace(),
            "textures/" + spriteId.getPath() + suffix + ".png");
        Optional<Resource> resource = resources.getResource(mapId);
        if (resource.isEmpty()) {
            return false;
        }

        try (InputStream stream = resource.get().open(); NativeImage image = NativeImage.read(stream)) {
            int spriteWidth = sprite.contents().width();
            int spriteHeight = sprite.contents().height();
            int frameHeight = Math.max(1, image.getWidth() * spriteHeight / Math.max(1, spriteWidth));
            frameHeight = Math.min(frameHeight, image.getHeight());

            int originX = Math.round(sprite.getU0() * width);
            int originY = Math.round(sprite.getV0() * height);
            for (int y = 0; y < spriteHeight; y++) {
                int sourceY = y * frameHeight / spriteHeight;
                for (int x = 0; x < spriteWidth; x++) {
                    int sourceX = x * image.getWidth() / spriteWidth;
                    int targetX = originX + x;
                    int targetY = originY + y;
                    if (targetX < 0 || targetY < 0 || targetX >= width || targetY >= height) {
                        continue;
                    }
                    target.putInt(((targetY * width) + targetX) * 4, toRgba(image.getPixel(sourceX, sourceY)));
                }
            }
            return true;
        } catch (IOException | RuntimeException e) {
            RadianteRenderer.LOGGER.warn("Could not read PBR map {}", mapId, e);
            return false;
        }
    }

    /** NativeImage hands out ARGB, the atlas stores RGBA in memory order. */
    static int toRgba(int argb) {
        int alpha = (argb >>> 24) & 0xFF;
        int red = (argb >>> 16) & 0xFF;
        int green = (argb >>> 8) & 0xFF;
        int blue = argb & 0xFF;
        return (alpha << 24) | (blue << 16) | (green << 8) | red;
    }

    static int upload(int textureId, ByteBuffer level0, int width, int height, int mipLevels) {
        if (textureId < 0) {
            textureId = TextureProxy.generateTextureId();
        }
        TextureProxy.prepareImage(textureId, mipLevels, width, height, VulkanConst.toVk(GpuFormat.RGBA8_UNORM));

        ByteBuffer current = level0;
        int levelWidth = width;
        int levelHeight = height;
        for (int level = 0; level < mipLevels; level++) {
            TextureProxy.queueUpload(MemoryUtil.memAddress(current), levelWidth * levelHeight * 4, levelWidth,
                textureId, 0, 0, 0, 0, levelWidth, levelHeight, level);
            if (level + 1 >= mipLevels) {
                break;
            }

            int nextWidth = Math.max(1, levelWidth / 2);
            int nextHeight = Math.max(1, levelHeight / 2);
            ByteBuffer next = MemoryUtil.memAlloc(nextWidth * nextHeight * 4);
            // Point sampling, because averaging normals or material flags of neighbouring texels is meaningless.
            for (int y = 0; y < nextHeight; y++) {
                for (int x = 0; x < nextWidth; x++) {
                    int sourceX = Math.min(levelWidth - 1, x * 2);
                    int sourceY = Math.min(levelHeight - 1, y * 2);
                    next.putInt((y * nextWidth + x) * 4, current.getInt((sourceY * levelWidth + sourceX) * 4));
                }
            }
            if (current != level0) {
                MemoryUtil.memFree(current);
            }
            current = next;
            levelWidth = nextWidth;
            levelHeight = nextHeight;
        }
        if (current != level0) {
            MemoryUtil.memFree(current);
        }
        return textureId;
    }
}
