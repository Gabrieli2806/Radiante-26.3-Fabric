package com.g2806.radiante.client.compat.distanthorizons;

import com.g2806.radiante.client.render.PBRVertexWriter;
import java.util.Arrays;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Holder;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;

/**
 * Turns a {@link LodSection} into boxes: each run of a column becomes a box as wide as the column, with only the
 * faces that something could see - a top nothing rests on, sides where the neighbouring column does not reach,
 * and the underside of an overhang. Tops of equal height and colour next to each other are merged, which leaves
 * flat land and the sea a handful of long strips instead of a face per column.
 *
 * <p>Positions are relative to the section's lowest corner at the bottom of the level.
 */
final class LodMesher {

    private static final int DEFAULT_GRASS = 0x91BD59;
    private static final int DEFAULT_FOLIAGE = 0x77AB2F;
    private static final int DEFAULT_WATER = 0x3F76E4;
    /** Emission of a far block with light level 15; near terrain gets its glow from the emission maps instead. */
    private static final float FULL_EMISSION = 2.0f;

    /** Least depth of the skirt along a section's edge; see mesh. */
    private static final int MIN_SKIRT = 16;

    private static final int NORTH = 0;
    private static final int SOUTH = 1;
    private static final int WEST = 2;
    private static final int EAST = 3;

    private LodMesher() {
    }

    /**
     * @param sink   how far everything is lowered, in blocks. Terrain drawn twice at the same height flickers
     *               between the two; lowering far terrain, and coarser sections more than finer ones, lets the
     *               nearer drawing win wherever two overlap for a moment - loaded chunks over far terrain that has
     *               not caught up yet, fine sections over the coarse one they are replacing.
     * @param hidden      per column ({@code x * width + z}), whether it is left out because the world's own chunk
     *                    or a finer section draws it there; null when none is
     * @param waterHidden per column, whether only its water is left out; see LodTerrain.chunkColumns
     */
    static void mesh(LodSection section, BlockLooks looks, int minY, float sink, int worldX, int worldZ,
        boolean @Nullable [] hidden, boolean @Nullable [] waterHidden,
        PBRVertexWriter solid, PBRVertexWriter water) {
        int width = section.width();
        int cw = section.columnBlocks();
        // Top faces are gathered per row of x and merged along z before they are written.
        long[] tops = new long[width * 4];
        int[] topColors = new int[width * 4];

        for (int x = 0; x < width; x++) {
            int topCount = 0;
            for (int z = 0; z < width; z++) {
                int index = x * width + z;
                if (hidden != null && hidden[index]) {
                    continue;
                }
                int[] runs = section.column(x, z);
                int runCount = runs.length / LodSection.RUN_INTS;
                if (runCount == 0) {
                    continue;
                }
                Holder<Biome> biome = section.biome(x, z);
                // Along the section's edge the neighbouring column is in another section, unknown here. The side
                // goes down only this far below the surface: enough to close the step to a neighbour of another
                // height, without a wall to the bottom of the world on every edge.
                int skirtFrom = runs[1] - Math.max(MIN_SKIRT, 2 * cw);
                double biomeX = worldX + (x + 0.5) * cw;
                double biomeZ = worldZ + (z + 0.5) * cw;
                float x0 = x * cw;
                float z0 = z * cw;

                for (int r = 0; r < runCount; r++) {
                    int at = r * LodSection.RUN_INTS;
                    int state = runs[at + 2];
                    byte kind = looks.kind(state);
                    if (kind == BlockLooks.KIND_SKIP) {
                        continue;
                    }
                    boolean isWater = kind == BlockLooks.KIND_WATER;
                    if (isWater && waterHidden != null && waterHidden[index]) {
                        continue;
                    }
                    int bottom = runs[at];
                    int top = runs[at + 1];
                    int light = packLight(runs[at + 3]);
                    PBRVertexWriter writer = isWater ? water : solid;
                    float emission = looks.lightEmission(state) / 15.0f * FULL_EMISSION;
                    writer.albedoEmission(emission);

                    // Top: unless something rests on it. Water over the sea floor leaves the floor's top in place.
                    if (!touchesAbove(runs, r, looks, isWater)) {
                        if (topCount == tops.length) {
                            tops = Arrays.copyOf(tops, tops.length * 2);
                            topColors = Arrays.copyOf(topColors, topColors.length * 2);
                        }
                        int color = tinted(looks.topColor(state), looks.topTint(state), biome, biomeX, biomeZ);
                        tops[topCount] = topKey(top - minY, isWater, looks.lightEmission(state), light, z);
                        topColors[topCount] = color;
                        topCount++;
                    }

                    int sideColor = tinted(looks.sideColor(state), looks.sideTint(state), biome, biomeX, biomeZ);
                    float y0 = bottom - minY - sink;
                    for (int side = 0; side < 4; side++) {
                        int nx = x + (side == WEST ? -1 : side == EAST ? 1 : 0);
                        int nz = z + (side == NORTH ? -1 : side == SOUTH ? 1 : 0);
                        int[] neighbour = nx < 0 || nz < 0 || nx >= width || nz >= width ? null
                            : section.column(nx, nz);
                        if (neighbour == null && isWater) {
                            // A skirt of water would stand along the edge as a pane of glass.
                            continue;
                        }
                        int sideBottom = neighbour == null ? Math.max(bottom, skirtFrom) : bottom;
                        if (sideBottom < top) {
                            emitSide(writer, neighbour, looks, isWater, sideBottom, top, minY, sink, side, x0, z0, cw,
                                sideColor, light);
                        }
                    }

                    // Underside of an overhang, or of the sea where nothing is below it.
                    if (bottom > minY && !touchesBelow(runs, r, looks, isWater)) {
                        writer.addVertex(x0, y0, z0).setColor(sideColor).setLight(light);
                        writer.addVertex(x0 + cw, y0, z0).setColor(sideColor).setLight(light);
                        writer.addVertex(x0 + cw, y0, z0 + cw).setColor(sideColor).setLight(light);
                        writer.addVertex(x0, y0, z0 + cw).setColor(sideColor).setLight(light);
                    }
                }
            }
            writeTops(tops, topColors, topCount, x * cw, cw, sink, solid, water, looks.waterSprite);
        }
    }

    /** The parts of one side of a run that the neighbouring column does not cover. */
    private static void emitSide(PBRVertexWriter writer, int @Nullable [] neighbour, BlockLooks looks,
        boolean isWater, int bottom, int top, int minY, float sink, int side, float x0, float z0, int cw, int color,
        int light) {
        int from = bottom;
        // The neighbour's runs are topmost first; walk them from the bottom up, filling the gaps between them.
        int count = neighbour == null ? 0 : neighbour.length / LodSection.RUN_INTS;
        for (int i = count - 1; i >= 0 && from < top; i--) {
            int at = i * LodSection.RUN_INTS;
            byte kind = looks.kind(neighbour[at + 2]);
            // Solid hides everything; water only hides water, so the sea floor's cliffs stay visible through it.
            boolean covers = kind == BlockLooks.KIND_SOLID || isWater && kind == BlockLooks.KIND_WATER;
            if (!covers || neighbour[at + 1] <= from) {
                continue;
            }
            int coverFrom = Math.max(neighbour[at], bottom);
            if (coverFrom > from) {
                sideQuad(writer, side, x0, z0, cw, Math.min(coverFrom, top) - minY - sink, from - minY - sink, color,
                    light);
            }
            from = Math.max(from, neighbour[at + 1]);
        }
        if (from < top) {
            sideQuad(writer, side, x0, z0, cw, top - minY - sink, from - minY - sink, color, light);
        }
    }

    /** A vertical face of the column box, wound so its front faces outward. */
    private static void sideQuad(PBRVertexWriter w, int side, float x0, float z0, int cw, float y1, float y0,
        int color, int light) {
        float x1 = x0 + cw;
        float z1 = z0 + cw;
        switch (side) {
            case NORTH -> {
                w.addVertex(x0, y0, z0).setColor(color).setLight(light);
                w.addVertex(x0, y1, z0).setColor(color).setLight(light);
                w.addVertex(x1, y1, z0).setColor(color).setLight(light);
                w.addVertex(x1, y0, z0).setColor(color).setLight(light);
            }
            case SOUTH -> {
                w.addVertex(x0, y0, z1).setColor(color).setLight(light);
                w.addVertex(x1, y0, z1).setColor(color).setLight(light);
                w.addVertex(x1, y1, z1).setColor(color).setLight(light);
                w.addVertex(x0, y1, z1).setColor(color).setLight(light);
            }
            case WEST -> {
                w.addVertex(x0, y0, z0).setColor(color).setLight(light);
                w.addVertex(x0, y0, z1).setColor(color).setLight(light);
                w.addVertex(x0, y1, z1).setColor(color).setLight(light);
                w.addVertex(x0, y1, z0).setColor(color).setLight(light);
            }
            default -> {
                w.addVertex(x1, y0, z0).setColor(color).setLight(light);
                w.addVertex(x1, y1, z0).setColor(color).setLight(light);
                w.addVertex(x1, y1, z1).setColor(color).setLight(light);
                w.addVertex(x1, y0, z1).setColor(color).setLight(light);
            }
        }
    }

    /**
     * Writes one row's tops, merging neighbours along z that share height, colour and everything else. The key
     * sorts equal faces together with z last, so a run of equal faces is a run of consecutive z.
     */
    private static void writeTops(long[] keys, int[] colors, int count, float x0, int cw, float sink,
        PBRVertexWriter solid,
        PBRVertexWriter water, @Nullable TextureAtlasSprite waterSprite) {
        if (count == 0) {
            return;
        }
        Integer[] order = new Integer[count];
        for (int i = 0; i < count; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> {
            int byKey = Long.compare(keys[a] & ~Z_MASK, keys[b] & ~Z_MASK);
            if (byKey != 0) {
                return byKey;
            }
            int byColor = Integer.compare(colors[a], colors[b]);
            return byColor != 0 ? byColor : Long.compare(keys[a] & Z_MASK, keys[b] & Z_MASK);
        });

        int i = 0;
        while (i < count) {
            long key = keys[order[i]];
            int color = colors[order[i]];
            int zStart = (int) (key & Z_MASK);
            int zEnd = zStart;
            int j = i + 1;
            while (j < count && (keys[order[j]] & ~Z_MASK) == (key & ~Z_MASK) && colors[order[j]] == color
                && (int) (keys[order[j]] & Z_MASK) == zEnd + 1) {
                zEnd++;
                j++;
            }
            float y = (float) (key >>> Y_SHIFT & 0xFFFF) - sink;
            boolean isWater = (key >>> WATER_SHIFT & 1) != 0;
            int emissionLevel = (int) (key >>> EMISSION_SHIFT & 0xF);
            int light = (int) (key >>> LIGHT_SHIFT & 0xFFFFFFFFL);
            PBRVertexWriter writer = isWater ? water : solid;
            writer.albedoEmission(emissionLevel / 15.0f * FULL_EMISSION);
            float z0 = zStart * cw;
            float z1 = (zEnd + 1) * cw;
            float x1 = x0 + cw;
            if (isWater && waterSprite != null) {
                // Textured, so the tracer knows it for water and shades it as a surface of its own.
                float u0 = waterSprite.getU0();
                float u1 = waterSprite.getU1();
                float v0 = waterSprite.getV0();
                float v1 = waterSprite.getV1();
                writer.addVertex(x0, y, z0).setColor(color).setUv(u0, v0).setLight(light);
                writer.addVertex(x0, y, z1).setColor(color).setUv(u0, v1).setLight(light);
                writer.addVertex(x1, y, z1).setColor(color).setUv(u1, v1).setLight(light);
                writer.addVertex(x1, y, z0).setColor(color).setUv(u1, v0).setLight(light);
            } else {
                writer.addVertex(x0, y, z0).setColor(color).setLight(light);
                writer.addVertex(x0, y, z1).setColor(color).setLight(light);
                writer.addVertex(x1, y, z1).setColor(color).setLight(light);
                writer.addVertex(x1, y, z0).setColor(color).setLight(light);
            }
            i = j;
        }
    }

    // Layout of a top face's sort key: z lowest, so faces equal in everything else sort by z.
    private static final long Z_MASK = 0xFFL;
    private static final int LIGHT_SHIFT = 8;
    private static final int EMISSION_SHIFT = 40;
    private static final int WATER_SHIFT = 44;
    private static final int Y_SHIFT = 45;

    private static long topKey(int y, boolean isWater, int emissionLevel, int light, int z) {
        return (long) (y & 0xFFFF) << Y_SHIFT | (isWater ? 1L : 0L) << WATER_SHIFT
            | (long) (emissionLevel & 0xF) << EMISSION_SHIFT | (light & 0xFFFFFFFFL) << LIGHT_SHIFT | z & Z_MASK;
    }

    private static boolean touchesAbove(int[] runs, int r, BlockLooks looks, boolean isWater) {
        if (r == 0) {
            return false;
        }
        int above = (r - 1) * LodSection.RUN_INTS;
        byte kind = looks.kind(runs[above + 2]);
        boolean covers = kind == BlockLooks.KIND_SOLID || isWater && kind == BlockLooks.KIND_WATER;
        return covers && runs[above] <= runs[r * LodSection.RUN_INTS + 1];
    }

    private static boolean touchesBelow(int[] runs, int r, BlockLooks looks, boolean isWater) {
        int below = (r + 1) * LodSection.RUN_INTS;
        if (below >= runs.length) {
            return false;
        }
        byte kind = looks.kind(runs[below + 2]);
        boolean covers = kind == BlockLooks.KIND_SOLID || isWater && kind == BlockLooks.KIND_WATER;
        return covers && runs[below + 1] >= runs[r * LodSection.RUN_INTS];
    }

    private static int packLight(int packed) {
        return LightCoordsUtil.pack(packed & 0xF, packed >> 4 & 0xF);
    }

    private static int tinted(int color, byte tint, @Nullable Holder<Biome> biome, double x, double z) {
        if (tint == BlockLooks.TINT_NONE) {
            return color;
        }
        int biomeColor = switch (tint) {
            case BlockLooks.TINT_GRASS -> biome == null ? DEFAULT_GRASS : biome.value().getGrassColor(x, z);
            case BlockLooks.TINT_FOLIAGE -> biome == null ? DEFAULT_FOLIAGE : biome.value().getFoliageColor();
            default -> biome == null ? DEFAULT_WATER : biome.value().getWaterColor();
        };
        int r = (color >> 16 & 0xFF) * (biomeColor >> 16 & 0xFF) / 255;
        int g = (color >> 8 & 0xFF) * (biomeColor >> 8 & 0xFF) / 255;
        int b = (color & 0xFF) * (biomeColor & 0xFF) / 255;
        return 0xFF000000 | r << 16 | g << 8 | b;
    }
}
