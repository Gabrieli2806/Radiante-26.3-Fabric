package com.g2806.radiante.client.render;

import com.g2806.radiante.client.option.Options;
import com.g2806.radiante.client.proxy.world.ChunkProxy;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.lwjgl.system.MemoryUtil;

/**
 * Compiles world sections into the renderer's PBR geometry. Sections are laid out exactly like Minecraft's
 * rotating section grid so the shaders can look a chunk up from a section coordinate.
 */
public final class ChunkManager {

    private static final int VERTEX_FORMAT_PBR = 12;
    private static final int GEOMETRY_TYPE_WORLD_SOLID = 1;
    private static final int GEOMETRY_TYPE_WORLD_TRANSPARENT = 2;
    private static final int MAX_REGION_SNAPSHOTS_PER_FRAME = 96;
    /**
     * Rebuilds of nearby sections compiled on the render thread per frame. A falling block landing or a piston
     * finishing its move swaps the moving block for terrain in one tick; built in the background, the terrain
     * arrives a frame or more after the moving block is gone and the block blinks out in between.
     */
    private static final int MAX_SYNC_REBUILDS_PER_FRAME = 4;

    private static final ThreadLocal<SectionCompileScratch> SCRATCH = ThreadLocal.withInitial(SectionCompileScratch::new);

    private static ExecutorService executor;
    private static final AtomicInteger pendingBuilds = new AtomicInteger();
    /** Section uploads hold the read lock; replacing the section grid takes the write lock. */
    private static final ReentrantReadWriteLock GRID_LOCK = new ReentrantReadWriteLock();
    private static volatile int generation;

    private static int gridSizeXZ;
    private static int gridSizeY;
    private static int minSectionY;
    private static final Long2IntOpenHashMap sectionNodeBySlot = new Long2IntOpenHashMap();
    private static final LongOpenHashSet compiledSections = new LongOpenHashSet();
    private static boolean forceAllDirty;
    /**
     * Newest build handed out for each section. A synchronous rebuild can overtake an older one still running in
     * the background, and the older one must not land on top of it afterwards.
     */
    private static final java.util.concurrent.ConcurrentHashMap<Long, Integer> latestBuild =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final AtomicInteger buildSequence = new AtomicInteger();
    /**
     * The section each grid slot currently holds. Builds are queued faster than they run, so after a teleport the
     * queue is still full of sections from where the player was; by the time one of those finishes, its slot belongs
     * to a section at the new location. Checking ownership drops that work before it is compiled, and stops a late
     * one from writing the old area back into a slot that now stands somewhere else.
     */
    private static java.util.concurrent.atomic.AtomicLongArray slotOwner =
        new java.util.concurrent.atomic.AtomicLongArray(0);
    private static final long NO_OWNER = Long.MIN_VALUE;

    private ChunkManager() {
    }

    public static synchronized void onTrackerCreated(SectionUpdateTracker tracker, int renderDistance, int minSection,
        int maxSection) {
        shutdown();
        GRID_LOCK.writeLock().lock();
        try {
            generation++;
            resetGrid(renderDistance, minSection, maxSection);
        } finally {
            GRID_LOCK.writeLock().unlock();
        }
    }

    private static void resetGrid(int renderDistance, int minSection, int maxSection) {
        gridSizeXZ = renderDistance * 2 + 1;
        gridSizeY = maxSection - minSection + 1;
        minSectionY = minSection;
        sectionNodeBySlot.clear();
        synchronized (compiledSections) {
            compiledSections.clear();
        }
        latestBuild.clear();
        slotOwner = new java.util.concurrent.atomic.AtomicLongArray(gridSizeXZ * gridSizeXZ * gridSizeY);
        for (int i = 0; i < slotOwner.length(); i++) {
            slotOwner.set(i, NO_OWNER);
        }
        forceAllDirty = true;
        executor = Executors.newFixedThreadPool(Math.max(1, Options.chunkBuildingThreads), runnable -> {
            Thread thread = new Thread(runnable, "Radiante Section Builder");
            thread.setDaemon(true);
            return thread;
        });
        ChunkProxy.init(gridSizeXZ * gridSizeXZ * gridSizeY, gridSizeXZ, gridSizeY, gridSizeXZ, minSection);
    }

    public static synchronized void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        pendingBuilds.set(0);
    }

    public static void markAllDirty() {
        forceAllDirty = true;
    }

    public static int slotOf(long sectionNode) {
        int gridX = Math.floorMod(SectionPos.x(sectionNode), gridSizeXZ);
        int gridY = SectionPos.y(sectionNode) - minSectionY;
        int gridZ = Math.floorMod(SectionPos.z(sectionNode), gridSizeXZ);
        if (gridY < 0 || gridY >= gridSizeY) {
            return -1;
        }
        return (gridZ * gridSizeY + gridY) * gridSizeXZ + gridX;
    }

    /** Snapshots dirty sections on the render thread and queues them for compilation. */
    public static void update(ClientLevel level, SectionUpdateTracker tracker, SectionPos cameraSection) {
        if (executor == null || level == null) {
            return;
        }

        ChunkProxy.updateSectionPos(cameraSection.x(), cameraSection.y(), cameraSection.z());

        boolean rebuildAll = forceAllDirty;
        forceAllDirty = false;
        RenderRegionCache cache = new RenderRegionCache();
        List<SectionUpdateTracker.SectionDirtyState> candidates = new ArrayList<>();
        // Relocating a slot takes the grid's write lock, so a builder thread cannot check the slot's owner, lose it
        // to the relocation, and then upload the old section into it anyway. Taken only on frames that move a slot.
        boolean[] relocating = new boolean[1];
        try {
            ((SectionTrackerAccess) tracker).radiante$forEachSection(state -> {
                long node = state.getSectionNode();
                int slot = slotOf(node);
                if (slot < 0) {
                    return;
                }

                int previous = sectionNodeBySlot.getOrDefault(slot, Integer.MIN_VALUE);
                boolean moved = previous != slotHash(node);
                if (moved) {
                    if (!relocating[0]) {
                        GRID_LOCK.writeLock().lock();
                        relocating[0] = true;
                    }
                    sectionNodeBySlot.put(slot, slotHash(node));
                    long evicted = slot < slotOwner.length() ? slotOwner.getAndSet(slot, node) : NO_OWNER;
                    synchronized (compiledSections) {
                        compiledSections.remove(node);
                        if (evicted != NO_OWNER) {
                            compiledSections.remove(evicted);
                        }
                    }
                    if (evicted != NO_OWNER) {
                        latestBuild.remove(evicted);
                    }
                    ChunkProxy.relocateSingle(slot, SectionPos.sectionToBlockCoord(SectionPos.x(node)),
                        SectionPos.sectionToBlockCoord(SectionPos.y(node)),
                        SectionPos.sectionToBlockCoord(SectionPos.z(node)));
                    state.setDirty(false);
                }

                if (rebuildAll) {
                    state.setDirty(false);
                }

                if (state.isDirty()) {
                    candidates.add(state);
                }
            });
        } finally {
            if (relocating[0]) {
                GRID_LOCK.writeLock().unlock();
            }
        }

        if (candidates.isEmpty()) {
            return;
        }

        BlockPos cameraBlock = cameraSection.center();
        candidates.sort((a, b) -> Double.compare(distanceSqr(a.getSectionNode(), cameraBlock),
            distanceSqr(b.getSectionNode(), cameraBlock)));

        int snapshots = 0;
        int syncRebuilds = 0;
        for (SectionUpdateTracker.SectionDirtyState state : candidates) {
            if (snapshots >= MAX_REGION_SNAPSHOTS_PER_FRAME) {
                break;
            }

            long node = state.getSectionNode();
            if (!tracker.hasAllNeighbors(level, node)) {
                continue;
            }

            state.setNotDirty();
            snapshots++;
            int slot = slotOf(node);
            RenderSectionRegion region = cache.createRegion(level, node);
            boolean important = distanceSqr(node, cameraBlock) < 768.0;
            int buildGeneration = generation;
            int sequence = buildSequence.incrementAndGet();
            latestBuild.put(node, sequence);

            // Only rebuilds: a first build near the player happens while loading in, where a hitch is worse than
            // a section showing up a frame late.
            if (important && syncRebuilds < MAX_SYNC_REBUILDS_PER_FRAME && isSectionReady(node)) {
                syncRebuilds++;
                try {
                    compile(slot, node, region, true, buildGeneration, sequence);
                } catch (Throwable t) {
                    RadianteRenderer.LOGGER.error("Failed to compile section {}", SectionPos.of(node), t);
                }
                continue;
            }

            pendingBuilds.incrementAndGet();
            executor.execute(() -> {
                try {
                    compile(slot, node, region, important, buildGeneration, sequence);
                } catch (Throwable t) {
                    RadianteRenderer.LOGGER.error("Failed to compile section {}", SectionPos.of(node), t);
                } finally {
                    pendingBuilds.decrementAndGet();
                }
            });
        }
    }

    private static int slotHash(long sectionNode) {
        return (int) (sectionNode ^ sectionNode >>> 32);
    }

    private static double distanceSqr(long sectionNode, BlockPos cameraBlock) {
        return SectionPos.of(sectionNode).center().distSqr(cameraBlock);
    }

    private static boolean ownsSlot(int slot, long sectionNode) {
        java.util.concurrent.atomic.AtomicLongArray owners = slotOwner;
        return slot >= 0 && slot < owners.length() && owners.get(slot) == sectionNode;
    }

    private static boolean isLatestBuild(long sectionNode, int sequence) {
        Integer latest = latestBuild.get(sectionNode);
        return latest == null || latest == sequence;
    }

    private static void compile(int slot, long sectionNode, RenderSectionRegion region, boolean important,
        int buildGeneration, int sequence) {
        // Queued before a teleport or superseded by a newer build: compiling it would only delay the sections that
        // are actually around the player.
        if (buildGeneration != generation || !ownsSlot(slot, sectionNode) || !isLatestBuild(sectionNode, sequence)) {
            return;
        }

        SectionCompileScratch scratch = SCRATCH.get();
        scratch.reset();

        Minecraft minecraft = Minecraft.getInstance();
        int atlasId = TextureTracker.idOf(TextureAtlas.LOCATION_BLOCKS);

        ModelBlockRenderer blockRenderer = scratch.blockRenderer(minecraft);
        FluidRenderer fluidRenderer = scratch.fluidRenderer(minecraft);

        BlockPos origin = SectionPos.of(sectionNode).origin();
        BlockPos max = origin.offset(15, 15, 15);

        BlockQuadOutput quadOutput = (x, y, z, quad, instance) -> scratch.writer(quad.materialInfo().layer(), atlasId)
            .putBlockBakedQuad(x, y, z, quad, instance);
        FluidRenderer.Output fluidOutput = layer -> scratch.writer(layer, atlasId).computeQuadNormals(true);

        for (BlockPos pos : BlockPos.betweenClosed(origin, max)) {
            BlockState blockState = region.getBlockState(pos);
            if (blockState.isAir()) {
                continue;
            }


            FluidState fluidState = blockState.getFluidState();
            if (!fluidState.isEmpty()) {
                fluidRenderer.tesselate(region, pos, fluidOutput, blockState, fluidState);
                scratch.currentWriter().computeQuadNormals(false);
            }

            if (blockState.getRenderShape() == RenderShape.MODEL) {
                blockRenderer.tesselateBlock(quadOutput, SectionPos.sectionRelative(pos.getX()),
                    SectionPos.sectionRelative(pos.getY()), SectionPos.sectionRelative(pos.getZ()), region, pos,
                    blockState, minecraft.getModelManager().getBlockStateModelSet().get(blockState),
                    blockState.getSeed(pos));
            }
        }

        boolean uploaded = false;
        GRID_LOCK.readLock().lock();
        try {
            // A section compiled for the previous world would land in a slot of the new grid, and one compiled for
            // a slot that has since been relocated would put the old area back.
            if (buildGeneration == generation && ownsSlot(slot, sectionNode) && isLatestBuild(sectionNode, sequence)) {
                upload(slot, sectionNode, sequence, origin, scratch, atlasId, important);
                uploaded = true;
            }
        } finally {
            GRID_LOCK.readLock().unlock();
        }
        if (uploaded) {
            synchronized (compiledSections) {
                compiledSections.add(sectionNode);
            }
        }
    }

    /** True once the section covering this position has been handed to the renderer. */
    public static boolean isSectionReady(BlockPos pos) {
        return isSectionReady(SectionPos.asLong(pos));
    }

    private static boolean isSectionReady(long node) {
        synchronized (compiledSections) {
            return compiledSections.contains(node);
        }
    }

    /**
     * Sections near the player are built by the renderer inside the current frame, which reads the vertex data
     * uploaded earlier in that same frame. Handing them over from a builder thread in the middle of a frame lets the
     * acceleration structure be built from buffers that were never filled, so they are applied on the render thread
     * before the frame is recorded.
     */
    private static final ConcurrentLinkedQueue<SectionUpload> IMPORTANT_UPLOADS = new ConcurrentLinkedQueue<>();

    private record SectionUpload(int generation, long sectionNode, int sequence, int slot, BlockPos origin, int[] geometryTypes, String[] names,
        int atlasId, int[] vertexCounts, long[] vertices) {

        void apply() {
            applyWith(true);
        }

        void applyAsync() {
            applyWith(false);
        }

        private void applyWith(boolean important) {
            int count = this.vertices.length;
            long geometryTypesPtr = MemoryUtil.nmemCalloc(count, Integer.BYTES);
            long geometryGroupNames = MemoryUtil.nmemCalloc(count, Long.BYTES);
            long geometryTextures = MemoryUtil.nmemCalloc(count, Integer.BYTES);
            long vertexFormats = MemoryUtil.nmemCalloc(count, Integer.BYTES);
            long vertexCountsPtr = MemoryUtil.nmemCalloc(count, Integer.BYTES);
            long verticesPtr = MemoryUtil.nmemCalloc(count, Long.BYTES);
            long[] namePtrs = new long[count];

            try {
                for (int i = 0; i < count; i++) {
                    namePtrs[i] = MemoryUtil.memAddress(MemoryUtil.memUTF8(this.names[i], true));
                    MemoryUtil.memPutInt(geometryTypesPtr + (long) i * Integer.BYTES, this.geometryTypes[i]);
                    MemoryUtil.memPutAddress(geometryGroupNames + (long) i * Long.BYTES, namePtrs[i]);
                    MemoryUtil.memPutInt(geometryTextures + (long) i * Integer.BYTES, this.atlasId);
                    MemoryUtil.memPutInt(vertexFormats + (long) i * Integer.BYTES, VERTEX_FORMAT_PBR);
                    MemoryUtil.memPutInt(vertexCountsPtr + (long) i * Integer.BYTES, this.vertexCounts[i]);
                    MemoryUtil.memPutAddress(verticesPtr + (long) i * Long.BYTES, this.vertices[i]);
                }

                ChunkProxy.rebuild(this.origin.getX(), this.origin.getY(), this.origin.getZ(), this.slot, count,
                    geometryTypesPtr, geometryGroupNames, geometryTextures, vertexFormats, vertexCountsPtr, verticesPtr,
                    important);
            } finally {
                MemoryUtil.nmemFree(geometryTypesPtr);
                MemoryUtil.nmemFree(geometryGroupNames);
                MemoryUtil.nmemFree(geometryTextures);
                MemoryUtil.nmemFree(vertexFormats);
                MemoryUtil.nmemFree(vertexCountsPtr);
                MemoryUtil.nmemFree(verticesPtr);
                for (long name : namePtrs) {
                    MemoryUtil.nmemFree(name);
                }
            }
        }

        void free() {
            for (long address : this.vertices) {
                MemoryUtil.nmemFree(address);
            }
        }
    }

    /** Applies near-player section rebuilds; call on the render thread before the renderer records a frame. */
    public static void applyImportantUploads() {
        SectionUpload upload;
        while ((upload = IMPORTANT_UPLOADS.poll()) != null) {
            try {
                if (upload.generation() == generation && ownsSlot(upload.slot(), upload.sectionNode())
                    && isLatestBuild(upload.sectionNode(), upload.sequence())) {
                    upload.apply();
                }
            } finally {
                upload.free();
            }
        }
    }

    private static void upload(int slot, long sectionNode, int sequence, BlockPos origin,
        SectionCompileScratch scratch, int atlasId, boolean important) {
        List<ChunkSectionLayer> layers = new ArrayList<>();
        for (Map.Entry<ChunkSectionLayer, PBRVertexWriter> entry : scratch.writers.entrySet()) {
            entry.getValue().finish();
            if (entry.getValue().vertexCount() > 0) {
                layers.add(entry.getKey());
            }
        }

        if (layers.isEmpty()) {
            ChunkProxy.invalidateSingle(slot);
            return;
        }

        int count = layers.size();
        int[] geometryTypes = new int[count];
        String[] names = new String[count];
        int[] vertexCounts = new int[count];
        long[] vertices = new long[count];
        for (int i = 0; i < count; i++) {
            ChunkSectionLayer layer = layers.get(i);
            PBRVertexWriter writer = scratch.writers.get(layer);
            geometryTypes[i] = layer == ChunkSectionLayer.SOLID ? GEOMETRY_TYPE_WORLD_SOLID
                : GEOMETRY_TYPE_WORLD_TRANSPARENT;
            names[i] = layer.label();
            vertexCounts[i] = writer.vertexCount();
            long size = (long) writer.vertexCount() * PBRVertexWriter.STRIDE;
            vertices[i] = MemoryUtil.nmemAllocChecked(size);
            MemoryUtil.memCopy(writer.address(), vertices[i], size);
        }

        SectionUpload sectionUpload = new SectionUpload(generation, sectionNode, sequence, slot, origin, geometryTypes, names, atlasId,
            vertexCounts, vertices);
        if (important) {
            IMPORTANT_UPLOADS.add(sectionUpload);
            return;
        }

        try {
            sectionUpload.applyAsync();
        } finally {
            sectionUpload.free();
        }
    }

    private static final class SectionCompileScratch {

        private final EnumMap<ChunkSectionLayer, PBRVertexWriter> writers = new EnumMap<>(ChunkSectionLayer.class);
        private ModelBlockRenderer blockRenderer;
        private FluidRenderer fluidRenderer;
        private PBRVertexWriter current;

        void reset() {
            for (PBRVertexWriter writer : this.writers.values()) {
                writer.reset();
            }
        }

        ModelBlockRenderer blockRenderer(Minecraft minecraft) {
            if (this.blockRenderer == null) {
                this.blockRenderer = new ModelBlockRenderer(false, true, minecraft.getBlockColors());
            }
            return this.blockRenderer;
        }

        FluidRenderer fluidRenderer(Minecraft minecraft) {
            if (this.fluidRenderer == null) {
                this.fluidRenderer = new FluidRenderer(minecraft.getModelManager().getFluidStateModelSet());
            }
            return this.fluidRenderer;
        }

        PBRVertexWriter writer(ChunkSectionLayer layer, int atlasId) {
            PBRVertexWriter writer = this.writers.get(layer);
            if (writer == null) {
                writer = new PBRVertexWriter(4096);
                this.writers.put(layer, writer);
            }
            writer.textureId(atlasId).alphaMode(alphaModeOf(layer)).coordinate(0);
            this.current = writer;
            return writer;
        }

        PBRVertexWriter currentWriter() {
            return this.current;
        }

        private static int alphaModeOf(ChunkSectionLayer layer) {
            return switch (layer) {
                case SOLID -> PBRVertexWriter.ALPHA_MODE_OPAQUE;
                case CUTOUT -> PBRVertexWriter.ALPHA_MODE_CUTOUT;
                case TRANSLUCENT -> PBRVertexWriter.ALPHA_MODE_TRANSPARENT;
            };
        }
    }
}
