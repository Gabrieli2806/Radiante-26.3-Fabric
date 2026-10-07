package com.g2806.radiante.client.compat.physicsmod;

import com.g2806.radiante.api.RadianteGeometry;
import com.g2806.radiante.client.RadianteClient;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import it.unimi.dsi.fastutil.ints.IntList;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Physics Mod (net.diebuddies) draws its debris and ragdolls from the end of Minecraft's terrain passes, which do
 * not run while the world is traced, so nothing of it showed. Worse, the same place is where it turns broken blocks
 * into debris at all. This does both of those jobs in its stead: it has the mod spawn what the frame's block updates
 * call for, then reads each body's mesh and where the simulation has it, and hands that to the ray tracer.
 *
 * <p>The mod is closed source and may change between versions, so everything is looked up by name at run time; if
 * anything is not as expected the bridge switches itself off with one line in the log and the game goes on.
 *
 * <p>Covered: block debris, mob ragdolls and blockified mobs, thrown item fragments - everything that is a rigid
 * body. Not covered: its smoke, liquids, ocean, snow and cloth, which are drawn by shaders of its own.
 */
public final class PhysicsModCompat implements RadianteGeometry.Provider {

    private static final String NAME = "radiante:physicsmod";

    private PhysicsModCompat() {
    }

    /** Registers the bridge when Physics Mod is installed. */
    public static void init() {
        try {
            Class.forName("net.diebuddies.physics.PhysicsMod", false, PhysicsModCompat.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError notInstalled) {
            return;
        }
        RadianteGeometry.register(NAME, new PhysicsModCompat());
        RadianteClient.LOGGER.info("Physics Mod found: its debris and ragdolls are traced by Radiante");
    }

    // ---- what is looked up in Physics Mod, once ----

    private boolean resolved;
    private Method getInstanceNullable;
    private Method getPhysicsWorld;
    private Method updateLastSeen;
    private Method getBodies;
    private Method getOffset;
    private Method getRenderPercent;
    private Method getQueueForModelCreation;
    private Method getEntity;
    private Method getDespawnScale;
    private Method getRed;
    private Method getGreen;
    private Method getBlue;
    private Method getMainRenderer;
    private Field physicsUpdater;
    private Method updatePhysics;
    private Field position;
    private Field oldPosition;
    private Field rotation;
    private Field oldRotation;
    private Field scale;
    private Field models;
    private Field dead;
    private Field modelTexture;
    private Field modelTextureMatrix;
    private Field modelTextureView;
    private Field modelMesh;
    private Field modelTranslucent;
    private Field meshPositions;
    private Field meshUvs;
    private Field meshColors;
    private Field meshIndices;

    private void resolve() throws ReflectiveOperationException {
        ClassLoader loader = PhysicsModCompat.class.getClassLoader();
        Class<?> mod = Class.forName("net.diebuddies.physics.PhysicsMod", true, loader);
        Class<?> world = Class.forName("net.diebuddies.physics.PhysicsWorld", true, loader);
        Class<?> body = Class.forName("net.diebuddies.physics.IRigidBody", true, loader);
        Class<?> renderable = Class.forName("net.diebuddies.physics.PhysicsRenderable", true, loader);
        Class<?> model = Class.forName("net.diebuddies.physics.Model", true, loader);
        Class<?> mesh = Class.forName("net.diebuddies.physics.Mesh", true, loader);
        Class<?> accessor = Class.forName("net.diebuddies.minecraft.LevelRendererAccessor", true, loader);
        Class<?> mainRenderer = Class.forName("net.diebuddies.render.MainRenderer", true, loader);
        Class<?> updater = Class.forName("net.diebuddies.render.PhysicsUpdater", true, loader);

        this.getInstanceNullable = mod.getMethod("getInstanceNullable", ClientLevel.class);
        this.getPhysicsWorld = mod.getMethod("getPhysicsWorld");
        this.updateLastSeen = world.getMethod("updateLastSeen");
        this.getBodies = world.getMethod("getBodies");
        this.getOffset = world.getMethod("getOffset");
        this.getRenderPercent = world.getMethod("getRenderPercent");
        this.getQueueForModelCreation = world.getMethod("getQueueForModelCreation");
        this.getEntity = body.getMethod("getEntity");
        this.getDespawnScale = renderable.getMethod("getDespawnScale", Level.class);
        this.getRed = renderable.getMethod("getRed");
        this.getGreen = renderable.getMethod("getGreen");
        this.getBlue = renderable.getMethod("getBlue");
        this.getMainRenderer = accessor.getMethod("physicsmod$getMainRenderer");
        this.physicsUpdater = accessible(mainRenderer.getDeclaredField("physicsUpdater"));
        this.updatePhysics = updater.getMethod("updatePhysics", mod, ClientLevel.class, Vec3.class, world);
        this.position = renderable.getField("position");
        this.oldPosition = renderable.getField("oldPosition");
        this.rotation = renderable.getField("rotation");
        this.oldRotation = renderable.getField("oldRotation");
        this.scale = renderable.getField("scale");
        this.models = renderable.getField("models");
        this.dead = accessible(renderable.getDeclaredField("dead"));
        this.modelTexture = model.getField("texture");
        this.modelTextureMatrix = model.getField("textureMatrix");
        this.modelTextureView = model.getField("textureID");
        this.modelMesh = model.getField("mesh");
        this.modelTranslucent = model.getField("translucent");
        this.meshPositions = mesh.getField("positions");
        this.meshUvs = mesh.getField("uvs");
        this.meshColors = mesh.getField("colors");
        this.meshIndices = mesh.getField("indices");
    }

    private static Field accessible(Field field) {
        field.setAccessible(true);
        return field;
    }

    // ---- per frame ----

    /**
     * A model's triangles, copied the first time it is seen. Physics Mod empties a mesh once it has uploaded it to
     * its own buffers, which while tracing never happens - but with ray tracing switched on mid game a mesh may
     * already be gone, and such a body cannot be shown.
     */
    private static final class Snapshot {
        float[] positions;
        float[] uvs;
        int[] colors;
        int[] indices;
        boolean seen;
    }

    private final Map<Object, Snapshot> snapshots = new IdentityHashMap<>();
    private final Matrix4f transform = new Matrix4f();
    private final Quaternionf turn = new Quaternionf();
    private final Vector3f corner = new Vector3f();
    private final Vector4f texel = new Vector4f();

    @Override
    public void provide(RadianteGeometry.Sink sink) {
        try {
            if (!this.resolved) {
                resolve();
                this.resolved = true;
            }
            provideChecked(sink);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError changed) {
            RadianteGeometry.unregister(NAME);
            this.snapshots.clear();
            RadianteClient.LOGGER.warn("Physics Mod is not the version Radiante knows; its debris will not show with "
                + "ray tracing on", changed);
        }
    }

    private void provideChecked(RadianteGeometry.Sink sink) throws ReflectiveOperationException {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        Object mod = this.getInstanceNullable.invoke(null, level);
        if (mod == null) {
            return;
        }
        Object world = this.getPhysicsWorld.invoke(mod);
        if (world == null) {
            return;
        }
        Vec3 camera = sink.camera();

        // What Physics Mod does when Minecraft draws the terrain: say the world is in view (it is dropped after a
        // while unseen), and turn the blocks broken since last frame into debris.
        this.updateLastSeen.invoke(world);
        Object updater = this.physicsUpdater.get(this.getMainRenderer.invoke(minecraft.levelRenderer));
        this.updatePhysics.invoke(updater, mod, level, camera, world);

        Vector3d offset = (Vector3d) this.getOffset.invoke(world);
        float between = (float) Math.max(0.0, Math.min(1.0, ((Number) this.getRenderPercent.invoke(world)).doubleValue()));

        for (Snapshot snapshot : this.snapshots.values()) {
            snapshot.seen = false;
        }
        for (Object body : (Iterable<?>) this.getBodies.invoke(world)) {
            Object renderable = this.getEntity.invoke(body);
            if (renderable == null) {
                continue;
            }
            List<?> bodyModels = (List<?>) this.models.get(renderable);
            if (bodyModels == null || bodyModels.isEmpty()) {
                continue;
            }
            float shrink = Math.max(0.0f, Math.min(1.0f,
                ((Number) this.getDespawnScale.invoke(renderable, level)).floatValue()));
            if (shrink <= 0.001f) {
                continue;
            }
            placeBody(renderable, between, offset, camera, shrink);
            int red = Math.round(255.0f * clamp((float) this.getRed.invoke(renderable)));
            int green = Math.round(255.0f * clamp((float) this.getGreen.invoke(renderable)));
            int blue = Math.round(255.0f * clamp((float) this.getBlue.invoke(renderable)));
            for (Object model : bodyModels) {
                writeModel(sink, model, red, green, blue);
            }
        }
        this.snapshots.values().removeIf(snapshot -> !snapshot.seen);

        // Bodies wait in this set for their GPU buffers, which Physics Mod makes when it draws. It does not draw
        // while the world is traced, so the dead ones are taken out here or the set would only ever grow; the live
        // ones stay, and get their buffers should ray tracing be switched off.
        Iterator<?> waiting = ((Set<?>) this.getQueueForModelCreation.invoke(world)).iterator();
        while (waiting.hasNext()) {
            if (this.dead.getBoolean(waiting.next())) {
                waiting.remove();
            }
        }
    }

    private static float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    /** Where the body is this frame, between its last two simulation steps, relative to the camera. */
    private void placeBody(Object renderable, float between, Vector3d offset, Vec3 camera, float shrink)
        throws ReflectiveOperationException {
        Vector3f now = (Vector3f) this.position.get(renderable);
        Vector3f before = (Vector3f) this.oldPosition.get(renderable);
        Quaternionf turnNow = (Quaternionf) this.rotation.get(renderable);
        Quaternionf turnBefore = (Quaternionf) this.oldRotation.get(renderable);
        Vector3f size = (Vector3f) this.scale.get(renderable);
        double x = before.x + (now.x - before.x) * (double) between + offset.x - camera.x;
        double y = before.y + (now.y - before.y) * (double) between + offset.y - camera.y;
        double z = before.z + (now.z - before.z) * (double) between + offset.z - camera.z;
        turnBefore.nlerp(turnNow, between, this.turn);
        this.transform.translationRotateScale((float) x, (float) y, (float) z, this.turn.x, this.turn.y, this.turn.z,
            this.turn.w, size.x * shrink, size.y * shrink, size.z * shrink);
    }

    private void writeModel(RadianteGeometry.Sink sink, Object model, int red, int green, int blue)
        throws ReflectiveOperationException {
        Snapshot snapshot = this.snapshots.get(model);
        if (snapshot == null) {
            snapshot = snapshot(model);
            if (snapshot == null) {
                return;
            }
            this.snapshots.put(model, snapshot);
        }
        snapshot.seen = true;
        GpuTextureView view = (GpuTextureView) this.modelTextureView.get(model);
        GpuTexture texture = view == null ? null : view.texture();
        if (texture == null) {
            return;
        }
        VertexConsumer out = sink.quads(texture, this.modelTranslucent.getBoolean(model)
            ? RadianteGeometry.Alpha.TRANSLUCENT : RadianteGeometry.Alpha.CUTOUT);
        int[] indices = snapshot.indices;
        for (int i = 0; i + 2 < indices.length; i += 3) {
            // A triangle goes in as a quad with its last corner twice.
            vertex(out, snapshot, indices[i], red, green, blue);
            vertex(out, snapshot, indices[i + 1], red, green, blue);
            vertex(out, snapshot, indices[i + 2], red, green, blue);
            vertex(out, snapshot, indices[i + 2], red, green, blue);
        }
    }

    private void vertex(VertexConsumer out, Snapshot snapshot, int index, int red, int green, int blue) {
        this.transform.transformPosition(snapshot.positions[index * 3], snapshot.positions[index * 3 + 1],
            snapshot.positions[index * 3 + 2], this.corner);
        // Packed by Physics Mod as red in the low byte, then green, blue and alpha.
        int color = snapshot.colors == null ? -1 : snapshot.colors[index];
        out.addVertex(this.corner.x, this.corner.y, this.corner.z)
            .setColor((color & 0xFF) * red / 255, (color >> 8 & 0xFF) * green / 255, (color >> 16 & 0xFF) * blue / 255,
                color >>> 24)
            .setUv(snapshot.uvs[index * 2], snapshot.uvs[index * 2 + 1]);
    }

    /** Copies a model's mesh, with its texture coordinates as Physics Mod would upload them; null without one. */
    @SuppressWarnings("unchecked")
    private Snapshot snapshot(Object model) throws ReflectiveOperationException {
        Object mesh = this.modelMesh.get(model);
        if (mesh == null) {
            return null;
        }
        List<Vector3f> positions = (List<Vector3f>) this.meshPositions.get(mesh);
        List<Vector2f> uvs = (List<Vector2f>) this.meshUvs.get(mesh);
        IntList colors = (IntList) this.meshColors.get(mesh);
        IntList indices = (IntList) this.meshIndices.get(mesh);
        if (positions == null || indices == null || positions.isEmpty() || indices.size() < 3) {
            return null;
        }
        int count = positions.size();
        Snapshot snapshot = new Snapshot();
        snapshot.positions = new float[count * 3];
        snapshot.uvs = new float[count * 2];
        for (int i = 0; i < count; i++) {
            Vector3f position = positions.get(i);
            snapshot.positions[i * 3] = position.x;
            snapshot.positions[i * 3 + 1] = position.y;
            snapshot.positions[i * 3 + 2] = position.z;
        }

        // A mesh's coordinates run over its sprite, 0 to 1, unless the model brings a matrix for them instead.
        TextureAtlasSprite sprite = (TextureAtlasSprite) this.modelTexture.get(model);
        Matrix4f matrix = (Matrix4f) this.modelTextureMatrix.get(model);
        boolean hasUvs = uvs != null && uvs.size() == count;
        float u0 = 0.0f;
        float u1 = 1.0f;
        float v0 = 0.0f;
        float v1 = 1.0f;
        if (sprite != null && matrix == null) {
            u0 = sprite.getU0();
            u1 = sprite.getU1();
            v0 = sprite.getV0();
            v1 = sprite.getV1();
        }
        for (int i = 0; i < count; i++) {
            float u = hasUvs ? clamp(uvs.get(i).x) : 0.5f;
            float v = hasUvs ? clamp(uvs.get(i).y) : 0.5f;
            u = u0 + u * (u1 - u0);
            v = v0 + v * (v1 - v0);
            if (matrix != null) {
                matrix.transform(this.texel.set(u, v, 0.0f, 1.0f));
                u = this.texel.x;
                v = this.texel.y;
            }
            snapshot.uvs[i * 2] = u;
            snapshot.uvs[i * 2 + 1] = v;
        }
        if (colors != null && colors.size() == count) {
            snapshot.colors = colors.toIntArray();
        }
        snapshot.indices = indices.toIntArray();
        for (int index : snapshot.indices) {
            if (index < 0 || index >= count) {
                return null;
            }
        }
        return snapshot;
    }
}
