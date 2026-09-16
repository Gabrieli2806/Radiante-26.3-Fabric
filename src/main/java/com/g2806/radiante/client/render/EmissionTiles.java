package com.g2806.radiante.client.render;

import com.g2806.radiante.client.proxy.vulkan.TextureProxy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.data.AtlasIds;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.system.MemoryUtil;

/**
 * Makes light emitting blocks glow and light up their surroundings. Their bright texels are written into a LabPBR
 * specular atlas (emission in alpha) mapped onto the block atlas, and registered as emitter cells the renderer
 * samples as light sources. Vanilla textures have no emission maps: a block's light level decides how strong
 * it is, and each part of the texture emits by its own brightness, so a torch stick stays dark while the flame glows.
 */
public final class EmissionTiles {

    /** Cells per sprite side; small enough for 16px textures to keep flames separate from their stick. */
    private static final int CELLS_PER_SIDE = 4;
    /** Texels darker than this do not emit. */
    private static final float MIN_LUMINANCE = 0.55f;
    private static final int CELL_BYTES = 8 * Float.BYTES;
    /** Scales how much light emitters cast on their surroundings. */
    private static final float LIGHT_STRENGTH = 8.0f;

    /** Renderer id of the generated LabPBR specular atlas that carries per-texel emission, or -1. */
    private static int specularTextureId = -1;
    private static int specularWidth;
    private static int specularHeight;
    private static int specularAtlasId = -1;
    private static final Identifier[] LAVA_SPRITES = {
        Identifier.withDefaultNamespace("block/lava_still"),
        Identifier.withDefaultNamespace("block/lava_flow"),
    };

    private static Object registeredFor;

    private EmissionTiles() {
    }

    public static void invalidate() {
        registeredFor = null;
    }

    /** Registers the emitters once per block atlas; call on the render thread once models are loaded. */
    public static void registerIfNeeded(Minecraft minecraft) {
        TextureAtlas atlas = minecraft.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
        Object key = atlas.getTexture();
        if (key == null || key == registeredFor) {
            return;
        }

        int atlasId = TextureTracker.idOf(atlas.getTexture());
        if (atlasId == 0) {
            return;
        }
        registeredFor = key;

        Map<TextureAtlasSprite, Integer> emitters = collectEmissiveSprites(minecraft, atlas);
        int atlasWidth = atlas.getTexture().getWidth(0);
        int atlasHeight = atlas.getTexture().getHeight(0);
        int mipLevels = atlas.getTexture().getMipLevels();
        ByteBuffer specular = MemoryUtil.memCalloc(atlasWidth * atlasHeight * 4);
        try {
            for (Map.Entry<TextureAtlasSprite, Integer> entry : emitters.entrySet()) {
                upload(atlasId, entry.getKey(), entry.getValue(), atlasWidth, atlasHeight, specular);
            }
            uploadSpecularAtlas(atlasId, specular, atlasWidth, atlasHeight, mipLevels);
        } finally {
            MemoryUtil.memFree(specular);
        }

        RadianteRenderer.LOGGER.info("Registered {} emissive block textures", emitters.size());
        ChunkManager.markAllDirty();
    }

    private static Map<TextureAtlasSprite, Integer> collectEmissiveSprites(Minecraft minecraft, TextureAtlas atlas) {
        Map<TextureAtlasSprite, Integer> emitters = new IdentityHashMap<>();
        RandomSource random = RandomSource.create(42L);
        List<BlockStateModelPart> parts = new ArrayList<>();

        for (Block block : BuiltInRegistries.BLOCK) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                int level = state.getLightEmission();
                if (level <= 0) {
                    continue;
                }

                BlockStateModel model = minecraft.getModelManager().getBlockStateModelSet().get(state);
                parts.clear();
                random.setSeed(42L);
                model.collectParts(random, parts);
                for (BlockStateModelPart part : parts) {
                    for (Direction direction : Direction.values()) {
                        addSprites(part.getQuads(direction), level, emitters);
                    }
                    addSprites(part.getQuads(null), level, emitters);
                }
            }
        }

        // Lava is drawn by the fluid renderer and has no block model.
        for (Identifier id : LAVA_SPRITES) {
            TextureAtlasSprite sprite = atlas.getSprite(id);
            if (sprite != null) {
                emitters.merge(sprite, 15, Math::max);
            }
        }
        return emitters;
    }

    private static void addSprites(List<BakedQuad> quads, int level, Map<TextureAtlasSprite, Integer> emitters) {
        for (BakedQuad quad : quads) {
            emitters.merge(quad.materialInfo().sprite(), level, Math::max);
        }
    }

    private static void upload(int atlasId, TextureAtlasSprite sprite, int level, int atlasWidth, int atlasHeight,
        ByteBuffer specular) {
        SpriteContents contents = sprite.contents();
        ByteBuffer[] mips = ((SpriteContentsAccess) contents).radiante$mipImages();
        if (mips.length == 0 || mips[0] == null) {
            return;
        }

        ByteBuffer pixels = mips[0];
        int width = contents.width();
        int height = contents.height();
        int rowPixels = Math.max(1, ((SpriteContentsAccess) contents).radiante$mipWidth(0));
        float strength = level / 15.0f;
        int cellWidth = Math.max(1, width / CELLS_PER_SIDE);
        int cellHeight = Math.max(1, height / CELLS_PER_SIDE);

        List<float[]> cells = new ArrayList<>();
        for (int cellY = 0; cellY < height; cellY += cellHeight) {
            for (int cellX = 0; cellX < width; cellX += cellWidth) {
                float emission = 0.0f;
                float red = 0.0f;
                float green = 0.0f;
                float blue = 0.0f;
                int texels = 0;
                for (int y = cellY; y < Math.min(height, cellY + cellHeight); y++) {
                    for (int x = cellX; x < Math.min(width, cellX + cellWidth); x++) {
                        int index = (y * rowPixels + x) * 4;
                        if (index + 3 >= pixels.capacity()) {
                            continue;
                        }
                        float r = (pixels.get(index) & 0xFF) / 255.0f;
                        float g = (pixels.get(index + 1) & 0xFF) / 255.0f;
                        float b = (pixels.get(index + 2) & 0xFF) / 255.0f;
                        float a = (pixels.get(index + 3) & 0xFF) / 255.0f;
                        texels++;
                        float luminance = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                        if (a < 0.1f || luminance < MIN_LUMINANCE) {
                            continue;
                        }
                        float weight = a * luminance;
                        writeSpecularEmission(specular, atlasWidth, atlasHeight,
                            Math.round(sprite.getU0() * atlasWidth) + x, Math.round(sprite.getV0() * atlasHeight) + y,
                            strength * luminance);
                        emission += weight;
                        red += r * weight;
                        green += g * weight;
                        blue += b * weight;
                    }
                }

                if (emission <= 0.0f || texels == 0) {
                    continue;
                }

                float u0 = (sprite.getU0() * atlasWidth + cellX) / atlasWidth;
                float v0 = (sprite.getV0() * atlasHeight + cellY) / atlasHeight;
                float u1 = (sprite.getU0() * atlasWidth + Math.min(width, cellX + cellWidth)) / atlasWidth;
                float v1 = (sprite.getV0() * atlasHeight + Math.min(height, cellY + cellHeight)) / atlasHeight;
                cells.add(new float[] {u0, v0, u1, v1, LIGHT_STRENGTH * strength * emission / texels, red / emission,
                    green / emission,
                    blue / emission});
            }
        }

        if (cells.isEmpty()) {
            return;
        }

        long address = MemoryUtil.nmemAllocChecked((long) cells.size() * CELL_BYTES);
        try {
            for (int i = 0; i < cells.size(); i++) {
                float[] cell = cells.get(i);
                for (int j = 0; j < cell.length; j++) {
                    MemoryUtil.memPutFloat(address + (long) i * CELL_BYTES + (long) j * Float.BYTES, cell[j]);
                }
            }
            long tileKey = ((long) sprite.getX() << 32) ^ sprite.getY() ^ ((long) width << 48) ^ height;
            TextureProxy.uploadEmissionTile(atlasId, tileKey, address, cells.size());
        } finally {
            MemoryUtil.nmemFree(address);
        }
    }

    /** The specular texture mapped onto the block atlas, used by BufferProxy.updateMapping. */
    public static int specularTextureFor(int albedoTextureId) {
        return albedoTextureId == specularAtlasId ? specularTextureId : -1;
    }

    /** LabPBR stores emission in the specular alpha: 0..254 scale linearly, 255 means none. */
    private static void writeSpecularEmission(ByteBuffer specular, int width, int height, int x, int y,
        float emission) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return;
        }
        int value = Math.max(1, Math.min(254, Math.round(emission * 254.0f)));
        specular.put((y * width + x) * 4 + 3, (byte) value);
    }

    /**
     * Everything outside emissive texels stays zero, which the shaders read exactly like a missing specular
     * texture, so only the emission changes.
     */
    private static void uploadSpecularAtlas(int atlasId, ByteBuffer level0, int width, int height, int mipLevels) {
        if (specularTextureId < 0) {
            specularTextureId = TextureProxy.generateTextureId();
            specularWidth = 0;
        }
        if (specularWidth != width || specularHeight != height) {
            TextureProxy.prepareImage(specularTextureId, mipLevels, width, height,
                com.mojang.renderpearl.backend.vulkan.VulkanConst.toVk(com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM));
            specularWidth = width;
            specularHeight = height;
        }

        ByteBuffer current = level0;
        int levelWidth = width;
        int levelHeight = height;
        for (int level = 0; level < mipLevels; level++) {
            TextureProxy.queueUpload(MemoryUtil.memAddress(current), levelWidth * levelHeight * 4, levelWidth,
                specularTextureId, 0, 0, 0, 0, levelWidth, levelHeight, level);
            if (level + 1 >= mipLevels) {
                break;
            }

            int nextWidth = Math.max(1, levelWidth / 2);
            int nextHeight = Math.max(1, levelHeight / 2);
            ByteBuffer next = MemoryUtil.memCalloc(nextWidth * nextHeight * 4);
            for (int y = 0; y < nextHeight; y++) {
                for (int x = 0; x < nextWidth; x++) {
                    int max = 0;
                    for (int dy = 0; dy < 2; dy++) {
                        for (int dx = 0; dx < 2; dx++) {
                            int sx = Math.min(levelWidth - 1, x * 2 + dx);
                            int sy = Math.min(levelHeight - 1, y * 2 + dy);
                            max = Math.max(max, current.get((sy * levelWidth + sx) * 4 + 3) & 0xFF);
                        }
                    }
                    next.put((y * nextWidth + x) * 4 + 3, (byte) max);
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
        specularAtlasId = atlasId;
    }
}
