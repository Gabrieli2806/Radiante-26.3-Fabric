package com.g2806.radiante.client.render;

import com.g2806.radiante.client.option.Options;
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
import net.minecraft.util.Mth;
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

    static final EntityCollector COLLECTOR =
        com.g2806.radiante.platform.RadiantePlatform.INSTANCE.createEntityCollector();
    private static final PoseStack POSE_STACK = new PoseStack();
    static final List<PendingEntity> PENDING = new ArrayList<>();
    private static boolean queued;
    private static int DEBUG_BLOCK_ENTITIES;
    private static final java.util.Set<String> DEBUG_FAILED_BLOCK_ENTITIES = new java.util.HashSet<>();
    /** How far out block entities are gathered, in chunks and in blocks; beyond this they are too small to matter. */
    private static final int BLOCK_ENTITY_CHUNK_RADIUS = 6;
    private static final double BLOCK_ENTITY_RANGE = 80.0;
    /**
     * How far the writing on signs is traced. Past this a letter is smaller than a pixel of the traced picture,
     * and every sign's text is an instance of its own whose letters are cut out by an any-hit shader: around a
     * spawn with hundreds of signs that was a large share of the frame for nothing anyone could read.
     */
    private static final double BLOCK_TEXT_RANGE = 40.0;
    /** How far from the camera banners still sway. */
    private static final double BANNER_SWAY_RANGE = 24.0;
    private static final int END_CRYSTAL_TINT = 0xD9A6FF;
    /** Below this the difference is the lightmap disagreeing with the block below the entity, not a glow. */
    private static final int SELF_LIT_MIN_EXCESS = 5;
    private static final int DISPLAY_MAX_GLOW_LEVEL = 3;
    private static final double[] SELF_LIT_SAMPLE_HEIGHTS = {0.0, 0.5, 1.0};
    private static int DEBUG_TEXT_LAYERS;

    /** Masks the ray tracing shaders select geometry with. */
    private static final int RAY_TRACING_WORLD = 0b00000001;
    /** Geometry camera rays skip in first person, while shadow rays and later bounces still see it. */
    private static final int RAY_TRACING_PLAYER = 0b00000010;
    private static final int RAY_TRACING_HAND = 0b00001000;
    /** Seen only by the glowing effect's outline rays (GLOW_OUTLINE_MASK in the shaders). */
    private static final int RAY_TRACING_GLOW_OUTLINE = 0b00000100;
    private static final int GLOW_OUTLINE_ID_SALT = 0x676C6F77;
    static final int RAY_TRACING_PARTICLE = 0b00100000;
    private static final int RAY_TRACING_CLOUD = 0b01000000;
    private static final int CLOUD_ID = "radiante:clouds".hashCode();
    private static final CloudGeometry CLOUDS = new CloudGeometry();
    private static final int NAME_TAG_ID_SALT = 0x6E616D65;
    private static final int PARTICLES_ID = "radiante:particles".hashCode();
    private static final PBRVertexWriter PARTICLE_WRITER = new PBRVertexWriter(4096);
    private static final int HAND_ID = "radiante:hand".hashCode();
    private static final int PLAYER_SHADOW_ID = "radiante:player_shadow".hashCode();
    private static final int WEATHER_ID = "radiante:weather".hashCode();
    private static final int BREAKING_ID_SALT = 0x62726B21;
    private static final int RAY_TRACING_WEATHER = 0b00010000;
    private static final PBRVertexWriter WEATHER_WRITER = new PBRVertexWriter(4096);
    private static final net.minecraft.resources.Identifier RAIN_TEXTURE =
        net.minecraft.resources.Identifier.withDefaultNamespace("textures/environment/rain.png");
    private static final net.minecraft.resources.Identifier SNOW_TEXTURE =
        net.minecraft.resources.Identifier.withDefaultNamespace("textures/environment/snow.png");
    /** Vanilla's per-column facing: each sheet is turned side-on to the camera column it stands in. */
    private static final float[] WEATHER_COLUMN_X = new float[32 * 32];
    private static final float[] WEATHER_COLUMN_Z = new float[32 * 32];

    static {
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                float deltaX = x - 16;
                float deltaZ = z - 16;
                float distance = Mth.length(deltaX, deltaZ);
                WEATHER_COLUMN_X[z * 32 + x] = distance == 0.0f ? 0.0f : -deltaZ / distance;
                WEATHER_COLUMN_Z[z * 32 + x] = distance == 0.0f ? 0.0f : deltaX / distance;
            }
        }
    }

    private EntityManager() {
    }

    /**
     * {@code vertices} is an offset into the frame arena, unless {@code directAddress} is set: then the vertices
     * are read from there as they are (a mesh kept between frames, like the clouds). {@code contentName} names the
     * content for keyed caching, or is null.
     */
    record PendingLayer(int geometryType, int textureId, int vertexCount, long vertices, String name,
        long directAddress, String contentName) {

        PendingLayer(int geometryType, int textureId, int vertexCount, long vertices, String name) {
            this(geometryType, textureId, vertexCount, vertices, name, 0L, null);
        }
    }

    /**
     * {@code cacheable}: geometry that usually stays the same from frame to frame (block entities). The native
     * side keeps the acceleration structure of such an entity while its content is unchanged instead of
     * rebuilding it every frame.
     */
    record PendingEntity(int id, double x, double y, double z, int rayTracingFlag,
        List<PendingLayer> layers, int prebuiltBlas) {

        PendingEntity(int id, double x, double y, double z, int rayTracingFlag, List<PendingLayer> layers) {
            this(id, x, y, z, rayTracingFlag, layers, PREBUILT_BLAS_NONE);
        }

        PendingEntity(int id, double x, double y, double z, int rayTracingFlag, List<PendingLayer> layers,
            boolean cacheable) {
            this(id, x, y, z, rayTracingFlag, layers, cacheable ? PREBUILT_BLAS_CACHEABLE : PREBUILT_BLAS_NONE);
        }
    }

    /** Tells native an entity may keep its acceleration structure while its geometry is unchanged. */
    private static final int PREBUILT_BLAS_NONE = -1;
    private static final int PREBUILT_BLAS_CACHEABLE = -2;
    /** Cached by the content name the layers carry, and moved by position alone; see Entities::KEYED_BLAS. */
    static final int PREBUILT_BLAS_KEYED = -3;

    public static void render(Minecraft minecraft, LevelRenderState levelRenderState) {
        if (minecraft.level == null) {
            return;
        }

        CameraRenderState cameraState = levelRenderState.cameraRenderState;
        PENDING.clear();
        // Last frame's vertex copies were consumed by the upload below; the arena starts over.
        ARENA.reset();
        BlockEntityCache.beginFrame(minecraft.level);
        CACHE_CANDIDATES.clear();

        DevProfiler.partsBegin();
        for (EntityRenderState state : levelRenderState.entityRenderStates) {
            collect(minecraft, cameraState, state);
        }
        DevProfiler.part(0);

        List<BlockEntityRenderState> blockEntityStates = collectBlockEntityStates(minecraft, levelRenderState,
            cameraState);
        DevProfiler.part(1);
        for (BlockEntityRenderState state : blockEntityStates) {
            collectBlockEntity(minecraft, cameraState, state);
        }
        DevProfiler.part(2);

        collectPlayerShadow(minecraft, levelRenderState, cameraState);
        collectBlockBreaking(minecraft, levelRenderState);
        collectParticles(levelRenderState, cameraState);
        collectExternal(levelRenderState, cameraState);
        collectWeather(levelRenderState, cameraState);
        OverlayLines.collectDebugGizmos(minecraft, cameraState);
        OverlayLines.collectBlockOutline(levelRenderState, cameraState);
        collectClouds(minecraft, levelRenderState, cameraState);
        collectHands(minecraft, levelRenderState, cameraState);
        Vec3 eye = cameraState.pos;
        PENDING.removeIf(entity -> isBlockText(entity) && (entity.x() - eye.x()) * (entity.x() - eye.x())
            + (entity.y() - eye.y()) * (entity.y() - eye.y()) + (entity.z() - eye.z()) * (entity.z() - eye.z())
            > BLOCK_TEXT_RANGE * BLOCK_TEXT_RANGE);
        DevProfiler.part(3);

        upload(NativeGeometry.COORDINATE_WORLD);
        DevProfiler.part(4);

        if (queued) {
            queued = false;
            EntityProxy.build();
        }
        DevProfiler.part(5);
    }

    private static void collect(Minecraft minecraft, CameraRenderState cameraState, EntityRenderState state) {
        // Entities that hang or stand where they were put: kept while their geometry stays the same, like block
        // entities (BlockEntityCache), instead of being written and built again every frame.
        boolean still = !state.appearsGlowing()
            && (state instanceof net.minecraft.client.renderer.entity.state.ItemFrameRenderState
                || state instanceof net.minecraft.client.renderer.entity.state.PaintingRenderState
                || state instanceof net.minecraft.client.renderer.entity.state.ArmorStandRenderState);
        if (still && BlockEntityCache.reuseEntity(historyId(state), state.x, state.y, state.z, PENDING)) {
            return;
        }

        COLLECTOR.reset();
        POSE_STACK.setIdentity();

        // A glow item frame is lit from within in vanilla rather than by the world around it, and nothing in its
        // model says so; the entity does.
        float emission = 0.0f;
        if (state instanceof net.minecraft.client.renderer.entity.state.ItemFrameRenderState frame
            && frame.isGlowFrame) {
            emission = Glow.GLOW_ITEM_FRAME;
        } else if (state instanceof net.minecraft.client.renderer.entity.state.EndCrystalRenderState) {
            // An end crystal is a light source in its own right, and its texture is what gives it its colour, so
            // the glow is kept modest: multiplied by a bright texture, a large value burns the whole thing white.
            emission = Glow.END_CRYSTAL;
            // Emission is the texture colour times a scalar, which burns toward white; a soft purple tint keeps
            // the light it casts violet, like the End.
            COLLECTOR.colorTint(END_CRYSTAL_TINT);
        }
        emission = Math.max(emission, selfLitEmission(minecraft, state));
        if (emission > 0.0f) {
            COLLECTOR.entityEmission(emission);
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

        int before = PENDING.size();
        DevProfiler.kind(state);
        addPending(historyId(state), state.x, state.y, state.z, RAY_TRACING_WORLD, still);
        DevProfiler.kind(null);
        if (still) {
            BlockEntityCache.collectedEntity(historyId(state), PENDING.subList(before, PENDING.size()),
                ARENA.addressOf(0L));
        }

        if (state.appearsGlowing()) {
            // Vanilla's glowing effect is an outline of the entity in its team colour, seen through walls. A copy of
            // the entity painted in that colour goes under a mask of its own that only the outline rays in
            // world.rgen look for; they draw the rim wherever a pixel misses the copy but a neighbour hits it.
            COLLECTOR.reset();
            COLLECTOR.colorOverride(state.outlineColor & 0xFFFFFF | 0x010101);
            POSE_STACK.setIdentity();
            try {
                minecraft.getEntityRenderDispatcher().submit(state, cameraState, 0.0, 0.0, 0.0, POSE_STACK, COLLECTOR);
            } catch (RuntimeException e) {
                return;
            }
            if (!COLLECTOR.isEmpty()) {
                addPending(historyId(state) ^ GLOW_OUTLINE_ID_SALT, state.x, state.y, state.z,
                    RAY_TRACING_GLOW_OUTLINE);
            }
        }
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
        float partialTicks = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);

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
                    // One that has not changed is handed back as it was; see BlockEntityCache.
                    if (BlockEntityCache.reuse(pos, entry.getValue(), PENDING)) {
                        continue;
                    }
                    BlockEntityRenderState state = minecraft.getBlockEntityRenderDispatcher()
                        .tryExtractRenderState(entry.getValue(), partialTicks, null, false);
                    if (state != null) {
                        // A banner sways all the time, which made every one of them new geometry every frame.
                        // Away from the camera the sway is not worth that: it hangs still and is kept.
                        if (state instanceof net.minecraft.client.renderer.blockentity.state.BannerRenderState banner
                            && pos.distToCenterSqr(camera) > BANNER_SWAY_RANGE * BANNER_SWAY_RANGE) {
                            banner.phase = 0.0f;
                        }
                        states.add(state);
                        CACHE_CANDIDATES.put(pos, entry.getValue());
                    }
                }
            }
        }
        addFarBeacons(minecraft, camera, partialTicks, states);
        return states;
    }

    /** Beacons in loaded chunks, found by a sweep every so often; see addFarBeacons. */
    private static final List<BlockPos> BEACONS = new ArrayList<>();
    private static final int BEACON_SWEEP_FRAMES = 40;
    private static int beaconSweepCountdown;
    private static Object beaconSweepLevel;

    /**
     * A beacon's beam is seen from as far as its chunk is loaded, unlike every other block entity, which ends at
     * {@link #BLOCK_ENTITY_RANGE}: without this the beam went out on walking away from it. Looking through every
     * loaded chunk each frame would cost too much, so the beacons are found by a sweep now and then and only their
     * render states are taken each frame.
     */
    private static void addFarBeacons(Minecraft minecraft, Vec3 camera, float partialTicks,
        List<BlockEntityRenderState> states) {
        if (beaconSweepLevel != minecraft.level) {
            beaconSweepLevel = minecraft.level;
            beaconSweepCountdown = 0;
        }
        if (beaconSweepCountdown-- <= 0) {
            beaconSweepCountdown = BEACON_SWEEP_FRAMES;
            BEACONS.clear();
            int centerX = Mth.floor(camera.x()) >> 4;
            int centerZ = Mth.floor(camera.z()) >> 4;
            int radius = minecraft.options.getEffectiveRenderDistance();
            for (int x = centerX - radius; x <= centerX + radius; x++) {
                for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                    LevelChunk chunk = minecraft.level.getChunkSource().getChunk(x, z, false);
                    if (chunk == null) {
                        continue;
                    }
                    for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                        if (blockEntity instanceof net.minecraft.world.level.block.entity.BeaconBlockEntity) {
                            BEACONS.add(blockEntity.getBlockPos());
                        }
                    }
                }
            }
        }
        if (BEACONS.isEmpty()) {
            return;
        }
        java.util.Set<BlockPos> present = new java.util.HashSet<>();
        for (BlockEntityRenderState state : states) {
            present.add(state.blockPos);
        }
        for (BlockPos pos : BEACONS) {
            if (present.contains(pos) || !(minecraft.level.getBlockEntity(pos)
                instanceof net.minecraft.world.level.block.entity.BeaconBlockEntity beacon)) {
                continue;
            }
            BlockEntityRenderState state = minecraft.getBlockEntityRenderDispatcher()
                .tryExtractRenderState(beacon, partialTicks, null, false);
            if (state != null) {
                states.add(state);
            }
        }
    }

    /**
     * How much of an entity's brightness comes from the entity rather than from the world. Vanilla mobs that light
     * themselves do it by overriding the block light handed to the renderer (GlowSquidRenderer returns 15, and so
     * do the blaze and the magma cube), which a lightmap would pick up and a path tracer cannot. Whatever that
     * light exceeds the block light actually at the entity's feet is taken as the entity's own glow.
     */
    private static float selfLitEmission(Minecraft minecraft, EntityRenderState state) {
        if (minecraft.level == null) {
            return 0.0f;
        }

        // The wither and its skulls are drawn full bright in vanilla (their renderers report block light 15) but give
        // off no light.
        if (state instanceof net.minecraft.client.renderer.entity.state.WitherRenderState
            || state instanceof net.minecraft.client.renderer.entity.state.WitherSkullRenderState) {
            return 0.0f;
        }
        int ownBlockLight = net.minecraft.util.LightCoordsUtil.block(state.lightCoords);
        if (ownBlockLight <= 0) {
            return 0.0f;
        }

        // Vanilla samples the light for an entity at a block this does not always agree on - a mob by a torch can
        // report a level or two more than the block under it - so the brightest of its feet, middle and head is
        // what counts, and only a wide gap on top of that is the entity lighting itself.
        int worldBlockLight = 0;
        for (double offset : SELF_LIT_SAMPLE_HEIGHTS) {
            BlockPos pos = BlockPos.containing(state.x, state.y + state.boundingBoxHeight * offset, state.z);
            worldBlockLight = Math.max(worldBlockLight,
                minecraft.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos));
        }

        int excess = ownBlockLight - worldBlockLight;
        if (excess < SELF_LIT_MIN_EXCESS) {
            return 0.0f;
        }
        // A display entity given a brightness of its own (maps and servers build signs, statues and whole walls from
        // them) is drawn unshaded in vanilla but lights nothing. At a mob's glow a wall of them became a wall of
        // lamps: the room washed out and the exposure with it. A low glow keeps them readable in the dark without
        // turning them into light sources.
        if (state instanceof net.minecraft.client.renderer.entity.state.DisplayEntityRenderState) {
            return Glow.ofLevel(Math.min(excess, DISPLAY_MAX_GLOW_LEVEL));
        }
        return Glow.ofLevel(excess);
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
        DevProfiler.kind(state);
        addPending(blockId(pos) ^ 0x5BD1E995, pos.getX(), pos.getY(), pos.getZ(), RAY_TRACING_WORLD, true);
        DevProfiler.kind(null);
        BlockEntity cached = CACHE_CANDIDATES.get(pos);
        if (cached != null) {
            BlockEntityCache.collected(pos, cached, PENDING.subList(before, PENDING.size()), ARENA.addressOf(0L));
        }
        if (Options.debugLogging && DEBUG_BLOCK_ENTITIES < 20) {
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


    /**
     * The cracks on a block being mined. Minecraft extracts one state per block in progress; each is drawn the way
     * vanilla's breaking pass does it, as the block's own model with the destroy-stage texture projected onto it,
     * placed at the block like any block entity.
     */
    private static void collectBlockBreaking(Minecraft minecraft, LevelRenderState levelRenderState) {
        if (levelRenderState.blockBreakingRenderStates.isEmpty()) {
            return;
        }

        List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> parts = new ArrayList<>();
        net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.createThreadLocalInstance();
        for (net.minecraft.client.renderer.state.level.BlockBreakingRenderState state
            : levelRenderState.blockBreakingRenderStates) {
            if (state.blockState().getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL) {
                continue;
            }

            BlockPos pos = state.blockPos();
            COLLECTOR.reset();
            POSE_STACK.setIdentity();
            POSE_STACK.translate(state.blockState().getOffset(pos));
            net.minecraft.client.renderer.block.dispatch.BlockStateModel model =
                minecraft.getModelManager().getBlockStateModelSet().get(state.blockState());
            random.setSeed(state.blockState().getSeed(pos));
            parts.clear();
            model.collectParts(random, parts);
            COLLECTOR.submitBreakingBlockModel(POSE_STACK, parts, state.progress());
            if (!COLLECTOR.isEmpty()) {
                // Under the particle mask: the cracks sit a hair in front of the block, and as shadow casters they
                // would shade the very face they are drawn on.
                addPending(blockId(pos) ^ BREAKING_ID_SALT, pos.getX(), pos.getY(), pos.getZ(), RAY_TRACING_PARTICLE);
            }
        }
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
                    // Translucent ones (smoke, campfire, dust) are kept by their alpha at random, as solid surfaces: traced as
                    // see-through geometry they looked like glass, the world showing through them clearer than in vanilla.
                    .alphaMode(layer.translucent() ? PBRVertexWriter.ALPHA_MODE_STOCHASTIC : PBRVertexWriter.ALPHA_MODE_CUTOUT)
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

    /** Writers for other mods' geometry (RadianteGeometry), reused from frame to frame. */
    private static final List<PBRVertexWriter> EXTERNAL_WRITERS = new ArrayList<>();

    /**
     * Geometry other mods hand over through {@link com.g2806.radiante.api.RadianteGeometry}: what they would have
     * drawn in level passes that do not run while the world is traced. Each provider becomes one object placed at
     * the camera, a layer per texture and alpha mode, built anew every frame as particles are - but under the world
     * mask, so it casts shadows and shows in reflections.
     */
    private static void collectExternal(LevelRenderState levelRenderState, CameraRenderState cameraState) {
        Vec3 camera = cameraState.pos;
        // Mods that submit as they would to Minecraft's own collector: camera-relative, so the object sits at the
        // camera.
        for (Map.Entry<String, com.g2806.radiante.api.RadianteGeometry.RegisteredSubmitter> entry
            : com.g2806.radiante.api.RadianteGeometry.submitters().entrySet()) {
            COLLECTOR.reset();
            try {
                entry.getValue().submitter().submit(COLLECTOR);
            } catch (RuntimeException e) {
                continue;
            }
            if (!COLLECTOR.isEmpty()) {
                addPending(entry.getKey().hashCode() ^ EXTERNAL_ID_SALT, camera.x(), camera.y(), camera.z(),
                    entry.getValue().castsShadows() ? RAY_TRACING_WORLD : RAY_TRACING_PARTICLE);
            }
        }

        Map<String, com.g2806.radiante.api.RadianteGeometry.RegisteredProvider> providers =
            com.g2806.radiante.api.RadianteGeometry.providers();
        if (providers.isEmpty()) {
            return;
        }
        float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        int[] used = {0};
        for (Map.Entry<String, com.g2806.radiante.api.RadianteGeometry.RegisteredProvider> provider
            : providers.entrySet()) {
            // Texture id and alpha mode, to the writer and the geometry type of its layer.
            Map<Long, PBRVertexWriter> writers = new java.util.LinkedHashMap<>();
            Map<Long, Integer> geometryTypes = new java.util.HashMap<>();
            com.g2806.radiante.api.RadianteGeometry.Sink sink = new com.g2806.radiante.api.RadianteGeometry.Sink() {
                @Override
                public Vec3 camera() {
                    return camera;
                }

                @Override
                public float partialTick() {
                    return partialTick;
                }

                @Override
                public com.mojang.blaze3d.vertex.VertexConsumer quads(com.mojang.blaze3d.textures.GpuTexture texture,
                    com.g2806.radiante.api.RadianteGeometry.Alpha alpha) {
                    return writer(TextureTracker.idOf(texture), alpha);
                }

                @Override
                public com.mojang.blaze3d.vertex.VertexConsumer quads(net.minecraft.resources.Identifier texture,
                    com.g2806.radiante.api.RadianteGeometry.Alpha alpha) {
                    return writer(TextureTracker.idOf(texture), alpha);
                }

                private PBRVertexWriter writer(int textureId, com.g2806.radiante.api.RadianteGeometry.Alpha alpha) {
                    long key = (long) textureId << 2 | alpha.ordinal();
                    PBRVertexWriter writer = writers.get(key);
                    if (writer != null) {
                        return writer;
                    }
                    if (used[0] == EXTERNAL_WRITERS.size()) {
                        EXTERNAL_WRITERS.add(new PBRVertexWriter(1024));
                    }
                    writer = EXTERNAL_WRITERS.get(used[0]++);
                    writer.reset();
                    writer.textureId(textureId)
                        .glintTextureId(0)
                        .alphaMode(switch (alpha) {
                            case OPAQUE -> PBRVertexWriter.ALPHA_MODE_OPAQUE;
                            case CUTOUT -> PBRVertexWriter.ALPHA_MODE_CUTOUT;
                            case TRANSLUCENT -> PBRVertexWriter.ALPHA_MODE_TRANSPARENT;
                            case DITHERED -> PBRVertexWriter.ALPHA_MODE_STOCHASTIC;
                        })
                        .coordinate(NativeGeometry.COORDINATE_WORLD)
                        .albedoEmission(0.0f)
                        .overlayEnabled(false)
                        .computeQuadNormals(true);
                    writers.put(key, writer);
                    // Only what is opaque all over may be solid geometry; see RenderTypeInfo.geometryType.
                    geometryTypes.put(key, alpha == com.g2806.radiante.api.RadianteGeometry.Alpha.OPAQUE
                        ? NativeGeometry.GEOMETRY_TYPE_WORLD_SOLID : NativeGeometry.GEOMETRY_TYPE_WORLD_TRANSPARENT);
                    return writer;
                }
            };
            try {
                provider.getValue().provider().provide(sink);
            } catch (RuntimeException e) {
                // Another mod's mistake costs its own geometry for the frame, not the frame.
                continue;
            }

            List<PendingLayer> layers = new ArrayList<>();
            for (Map.Entry<Long, PBRVertexWriter> entry : writers.entrySet()) {
                PBRVertexWriter writer = entry.getValue();
                writer.finish();
                if (writer.vertexCount() == 0 || writer.vertexCount() % 4 != 0) {
                    continue;
                }
                layers.add(new PendingLayer(geometryTypes.get(entry.getKey()), (int) (entry.getKey() >> 2),
                    writer.vertexCount(), copyVertices(writer), "Entity"));
            }
            if (!layers.isEmpty()) {
                PENDING.add(new PendingEntity(provider.getKey().hashCode() ^ EXTERNAL_ID_SALT, camera.x(), camera.y(),
                    camera.z(), provider.getValue().castsShadows() ? RAY_TRACING_WORLD : RAY_TRACING_PARTICLE, layers));
            }
        }
    }

    private static final int EXTERNAL_ID_SALT = 0x65787467;

    /**
     * Rain and snow: the same sheets vanilla draws, built from the columns Minecraft already extracted. They go in
     * under the weather mask, which camera rays see and shadow rays skip, and use the stochastic alpha mode so a
     * streak is a faint visible surface rather than clear glass.
     */
    /**
     * Blocks vanilla rain falls per tick: its texture scrolls (3 to 4) / 32 of a texture height per tick, and a
     * texture height covers four blocks. Each column picks its own speed in that range; the middle one is used.
     */
    private static final float RAIN_FALL_PER_TICK = 4.0f * 3.5f / 32.0f;
    private static double lastRainTime = Double.NaN;
    private static float rainFallPerFrame;
    /** Dev switch for comparing with and without rain motion vectors. */
    public static boolean rainMotion = true;

    /** How far rain fell since the previous frame, for its motion vectors; see WorldUBO.rainFallPerFrame. */
    public static float rainFallPerFrame(LevelRenderState levelRenderState) {
        double now = levelRenderState.gameTime + Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        double ticks = Double.isNaN(lastRainTime) ? 0.0 : now - lastRainTime;
        lastRainTime = now;
        // A pause or a jump in time is not rain falling.
        rainFallPerFrame = rainMotion && ticks > 0.0 && ticks < 2.0 ? (float) ticks * RAIN_FALL_PER_TICK : 0.0f;
        return rainFallPerFrame;
    }

    private static void collectWeather(LevelRenderState levelRenderState, CameraRenderState cameraState) {
        net.minecraft.client.renderer.state.level.WeatherRenderState weather = levelRenderState.weatherRenderState;
        if (weather.intensity <= 0.0f || (weather.rainColumns.isEmpty() && weather.snowColumns.isEmpty())) {
            return;
        }

        Vec3 camera = cameraState.pos;
        List<PendingLayer> layers = new ArrayList<>();
        addWeatherLayer(layers, weather.rainColumns, RAIN_TEXTURE, camera, 1.0f, weather.radius, weather.intensity,
            true);
        addWeatherLayer(layers, weather.snowColumns, SNOW_TEXTURE, camera, 0.8f, weather.radius, weather.intensity,
            false);
        if (!layers.isEmpty()) {
            PENDING.add(new PendingEntity(WEATHER_ID, camera.x(), camera.y(), camera.z(), RAY_TRACING_WEATHER,
                layers));
        }
    }

    private static void addWeatherLayer(List<PendingLayer> layers,
        List<net.minecraft.client.renderer.WeatherEffectRenderer.ColumnInstance> columns,
        net.minecraft.resources.Identifier texture, Vec3 camera, float maxAlpha, int radius, float intensity,
        boolean rain) {
        if (columns.isEmpty()) {
            return;
        }
        int textureId = TextureTracker.idOf(texture);
        if (textureId == 0) {
            return;
        }

        PBRVertexWriter writer = WEATHER_WRITER.textureId(textureId)
            .glintTextureId(0)
            .alphaMode(PBRVertexWriter.ALPHA_MODE_STOCHASTIC)
            .coordinate(NativeGeometry.COORDINATE_WORLD)
            .albedoEmission(0.0f)
            .overlayEnabled(false)
            .computeQuadNormals(true)
            .rain(rain);
        writer.reset();

        float radiusSq = Math.max(radius * radius, 1);
        int cameraX = Mth.floor(camera.x());
        int cameraZ = Mth.floor(camera.z());
        for (net.minecraft.client.renderer.WeatherEffectRenderer.ColumnInstance column : columns) {
            int indexX = column.x() - cameraX + 16;
            int indexZ = column.z() - cameraZ + 16;
            if (indexX < 0 || indexX >= 32 || indexZ < 0 || indexZ >= 32) {
                continue;
            }
            float relativeX = (float) (column.x() + 0.5 - camera.x());
            float relativeZ = (float) (column.z() + 0.5 - camera.z());
            float distanceSq = relativeX * relativeX + relativeZ * relativeZ;
            float alpha = Mth.lerp(Math.min(distanceSq / radiusSq, 1.0f), maxAlpha, 0.5f) * intensity;
            int color = net.minecraft.util.ARGB.white(alpha);
            float halfX = WEATHER_COLUMN_X[indexZ * 32 + indexX] / 2.0f;
            float halfZ = WEATHER_COLUMN_Z[indexZ * 32 + indexX] / 2.0f;
            float x0 = relativeX - halfX;
            float x1 = relativeX + halfX;
            float z0 = relativeZ - halfZ;
            float z1 = relativeZ + halfZ;
            float y1 = (float) (column.topY() - camera.y());
            float y0 = (float) (column.bottomY() - camera.y());
            float u0 = column.uOffset();
            float u1 = column.uOffset() + 1.0f;
            float v0 = column.bottomY() * 0.25f + column.vOffset();
            float v1 = column.topY() * 0.25f + column.vOffset();
            writer.addVertex(x0, y1, z0).setColor(color).setUv(u0, v0).setLight(column.lightCoords());
            writer.addVertex(x1, y1, z1).setColor(color).setUv(u1, v0).setLight(column.lightCoords());
            writer.addVertex(x1, y0, z1).setColor(color).setUv(u1, v1).setLight(column.lightCoords());
            writer.addVertex(x0, y0, z0).setColor(color).setUv(u0, v1).setLight(column.lightCoords());
        }

        writer.finish();
        if (writer.vertexCount() == 0 || writer.vertexCount() % 4 != 0) {
            return;
        }
        layers.add(new PendingLayer(NativeGeometry.GEOMETRY_TYPE_WORLD_TRANSPARENT, textureId, writer.vertexCount(),
            copyVertices(writer), "Entity"));
    }



    private static boolean cloudsShownLastFrame;
    private static int cloudPauseFrames;

    /** The render pipeline was just rebuilt; see collectClouds. */
    public static void onPipelineRebuilt() {
        BlockEntityCache.invalidate();
        cloudPauseFrames = 3;
        cloudsShownLastFrame = false;
    }

    /** Vanilla's clouds, when the shader pack's cloud mode asks for them rather than its ray marched ones. */
    private static void collectClouds(Minecraft minecraft, LevelRenderState levelRenderState,
        CameraRenderState cameraState) {
        String mode = com.g2806.radiante.client.pipeline.Pipeline.getCloudMode();
        if (mode == null || !mode.endsWith(".vanilla") || net.minecraft.util.ARGB.alpha(levelRenderState.cloudColor) == 0) {
            cloudsShownLastFrame = false;
            return;
        }
        // Clouds that were off for a while come back under a new key: the renderer let go of the old acceleration
        // structure once nothing used it, and handing it the old key again (clouds off, then back to vanilla) made
        // it trace a structure that no longer existed - the device was lost.
        // Nor right after the pipeline was rebuilt: a structure first built in those frames was traced before its
        // vertices had reached the GPU. A few frames without clouds are not noticed.
        if (cloudPauseFrames > 0) {
            cloudPauseFrames--;
            cloudsShownLastFrame = false;
            return;
        }
        if (!cloudsShownLastFrame) {
            CLOUDS.invalidate();
        }
        cloudsShownLastFrame = true;
        net.minecraft.client.renderer.CloudRenderer renderer =
            ((LevelRendererGizmoAccess) minecraft.levelRenderer).radiante$cloudRenderer();
        PBRVertexWriter writer = CLOUDS.update(
            ((com.g2806.radiante.mixin.world.CloudRendererAccessor) renderer).radiante$texture(),
            minecraft.options.getCloudStatus(), levelRenderState.cloudColor, levelRenderState.cloudHeight,
            minecraft.options.cloudRange().get(), cameraState.pos, levelRenderState.gameTime,
            Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false));
        if (writer == null || writer.vertexCount() % 4 != 0) {
            return;
        }
        // The mesh only changes when the camera crosses into another cloud cell; in between it just drifts. So its
        // acceleration structure is kept and only moved, and its vertices are handed over from the mesh itself
        // rather than copied: rebuilding it every frame cost more than everything else on the render thread.
        List<PendingLayer> layers = new ArrayList<>();
        layers.add(new PendingLayer(NativeGeometry.GEOMETRY_TYPE_WORLD_CLOUD, 0, writer.vertexCount(), 0L, "clouds",
            writer.address(), "clouds#" + CLOUDS.version()));
        PENDING.add(new PendingEntity(CLOUD_ID, CLOUDS.originX, CLOUDS.originY, CLOUDS.originZ, RAY_TRACING_CLOUD,
            layers, PREBUILT_BLAS_KEYED));
    }




    /** The held items and arms are submitted separately from the world and follow the camera. */
    /**
     * The player, in first person. Minecraft draws nothing of them then - no model is extracted for the camera
     * entity - so the world around the player had no shadow of them in it and they were missing from every
     * reflection. Their model is submitted here under the mask camera rays skip and shadow rays and later bounces
     * keep, which is how the original Radiance did it. Off with the "First Person Shadow" option.
     */
    private static void collectPlayerShadow(Minecraft minecraft, LevelRenderState levelRenderState,
        CameraRenderState cameraState) {
        if (!Options.firstPersonShadow || minecraft.player == null
            || !minecraft.options.getCameraType().isFirstPerson() || cameraState.entityRenderState.isSleeping
            || minecraft.gameMode == null || minecraft.gameMode.getPlayerMode() == GameType.SPECTATOR) {
            return;
        }

        // 26.2 extracts nothing for the camera entity, so the player's state is extracted here.
        EntityRenderState state = minecraft.getEntityRenderDispatcher().extractEntity(minecraft.player,
            minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        COLLECTOR.reset();
        POSE_STACK.setIdentity();

        try {
            minecraft.getEntityRenderDispatcher().submit(state, cameraState, 0.0, 0.0, 0.0, POSE_STACK, COLLECTOR);
        } catch (RuntimeException e) {
            return;
        }

        if (COLLECTOR.isEmpty()) {
            return;
        }

        addPending(PLAYER_SHADOW_ID, state.x, state.y, state.z, RAY_TRACING_PLAYER);
    }

    private static void collectHands(Minecraft minecraft, LevelRenderState levelRenderState,
        CameraRenderState cameraState) {
        if (minecraft.player == null || minecraft.gameRenderer.gameRenderState().guiRenderState.isHudHidden || !minecraft.options.getCameraType().isFirstPerson()
            || cameraState.entityRenderState.isSleeping
            || minecraft.gameMode == null || minecraft.gameMode.getPlayerMode() == GameType.SPECTATOR) {
            return;
        }

        COLLECTOR.reset();
        POSE_STACK.setIdentity();
        // Minecraft poses the hands in its view space; rotate them into world orientation around the camera.
        POSE_STACK.mulPose(new Matrix4f(cameraState.viewRotationMatrix).invert());
        COLLECTOR.held(true);

        try {
            float partialTicks = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            minecraft.gameRenderer.itemInHandRenderer.submitHandsWithItems(partialTicks, POSE_STACK, COLLECTOR,
                minecraft.player, minecraft.getEntityRenderDispatcher().getPackedLightCoords(minecraft.player, partialTicks));
        } catch (RuntimeException e) {
            return;
        }

        addPending(HAND_ID, cameraState.pos.x(), cameraState.pos.y(), cameraState.pos.z(), RAY_TRACING_HAND);
    }

    /**
     * The id an entity's geometry is matched to the previous frame's by, for its motion vectors. Minecraft makes a
     * new render state every frame, so keyed by the state object nothing ever matched: every moving mob and player
     * was taken for a still one, and the upscaler, finding it elsewhere than the motion vectors said, threw its
     * history away and showed it noisy.
     */
    /**
     * The id of whatever stands at a block. {@code BlockPos.hashCode} will not do: it gives two blocks 31 apart
     * along x and one apart in height the same number, and the renderer keeps what it has built by id - two signs
     * sharing one took each other's place every frame and were both built again every frame, by the hundred
     * around a server spawn.
     */
    private static int blockId(BlockPos pos) {
        long mixed = pos.asLong() * 0x9E3779B97F4A7C15L;
        return (int) (mixed ^ mixed >>> 32);
    }

    private static int historyId(EntityRenderState state) {
        int entityId = ((EntityIdHolder) state).radiante$entityId();
        return entityId == Integer.MIN_VALUE ? System.identityHashCode(state) : entityId * 0x9E3779B1 ^ 0x7F4A7C15;
    }

    /** The text instance of a block entity (see addPending), as against a name tag, which is never kept. */
    private static boolean isBlockText(PendingEntity entity) {
        return entity.rayTracingFlag() == RAY_TRACING_PARTICLE && entity.prebuiltBlas() != PREBUILT_BLAS_NONE
            && !entity.layers().isEmpty() && entity.layers().get(0).name().startsWith("text");
    }

    private static void addPending(int id, double x, double y, double z, int rayTracingFlag) {
        addPending(id, x, y, z, rayTracingFlag, false);
    }

    private static void addPending(int id, double x, double y, double z, int rayTracingFlag, boolean cacheable) {
        // An entity whose origin is not a real point in the world - a block-attached one that lost its support
        // block reports exactly that - would place its whole model outside anything the tracer can build.
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return;
        }

        List<PendingLayer> layers = new ArrayList<>();
        List<PendingLayer> nameTagLayers = new ArrayList<>();
        int profiledVertices = 0;
        for (Map.Entry<RenderType, PBRVertexWriter> entry : COLLECTOR.layers().entrySet()) {
            PBRVertexWriter writer = entry.getValue();
            writer.finish();
            if (writer.vertexCount() == 0 || writer.vertexCount() % 4 != 0) {
                RenderTypeInfo dropped = RenderTypeInfo.of(entry.getKey());
                if (Options.debugLogging && DEBUG_TEXT_LAYERS < 5 && dropped.needsComputedNormals()) {
                    DEBUG_TEXT_LAYERS++;
                    RadianteRenderer.LOGGER.info("layer dropped: group={} vertices={}", dropped.groupName(),
                        writer.vertexCount());
                }
                continue;
            }

            RenderTypeInfo info = RenderTypeInfo.of(entry.getKey());
            if (Options.debugLogging && DEBUG_TEXT_LAYERS < 5 && info.needsComputedNormals()) {
                DEBUG_TEXT_LAYERS++;
                RadianteRenderer.LOGGER.info("layer accepted: group={} geometry={} textureId={} vertices={}",
                    info.groupName(), info.geometryType(), info.textureId(), writer.vertexCount());
            }
            PendingLayer layer = new PendingLayer(info.geometryType(), info.textureId(), writer.vertexCount(),
                copyVertices(writer), info.groupName());
            profiledVertices += writer.vertexCount();
            // Text casts no shadow, as in vanilla: the letters of a sign stand a hair off its board, and their
            // shadow on it read as a second, darker line of text behind the first.
            boolean overlay = COLLECTOR.isNameTagLayer(entry.getKey()) || info.groupName().startsWith("text");
            (overlay ? nameTagLayers : layers).add(layer);
        }
        DevProfiler.count(layers.size() + nameTagLayers.size(), profiledVertices);

        if (!layers.isEmpty()) {
            PENDING.add(new PendingEntity(id, x, y, z, rayTracingFlag, layers, cacheable));
        }
        // Name tags go in as their own instance under the particle mask: seen by camera rays, skipped by shadow
        // rays, so neither the letters nor the plate behind them cast a shadow on the world.
        if (!nameTagLayers.isEmpty() && rayTracingFlag != RAY_TRACING_GLOW_OUTLINE) {
            PENDING.add(new PendingEntity(id ^ NAME_TAG_ID_SALT, x, y, z, RAY_TRACING_PARTICLE, nameTagLayers,
                cacheable));
        }
    }

    /**
     * The collector reuses its buffers between entities, so each layer keeps its own copy until the native renderer
     * has consumed it. Those copies used to be one allocation each, which in a busy world meant hundreds of
     * malloc and free calls every frame; they all live for exactly one frame, so one arena serves them all.
     * An offset is returned rather than an address because growing the arena moves it.
     */
    static long copyVertices(PBRVertexWriter writer) {
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
    /** Block entities collected this frame from the chunks around the camera, by position; see BlockEntityCache. */
    private static final Map<BlockPos, BlockEntity> CACHE_CANDIDATES = new java.util.HashMap<>();

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

    /** One native copy of the latest content name; the clouds' name changes with every rebuild of their mesh. */
    private static String contentName;
    private static java.nio.ByteBuffer contentNameBuffer;

    private static long contentNameAddress(String name) {
        if (name.startsWith(BlockEntityCache.NAME_PREFIX)) {
            return BlockEntityCache.nameAddress(name);
        }
        if (!name.equals(contentName)) {
            if (contentNameBuffer != null) {
                MemoryUtil.memFree(contentNameBuffer);
            }
            contentNameBuffer = MemoryUtil.memUTF8(name, true);
            contentName = name;
        }
        return MemoryUtil.memAddress(contentNameBuffer);
    }

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
                MemoryUtil.memPutInt(prebuiltBlas + (long) i * Integer.BYTES, entity.prebuiltBlas());

                for (PendingLayer layer : entity.layers()) {
                    MemoryUtil.memPutInt(geometryTypes + (long) layerIndex * Integer.BYTES, layer.geometryType());
                    MemoryUtil.memPutAddress(geometryGroupNames + (long) layerIndex * Long.BYTES,
                        groupNameAddress(layer.name()));
                    MemoryUtil.memPutAddress(geometryContentNames + (long) layerIndex * Long.BYTES,
                        layer.contentName() == null ? 0L : contentNameAddress(layer.contentName()));
                    MemoryUtil.memPutInt(geometryTextures + (long) layerIndex * Integer.BYTES, layer.textureId());
                    MemoryUtil.memPutInt(vertexFormats + (long) layerIndex * Integer.BYTES,
                        NativeGeometry.VERTEX_FORMAT_PBR);
                    MemoryUtil.memPutInt(indexFormats + (long) layerIndex * Integer.BYTES,
                        NativeGeometry.DRAW_MODE_QUADS);
                    MemoryUtil.memPutInt(vertexCounts + (long) layerIndex * Integer.BYTES, layer.vertexCount());
                    MemoryUtil.memPutAddress(vertices + (long) layerIndex * Long.BYTES,
                        layer.directAddress() != 0L ? layer.directAddress() : ARENA.addressOf(layer.vertices()));
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
