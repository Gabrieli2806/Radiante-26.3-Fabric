package com.g2806.radiante.client.render;

import java.util.List;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Doors and trapdoors are two thin sheets a few pixels apart with their windows cut out of the texture, and nothing
 * joins the sheets around a window. Rasterised with flat lighting nobody notices; traced, a window shows the empty
 * gap and the shadowed inside of the far sheet, and the panel reads as hollow. This closes every window with thin
 * walls spanning the panel's thickness, each coloured like the solid texel it borders - the way held items are given
 * depth.
 */
final class CutoutWalls {

    private static final float MIN_FACE_AREA = 0.2f;
    private static final float MAX_THICKNESS = 0.5f;
    private static final int FULL_BRIGHT = 0xF000F0;

    private CutoutWalls() {
    }

    static void emit(List<BakedQuad> quads, float bx, float by, float bz, PBRVertexWriter writer) {
        for (BakedQuad face : quads) {
            Direction direction = face.direction();
            if (direction.getAxisDirection() != Direction.AxisDirection.POSITIVE || area(face) < MIN_FACE_AREA) {
                continue;
            }
            float thickness = thickness(face, quads);
            if (thickness <= 0.0f) {
                continue;
            }
            emitFace(face, thickness, bx, by, bz, writer);
        }
    }

    /** Distance to the big face on the other side of the panel, or 0 when there is none. */
    private static float thickness(BakedQuad face, List<BakedQuad> quads) {
        Direction direction = face.direction();
        float plane = axisValue(face.position0(), direction.getAxis());
        for (BakedQuad other : quads) {
            if (other.direction() != direction.getOpposite() || other.materialInfo().sprite() != face.materialInfo().sprite()
                || area(other) < MIN_FACE_AREA) {
                continue;
            }
            float distance = plane - axisValue(other.position0(), direction.getAxis());
            if (distance > 1.0e-4f && distance <= MAX_THICKNESS) {
                return distance;
            }
        }
        return 0.0f;
    }

    private static void emitFace(BakedQuad face, float thickness, float bx, float by, float bz,
        PBRVertexWriter writer) {
        TextureAtlasSprite sprite = face.materialInfo().sprite();
        SpriteContents contents = sprite.contents();
        int width = contents.width();
        int height = contents.height();
        float spanU = sprite.getU1() - sprite.getU0();
        float spanV = sprite.getV1() - sprite.getV0();
        if (spanU == 0.0f || spanV == 0.0f) {
            return;
        }

        // Corners 0, 1 and 3 span the quad; texel coordinates of each, in the sprite's own pixel grid.
        float[] tx = new float[4];
        float[] ty = new float[4];
        for (int i = 0; i < 4; i++) {
            long uv = face.packedUV(i);
            tx[i] = (UVPair.unpackU(uv) - sprite.getU0()) / spanU * width;
            ty[i] = (UVPair.unpackV(uv) - sprite.getV0()) / spanV * height;
        }
        float ax = tx[1] - tx[0];
        float ay = ty[1] - ty[0];
        float cx = tx[3] - tx[0];
        float cy = ty[3] - ty[0];
        float det = ax * cy - ay * cx;
        if (Math.abs(det) < 1.0e-6f) {
            return;
        }

        int x0 = Math.max(0, Math.round(Math.min(Math.min(tx[0], tx[1]), Math.min(tx[2], tx[3]))));
        int x1 = Math.min(width, Math.round(Math.max(Math.max(tx[0], tx[1]), Math.max(tx[2], tx[3]))));
        int y0 = Math.max(0, Math.round(Math.min(Math.min(ty[0], ty[1]), Math.min(ty[2], ty[3]))));
        int y1 = Math.min(height, Math.round(Math.max(Math.max(ty[0], ty[1]), Math.max(ty[2], ty[3]))));

        Vector3fc p0 = face.position0();
        Vector3f edgeS = new Vector3f(face.position1()).sub(p0);
        Vector3f edgeT = new Vector3f(face.position3()).sub(p0);
        Vector3f inward = new Vector3f(face.direction().getUnitVec3f()).mul(-thickness);
        Mapping mapping = new Mapping(tx[0], ty[0], ax, ay, cx, cy, det, p0, edgeS, edgeT);

        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                if (contents.isTransparent(0, x, y)) {
                    continue;
                }
                float u = sprite.getU0() + (x + 0.5f) / width * spanU;
                float v = sprite.getV0() + (y + 0.5f) / height * spanV;
                if (x + 1 < x1 && contents.isTransparent(0, x + 1, y)) {
                    wall(mapping, x + 1, y, x + 1, y + 1, x + 1.5f, y + 0.5f, x + 0.5f, y + 0.5f, inward, u, v, bx, by, bz, writer);
                }
                if (x - 1 >= x0 && contents.isTransparent(0, x - 1, y)) {
                    wall(mapping, x, y, x, y + 1, x - 0.5f, y + 0.5f, x + 0.5f, y + 0.5f, inward, u, v, bx, by, bz, writer);
                }
                if (y + 1 < y1 && contents.isTransparent(0, x, y + 1)) {
                    wall(mapping, x, y + 1, x + 1, y + 1, x + 0.5f, y + 1.5f, x + 0.5f, y + 0.5f, inward, u, v, bx, by, bz, writer);
                }
                if (y - 1 >= y0 && contents.isTransparent(0, x, y - 1)) {
                    wall(mapping, x, y, x + 1, y, x + 0.5f, y - 0.5f, x + 0.5f, y + 0.5f, inward, u, v, bx, by, bz, writer);
                }
            }
        }
    }

    /** A wall along the texel edge (ex0, ey0)-(ex1, ey1), facing from the solid texel towards the hole. */
    private static void wall(Mapping mapping, float ex0, float ey0, float ex1, float ey1, float holeX, float holeY,
        float solidX, float solidY, Vector3f inward, float u, float v, float bx, float by, float bz,
        PBRVertexWriter writer) {
        Vector3f a = mapping.position(ex0, ey0);
        Vector3f b = mapping.position(ex1, ey1);
        Vector3f normal = mapping.position(holeX, holeY).sub(mapping.position(solidX, solidY)).normalize();
        vertex(writer, a, bx, by, bz, u, v, normal);
        vertex(writer, b, bx, by, bz, u, v, normal);
        vertex(writer, new Vector3f(b).add(inward), bx, by, bz, u, v, normal);
        vertex(writer, new Vector3f(a).add(inward), bx, by, bz, u, v, normal);
    }

    private static void vertex(PBRVertexWriter writer, Vector3f p, float bx, float by, float bz, float u, float v,
        Vector3f normal) {
        writer.addVertex(p.x() + bx, p.y() + by, p.z() + bz, -1, u, v, OverlayTexture.NO_OVERLAY, FULL_BRIGHT,
            normal.x(), normal.y(), normal.z());
    }

    private static float area(BakedQuad quad) {
        Vector3f s = new Vector3f(quad.position1()).sub(quad.position0());
        Vector3f t = new Vector3f(quad.position3()).sub(quad.position0());
        return s.cross(t).length();
    }

    private static float axisValue(Vector3fc p, Direction.Axis axis) {
        return switch (axis) {
            case X -> p.x();
            case Y -> p.y();
            case Z -> p.z();
        };
    }

    /** Texel coordinates on the face to a position in the block, through the quad's own UV layout. */
    private record Mapping(float tx0, float ty0, float ax, float ay, float cx, float cy, float det, Vector3fc p0,
                           Vector3fc edgeS, Vector3fc edgeT) {

        Vector3f position(float tx, float ty) {
            float dx = tx - this.tx0;
            float dy = ty - this.ty0;
            float s = (dx * this.cy - dy * this.cx) / this.det;
            float t = (this.ax * dy - this.ay * dx) / this.det;
            return new Vector3f(this.p0).add(new Vector3f(this.edgeS).mul(s)).add(new Vector3f(this.edgeT).mul(t));
        }
    }
}
