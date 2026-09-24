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
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.SimpleGizmoCollector;
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

    private static final EntityCollector COLLECTOR =
        com.g2806.radiante.platform.RadiantePlatform.INSTANCE.createEntityCollector();
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
    /**
     * How brightly an entity that lights itself glows at full strength. Vanilla says "this mob is lit" by handing
     * the renderer a block light of its own instead of the one at its position - a glow squid at 15, a blaze, a
     * magma cube - and the path tracer has no lightmap to read that from, so it becomes emission scaled by how far
     * the entity's own light exceeds the light actually around it.
     */
    private static final float SELF_LIT_EMISSION = 2.5f;
    /** Below this the difference is the lightmap disagreeing with the block below the entity, not a glow. */
    private static final int SELF_LIT_MIN_EXCESS = 5;
    private static final double[] SELF_LIT_SAMPLE_HEIGHTS = {0.0, 0.5, 1.0};
    private static int DEBUG_TEXT_LAYERS;
    private static final int GIZMO_ID = "radiante:gizmo".hashCode();
    private static final PBRVertexWriter GIZMO_WRITER = new PBRVertexWriter(2048);
    /**
     * How bright a debug gizmo line reads no matter the light actually there - it has to stand out in the dark,
     * in direct sun and inside solid blocks alike, the way vanilla's own unlit line rendering does.
     */
    private static final float GIZMO_EMISSION = 2.0f;
    /**
     * A gizmo line's width is a vanilla screen pixel count (1 for most, 4 for chunk grid lines); reprojecting
     * that every frame is not worth it for a debug overlay, so it becomes a small, fixed world thickness instead.
     */
    private static final float GIZMO_LINE_WIDTH_SCALE = 0.015f;
    private static final float GIZMO_LINE_MIN_THICKNESS = 0.015f;
    private static final int OUTLINE_ID = "radiante:block_outline".hashCode();
    private static final PBRVertexWriter OUTLINE_WRITER = new PBRVertexWriter(256);
    /**
     * Outline width as a share of the screen's height. Rays are traced at the upscaler's lower render resolution,
     * so a line only a couple of output pixels wide falls between rays and breaks up into dots.
     */
    private static final double OUTLINE_SCREEN_FRACTION = 3.0 / 720.0;

    /** Masks the ray tracing shaders select geometry with. */
    private static final int RAY_TRACING_WORLD = 0b00000001;
    /** Geometry camera rays skip in first person, while shadow rays and later bounces still see it. */
    private static final int RAY_TRACING_PLAYER = 0b00000010;
    private static final int RAY_TRACING_HAND = 0b00001000;
    /** Seen only by the glowing effect's outline rays (GLOW_OUTLINE_MASK in the shaders). */
    private static final int RAY_TRACING_GLOW_OUTLINE = 0b00000100;
    private static final int GLOW_OUTLINE_ID_SALT = 0x676C6F77;
    private static final int RAY_TRACING_PARTICLE = 0b00100000;
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

        collectPlayerShadow(minecraft, levelRenderState, cameraState);
        collectBlockBreaking(minecraft, levelRenderState);
        collectParticles(levelRenderState, cameraState);
        collectWeather(levelRenderState, cameraState);
        collectDebugGizmos(minecraft, cameraState);
        collectBlockOutline(levelRenderState, cameraState);
        collectClouds(minecraft, levelRenderState, cameraState);
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
        float emission = 0.0f;
        if (state instanceof net.minecraft.client.renderer.entity.state.ItemFrameRenderState frame
            && frame.isGlowFrame) {
            emission = GLOW_FRAME_EMISSION;
        } else if (state instanceof net.minecraft.client.renderer.entity.state.EndCrystalRenderState) {
            // An end crystal is a light source in its own right, and its texture is what gives it its colour, so
            // the glow is kept modest: multiplied by a bright texture, a large value burns the whole thing white.
            emission = END_CRYSTAL_EMISSION;
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

        addPending(System.identityHashCode(state), state.x, state.y, state.z, RAY_TRACING_WORLD);

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
                addPending(System.identityHashCode(state) ^ GLOW_OUTLINE_ID_SALT, state.x, state.y, state.z,
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
        return excess < SELF_LIT_MIN_EXCESS ? 0.0f : SELF_LIT_EMISSION * excess / 15.0f;
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

    /** One line per kind of block entity the renderer receives, so a missing one can be told from a broken one. */
    private static void debugBlockEntityKind(BlockEntityRenderState state) {
        if (!Options.debugLogging || !DEBUG_BLOCK_ENTITY_KINDS.add(state.getClass().getSimpleName())) {
            return;
        }
        RadianteRenderer.LOGGER.info("block entity kind reaching the renderer: {} at {}",
            state.getClass().getSimpleName(), state.blockPos);
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
            COLLECTOR.submitBreakingBlockModel(POSE_STACK, parts, state.progress(), model.hasMaterialFlag(1));
            if (!COLLECTOR.isEmpty()) {
                // Under the particle mask: the cracks sit a hair in front of the block, and as shadow casters they
                // would shade the very face they are drawn on.
                addPending(pos.hashCode() ^ BREAKING_ID_SALT, pos.getX(), pos.getY(), pos.getZ(), RAY_TRACING_PARTICLE);
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

    /**
     * Rain and snow: the same sheets vanilla draws, built from the columns Minecraft already extracted. They go in
     * under the weather mask, which camera rays see and shadow rays skip, and use the stochastic alpha mode so a
     * streak is a faint visible surface rather than clear glass.
     */
    private static void collectWeather(LevelRenderState levelRenderState, CameraRenderState cameraState) {
        net.minecraft.client.renderer.state.level.WeatherRenderState weather = levelRenderState.weatherRenderState;
        if (weather.intensity <= 0.0f || (weather.rainColumns.isEmpty() && weather.snowColumns.isEmpty())) {
            return;
        }

        Vec3 camera = cameraState.pos;
        List<PendingLayer> layers = new ArrayList<>();
        addWeatherLayer(layers, weather.rainColumns, RAIN_TEXTURE, camera, 1.0f, weather.radius, weather.intensity);
        addWeatherLayer(layers, weather.snowColumns, SNOW_TEXTURE, camera, 0.8f, weather.radius, weather.intensity);
        if (!layers.isEmpty()) {
            PENDING.add(new PendingEntity(WEATHER_ID, camera.x(), camera.y(), camera.z(), RAY_TRACING_WEATHER,
                layers));
        }
    }

    private static void addWeatherLayer(List<PendingLayer> layers,
        List<net.minecraft.client.renderer.WeatherEffectRenderer.ColumnInstance> columns,
        net.minecraft.resources.Identifier texture, Vec3 camera, float maxAlpha, int radius, float intensity) {
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
            .computeQuadNormals(true);
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

    /**
     * F3+B entity hitboxes, F3+G chunk borders, and anything else that calls {@code Gizmos.line}/{@code .cuboid}/
     * etc. Vanilla only drains the collector these land in from inside {@code LevelRenderer.render}, which the
     * ray tracer replaces entirely, so nothing ever emptied it and the overlays those shortcuts are meant to
     * toggle never appeared. Drained here instead and turned into thin, camera-facing, unlit quads under the
     * particle mask, so they show up without casting a shadow or feeding indirect light back into the scene.
     * Quads, triangle fans and text gizmos (used by other, rarer debug views) are not handled yet - only lines,
     * which is everything both F3+B and F3+G actually draw.
     */
    private static void collectDebugGizmos(Minecraft minecraft, CameraRenderState cameraState) {
        SimpleGizmoCollector collector =
            ((LevelRendererGizmoAccess) minecraft.levelRenderer).radiante$renderThreadGizmos();
        List<SimpleGizmoCollector.GizmoInstance> instances = collector.drainGizmos();
        if (instances.isEmpty()) {
            return;
        }

        DrawableGizmoPrimitives primitives = new DrawableGizmoPrimitives();
        long currentMillis = net.minecraft.util.Util.getMillis();
        for (SimpleGizmoCollector.GizmoInstance instance : instances) {
            instance.gizmo().emit(primitives, instance.getAlphaMultiplier(currentMillis));
        }
        if (primitives.isEmpty()) {
            return;
        }

        COLLECTOR.reset();
        // onTop is vanilla's "ignore depth, always show through walls"; nothing here does that yet, both groups
        // are traced as ordinary depth-correct geometry.
        primitives.submit(COLLECTOR, cameraState, false);

        Vec3 camera = cameraState.pos;
        List<PendingLayer> layers = new ArrayList<>();
        PBRVertexWriter writer = GIZMO_WRITER.textureId(0)
            .glintTextureId(0)
            .glintEnabled(false)
            .alphaMode(PBRVertexWriter.ALPHA_MODE_TRANSPARENT)
            .coordinate(NativeGeometry.COORDINATE_WORLD)
            .albedoEmission(GIZMO_EMISSION)
            .overlayEnabled(false)
            .computeQuadNormals(true);
        writer.reset();
        for (DrawableGizmoPrimitives.Group group : COLLECTOR.drainGizmoGroups()) {
            for (DrawableGizmoPrimitives.Line line : group.lines()) {
                addGizmoLineQuad(writer, line, camera);
            }
        }
        writer.finish();
        if (writer.vertexCount() > 0 && writer.vertexCount() % 4 == 0) {
            layers.add(new PendingLayer(NativeGeometry.GEOMETRY_TYPE_WORLD_TRANSPARENT, 0, writer.vertexCount(),
                copyVertices(writer), "Entity"));
        }

        if (!layers.isEmpty()) {
            PENDING.add(new PendingEntity(GIZMO_ID, camera.x(), camera.y(), camera.z(), RAY_TRACING_PARTICLE,
                layers));
        }
    }

    /**
     * The outline around the block under the crosshair. Minecraft submits it from inside {@code LevelRenderer.render},
     * which the ray tracer replaces, so it never appeared. Each edge of the block's outline shape becomes a thin
     * camera-facing quad of constant on-screen width, pushed a hair off the block so the faces it borders cannot
     * hide it, and traced under the particle mask so it casts no shadow.
     */
    private static void collectBlockOutline(LevelRenderState levelRenderState, CameraRenderState cameraState) {
        net.minecraft.client.renderer.state.level.BlockOutlineRenderState state =
            levelRenderState.blockOutlineRenderState;
        if (state == null || state.shape().isEmpty()) {
            return;
        }

        Vec3 camera = cameraState.pos;
        BlockPos pos = state.pos();
        // Vanilla: translucent black, or the high contrast option's colour.
        int color = state.highContrast() ? 0xFF5FFFE1 : 0xFF000000;
        // Blocks per block of distance for the screen fraction above; m11 is 1 / tan(half the vertical fov).
        double widthPerDistance = 2.0 / cameraState.projectionMatrix.m11() * OUTLINE_SCREEN_FRACTION;
        net.minecraft.world.phys.AABB bounds = state.shape().bounds();
        double centerX = pos.getX() + (bounds.minX + bounds.maxX) * 0.5;
        double centerY = pos.getY() + (bounds.minY + bounds.maxY) * 0.5;
        double centerZ = pos.getZ() + (bounds.minZ + bounds.maxZ) * 0.5;

        PBRVertexWriter writer = OUTLINE_WRITER.textureId(0)
            .glintTextureId(0)
            .glintEnabled(false)
            .alphaMode(PBRVertexWriter.ALPHA_MODE_OPAQUE)
            .coordinate(NativeGeometry.COORDINATE_WORLD)
            .albedoEmission(state.highContrast() ? GIZMO_EMISSION : 0.0f)
            .overlayEnabled(false)
            .computeQuadNormals(true);
        writer.reset();
        state.shape().forAllEdges((x1, y1, z1, x2, y2, z2) -> {
            Vec3 start = new Vec3(pos.getX() + x1, pos.getY() + y1, pos.getZ() + z1);
            Vec3 end = new Vec3(pos.getX() + x2, pos.getY() + y2, pos.getZ() + z2);
            double distance = Math.max(0.05, camera.distanceTo(start.add(end).scale(0.5)));
            double halfWidth = distance * widthPerDistance * 0.5;
            start = pushOut(start, centerX, centerY, centerZ, halfWidth);
            end = pushOut(end, centerX, centerY, centerZ, halfWidth);
            addLineQuad(writer, start, end, color, halfWidth, camera);
        });
        writer.finish();
        if (writer.vertexCount() == 0 || writer.vertexCount() % 4 != 0) {
            return;
        }

        List<PendingLayer> layers = new ArrayList<>();
        layers.add(new PendingLayer(NativeGeometry.GEOMETRY_TYPE_WORLD_SOLID, 0, writer.vertexCount(),
            copyVertices(writer), "Entity"));
        PENDING.add(new PendingEntity(OUTLINE_ID, camera.x(), camera.y(), camera.z(), RAY_TRACING_PARTICLE, layers));
    }

    /** Vanilla's clouds, when the shader pack's cloud mode asks for them rather than its ray marched ones. */
    private static void collectClouds(Minecraft minecraft, LevelRenderState levelRenderState,
        CameraRenderState cameraState) {
        String mode = com.g2806.radiante.client.pipeline.Pipeline.getCloudMode();
        if (mode == null || !mode.endsWith(".vanilla") || net.minecraft.util.ARGB.alpha(levelRenderState.cloudColor) == 0) {
            return;
        }
        net.minecraft.client.renderer.CloudRenderer renderer =
            ((LevelRendererGizmoAccess) minecraft.levelRenderer).radiante$cloudRenderer();
        PBRVertexWriter writer = CLOUDS.update(
            ((com.g2806.radiante.mixin.world.CloudRendererAccessor) renderer).radiante$texture(),
            minecraft.options.getCloudStatus(), levelRenderState.cloudColor, levelRenderState.cloudHeight,
            minecraft.options.cloudRange().get(), cameraState.pos, levelRenderState.gameTime,
            levelRenderState.worldPartialTicks);
        if (writer == null || writer.vertexCount() % 4 != 0) {
            return;
        }
        List<PendingLayer> layers = new ArrayList<>();
        layers.add(new PendingLayer(NativeGeometry.GEOMETRY_TYPE_WORLD_CLOUD, 0, writer.vertexCount(),
            copyVertices(writer), "clouds"));
        PENDING.add(new PendingEntity(CLOUD_ID, CLOUDS.originX, CLOUDS.originY, CLOUDS.originZ, RAY_TRACING_CLOUD,
            layers));
    }

    /** Moves an outline corner away from the shape's centre, each axis on its own, so it sits just outside a face. */
    private static Vec3 pushOut(Vec3 point, double centerX, double centerY, double centerZ, double amount) {
        return new Vec3(point.x + Math.signum(point.x - centerX) * amount,
            point.y + Math.signum(point.y - centerY) * amount,
            point.z + Math.signum(point.z - centerZ) * amount);
    }

    /** One gizmo line as a thin camera-facing quad, the way vanilla's own line rasteriser fakes width too. */
    private static void addGizmoLineQuad(PBRVertexWriter writer, DrawableGizmoPrimitives.Line line, Vec3 camera) {
        double halfWidth = Math.max(GIZMO_LINE_MIN_THICKNESS, line.width() * GIZMO_LINE_WIDTH_SCALE) * 0.5;
        addLineQuad(writer, line.start(), line.end(), line.color(), halfWidth, camera);
    }

    private static void addLineQuad(PBRVertexWriter writer, Vec3 start, Vec3 end, int color, double halfWidth,
        Vec3 camera) {
        Vec3 dir = end.subtract(start);
        double length = dir.length();
        if (!(length > 1.0e-5)) {
            return;
        }
        dir = dir.scale(1.0 / length);

        Vec3 mid = start.add(end).scale(0.5);
        Vec3 side = dir.cross(camera.subtract(mid));
        double sideLength = side.length();
        if (!(sideLength > 1.0e-5)) {
            // The line points straight at the camera; any perpendicular keeps it visible instead of vanishing.
            side = dir.cross(new Vec3(0.0, 1.0, 0.0));
            sideLength = side.length();
            if (!(sideLength > 1.0e-5)) {
                side = new Vec3(1.0, 0.0, 0.0);
                sideLength = 1.0;
            }
        }
        side = side.scale(halfWidth / sideLength);

        float sx = (float) side.x;
        float sy = (float) side.y;
        float sz = (float) side.z;
        float ax = (float) (start.x - camera.x());
        float ay = (float) (start.y - camera.y());
        float az = (float) (start.z - camera.z());
        float bx = (float) (end.x - camera.x());
        float by = (float) (end.y - camera.y());
        float bz = (float) (end.z - camera.z());
        writer.addVertex(ax - sx, ay - sy, az - sz).setColor(color);
        writer.addVertex(ax + sx, ay + sy, az + sz).setColor(color);
        writer.addVertex(bx + sx, by + sy, bz + sz).setColor(color);
        writer.addVertex(bx - sx, by - sy, bz - sz).setColor(color);
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
        PlayerRenderState playerState = levelRenderState.playerRenderState;
        if (!Options.firstPersonShadow || !playerState.hasPlayer || playerState.avatarRenderState == null
            || !minecraft.options.getCameraType().isFirstPerson() || cameraState.entityRenderState.isSleeping
            || minecraft.gameMode == null || minecraft.gameMode.getPlayerMode() == GameType.SPECTATOR) {
            return;
        }

        EntityRenderState state = playerState.avatarRenderState;
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
        List<PendingLayer> nameTagLayers = new ArrayList<>();
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
            (COLLECTOR.isNameTagLayer(entry.getKey()) ? nameTagLayers : layers).add(layer);
        }

        if (!layers.isEmpty()) {
            PENDING.add(new PendingEntity(id, x, y, z, rayTracingFlag, layers));
        }
        // Name tags go in as their own instance under the particle mask: seen by camera rays, skipped by shadow
        // rays, so neither the letters nor the plate behind them cast a shadow on the world.
        if (!nameTagLayers.isEmpty() && rayTracingFlag != RAY_TRACING_GLOW_OUTLINE) {
            PENDING.add(new PendingEntity(id ^ NAME_TAG_ID_SALT, x, y, z, RAY_TRACING_PARTICLE, nameTagLayers));
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
