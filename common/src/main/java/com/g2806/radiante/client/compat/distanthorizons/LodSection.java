package com.g2806.radiante.client.compat.distanthorizons;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;

/**
 * The terrain of one Distant Horizons section, copied out of the mod into plain data: a square of columns, each a
 * list of vertical runs of one block. Runs of air are left out.
 *
 * @param width        columns along each side
 * @param columnBlocks blocks each column spans along x and z
 * @param runs         per column (index {@code x * width + z}): its runs, four ints each - bottom y (inclusive),
 *                     top y (exclusive), block state id, and packed light (block light in the low 4 bits, sky light
 *                     in the next 4), topmost run first
 * @param biomes       per column: the biome of its topmost run, null for an empty column
 * @param complete     whether Distant Horizons had every column; one still being generated has gaps
 * @param detailed     per column, whether it comes from a whole chunk rather than a quick surface pass; null when
 *                     all do
 */
record LodSection(int width, int columnBlocks, int[][] runs, @Nullable Holder<Biome>[] biomes, boolean complete,
                  boolean @Nullable [] detailed) {

    static final int RUN_INTS = 4;

    int[] column(int x, int z) {
        return this.runs[x * this.width + z];
    }

    boolean detailed(int x, int z) {
        return this.detailed == null || this.detailed[x * this.width + z];
    }

    @Nullable Holder<Biome> biome(int x, int z) {
        return this.biomes[x * this.width + z];
    }
}
