package com.g2806.radiante.client.compat.distanthorizons;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.RenderThreadTaskHandler;
import com.seibel.distanthorizons.core.world.AbstractDhWorld;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Everything Radiante reads from Distant Horizons, and the only class that touches it.
 *
 * <p>The public API covers the level, the settings and the data points, but its terrain queries only hand out
 * full-detail block columns: reading a far away area through it would load the full-detail data of every block in
 * it. The coarse sections Distant Horizons keeps for the distance are reached through three internal calls instead
 * - the world, the level's data provider and a section from it - and turned into API data points straight away.
 * When a Distant Horizons update changes any of that, it shows up here and nowhere else.
 */
final class DhData {

    /** Detail level of a section whose columns are single blocks; each level up doubles the column width. */
    static final int BLOCK_SECTION_DETAIL = DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL;


    private DhData() {
    }

    /** Whether Distant Horizons has a world loaded and is set to draw it. */
    static boolean isActive() {
        return DhApi.Delayed.worldProxy != null && DhApi.Delayed.configs != null
            && DhApi.Delayed.worldProxy.worldLoaded()
            && DhApi.Delayed.configs.graphics().renderingEnabled().getValue();
    }

    /**
     * Has Distant Horizons take the client's level on as one it draws. It does that from its own renderer, the first
     * time it draws the level; with ray tracing on from the start that never happens, and a level it does not draw
     * is one it neither loads terrain for nor generates any, so the far terrain stayed empty. Cheap to repeat.
     */
    static void loadClientLevel() {
        var world = SharedApi.tryGetDhClientWorld();
        var wrapper = com.seibel.distanthorizons.core.api.internal.ClientApi.RENDER_STATE.clientLevelWrapper;
        if (world != null && wrapper != null) {
            world.getOrLoadClientLevel(wrapper);
        }
    }

    /**
     * Runs the work Distant Horizons queues for the render thread. It runs it itself only while drawing its own
     * terrain, which it never does while Radiante traces; left undone, its queue of sections waiting for the
     * render thread fills up and loading and generating new terrain stall behind it.
     */
    static void runRenderThreadTasks() {
        RenderThreadTaskHandler.INSTANCE.runRenderThreadTasks();
    }

    static boolean isConfigLoaded() {
        return DhApi.Delayed.configs != null;
    }

    /** Whether Distant Horizons' "override vanilla graphics settings" toggle is on. */
    static boolean overridesVanillaSettings() {
        return Config.Client.Advanced.Graphics.overrideVanillaGraphicsSettings.get();
    }

    /** How far Distant Horizons draws, in blocks. */
    static int renderDistanceBlocks() {
        return DhApi.Delayed.configs.graphics().chunkRenderDistance().getValue() * 16;
    }

    /**
     * Distant Horizons' handle on {@code level}, or null while it has none. In single player it keeps its data on
     * the integrated server's level of the same dimension, not on the client's.
     */
    static @Nullable Object levelFor(ClientLevel level) {
        IDhApiLevelWrapper sameDimension = null;
        for (IDhApiLevelWrapper wrapper : DhApi.Delayed.worldProxy.getAllLoadedLevelWrappers()) {
            Object wrapped = wrapper.getWrappedMcObject();
            if (wrapped == level) {
                return wrapper;
            }
            if (wrapped instanceof Level other && other.dimension().equals(level.dimension())) {
                sameDimension = wrapper;
            }
        }
        return sameDimension;
    }

    /**
     * When the section's data last changed, or null while it has none. Cheap enough to poll: generating terrain and
     * editing it both show up here.
     */
    static @Nullable Long timestamp(Object level, int detail, int x, int z) {
        FullDataSourceProviderV2 provider = provider(level);
        return provider == null ? null : provider.getTimestampForPos(DhSectionPos.encode((byte) detail, x, z));
    }

    static final int NO_DATA = 0;
    static final int PARTIAL = 1;
    static final int COMPLETE = 2;

    /**
     * Whether Distant Horizons has stored anything for the section: a look at its date alone, without reading the
     * data. Asking for the data itself to find out, for hundreds of sections, kept Distant Horizons' own file
     * threads so busy that its terrain generation, which saves through the same threads, all but stopped.
     */
    static boolean exists(Object level, int detail, int x, int z) {
        return timestamp(level, detail, x, z) != null;
    }

    /** How much of the section Distant Horizons has: none, some columns or all. Reads it; off the render thread. */
    static int coverage(Object level, int detail, int x, int z) {
        FullDataSourceProviderV2 provider = provider(level);
        FullDataSourceV2 source = provider == null ? null : provider.get(DhSectionPos.encode((byte) detail, x, z));
        if (source == null) {
            return NO_DATA;
        }
        try {
            return coverage(source);
        } finally {
            source.close();
        }
    }

    /**
     * Whether every column of a section has data. Distant Horizons fills sections in as it generates, so one can
     * exist with only some of its columns; drawn, the rest would be holes, where its coarser parent - generated
     * earlier - still has the whole area.
     */
    private static int coverage(FullDataSourceV2 source) {
        if (source.isEmpty) {
            return NO_DATA;
        }
        for (var column : source.dataPoints) {
            if (column == null || column.isEmpty()) {
                return PARTIAL;
            }
        }
        return COMPLETE;
    }

    /** One section's terrain, or null while Distant Horizons has not all of it. Blocks; run off the render thread. */
    static @Nullable LodSection fetch(Object level, int detail, int x, int z) throws Exception {
        FullDataSourceProviderV2 provider = provider(level);
        if (provider == null) {
            return null;
        }
        // Read on this thread rather than through getAsync, which queues on Distant Horizons' own file threads;
        // see exists.
        FullDataSourceV2 source = provider.get(DhSectionPos.encode((byte) detail, x, z));
        if (source == null) {
            return null;
        }
        try {
            int coverage = coverage(source);
            if (coverage == NO_DATA) {
                return null;
            }
            return copy(source, detail, ((IDhApiLevelWrapper) level).getMinHeight(), coverage == COMPLETE);
        } finally {
            // Sections come out of a pool and go back to it here; the data was copied out above.
            source.close();
        }
    }

    /** Stands for air while a column is being simplified; no run of it is kept. */
    private static final int AIR = -1;

    /**
     * Per column, whether Distant Horizons has it from a whole chunk - generated in full or read from one the
     * player had loaded - rather than from its quick surface pass, which only roughs the height out in steps
     * several blocks wide. Null when every column is.
     */
    private static boolean @Nullable [] detailed(FullDataSourceV2 source, int width) {
        var steps = source.columnGenerationSteps;
        if (steps == null || steps.size() < width * width) {
            return null;
        }
        byte full = com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep.FEATURES.value;
        boolean[] detailed = new boolean[width * width];
        boolean all = true;
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < width; z++) {
                boolean whole = steps.getByte(FullDataSourceV2.relativePosToIndex(x, z)) >= full;
                detailed[x * width + z] = whole;
                all &= whole;
            }
        }
        return all ? null : detailed;
    }

    @SuppressWarnings("unchecked")
    private static LodSection copy(FullDataSourceV2 source, int detail, int minY, boolean complete) {
        int width = source.getWidthInDataColumns();
        int columnBlocks = 1 << (detail - BLOCK_SECTION_DETAIL);
        int[][] runs = new int[width * width][];
        Holder<Biome>[] biomes = new Holder[width * width];
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < width; z++) {
                int index = x * width + z;
                List<DhApiTerrainDataPoint> points = source.getApiDataPointColumn(x, z);
                int[] column = new int[points.size() * LodSection.RUN_INTS];
                int count = 0;
                int highest = Integer.MIN_VALUE;
                for (DhApiTerrainDataPoint point : points) {
                    if (point.topYBlockPos <= point.bottomYBlockPos) {
                        continue;
                    }
                    IDhApiBlockStateWrapper wrapper = point.blockStateWrapper;
                    int state = wrapper == null || wrapper.isAir()
                        || !(wrapper.getWrappedMcObject() instanceof BlockState blockState)
                        || isHollow(blockState) ? AIR : Block.BLOCK_STATE_REGISTRY.getId(blockState);
                    int at = count * LodSection.RUN_INTS;
                    // Heights come relative to the bottom of the level.
                    column[at] = point.bottomYBlockPos + minY;
                    column[at + 1] = point.topYBlockPos + minY;
                    column[at + 2] = state;
                    column[at + 3] = (point.blockLightLevel & 0xF) | (point.skyLightLevel & 0xF) << 4;
                    if (state != AIR && point.topYBlockPos > highest) {
                        highest = point.topYBlockPos;
                        biomes[index] = biomeOf(point);
                    }
                    count++;
                }
                runs[index] = simplify(sortTopFirst(Arrays.copyOf(column, count * LodSection.RUN_INTS)));
            }
        }
        return new LodSection(width, columnBlocks, runs, biomes, complete, detailed(source, width));
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Holder<Biome> biomeOf(DhApiTerrainDataPoint point) {
        return point.biomeWrapper != null && point.biomeWrapper.getWrappedMcObject() instanceof Holder<?> holder
            ? (Holder<Biome>) holder
            : null;
    }

    /**
     * Fills the caves and joins runs of one block. Air the sky does not reach, under something solid, is a cave:
     * nothing outside sees into it, and drawn as it is it turns every column into a stack of boxes whose walls
     * cost more than the rest of the terrain together. The run below a cave is raised to close it. Air runs are
     * dropped afterwards; only blocks are kept.
     */
    private static int[] simplify(int[] column) {
        int count = column.length / LodSection.RUN_INTS;
        int[] out = new int[column.length];
        int kept = 0;
        boolean underSolid = false;
        boolean closeGap = false;
        for (int i = 0; i < count; i++) {
            int at = i * LodSection.RUN_INTS;
            int state = column[at + 2];
            if (state == AIR) {
                int skyLight = column[at + 3] >> 4 & 0xF;
                if (underSolid && skyLight == 0) {
                    closeGap = true;
                }
                continue;
            }
            int bottom = column[at];
            int top = column[at + 1];
            if (kept > 0) {
                int above = (kept - 1) * LodSection.RUN_INTS;
                if (closeGap) {
                    top = out[above];
                }
                if (top >= out[above] && out[above + 2] == state) {
                    // Touches the run above and is the same block: one run.
                    out[above] = Math.min(out[above], bottom);
                    closeGap = false;
                    continue;
                }
            }
            closeGap = false;
            underSolid = true;
            int to = kept * LodSection.RUN_INTS;
            out[to] = bottom;
            out[to + 1] = top;
            out[to + 2] = state;
            out[to + 3] = column[at + 3];
            kept++;
        }
        return Arrays.copyOf(out, kept * LodSection.RUN_INTS);
    }

    /**
     * Blocks with nothing solid to them - vines, grass, flowers - that far terrain leaves out anyway. They count as
     * air, so caves hung with cave vines are filled like any other instead of each keeping its walls.
     */
    private static boolean isHollow(BlockState state) {
        return state.getFluidState().isEmpty() && !state.is(net.minecraft.tags.BlockTags.SNOW)
            && state.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,
                net.minecraft.core.BlockPos.ZERO).isEmpty();
    }

    /** Orders the runs from the highest down; Distant Horizons does not promise an order. */
    private static int[] sortTopFirst(int[] column) {
        int count = column.length / LodSection.RUN_INTS;
        for (int i = 1; i < count; i++) {
            for (int j = i; j > 0 && column[j * LodSection.RUN_INTS] > column[(j - 1) * LodSection.RUN_INTS]; j--) {
                for (int k = 0; k < LodSection.RUN_INTS; k++) {
                    int a = j * LodSection.RUN_INTS + k;
                    int b = (j - 1) * LodSection.RUN_INTS + k;
                    int swap = column[a];
                    column[a] = column[b];
                    column[b] = swap;
                }
            }
        }
        return column;
    }

    private static @Nullable FullDataSourceProviderV2 provider(Object level) {
        AbstractDhWorld world = SharedApi.getAbstractDhWorld();
        if (world == null) {
            return null;
        }
        IDhLevel dhLevel = world.getLevel((ILevelWrapper) level);
        return dhLevel == null ? null : dhLevel.getFullDataProvider();
    }
}
