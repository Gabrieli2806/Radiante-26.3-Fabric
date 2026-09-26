package com.g2806.radiante.client.render;

import com.g2806.radiante.client.option.Options;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.SimpleGizmoCollector;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;

import static com.g2806.radiante.client.render.EntityManager.COLLECTOR;
import static com.g2806.radiante.client.render.EntityManager.PENDING;
import static com.g2806.radiante.client.render.EntityManager.RAY_TRACING_PARTICLE;
import static com.g2806.radiante.client.render.EntityManager.copyVertices;

import com.g2806.radiante.client.render.EntityManager.PendingEntity;
import com.g2806.radiante.client.render.EntityManager.PendingLayer;

/**
 * Lines drawn over the world rather than part of it: F3+B hitboxes, F3+G chunk borders and other debug gizmos,
 * and the outline of the block under the crosshair. Each line becomes a thin camera-facing quad, traced under the
 * particle mask so it casts no shadow. Split out of EntityManager, which gathers them with everything else.
 */
final class OverlayLines {
    private static final int GIZMO_ID = "radiante:gizmo".hashCode();
    private static final PBRVertexWriter GIZMO_WRITER = new PBRVertexWriter(2048);
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

    /**
     * F3+B entity hitboxes, F3+G chunk borders, and anything else that calls {@code Gizmos.line}/{@code .cuboid}/
     * etc. Vanilla only drains the collector these land in from inside {@code LevelRenderer.render}, which the
     * ray tracer replaces entirely, so nothing ever emptied it and the overlays those shortcuts are meant to
     * toggle never appeared. Drained here instead and turned into thin, camera-facing, unlit quads under the
     * particle mask, so they show up without casting a shadow or feeding indirect light back into the scene.
     * Quads, triangle fans and text gizmos (used by other, rarer debug views) are not handled yet - only lines,
     * which is everything both F3+B and F3+G actually draw.
     */
    static void collectDebugGizmos(Minecraft minecraft, CameraRenderState cameraState) {
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
            .albedoEmission(Glow.GIZMO)
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
    static void collectBlockOutline(LevelRenderState levelRenderState, CameraRenderState cameraState) {
        net.minecraft.client.renderer.state.level.BlockOutlineRenderState state =
            levelRenderState.blockOutlineRenderState;
        if (!Options.blockOutline || state == null || state.shape().isEmpty()) {
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
            .albedoEmission(state.highContrast() ? Glow.GIZMO : 0.0f)
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

    private OverlayLines() {
    }
}
