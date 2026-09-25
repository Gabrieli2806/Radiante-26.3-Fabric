package com.g2806.radiante.client.compat.distanthorizons;

import com.g2806.radiante.client.render.SpriteContentsAccess;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * What every block state looks like from far away: the average colour of the texture on its top and on its sides,
 * and how the biome tints each. Far terrain is drawn one colour per face, as Distant Horizons draws it; a texture
 * stretched over a column many blocks wide would only look worse.
 *
 * <p>Built on the render thread, where the block models and sprites can be read safely, and then only read from
 * the builder threads. A resource reload makes a new one.
 */
final class BlockLooks {

    /** How a face takes the biome's colour. */
    static final byte TINT_NONE = 0;
    static final byte TINT_GRASS = 1;
    static final byte TINT_FOLIAGE = 2;
    static final byte TINT_WATER = 3;

    /** Left out: nothing solid to stand on, like tall grass or flowers, which would draw as full cubes. */
    static final byte KIND_SKIP = 0;
    static final byte KIND_SOLID = 1;
    static final byte KIND_WATER = 2;

    private static final int ALPHA_CUTOFF = 26;

    private final byte[] kinds;
    private final int[] topColors;
    private final int[] sideColors;
    private final byte[] topTints;
    private final byte[] sideTints;
    private final byte[] emissions;
    /** The water texture, drawn on water surfaces so the tracer shades them as water. */
    final @Nullable TextureAtlasSprite waterSprite;

    private BlockLooks(int size, @Nullable TextureAtlasSprite waterSprite) {
        this.kinds = new byte[size];
        this.topColors = new int[size];
        this.sideColors = new int[size];
        this.topTints = new byte[size];
        this.sideTints = new byte[size];
        this.emissions = new byte[size];
        this.waterSprite = waterSprite;
    }

    byte kind(int stateId) {
        return stateId >= 0 && stateId < this.kinds.length ? this.kinds[stateId] : KIND_SKIP;
    }

    int topColor(int stateId) {
        return this.topColors[stateId];
    }

    int sideColor(int stateId) {
        return this.sideColors[stateId];
    }

    byte topTint(int stateId) {
        return this.topTints[stateId];
    }

    byte sideTint(int stateId) {
        return this.sideTints[stateId];
    }

    /** The block's light level, 0 to 15; far lava and glowstone glow like the near ones. */
    int lightEmission(int stateId) {
        return this.emissions[stateId];
    }

    /** Reads every block state's model; call on the render thread. */
    static BlockLooks build(Minecraft minecraft) {
        int size = Block.BLOCK_STATE_REGISTRY.size();
        BlockLooks looks = new BlockLooks(size, waterSprite(minecraft));
        Map<TextureAtlasSprite, Integer> averages = new IdentityHashMap<>();
        RandomSource random = RandomSource.create(42L);
        List<BlockStateModelPart> parts = new ArrayList<>();

        for (int id = 0; id < size; id++) {
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
            if (state == null) {
                continue;
            }
            if (state.isAir()) {
                continue;
            }
            boolean bodiless = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
            boolean fluid = !state.getFluidState().isEmpty();
            if (bodiless && state.getFluidState().is(FluidTags.WATER)) {
                // Water itself, or something with no body standing in it (seagrass, kelp).
                looks.kinds[id] = KIND_WATER;
                looks.topTints[id] = TINT_WATER;
                looks.sideTints[id] = TINT_WATER;
                looks.topColors[id] = 0xFFFFFFFF;
                looks.sideColors[id] = 0xFFFFFFFF;
                continue;
            }
            if (bodiless && !fluid && !isGroundCover(state)) {
                continue;
            }
            looks.emissions[id] = (byte) state.getLightEmission();

            BlockStateModel model = minecraft.getModelManager().getBlockStateModelSet().get(state);
            parts.clear();
            random.setSeed(42L);
            model.collectParts(random, parts);
            BakedQuad top = firstQuad(parts, Direction.UP);
            BakedQuad side = firstQuad(parts, Direction.NORTH);
            if (top == null) {
                top = side;
            }
            if (side == null) {
                side = top;
            }
            if (top == null) {
                // Drawn some other way (a block entity, lava's fluid model): its particle texture stands in.
                TextureAtlasSprite particle = model.particleMaterial().sprite();
                int color = averages.computeIfAbsent(particle, BlockLooks::average);
                looks.kinds[id] = color == 0 ? KIND_SKIP : KIND_SOLID;
                looks.topColors[id] = color;
                looks.sideColors[id] = color;
                continue;
            }
            looks.kinds[id] = KIND_SOLID;
            looks.topColors[id] = averages.computeIfAbsent(top.materialInfo().sprite(), BlockLooks::average);
            looks.sideColors[id] = averages.computeIfAbsent(side.materialInfo().sprite(), BlockLooks::average);
            looks.topTints[id] = top.materialInfo().isTinted() ? tintOf(state) : TINT_NONE;
            looks.sideTints[id] = side.materialInfo().isTinted() ? tintOf(state) : TINT_NONE;
        }
        return looks;
    }

    /** Snow layers and carpets have little or no collision yet cover the ground they lie on. */
    private static boolean isGroundCover(BlockState state) {
        return state.is(BlockTags.SNOW) || state.is(BlockTags.WOOL_CARPETS);
    }

    private static byte tintOf(BlockState state) {
        return state.is(BlockTags.LEAVES) ? TINT_FOLIAGE : TINT_GRASS;
    }

    private static @Nullable BakedQuad firstQuad(List<BlockStateModelPart> parts, Direction direction) {
        for (BlockStateModelPart part : parts) {
            List<BakedQuad> quads = part.getQuads(direction);
            if (!quads.isEmpty()) {
                return quads.getFirst();
            }
        }
        for (BlockStateModelPart part : parts) {
            for (BakedQuad quad : part.getQuads(null)) {
                if (quad.direction() == direction) {
                    return quad;
                }
            }
        }
        return null;
    }

    private static @Nullable TextureAtlasSprite waterSprite(Minecraft minecraft) {
        BlockStateModel model = minecraft.getModelManager().getBlockStateModelSet()
            .get(net.minecraft.world.level.block.Blocks.WATER.defaultBlockState());
        return model == null ? null : model.particleMaterial().sprite();
    }

    /** The alpha-weighted average colour of a sprite's first frame, opaque; 0 when it is fully transparent. */
    private static int average(TextureAtlasSprite sprite) {
        SpriteContents contents = sprite.contents();
        ByteBuffer[] mips = ((SpriteContentsAccess) contents).radiante$mipImages();
        if (mips.length == 0 || mips[0] == null) {
            return 0xFF808080;
        }
        ByteBuffer pixels = mips[0];
        int width = contents.width();
        int height = contents.height();
        int rowPixels = Math.max(1, ((SpriteContentsAccess) contents).radiante$mipWidth(0));
        long r = 0;
        long g = 0;
        long b = 0;
        long weight = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = (y * rowPixels + x) * 4;
                if (index + 3 >= pixels.capacity()) {
                    continue;
                }
                int a = pixels.get(index + 3) & 0xFF;
                if (a < ALPHA_CUTOFF) {
                    continue;
                }
                r += (long) (pixels.get(index) & 0xFF) * a;
                g += (long) (pixels.get(index + 1) & 0xFF) * a;
                b += (long) (pixels.get(index + 2) & 0xFF) * a;
                weight += a;
            }
        }
        if (weight == 0) {
            return 0;
        }
        return 0xFF000000 | (int) (r / weight) << 16 | (int) (g / weight) << 8 | (int) (b / weight);
    }
}
