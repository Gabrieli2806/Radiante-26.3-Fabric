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
import net.minecraft.world.level.GameType;
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

        for (EntityRenderState state : levelRenderState.entityRenderStates) {
            collect(minecraft, cameraState, state);
        }

        for (BlockEntityRenderState state : levelRenderState.blockEntityRenderStates) {
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

    private static void collectBlockEntity(Minecraft minecraft, CameraRenderState cameraState,
        BlockEntityRenderState state) {
        COLLECTOR.reset();
        POSE_STACK.setIdentity();

        try {
            minecraft.getBlockEntityRenderDispatcher().submit(state, POSE_STACK, COLLECTOR, cameraState);
        } catch (RuntimeException e) {
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
                // Cut out particles must not be traced as solid geometry; see RenderTypeInfo.geometryType.
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
        List<PendingLayer> layers = new ArrayList<>();
        for (Map.Entry<RenderType, PBRVertexWriter> entry : COLLECTOR.layers().entrySet()) {
            PBRVertexWriter writer = entry.getValue();
            writer.finish();
            if (writer.vertexCount() == 0 || writer.vertexCount() % 4 != 0) {
                continue;
            }

            RenderTypeInfo info = RenderTypeInfo.of(entry.getKey());
            layers.add(new PendingLayer(info.geometryType(), info.textureId(), writer.vertexCount(),
                copyVertices(writer), info.groupName()));
        }

        if (!layers.isEmpty()) {
            PENDING.add(new PendingEntity(id, x, y, z, rayTracingFlag, layers));
        }
    }

    /**
     * The collector reuses its buffers between entities, so each layer keeps its own copy until the native
     * renderer has consumed it.
     */
    private static long copyVertices(PBRVertexWriter writer) {
        long size = (long) writer.vertexCount() * PBRVertexWriter.STRIDE;
        long copy = MemoryUtil.nmemAllocChecked(size);
        MemoryUtil.memCopy(writer.address(), copy, size);
        return copy;
    }

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

        long hashCodes = MemoryUtil.nmemCalloc(entityCount, Integer.BYTES);
        long posXs = MemoryUtil.nmemCalloc(entityCount, Double.BYTES);
        long posYs = MemoryUtil.nmemCalloc(entityCount, Double.BYTES);
        long posZs = MemoryUtil.nmemCalloc(entityCount, Double.BYTES);
        long rayTracingFlags = MemoryUtil.nmemCalloc(entityCount, Integer.BYTES);
        long postRenderFlags = MemoryUtil.nmemCalloc(entityCount, Integer.BYTES);
        long prebuiltBlas = MemoryUtil.nmemCalloc(entityCount, Integer.BYTES);
        long posts = MemoryUtil.nmemCalloc(entityCount, Integer.BYTES);
        long layerCounts = MemoryUtil.nmemCalloc(entityCount, Integer.BYTES);
        long geometryTypes = MemoryUtil.nmemCalloc(layerCount, Integer.BYTES);
        long geometryGroupNames = MemoryUtil.nmemCalloc(layerCount, Long.BYTES);
        long geometryContentNames = MemoryUtil.nmemCalloc(layerCount, Long.BYTES);
        long geometryTextures = MemoryUtil.nmemCalloc(layerCount, Integer.BYTES);
        long vertexFormats = MemoryUtil.nmemCalloc(layerCount, Integer.BYTES);
        long indexFormats = MemoryUtil.nmemCalloc(layerCount, Integer.BYTES);
        long vertexCounts = MemoryUtil.nmemCalloc(layerCount, Integer.BYTES);
        long vertices = MemoryUtil.nmemCalloc(layerCount, Long.BYTES);
        long[] names = new long[layerCount];
        long[] vertexBuffers = new long[layerCount];

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
                    names[layerIndex] = MemoryUtil.memAddress(MemoryUtil.memUTF8(layer.name(), true));
                    vertexBuffers[layerIndex] = layer.vertices();
                    MemoryUtil.memPutInt(geometryTypes + (long) layerIndex * Integer.BYTES, layer.geometryType());
                    MemoryUtil.memPutAddress(geometryGroupNames + (long) layerIndex * Long.BYTES, names[layerIndex]);
                    MemoryUtil.memPutAddress(geometryContentNames + (long) layerIndex * Long.BYTES, 0L);
                    MemoryUtil.memPutInt(geometryTextures + (long) layerIndex * Integer.BYTES, layer.textureId());
                    MemoryUtil.memPutInt(vertexFormats + (long) layerIndex * Integer.BYTES,
                        NativeGeometry.VERTEX_FORMAT_PBR);
                    MemoryUtil.memPutInt(indexFormats + (long) layerIndex * Integer.BYTES,
                        NativeGeometry.DRAW_MODE_QUADS);
                    MemoryUtil.memPutInt(vertexCounts + (long) layerIndex * Integer.BYTES, layer.vertexCount());
                    MemoryUtil.memPutAddress(vertices + (long) layerIndex * Long.BYTES, layer.vertices());
                    layerIndex++;
                }
            }

            EntityProxy.queue(0.0125f, coordinate, false, entityCount, hashCodes, posXs,
                posYs, posZs, rayTracingFlags, postRenderFlags, prebuiltBlas, posts, layerCounts, geometryTypes,
                geometryGroupNames, geometryContentNames, geometryTextures, vertexFormats, indexFormats,
                vertexCounts, vertices);
        } finally {
            MemoryUtil.nmemFree(hashCodes);
            MemoryUtil.nmemFree(posXs);
            MemoryUtil.nmemFree(posYs);
            MemoryUtil.nmemFree(posZs);
            MemoryUtil.nmemFree(rayTracingFlags);
            MemoryUtil.nmemFree(postRenderFlags);
            MemoryUtil.nmemFree(prebuiltBlas);
            MemoryUtil.nmemFree(posts);
            MemoryUtil.nmemFree(layerCounts);
            MemoryUtil.nmemFree(geometryTypes);
            MemoryUtil.nmemFree(geometryGroupNames);
            MemoryUtil.nmemFree(geometryContentNames);
            MemoryUtil.nmemFree(geometryTextures);
            MemoryUtil.nmemFree(vertexFormats);
            MemoryUtil.nmemFree(indexFormats);
            MemoryUtil.nmemFree(vertexCounts);
            MemoryUtil.nmemFree(vertices);
            for (long name : names) {
                if (name != 0L) {
                    MemoryUtil.nmemFree(name);
                }
            }
            for (long buffer : vertexBuffers) {
                if (buffer != 0L) {
                    MemoryUtil.nmemFree(buffer);
                }
            }
            PENDING.clear();
        }
    }
}
