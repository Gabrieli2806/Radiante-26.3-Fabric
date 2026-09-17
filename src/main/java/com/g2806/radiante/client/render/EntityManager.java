package com.g2806.radiante.client.render;

import com.g2806.radiante.client.proxy.world.EntityProxy;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

/**
 * Feeds mobs, the player and the first person hands into the renderer. Minecraft submits them once per frame
 * and the geometry is rebuilt every frame, which is what the renderer expects for dynamic entities.
 */
public final class EntityManager {

    private static final EntityCollector COLLECTOR = new EntityCollector();
    private static final PoseStack POSE_STACK = new PoseStack();
    private static final List<PendingEntity> PENDING = new ArrayList<>();
    private static boolean queued;
    private static int DEBUG_BLOCK_ENTITIES;
    private static final java.util.Set<String> DEBUG_BLOCK_ENTITY_KINDS = new java.util.HashSet<>();
    private static final java.util.Set<String> DEBUG_FAILED_BLOCK_ENTITIES = new java.util.HashSet<>();
    /** How far out block entities are gathered, in chunks and in blocks; beyond this they are too small to matter. */
    private static final int BLOCK_ENTITY_CHUNK_RADIUS = 6;
    private static final double BLOCK_ENTITY_RANGE = 80.0;
    /** How brightly a glow item frame lights itself. */
    private static final float GLOW_FRAME_EMISSION = 1.5f;
    /** An end crystal burns from inside; vanilla draws it at full brightness whatever the light around it. */
    private static final float END_CRYSTAL_EMISSION = 3.0f;
    private static int DEBUG_TEXT_LAYERS;

    /** Masks the ray tracing shaders select geometry with. */
    private static final int RAY_TRACING_WORLD = 0b00000001;
    private static final int RAY_TRACING_HAND = 0b00001000;
    private static final int RAY_TRACING_PARTICLE = 0b00100000;
    private static final int PARTICLES_ID = "radiante:particles".hashCode();
    private static final PBRVertexWriter PARTICLE_WRITER = new PBRVertexWriter(4096);
    private static final int HAND_ID = "radiante:hand".hashCode();

    private EntityManager() {
    }

    private record PendingLayer(int geometryType, int textureId, int vertexCount, long vertices, String name) {
    }

    private record PendingEntity(int id, double x, double y, double z, int rayTracingFlag,
        List<PendingLayer> layers) {
    }

    public static void render(Minecraft minecraft, LevelRenderState levelRenderState) {
        if (minecraft.level == null) {
            return;
        }

        CameraRenderState cameraState = levelRenderState.cameraRenderState;
        PENDING.clear();
        // Last frame's vertex copies were consumed by the upload below; the arena starts over.
        ARENA.reset();

        for (EntityRenderState state : levelRenderState.entityRenderStates) {
            collect(minecraft, cameraState, state);
        }

        for (BlockEntityRenderState state : collectBlockEntityStates(minecraft, levelRenderState, cameraState)) {
            collectBlockEntity(minecraft, cameraState, state);
        }

        collectParticles(levelRenderState, cameraState);
        collectHands(minecraft, levelRenderState, cameraState);

        upload(NativeGeometry.COORDINATE_WORLD);

        if (queued) {
            queued = false;
            EntityProxy.build();
        }
    }

    private static void collect(Minecraft minecraft, CameraRenderState cameraState, EntityRenderState state) {

        COLLECTOR.reset();
        POSE_STACK.setIdentity();

        // A glow item frame is lit from within in vanilla rather than by the world around it, and nothing in its
        // model says so; the entity does.
        if (state instanceof net.minecraft.client.renderer.entity.state.ItemFrameRenderState frame
            && frame.isGlowFrame) {
            COLLECTOR.entityEmission(GLOW_FRAME_EMISSION);
        } else if (state instanceof net.minecraft.client.renderer.entity.state.EndCrystalRenderState) {
            // An end crystal is a light source in its own right, and its texture is what gives it its colour, so
            // the glow is kept modest: multiplied by a bright texture, a large value burns the whole thing white.
            COLLECTOR.entityEmission(END_CRYSTAL_EMISSION);
        }

        try {
            // Built around the entity origin; the renderer places the instance at the entity position.
            minecraft.getEntityRenderDispatcher().submit(state, cameraState, 0.0, 0.0, 0.0, POSE_STACK, COLLECTOR);
        } catch (RuntimeException e) {
            return;
        }

        if (COLLECTOR.isEmpty()) {
            return;
        }

        addPending(System.identityHashCode(state), state.x, state.y, state.z, RAY_TRACING_WORLD);
    }

    /**
     * Minecraft only extracts block entities from the chunk sections it decided to draw, and the ray tracer replaces
     * that pass entirely, so its list holds nothing but the handful that render from any distance, such as beacons.
     * Everything placed in the world - signs, chests, banners, end portals - has to be gathered here instead, from
     * the chunks around the camera.
     */
    private static List<BlockEntityRenderState> collectBlockEntityStates(Minecraft minecraft,
        LevelRenderState levelRenderState, CameraRenderState cameraState) {
        List<BlockEntityRenderState> states = new ArrayList<>(levelRenderState.blockEntityRenderStates);
        if (minecraft.level == null) {
            return states;
        }

        Vec3 camera = cameraState.pos;
        int centerX = Mth.floor(camera.x()) >> 4;
        int centerZ = Mth.floor(camera.z()) >> 4;
        int radius = Math.min(BLOCK_ENTITY_CHUNK_RADIUS, minecraft.options.getEffectiveRenderDistance());
        float partialTicks = levelRenderState.worldPartialTicks;

        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                LevelChunk chunk = minecraft.level.getChunkSource().getChunk(x, z, false);
                if (chunk == null) {
                    continue;
                }
                for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                    BlockPos pos = entry.getKey();
                    if (pos.distToCenterSqr(camera) > BLOCK_ENTITY_RANGE * BLOCK_ENTITY_RANGE) {
                        continue;
                    }
                    BlockEntityRenderState state = minecraft.getBlockEntityRenderDispatcher()
                        .tryExtractRenderState(entry.getValue(), partialTicks, null, false);
                    if (state != null) {
                        states.add(state);
                    }
                }
            }
        }
        return states;
    }

    private static void collectBlockEntity(Minecraft minecraft, CameraRenderState cameraState,
        BlockEntityRenderState state) {
        COLLECTOR.reset();
        POSE_STACK.setIdentity();

        try {
            minecraft.getBlockEntityRenderDispatcher().submit(state, POSE_STACK, COLLECTOR, cameraState);
        } catch (RuntimeException e) {
            // Swallowing this silently once hid the reason a whole kind of block entity never appeared.
            if (DEBUG_FAILED_BLOCK_ENTITIES.add(state.getClass().getSimpleName())) {
                RadianteRenderer.LOGGER.warn("Block entity {} could not be collected", state.getClass().getSimpleName(),
                    e);
            }
            return;
        }

        if (COLLECTOR.isEmpty()) {
            return;
        }

        BlockPos pos = state.blockPos;
        int before = PENDING.size();
        addPending(pos.hashCode() ^ 0x5BD1E995, pos.getX(), pos.getY(), pos.getZ(), RAY_TRACING_WORLD);
        if (DEBUG_BLOCK_ENTITIES < 20) {
            for (RenderType type : COLLECTOR.layers().keySet()) {
                RenderTypeInfo info = RenderTypeInfo.of(type);
                if (!info.groupName().equals("Entity")) {
                    DEBUG_BLOCK_ENTITIES++;
                    RadianteRenderer.LOGGER.info("special block entity at {}: group={} geometry={} vertices={} added={}",
                        pos, info.groupName(), info.geometryType(), COLLECTOR.layers().get(type).vertexCount(),
                        PENDING.size() - before);
                }
            }
        }
    }

    /** One line per kind of block entity the renderer receives, so a missing one can be told from a broken one. */
    private static void debugBlockEntityKind(BlockEntityRenderState state) {
        if (!DEBUG_BLOCK_ENTITY_KINDS.add(state.getClass().getSimpleName())) {
            return;
        }
        RadianteRenderer.LOGGER.info("block entity kind reaching the renderer: {} at {}",
            state.getClass().getSimpleName(), state.blockPos);
    }

    /** Particles arrive already positioned relative to the camera and facing it. */
    private static void collectParticles(LevelRenderState levelRenderState, CameraRenderState cameraState) {
        COLLECTOR.reset();
        levelRenderState.particlesRenderState.submit(COLLECTOR, cameraState);

        List<PendingLayer> layers = new ArrayList<>();
        for (QuadParticleRenderState group : COLLECTOR.drainParticleGroups()) {
            for (SingleQuadParticle.Layer layer : group.layers()) {
                int textureId = TextureTracker.idOf(layer.textureAtlasLocation());
                PBRVertexWriter writer = PARTICLE_WRITER.textureId(textureId)
                    .glintTextureId(0)
                    .alphaMode(layer.translucent() ? PBRVertexWriter.ALPHA_MODE_TRANSPARENT
                        : PBRVertexWriter.ALPHA_MODE_CUTOUT)
                    .coordinate(NativeGeometry.COORDINATE_WORLD)
                    .albedoEmission(0.0f)
                    .overlayEnabled(false)
                    .computeQuadNormals(true);
                writer.reset();
                group.buildLayer(layer, writer);
                writer.finish();
                if (writer.vertexCount() == 0 || writer.vertexCount() % 4 != 0) {
                    continue;
                }
                // Cut out particles must not be traced as solid geometry; see RenderTypeInfo.geometryType. The
                // no-reflect geometry type looks like the right home for billboards, but its hit group shades them
                // black, so they stay ordinary transparent geometry until that is understood.
                layers.add(new PendingLayer(NativeGeometry.GEOMETRY_TYPE_WORLD_TRANSPARENT, textureId,
                    writer.vertexCount(), copyVertices(writer), "Entity"));
            }
        }

        if (!layers.isEmpty()) {
            Vec3 camera = cameraState.pos;
            PENDING.add(new PendingEntity(PARTICLES_ID, camera.x(), camera.y(), camera.z(), RAY_TRACING_PARTICLE,
                layers));
        }
    }

    /** The held items and arms are submitted separately from the world and follow the camera. */
    private static void collectHands(Minecraft minecraft, LevelRenderState levelRenderState,
        CameraRenderState cameraState) {
        PlayerRenderState playerState = levelRenderState.playerRenderState;
        if (!playerState.hasPlayer || minecraft.gameRenderer.gameRenderState().guiRenderState.isHudHidden || !minecraft.options.getCameraType().isFirstPerson()
            || cameraState.entityRenderState.isSleeping
            || minecraft.gameMode == null || minecraft.gameMode.getPlayerMode() == GameType.SPECTATOR) {
            return;
        }

        COLLECTOR.reset();
        POSE_STACK.setIdentity();
        // Minecraft poses the hands in its view space; rotate them into world orientation around the camera.
        POSE_STACK.mulPose(new Matrix4f(cameraState.viewRotationMatrix).invert());

        try {
            minecraft.gameRenderer.firstPersonHandsAndItemsRenderer.submitHandsWithItems(
                cameraState.cameraEntityPartialTicks, POSE_STACK, COLLECTOR, playerState,
                playerState.firstPersonHandsAndItems);
        } catch (RuntimeException e) {
            return;
        }

        addPending(HAND_ID, cameraState.pos.x(), cameraState.pos.y(), cameraState.pos.z(), RAY_TRACING_HAND);
    }

    private static void addPending(int id, double x, double y, double z, int rayTracingFlag) {
        // An entity whose origin is not a real point in the world - a block-attached one that lost its support
        // block reports exactly that - would place its whole model outside anything the tracer can build.
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return;
        }

        List<PendingLayer> layers = new ArrayList<>();
        for (Map.Entry<RenderType, PBRVertexWriter> entry : COLLECTOR.layers().entrySet()) {
            PBRVertexWriter writer = entry.getValue();
            writer.finish();
            if (writer.vertexCount() == 0 || writer.vertexCount() % 4 != 0) {
                RenderTypeInfo dropped = RenderTypeInfo.of(entry.getKey());
                if (DEBUG_TEXT_LAYERS < 5 && dropped.needsComputedNormals()) {
                    DEBUG_TEXT_LAYERS++;
                    RadianteRenderer.LOGGER.info("layer dropped: group={} vertices={}", dropped.groupName(),
                        writer.vertexCount());
                }
                continue;
            }

            RenderTypeInfo info = RenderTypeInfo.of(entry.getKey());
            if (DEBUG_TEXT_LAYERS < 5 && info.needsComputedNormals()) {
                DEBUG_TEXT_LAYERS++;
                RadianteRenderer.LOGGER.info("layer accepted: group={} geometry={} textureId={} vertices={}",
                    info.groupName(), info.geometryType(), info.textureId(), writer.vertexCount());
            }
            layers.add(new PendingLayer(info.geometryType(), info.textureId(), writer.vertexCount(),
                copyVertices(writer), info.groupName()));
        }

        if (!layers.isEmpty()) {
            PENDING.add(new PendingEntity(id, x, y, z, rayTracingFlag, layers));
        }
    }

    /**
     * The collector reuses its buffers between entities, so each layer keeps its own copy until the native renderer
     * has consumed it. Those copies used to be one allocation each, which in a busy world meant hundreds of
     * malloc and free calls every frame; they all live for exactly one frame, so one arena serves them all.
     * An offset is returned rather than an address because growing the arena moves it.
     */
    private static long copyVertices(PBRVertexWriter writer) {
        long size = (long) writer.vertexCount() * PBRVertexWriter.STRIDE;
        return ARENA.append(writer.address(), size);
    }

    /** A bump allocator for everything that lives for one frame and is then handed to the renderer. */
    private static final class Arena {

        private long address;
        private long capacity;
        private long used;

        long append(long source, long size) {
            reserve(this.used + size);
            long offset = this.used;
            MemoryUtil.memCopy(source, this.address + offset, size);
            this.used += size;
            return offset;
        }

        long addressOf(long offset) {
            return this.address + offset;
        }

        void reset() {
            this.used = 0L;
        }

        private void reserve(long required) {
            if (required <= this.capacity) {
                return;
            }
            long grown = Math.max(required, Math.max(this.capacity * 2L, 1L << 16));
            this.address = this.address == 0L ? MemoryUtil.nmemAllocChecked(grown)
                : MemoryUtil.nmemReallocChecked(this.address, grown);
            this.capacity = grown;
        }
    }

    private static final Arena ARENA = new Arena();

    /**
     * Group names are a handful of fixed strings, but a native copy of one was being allocated for every layer of
     * every entity every frame. They never change, so each is encoded once and kept.
     */
    private static long groupNameAddress(String name) {
        Long cached = GROUP_NAMES.get(name);
        if (cached != null) {
            return cached;
        }
        long address = MemoryUtil.memAddress(MemoryUtil.memUTF8(name, true));
        GROUP_NAMES.put(name, address);
        return address;
    }

    private static final Map<String, Long> GROUP_NAMES = new java.util.HashMap<>();

    /**
     * The fixed set of native arrays the upload hands over. Each slot keeps its buffer between frames and only
     * ever grows, so a frame costs no allocation at all once the world has settled.
     */
    private static final class Scratch {

        private static final int SLOTS = 17;

        private final long[] addresses = new long[SLOTS];
        private final long[] sizes = new long[SLOTS];

        long ints(int slot, int count) {
            return reserve(slot, (long) count * Integer.BYTES);
        }

        long longs(int slot, int count) {
            return reserve(slot, (long) count * Long.BYTES);
        }

        long doubles(int slot, int count) {
            return reserve(slot, (long) count * Double.BYTES);
        }

        /** For the arrays the upload never writes: they have to read as zero, as a fresh allocation did. */
        long zeroedInts(int slot, int count) {
            long size = (long) count * Integer.BYTES;
            long address = reserve(slot, size);
            MemoryUtil.memSet(address, 0, size);
            return address;
        }

        long zeroedLongs(int slot, int count) {
            long size = (long) count * Long.BYTES;
            long address = reserve(slot, size);
            MemoryUtil.memSet(address, 0, size);
            return address;
        }

        private long reserve(int slot, long size) {
            if (size <= this.sizes[slot] && this.addresses[slot] != 0L) {
                return this.addresses[slot];
            }
            long grown = Math.max(size, Math.max(this.sizes[slot] * 2L, 256L));
            this.addresses[slot] = this.addresses[slot] == 0L ? MemoryUtil.nmemAllocChecked(grown)
                : MemoryUtil.nmemReallocChecked(this.addresses[slot], grown);
            this.sizes[slot] = grown;
            return this.addresses[slot];
        }
    }

    private static final Scratch SCRATCH = new Scratch();

    private static void upload(int coordinate) {
        if (PENDING.isEmpty()) {
            return;
        }

        queued = true;
        int entityCount = PENDING.size();
        int layerCount = 0;
        for (PendingEntity entity : PENDING) {
            layerCount += entity.layers().size();
        }

        // These seventeen arrays are the same shape every frame and only ever grow, so they are kept between
        // frames. Allocating and freeing them per frame was pure overhead on the render thread.
        long hashCodes = SCRATCH.ints(0, entityCount);
        long posXs = SCRATCH.doubles(1, entityCount);
        long posYs = SCRATCH.doubles(2, entityCount);
        long posZs = SCRATCH.doubles(3, entityCount);
        long rayTracingFlags = SCRATCH.ints(4, entityCount);
        long postRenderFlags = SCRATCH.zeroedInts(5, entityCount);
        long prebuiltBlas = SCRATCH.ints(6, entityCount);
        long posts = SCRATCH.zeroedInts(7, entityCount);
        long layerCounts = SCRATCH.ints(8, entityCount);
        long geometryTypes = SCRATCH.ints(9, layerCount);
        long geometryGroupNames = SCRATCH.longs(10, layerCount);
        long geometryContentNames = SCRATCH.zeroedLongs(11, layerCount);
        long geometryTextures = SCRATCH.ints(12, layerCount);
        long vertexFormats = SCRATCH.ints(13, layerCount);
        long indexFormats = SCRATCH.ints(14, layerCount);
        long vertexCounts = SCRATCH.ints(15, layerCount);
        long vertices = SCRATCH.longs(16, layerCount);

        try {
            int layerIndex = 0;
            for (int i = 0; i < entityCount; i++) {
                PendingEntity entity = PENDING.get(i);
                MemoryUtil.memPutInt(hashCodes + (long) i * Integer.BYTES, entity.id());
                MemoryUtil.memPutDouble(posXs + (long) i * Double.BYTES, entity.x());
                MemoryUtil.memPutDouble(posYs + (long) i * Double.BYTES, entity.y());
                MemoryUtil.memPutDouble(posZs + (long) i * Double.BYTES, entity.z());
                MemoryUtil.memPutInt(layerCounts + (long) i * Integer.BYTES, entity.layers().size());
                MemoryUtil.memPutInt(rayTracingFlags + (long) i * Integer.BYTES, entity.rayTracingFlag());
                // A negative id means the renderer has to build the acceleration structure itself.
                MemoryUtil.memPutInt(prebuiltBlas + (long) i * Integer.BYTES, -1);

                for (PendingLayer layer : entity.layers()) {
                    MemoryUtil.memPutInt(geometryTypes + (long) layerIndex * Integer.BYTES, layer.geometryType());
                    MemoryUtil.memPutAddress(geometryGroupNames + (long) layerIndex * Long.BYTES,
                        groupNameAddress(layer.name()));
                    MemoryUtil.memPutAddress(geometryContentNames + (long) layerIndex * Long.BYTES, 0L);
                    MemoryUtil.memPutInt(geometryTextures + (long) layerIndex * Integer.BYTES, layer.textureId());
                    MemoryUtil.memPutInt(vertexFormats + (long) layerIndex * Integer.BYTES,
                        NativeGeometry.VERTEX_FORMAT_PBR);
                    MemoryUtil.memPutInt(indexFormats + (long) layerIndex * Integer.BYTES,
                        NativeGeometry.DRAW_MODE_QUADS);
                    MemoryUtil.memPutInt(vertexCounts + (long) layerIndex * Integer.BYTES, layer.vertexCount());
                    MemoryUtil.memPutAddress(vertices + (long) layerIndex * Long.BYTES,
                        ARENA.addressOf(layer.vertices()));
                    layerIndex++;
                }
            }

            EntityProxy.queue(0.0125f, coordinate, false, entityCount, hashCodes, posXs,
                posYs, posZs, rayTracingFlags, postRenderFlags, prebuiltBlas, posts, layerCounts, geometryTypes,
                geometryGroupNames, geometryContentNames, geometryTextures, vertexFormats, indexFormats,
                vertexCounts, vertices);
        } finally {
            PENDING.clear();
        }
    }
}
