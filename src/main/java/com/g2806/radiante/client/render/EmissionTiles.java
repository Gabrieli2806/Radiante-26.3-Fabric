package com.g2806.radiante.client.render;

import com.g2806.radiante.client.proxy.vulkan.TextureProxy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    /** Texels whose brightest channel is below this do not emit. */
    private static final float MIN_BRIGHTNESS = 0.55f;
    /** Floor for textures that never reach MIN_BRIGHTNESS anywhere. */
    private static final float DARK_EMITTER_MIN_BRIGHTNESS = 0.15f;
    /** For those dark emitters, a texel emits when it is this bright relative to the brightest texel. */
    private static final float RELATIVE_BRIGHTNESS = 0.6f;
    /**
     * And for every other texture, how bright a texel has to be next to the brightest one in the same sprite. A
     * torch is the reason this exists: its flame sits at full brightness while its wooden stick reaches 0.62, above
     * the absolute floor, so on that alone the whole torch glowed - stick included.
     */
    private static final float BRIGHT_EMITTER_RELATIVE = 0.65f;
    /** Texels at least this saturated count as the coloured, glowing part of a texture. */
    private static final float MIN_GLOW_SATURATION = 0.35f;
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

    /**
     * Blocks Radiante lights that vanilla leaves dark. A block of redstone carries no light level at all in
     * Minecraft, but it reads as a solid block of glowing mineral and Bedrock's ray traced mode treats it as one,
     * so it gets a low level of its own here. Kept deliberately dim: it is decoration, not a torch.
     */
    private static final Map<Block, Integer> EXTRA_EMISSION = Map.of(
        net.minecraft.world.level.block.Blocks.REDSTONE_BLOCK, 5);

    private static Object registeredFor;

    private EmissionTiles() {
    }

    public static void invalidate() {
        registeredFor = null;
        PbrAtlases.invalidate();
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

        // A resource pack that ships its own PBR maps knows better than anything we can derive from the albedo,
        // but the emitter cells are still needed either way: they are what makes a torch light up the room rather
        // than merely look bright.
        boolean packMaps = PbrAtlases.build(minecraft, atlas, atlasId) > 0;

        Map<TextureAtlasSprite, Integer> emitters = collectEmissiveSprites(minecraft, atlas);
        int atlasWidth = atlas.getTexture().getWidth(0);
        int atlasHeight = atlas.getTexture().getHeight(0);
        int mipLevels = atlas.getTexture().getMipLevels();
        ByteBuffer specular = MemoryUtil.memCalloc(atlasWidth * atlasHeight * 4);
        try {
            for (Map.Entry<TextureAtlasSprite, Integer> entry : emitters.entrySet()) {
                upload(atlasId, entry.getKey(), entry.getValue(), atlasWidth, atlasHeight,
                    packMaps ? null : specular);
            }
            if (!packMaps) {
                uploadSpecularAtlas(atlasId, specular, atlasWidth, atlasHeight, mipLevels);
            }
        } finally {
            MemoryUtil.memFree(specular);
        }

        if (com.g2806.radiante.client.option.Options.debugLogging) {
            RadianteRenderer.LOGGER.info("Registered {} emissive block textures", emitters.size());
        }
        ChunkManager.markAllDirty();
    }

    private static Map<TextureAtlasSprite, Integer> collectEmissiveSprites(Minecraft minecraft, TextureAtlas atlas) {
        Map<TextureAtlasSprite, Integer> emitters = new IdentityHashMap<>();
        // Textures are shared between blocks: a beacon is a light source and its model is built out of the ordinary
        // glass texture. Marking every sprite of an emissive block's model as emissive would light up every pane of
        // glass in the world, so a sprite that any block without light emission also uses is left alone.
        Set<TextureAtlasSprite> sharedWithDarkBlocks = Collections.newSetFromMap(new IdentityHashMap<>());
        RandomSource random = RandomSource.create(42L);
        List<BlockStateModelPart> parts = new ArrayList<>();

        for (Block block : BuiltInRegistries.BLOCK) {
            // Redstone ore is the same block and the same texture whether it is lit or not, and only the lit state
            // carries a light level. Blacklisting on the dark state alone therefore threw away the very sprite it
            // was meant to light, so what disqualifies a sprite is a block that emits nothing in any of its states.
            boolean emitsSomewhere = false;
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                if (state.getLightEmission() > 0) {
                    emitsSomewhere = true;
                    break;
                }
            }

            int extra = EXTRA_EMISSION.getOrDefault(block, 0);
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                int level = Math.max(state.getLightEmission(), extra);
                BlockStateModel model = minecraft.getModelManager().getBlockStateModelSet().get(state);
                parts.clear();
                random.setSeed(42L);
                model.collectParts(random, parts);
                for (BlockStateModelPart part : parts) {
                    for (Direction direction : Direction.values()) {
                        addSprites(part.getQuads(direction), level, emitsSomewhere || extra > 0, emitters,
                            sharedWithDarkBlocks);
                    }
                    addSprites(part.getQuads(null), level, emitsSomewhere || extra > 0, emitters,
                        sharedWithDarkBlocks);
                }
            }
        }

        emitters.keySet().removeAll(sharedWithDarkBlocks);

        // Lava is drawn by the fluid renderer and has no block model.
        for (Identifier id : LAVA_SPRITES) {
            TextureAtlasSprite sprite = atlas.getSprite(id);
            if (sprite != null) {
                emitters.merge(sprite, 15, Math::max);
            }
        }
        return emitters;
    }

    private static void addSprites(List<BakedQuad> quads, int level, boolean blockEmitsSomewhere,
        Map<TextureAtlasSprite, Integer> emitters, Set<TextureAtlasSprite> sharedWithDarkBlocks) {
        for (BakedQuad quad : quads) {
            TextureAtlasSprite sprite = quad.materialInfo().sprite();
            if (level > 0) {
                emitters.merge(sprite, level, Math::max);
            } else if (!blockEmitsSomewhere) {
                sharedWithDarkBlocks.add(sprite);
            }
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
        // Brightness is the strongest channel, not perceived luminance: pure red has a luminance of 0.21, so a
        // luminance cut skips the red of redstone and the orange of magma, and lets only the palest specks of lava
        // through. Textures that glow in colour also carry pale highlights and grey stone, so when a texture has
        // bright, saturated texels only those emit; the white glints on redstone ore stay dark. Textures that never
        // get bright anywhere, a nether portal glowing all over in dark purple, fall back to a relative cut.
        float brightest = maxBrightness(pixels, width, height, rowPixels);
        float threshold = brightest >= MIN_BRIGHTNESS
            ? Math.max(MIN_BRIGHTNESS, brightest * BRIGHT_EMITTER_RELATIVE)
            : Math.max(DARK_EMITTER_MIN_BRIGHTNESS, brightest * RELATIVE_BRIGHTNESS);
        boolean coloured = hasSaturatedTexels(pixels, width, height, rowPixels, threshold);
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
                        float brightness = Math.max(r, Math.max(g, b));
                        if (a < 0.1f || brightness < threshold
                            || (coloured && saturation(r, g, b) < MIN_GLOW_SATURATION)) {
                            continue;
                        }
                        float weight = a * brightness;
                        if (specular != null) {
                            writeSpecularEmission(specular, atlasWidth, atlasHeight,
                                Math.round(sprite.getU0() * atlasWidth) + x,
                                Math.round(sprite.getV0() * atlasHeight) + y, strength * brightness);
                        }
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

    private static float maxBrightness(ByteBuffer pixels, int width, int height, int rowPixels) {
        float max = 0.0f;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = (y * rowPixels + x) * 4;
                if (index + 3 >= pixels.capacity() || (pixels.get(index + 3) & 0xFF) < 26) {
                    continue;
                }
                max = Math.max(max, Math.max((pixels.get(index) & 0xFF),
                    Math.max(pixels.get(index + 1) & 0xFF, pixels.get(index + 2) & 0xFF)) / 255.0f);
            }
        }
        return max;
    }

    private static boolean hasSaturatedTexels(ByteBuffer pixels, int width, int height, int rowPixels,
        float threshold) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = (y * rowPixels + x) * 4;
                if (index + 3 >= pixels.capacity() || (pixels.get(index + 3) & 0xFF) < 26) {
                    continue;
                }
                float r = (pixels.get(index) & 0xFF) / 255.0f;
                float g = (pixels.get(index + 1) & 0xFF) / 255.0f;
                float b = (pixels.get(index + 2) & 0xFF) / 255.0f;
                if (Math.max(r, Math.max(g, b)) >= threshold && saturation(r, g, b) >= MIN_GLOW_SATURATION) {
                    return true;
                }
            }
        }
        return false;
    }

    private static float saturation(float r, float g, float b) {
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        return max <= 0.0f ? 0.0f : (max - min) / max;
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
