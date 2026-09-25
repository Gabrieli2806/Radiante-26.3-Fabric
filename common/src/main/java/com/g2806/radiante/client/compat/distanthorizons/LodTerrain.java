package com.g2806.radiante.client.compat.distanthorizons;

import com.g2806.radiante.client.render.ChunkManager;
import com.g2806.radiante.client.render.PBRVertexWriter;
import com.g2806.radiante.client.render.RadianteRenderer;
import com.g2806.radiante.client.render.TextureTracker;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Keeps the far terrain in the tracer: picks the Distant Horizons sections to show around the camera, builds their
 * geometry in the background and hands it to the renderer's spare slots ({@link ChunkManager#EXTRA_SLOTS}).
 *
 * <p>Sections are picked the way Distant Horizons picks its own: a quadtree, split into finer sections closer to
 * the camera, but only where the finer sections exist. Distant Horizons generates coarse terrain far out long
 * before the fine terrain inside it, so a section whose children are not all there yet stays and draws the part
 * they do not cover; a section whose children are all built drops out. Coverage only ever moves from built geometry
 * to built geometry, so terrain does not blink out while its replacement is on its way.
 *
 * <p>Where the world's own chunks are built no far terrain is drawn: sections along that edge leave them out.
 */
final class LodTerrain {

    /** Coarsest sections used, 2048 blocks wide: their positions stay exact in the half floats terrain uses. */
    private static final int COARSEST_DETAIL = 11;
    /** A section is split while the camera is closer than this many of its widths. */
    private static final double SPLIT_DISTANCE = 1.0;
    private static final long RESELECT_INTERVAL_NS = 500_000_000L;
    private static final double RESELECT_DISTANCE = 16.0;
    /** How often built sections are checked for newer data: generation filling them in, or edits. */
    private static final long REFRESH_INTERVAL_NS = 5_000_000_000L;
    /**
     * Coarse sections Distant Horizons assembles from finer ones when asked have no date to tell when they
     * changed. One built from all its columns is read again this often; one that still had gaps, every refresh,
     * so it fills in as generation goes on instead of staying as it was first seen.
     */
    private static final long UNDATED_REFRESH_NS = 30_000_000_000L;
    /** A section Distant Horizons had no data for is asked again after this long. */
    private static final long RETRY_INTERVAL_NS = 15_000_000_000L;
    private static final int BUILDER_THREADS = 3;
    /**
     * Chunks past the loaded area that coarse sections leave out as well. They are built again when the player
     * moves, and flying fast the loaded area outran the rebuild: coarse blocks dozens wide stood over the new
     * chunks for a second or two. The margin gives the rebuild that long to land.
     */
    private static final int SQUARE_MARGIN_CHUNKS = 3;
    private static final int ALL_QUADRANTS = 0xF;
    private static final int ALL_CHUNKS = 0xFFFF;

    private final Map<Long, Tile> tiles = new HashMap<>();
    /** What Distant Horizons has: whether a section has data, and when that was last asked. */
    private final Map<Long, Availability> availability = new ConcurrentHashMap<>();
    private final Set<Long> probing = ConcurrentHashMap.newKeySet();
    private final ArrayDeque<Integer> freeSlots = new ArrayDeque<>();
    private final PriorityBlockingQueue<Job> jobs = new PriorityBlockingQueue<>();
    private final List<Thread> builders = new ArrayList<>();
    private final AtomicLong jobOrder = new AtomicLong();

    private @Nullable ClientLevel level;
    private volatile @Nullable Object dhLevel;
    private int generation = -1;
    private @Nullable Object looksKey;
    private volatile @Nullable BlockLooks looks;
    private volatile int atlasId;
    private int minY;

    private double lastSelectX = Double.NaN;
    private double lastSelectZ = Double.NaN;
    private long lastSelect;
    private long lastRefresh;
    private float reach;
    private volatile boolean closed;

    LodTerrain() {
        for (int i = 0; i < BUILDER_THREADS; i++) {
            Thread thread = new Thread(this::buildLoop, "Radiante LOD Builder " + i);
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 2);
            thread.start();
            this.builders.add(thread);
        }
    }

    float reach() {
        return this.reach;
    }

    void update(Minecraft minecraft, Vec3 camera) {
        // Also with no world open: leaving one queues the release of Distant Horizons' GPU buffers here.
        DhData.runRenderThreadTasks();
        ClientLevel current = minecraft.level;
        Object currentDhLevel = current != null && DhData.isActive() ? DhData.levelFor(current) : null;
        if (currentDhLevel == null) {
            reset();
            return;
        }
        if (current != this.level || currentDhLevel != this.dhLevel || ChunkManager.generation() != this.generation) {
            reset();
            this.level = current;
            this.dhLevel = currentDhLevel;
            this.generation = ChunkManager.generation();
            this.minY = current.getMinY();
            for (int i = 0; i < ChunkManager.EXTRA_SLOTS; i++) {
                this.freeSlots.add(i);
            }
        }
        if (!refreshLooks(minecraft)) {
            return;
        }

        long now = System.nanoTime();
        int distance = DhData.renderDistanceBlocks();
        this.reach = (float) (distance * Math.sqrt(2.0) + 512.0);
        boolean moved = Double.isNaN(this.lastSelectX)
            || Math.abs(camera.x - this.lastSelectX) + Math.abs(camera.z - this.lastSelectZ) > RESELECT_DISTANCE;
        if (moved || now - this.lastSelect > RESELECT_INTERVAL_NS) {
            this.lastSelect = now;
            this.lastSelectX = camera.x;
            this.lastSelectZ = camera.z;
            select(minecraft, current, camera, distance, now);
        }
        if (now - this.lastRefresh > REFRESH_INTERVAL_NS) {
            this.lastRefresh = now;
            for (Tile tile : this.tiles.values()) {
                if (tile.builtOnce) {
                    queueBuild(tile, camera, true);
                }
            }
        }
    }

    void close() {
        reset();
        this.closed = true;
        for (Thread thread : this.builders) {
            thread.interrupt();
        }
    }

    /** Builds the block colours again when the block atlas changed; false while there is no atlas yet. */
    private boolean refreshLooks(Minecraft minecraft) {
        TextureAtlas atlas = minecraft.getAtlasManager().getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS);
        Object key = TextureTracker.gpuTextureOrNull(atlas);
        if (key == null) {
            return false;
        }
        if (key != this.looksKey) {
            int id = TextureTracker.idOf(TextureAtlas.LOCATION_BLOCKS);
            if (id == 0) {
                return false;
            }
            this.looks = BlockLooks.build(minecraft);
            this.atlasId = id;
            this.looksKey = key;
            for (Tile tile : this.tiles.values()) {
                tile.dataStamp = null;
                tile.needsBuild = true;
            }
        }
        return true;
    }

    // ---- choosing sections ----

    private void select(Minecraft minecraft, ClientLevel level, Vec3 camera, int distance, long now) {
        int loadedRadius = minecraft.options.getEffectiveRenderDistance();
        int cameraChunkX = Math.floorDiv((int) Math.floor(camera.x), 16);
        int cameraChunkZ = Math.floorDiv((int) Math.floor(camera.z), 16);
        Selection selection = new Selection(level, camera, distance, cameraChunkX, cameraChunkZ, loadedRadius, now);

        int rootWidth = 1 << COARSEST_DETAIL;
        int minRootX = Math.floorDiv((int) Math.floor(camera.x) - distance, rootWidth);
        int maxRootX = Math.floorDiv((int) Math.floor(camera.x) + distance, rootWidth);
        int minRootZ = Math.floorDiv((int) Math.floor(camera.z) - distance, rootWidth);
        int maxRootZ = Math.floorDiv((int) Math.floor(camera.z) + distance, rootWidth);
        for (int x = minRootX; x <= maxRootX; x++) {
            for (int z = minRootZ; z <= maxRootZ; z++) {
                selection.visit(COARSEST_DETAIL, x, z);
            }
        }

        for (Map.Entry<Long, Wanted> entry : selection.chosen.entrySet()) {
            Wanted wanted = entry.getValue();
            Tile tile = this.tiles.get(entry.getKey());
            if (tile == null) {
                Integer slot = this.freeSlots.poll();
                if (slot == null) {
                    continue;
                }
                tile = new Tile(wanted.detail(), wanted.x(), wanted.z(), slot, wanted.hide());
                this.tiles.put(entry.getKey(), tile);
            } else if (!tile.hide.equals(wanted.hide())) {
                tile.hide = wanted.hide();
                tile.needsBuild = true;
                // What it leaves out changed, so its geometry may be standing over real terrain now: first.
                tile.urgent = true;
            }
            if (tile.needsBuild) {
                queueBuild(tile, camera, false);
            }
        }

        for (Iterator<Map.Entry<Long, Tile>> it = this.tiles.entrySet().iterator(); it.hasNext();) {
            Tile tile = it.next().getValue();
            if (!selection.chosen.containsKey(tile.key)) {
                it.remove();
                drop(tile);
            }
        }
    }

    /** Walks the quadtree of sections, collecting the ones to draw and what each must leave out. */
    private final class Selection {

        final Map<Long, Wanted> chosen = new HashMap<>();
        private final ClientLevel level;
        private final Vec3 camera;
        private final int distance;
        private final int minLoadedChunkX;
        private final int maxLoadedChunkX;
        private final int minLoadedChunkZ;
        private final int maxLoadedChunkZ;
        private final long now;

        Selection(ClientLevel level, Vec3 camera, int distance, int chunkX, int chunkZ, int loadedRadius, long now) {
            this.level = level;
            this.camera = camera;
            this.distance = distance;
            this.minLoadedChunkX = chunkX - loadedRadius;
            this.maxLoadedChunkX = chunkX + loadedRadius;
            this.minLoadedChunkZ = chunkZ - loadedRadius;
            this.maxLoadedChunkZ = chunkZ + loadedRadius;
            this.now = now;
        }

        /** Chooses what draws this section's area; true when all of it is drawn by geometry already built. */
        boolean visit(int detail, int x, int z) {
            int width = 1 << detail;
            double distanceTo = distanceTo(detail, x, z);
            if (distanceTo > this.distance) {
                return true;
            }
            long key = Tile.key(detail, x, z);
            int minX = x * width;
            int minZ = z * width;
            boolean nearLoaded = overlapsLoaded(minX, minZ, width);
            int loadedChunks = 0;
            int arrivedChunks = 0;
            if (nearLoaded && detail == DhData.BLOCK_SECTION_DETAIL) {
                loadedChunks = loadedChunks(minX, minZ, width, true);
                arrivedChunks = loadedChunks(minX, minZ, width, false);
                if (loadedChunks == ALL_CHUNKS) {
                    return true;
                }
            }

            int covered = 0;
            boolean split = detail > DhData.BLOCK_SECTION_DETAIL
                && (distanceTo < SPLIT_DISTANCE * width || nearLoaded);
            if (split) {
                for (int i = 0; i < 4; i++) {
                    int childX = x * 2 + (i & 1);
                    int childZ = z * 2 + (i >> 1);
                    if (distanceTo(detail - 1, childX, childZ) > this.distance) {
                        covered |= 1 << i;
                    } else if (hasData(detail - 1, childX, childZ) && visit(detail - 1, childX, childZ)) {
                        covered |= 1 << i;
                    }
                }
                if (covered == ALL_QUADRANTS) {
                    return true;
                }
            }

            Availability own = LodTerrain.this.availability.get(key);
            if (own != null && own.none()) {
                // Nothing here to draw; the section above covers the area if it can.
                return false;
            }
            Tile tile = LodTerrain.this.tiles.get(key);
            boolean built = tile != null && tile.builtOnce;
            if (!built && !split) {
                // Sections merging back into this one keep drawing until it is built.
                keepBuiltInside(detail, x, z);
            }
            // The loaded square only matters to the coarser sections; left out of the others so they are not built
            // again each time the player crosses into another chunk.
            boolean square = nearLoaded && detail > DhData.BLOCK_SECTION_DETAIL;
            Hide hide = square
                ? new Hide(covered, 0, 0, true, this.minLoadedChunkX - SQUARE_MARGIN_CHUNKS,
                    this.maxLoadedChunkX + SQUARE_MARGIN_CHUNKS, this.minLoadedChunkZ - SQUARE_MARGIN_CHUNKS,
                    this.maxLoadedChunkZ + SQUARE_MARGIN_CHUNKS)
                : new Hide(covered, loadedChunks, arrivedChunks, false, 0, 0, 0, 0);
            this.chosen.put(key, new Wanted(detail, x, z, hide));
            return built;
        }

        /** Whether Distant Horizons has the section; asks it (in the background) when that is not known yet. */
        private boolean hasData(int detail, int x, int z) {
            long key = Tile.key(detail, x, z);
            Availability known = LodTerrain.this.availability.get(key);
            if (known == null || !known.complete() && this.now - known.checkedAt > RETRY_INTERVAL_NS) {
                queueProbe(key, detail, x, z, distanceTo(detail, x, z));
            }
            if (known == null || known.none()) {
                return false;
            }
            // The finest sections take over even with gaps. Next to the loaded chunks Distant Horizons has some
            // columns of them long before all (the ones it read from chunks the player passed through); the
            // coarse section standing in meanwhile drew blocks dozens wide right beside the real terrain, with
            // every tree and hill in them as a tower. A gap shows only where Distant Horizons has nothing yet.
            return known.complete() || detail <= DhData.BLOCK_SECTION_DETAIL + 1;
        }

        private void keepBuiltInside(int detail, int x, int z) {
            long minX = (long) x << detail;
            long minZ = (long) z << detail;
            long maxX = minX + (1L << detail);
            long maxZ = minZ + (1L << detail);
            for (Tile tile : LodTerrain.this.tiles.values()) {
                if (tile.detail >= detail || !tile.builtOnce) {
                    continue;
                }
                long tileX = (long) tile.x << tile.detail;
                long tileZ = (long) tile.z << tile.detail;
                if (tileX >= minX && tileX < maxX && tileZ >= minZ && tileZ < maxZ) {
                    this.chosen.putIfAbsent(tile.key, new Wanted(tile.detail, tile.x, tile.z, tile.hide));
                }
            }
        }

        private double distanceTo(int detail, int x, int z) {
            int width = 1 << detail;
            double minX = (double) x * width;
            double minZ = (double) z * width;
            double dx = Math.max(Math.max(minX - this.camera.x, this.camera.x - (minX + width)), 0.0);
            double dz = Math.max(Math.max(minZ - this.camera.z, this.camera.z - (minZ + width)), 0.0);
            return Math.max(dx, dz);
        }

        private boolean overlapsLoaded(int minX, int minZ, int width) {
            int minChunkX = minX >> 4;
            int maxChunkX = (minX + width - 1) >> 4;
            int minChunkZ = minZ >> 4;
            int maxChunkZ = (minZ + width - 1) >> 4;
            return maxChunkX >= this.minLoadedChunkX - SQUARE_MARGIN_CHUNKS
                && minChunkX <= this.maxLoadedChunkX + SQUARE_MARGIN_CHUNKS
                && maxChunkZ >= this.minLoadedChunkZ - SQUARE_MARGIN_CHUNKS
                && minChunkZ <= this.maxLoadedChunkZ + SQUARE_MARGIN_CHUNKS;
        }

        /** Of the 4 x 4 chunks of a finest section, the ones built by the renderer, one bit each ({@code x * 4 + z}). */
        private int loadedChunks(int minX, int minZ, int width, boolean mustBeBuilt) {
            int chunks = width >> 4;
            int mask = 0;
            for (int cx = 0; cx < chunks; cx++) {
                for (int cz = 0; cz < chunks; cz++) {
                    int chunkX = (minX >> 4) + cx;
                    int chunkZ = (minZ >> 4) + cz;
                    boolean inRange = chunkX >= this.minLoadedChunkX && chunkX <= this.maxLoadedChunkX
                        && chunkZ >= this.minLoadedChunkZ && chunkZ <= this.maxLoadedChunkZ;
                    // Left to the far terrain until the renderer has built it, or the chunk would blink out
                    // between arriving and being built.
                    if (inRange && this.level.getChunkSource().hasChunk(chunkX, chunkZ)
                        && (!mustBeBuilt || ChunkManager.isColumnBuilt(chunkX, chunkZ))) {
                        mask |= 1 << (cx * 4 + cz);
                    }
                }
            }
            return mask;
        }
    }

    /**
     * What a section leaves out: the quadrants finer sections draw (bit {@code (x & 1) | (z & 1) << 1} of the
     * child), and the world's own chunks - exactly, one bit per chunk, for the finest sections; as the square of
     * loaded chunks for coarser ones, which only draw near them while the finer ones are not there yet.
     */
    private record Hide(int quadrants, int chunks, int waterChunks, boolean square, int minChunkX, int maxChunkX, int minChunkZ,
                        int maxChunkZ) {

        boolean isEmpty() {
            return this.quadrants == 0 && this.chunks == 0 && this.waterChunks == 0 && !this.square;
        }
    }

    private record Wanted(int detail, int x, int z, Hide hide) {
    }

    /**
     * What Distant Horizons has of a section. Only a complete section takes over from its parent, except the
     * finest (see Selection.hasData); one with only some columns is still drawn where nothing coarser can stand in.
     */
    private record Availability(int coverage, long checkedAt) {

        boolean complete() {
            return this.coverage == DhData.COMPLETE;
        }

        boolean none() {
            return this.coverage == DhData.NO_DATA;
        }
    }

    private void drop(Tile tile) {
        synchronized (tile) {
            tile.removed = true;
            ChunkManager.clearExtraSlot(this.generation, tile.slot);
        }
        this.freeSlots.add(tile.slot);
    }

    private void reset() {
        this.jobs.clear();
        for (Tile tile : this.tiles.values()) {
            drop(tile);
        }
        this.tiles.clear();
        this.availability.clear();
        this.probing.clear();
        this.freeSlots.clear();
        this.level = null;
        this.dhLevel = null;
        this.generation = -1;
        this.reach = 0.0f;
        this.lastSelectX = Double.NaN;
    }

    // ---- building ----

    private static final int KIND_BUILD = 0;
    private static final int KIND_REFRESH = 1;
    private static final int KIND_PROBE = 2;

    private void queueBuild(Tile tile, Vec3 camera, boolean refreshOnly) {
        if (tile.queued) {
            return;
        }
        tile.queued = true;
        tile.needsBuild = false;
        double width = 1 << tile.detail;
        double dx = tile.x * width + width / 2 - camera.x;
        double dz = tile.z * width + width / 2 - camera.z;
        // Nearest first; checks for newer data go behind everything else.
        double priority = Math.sqrt(dx * dx + dz * dz) + (refreshOnly ? 1.0e9 : 0.0) - (tile.urgent ? 1.0e9 : 0.0);
        tile.urgent = false;
        this.jobs.add(new Job(refreshOnly ? KIND_REFRESH : KIND_BUILD, tile, tile.key, tile.detail, tile.x, tile.z,
            this.dhLevel, this.generation, priority, this.jobOrder.getAndIncrement()));
    }

    private void queueProbe(long key, int detail, int x, int z, double distance) {
        if (this.probing.add(key)) {
            this.jobs.add(new Job(KIND_PROBE, null, key, detail, x, z, this.dhLevel, this.generation, distance,
                this.jobOrder.getAndIncrement()));
        }
    }

    private record Job(int kind, @Nullable Tile tile, long key, int detail, int x, int z, @Nullable Object dhLevel,
                       int generation, double priority, long order) implements Comparable<Job> {

        @Override
        public int compareTo(Job other) {
            int byPriority = Double.compare(this.priority, other.priority);
            return byPriority != 0 ? byPriority : Long.compare(this.order, other.order);
        }
    }

    private void buildLoop() {
        PBRVertexWriter solid = new PBRVertexWriter(4096);
        PBRVertexWriter water = new PBRVertexWriter(1024);
        while (!this.closed) {
            Job job;
            try {
                job = this.jobs.take();
            } catch (InterruptedException e) {
                break;
            }
            try {
                if (job.kind == KIND_PROBE) {
                    probe(job);
                } else {
                    build(job, solid, water);
                }
            } catch (java.util.concurrent.TimeoutException e) {
                // Distant Horizons is busy or its level is closing; the section is asked for again later.
            } catch (Exception e) {
                RadianteRenderer.LOGGER.warn("Could not build far terrain for a Distant Horizons section", e);
            } finally {
                if (job.kind == KIND_PROBE) {
                    this.probing.remove(job.key);
                } else if (job.tile != null) {
                    job.tile.queued = false;
                }
            }
        }
        solid.close();
        water.close();
    }

    private void probe(Job job) throws Exception {
        if (job.dhLevel == null || job.dhLevel != this.dhLevel) {
            return;
        }
        int coverage = DhData.coverage(job.dhLevel, job.detail, job.x, job.z);
        if (job.dhLevel == this.dhLevel) {
            this.availability.put(job.key, new Availability(coverage, System.nanoTime()));
        }
    }

    private void build(Job job, PBRVertexWriter solid, PBRVertexWriter water) throws Exception {
        Tile tile = Objects.requireNonNull(job.tile);
        BlockLooks currentLooks = this.looks;
        if (tile.removed || currentLooks == null || job.dhLevel == null) {
            return;
        }
        // Coarse sections are often put together from finer ones when asked for, without a stored date; a
        // missing date is no reason to skip one.
        Long stamp = DhData.timestamp(job.dhLevel, tile.detail, tile.x, tile.z);
        Hide hide = tile.hide;
        if (job.kind == KIND_REFRESH && Objects.equals(stamp, tile.dataStamp) && hide.equals(tile.builtHide)
            && (stamp != null || tile.builtComplete && System.nanoTime() - tile.builtAt < UNDATED_REFRESH_NS)) {
            return;
        }
        LodSection section = DhData.fetch(job.dhLevel, tile.detail, tile.x, tile.z);
        if (job.dhLevel == this.dhLevel) {
            int coverage = section == null ? DhData.NO_DATA : section.complete() ? DhData.COMPLETE : DhData.PARTIAL;
            this.availability.put(tile.key, new Availability(coverage, System.nanoTime()));
        }
        if (section == null) {
            return;
        }

        int width = 1 << tile.detail;
        int originX = tile.x * width;
        int originZ = tile.z * width;
        boolean[] hidden = hide.isEmpty() ? null : hiddenColumns(section, hide, originX, originZ);
        boolean[] waterHidden = hide.waterChunks() == 0 ? null : chunkColumns(section, hide.waterChunks());

        solid.reset();
        water.reset();
        int atlas = this.atlasId;
        solid.textureId(atlas).alphaMode(PBRVertexWriter.ALPHA_MODE_OPAQUE).coordinate(0).water(false)
            .overlayEnabled(false).computeQuadNormals(true);
        water.textureId(atlas).alphaMode(PBRVertexWriter.ALPHA_MODE_TRANSPARENT).coordinate(0).water(true)
            .overlayEnabled(false).computeQuadNormals(true);
        LodMesher.mesh(section, currentLooks, this.minY, sinkOf(tile.detail), originX, originZ, hidden, waterHidden,
            solid, water);
        solid.finish();
        water.finish();

        List<PBRVertexWriter> layers = new ArrayList<>(2);
        List<ChunkSectionLayer> kinds = new ArrayList<>(2);
        if (solid.vertexCount() > 0) {
            layers.add(solid);
            kinds.add(ChunkSectionLayer.SOLID);
        }
        if (water.vertexCount() > 0) {
            layers.add(water);
            kinds.add(ChunkSectionLayer.TRANSLUCENT);
        }

        int count = layers.size();
        int[] types = new int[count];
        String[] names = new String[count];
        int[] vertexCounts = new int[count];
        long[] vertices = new long[count];
        try {
            for (int i = 0; i < count; i++) {
                PBRVertexWriter writer = layers.get(i);
                types[i] = kinds.get(i) == ChunkSectionLayer.SOLID ? ChunkManager.GEOMETRY_TYPE_WORLD_SOLID
                    : ChunkManager.GEOMETRY_TYPE_WORLD_TRANSPARENT;
                names[i] = kinds.get(i).label();
                vertexCounts[i] = writer.vertexCount();
                long size = (long) writer.vertexCount() * PBRVertexWriter.STRIDE;
                vertices[i] = MemoryUtil.nmemAllocChecked(size);
                MemoryUtil.memCopy(writer.address(), vertices[i], size);
            }
            synchronized (tile) {
                if (tile.removed) {
                    return;
                }
                if (count == 0) {
                    ChunkManager.clearExtraSlot(job.generation, tile.slot);
                } else {
                    ChunkManager.submitExtraGeometry(job.generation, tile.slot,
                        new BlockPos(originX, this.minY, originZ), types, names, atlas, vertexCounts, vertices);
                }
                tile.dataStamp = stamp;
                tile.builtHide = hide;
                tile.builtOnce = true;
                tile.builtComplete = section.complete();
                tile.builtAt = System.nanoTime();
            }
        } finally {
            for (long address : vertices) {
                if (address != 0L) {
                    MemoryUtil.nmemFree(address);
                }
            }
        }
    }

    /**
     * How far a section's terrain is lowered (see LodMesher.mesh): a quarter block for the finest, a quarter more
     * per level coarser. Quarters stay exact in the half floats terrain positions are stored in.
     */
    private static float sinkOf(int detail) {
        return 0.25f * (detail - DhData.BLOCK_SECTION_DETAIL + 1);
    }

    /** Marks the columns a section leaves out; see {@link Hide}. */
    private static boolean[] hiddenColumns(LodSection section, Hide hide, int originX, int originZ) {
        int width = section.width();
        int columnBlocks = section.columnBlocks();
        int half = width / 2;
        boolean[] hidden = new boolean[width * width];
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < width; z++) {
                int quadrant = (x >= half ? 1 : 0) | (z >= half ? 2 : 0);
                boolean out = (hide.quadrants() & 1 << quadrant) != 0;
                if (!out && hide.chunks() != 0) {
                    out = inChunkMask(x, z, columnBlocks, hide.chunks());
                }
                if (!out && hide.square()) {
                    // Any overlap, not the column's centre: a coarse column is up to a few dozen blocks wide and as
                    // tall as the highest thing in it, and one reaching into the loaded area stood over the real
                    // terrain there as a plateau.
                    int minChunkX = originX + x * columnBlocks >> 4;
                    int maxChunkX = originX + (x + 1) * columnBlocks - 1 >> 4;
                    int minChunkZ = originZ + z * columnBlocks >> 4;
                    int maxChunkZ = originZ + (z + 1) * columnBlocks - 1 >> 4;
                    out = maxChunkX >= hide.minChunkX() && minChunkX <= hide.maxChunkX()
                        && maxChunkZ >= hide.minChunkZ() && minChunkZ <= hide.maxChunkZ();
                }
                hidden[x * width + z] = out;
            }
        }
        return hidden;
    }

    /**
     * The columns of a finest section inside the given chunks. Water uses the chunks the world has loaded rather
     * than built: two translucent surfaces a fraction of a block apart both show, darkening the sea in squares
     * while a chunk is built, where two opaque ones would simply let the nearer win.
     */
    private static boolean[] chunkColumns(LodSection section, int chunks) {
        int width = section.width();
        boolean[] hidden = new boolean[width * width];
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < width; z++) {
                hidden[x * width + z] = inChunkMask(x, z, section.columnBlocks(), chunks);
            }
        }
        return hidden;
    }

    private static boolean inChunkMask(int x, int z, int columnBlocks, int chunks) {
        int chunkX = x * columnBlocks >> 4;
        int chunkZ = z * columnBlocks >> 4;
        return chunkX < 4 && chunkZ < 4 && (chunks & 1 << (chunkX * 4 + chunkZ)) != 0;
    }

    private static final class Tile {

        final long key;
        final int detail;
        final int x;
        final int z;
        final int slot;
        volatile Hide hide;
        volatile boolean queued;
        volatile boolean urgent;
        volatile boolean needsBuild = true;
        volatile boolean removed;
        volatile boolean builtOnce;
        volatile @Nullable Long dataStamp;
        volatile @Nullable Hide builtHide;
        volatile boolean builtComplete;
        volatile long builtAt;

        Tile(int detail, int x, int z, int slot, Hide hide) {
            this.key = key(detail, x, z);
            this.detail = detail;
            this.x = x;
            this.z = z;
            this.slot = slot;
            this.hide = hide;
        }

        /** Detail in the top 6 bits, then 29 bits each of x and z; section coordinates stay well inside that. */
        static long key(int detail, int x, int z) {
            return (long) detail << 58 | ((long) x & 0x1FFFFFFFL) << 29 | (long) z & 0x1FFFFFFFL;
        }
    }
}
