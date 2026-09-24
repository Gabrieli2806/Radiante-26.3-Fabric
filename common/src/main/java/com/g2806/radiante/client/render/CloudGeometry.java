package com.g2806.radiante.client.render;

import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Minecraft's own clouds as ray traced geometry: the same 12x4x12 cells, from the same clouds.png, drifting the same
 * way. Vanilla builds them on the GPU from a packed face list only its cloud shader can read, and draws them inside
 * the level render this renderer replaces. The cells are expanded to quads here instead, and only rebuilt when the
 * camera crosses into another cell; in between the mesh just moves with the drift.
 */
final class CloudGeometry {

    private static final float CELL_SIZE = 12.0f;
    private static final float CELL_HEIGHT = 4.0f;

    private final PBRVertexWriter writer = new PBRVertexWriter(4096);
    private int prevCellX = Integer.MIN_VALUE;
    private int prevCellZ = Integer.MIN_VALUE;
    private CloudStatus prevStatus;
    private int prevColor;
    private int prevRadius = -1;
    private CloudRenderer.TextureData prevTexture;

    /** World position the current mesh is relative to, valid after {@link #update}. */
    double originX;
    double originY;
    double originZ;

    /**
     * Rebuilds the mesh when needed and places it for this frame; returns the writer holding it, or null when there
     * is nothing to draw.
     */
    PBRVertexWriter update(CloudRenderer.TextureData texture, CloudStatus status, int color, float bottomY,
        int rangeChunks, Vec3 camera, long gameTime, float partialTicks) {
        if (texture == null || status == CloudStatus.OFF) {
            return null;
        }

        float cloudOffset = (float) (gameTime % (texture.width() * 400L)) + partialTicks;
        double cloudX = camera.x + cloudOffset * 0.030000001f;
        double cloudZ = camera.z + 3.96f;
        double textureWidthBlocks = texture.width() * (double) CELL_SIZE;
        double textureHeightBlocks = texture.height() * (double) CELL_SIZE;
        cloudX -= Mth.floor(cloudX / textureWidthBlocks) * textureWidthBlocks;
        cloudZ -= Mth.floor(cloudZ / textureHeightBlocks) * textureHeightBlocks;
        int cellX = Mth.floor(cloudX / CELL_SIZE);
        int cellZ = Mth.floor(cloudZ / CELL_SIZE);
        float xInCell = (float) (cloudX - cellX * CELL_SIZE);
        float zInCell = (float) (cloudZ - cellZ * CELL_SIZE);
        int radiusCells = Mth.ceil(rangeChunks * 16 / CELL_SIZE);

        if (texture != this.prevTexture || cellX != this.prevCellX || cellZ != this.prevCellZ
            || status != this.prevStatus || color != this.prevColor
            || radiusCells != this.prevRadius) {
            this.prevTexture = texture;
            this.prevCellX = cellX;
            this.prevCellZ = cellZ;
            this.prevStatus = status;
            this.prevColor = color;
            this.prevRadius = radiusCells;
            this.build(texture, cellX, cellZ, status == CloudStatus.FANCY, radiusCells, color);
        }

        this.originX = camera.x - xInCell;
        this.originY = bottomY;
        this.originZ = camera.z - zInCell;
        return this.writer.vertexCount() > 0 ? this.writer : null;
    }

    private void build(CloudRenderer.TextureData texture, int centerX, int centerZ, boolean fancy,
        int radiusCells, int color) {
        this.writer.textureId(0)
            .glintTextureId(0)
            .glintEnabled(false)
            .alphaMode(PBRVertexWriter.ALPHA_MODE_OPAQUE)
            .coordinate(NativeGeometry.COORDINATE_WORLD)
            .albedoEmission(0.0f)
            .overlayEnabled(false)
            .computeQuadNormals(false);
        this.writer.reset();

        long[] cells = texture.cells();
        int width = texture.width();
        int height = texture.height();
        int radius2 = radiusCells * radiusCells;
        for (int dz = -radiusCells; dz <= radiusCells; dz++) {
            for (int dx = -radiusCells; dx <= radiusCells; dx++) {
                if (dx * dx + dz * dz > radius2) {
                    continue;
                }
                long cell = cells[Math.floorMod(centerX + dx, width) + Math.floorMod(centerZ + dz, height) * width];
                if (cell == 0L) {
                    continue;
                }
                int cellColor = multiply(color, (int) (cell >> 4));
                if (fancy) {
                    this.extrudedCell(dx, dz, cell, cellColor);
                } else {
                    this.quad(dx, dz, 0.0f, 0, -1, 0, cellColor);
                }
            }
        }
        this.writer.finish();
    }

    /**
     * A closed box: top, bottom, and every side with no cloud next to it. Vanilla leaves out the faces the camera
     * cannot see from where it is; a ray tracer also sees clouds from reflections and shadow rays, from any angle.
     */
    private void extrudedCell(int x, int z, long cell, int color) {
        this.quad(x, z, CELL_HEIGHT, 0, 1, 0, color);
        this.quad(x, z, 0.0f, 0, -1, 0, color);
        if ((cell >> 3 & 1L) != 0L) {
            this.quad(x, z, 0.0f, 0, 0, -1, color);
        }
        if ((cell >> 1 & 1L) != 0L) {
            this.quad(x, z, 0.0f, 0, 0, 1, color);
        }
        if ((cell & 1L) != 0L) {
            this.quad(x, z, 0.0f, -1, 0, 0, color);
        }
        if ((cell >> 2 & 1L) != 0L) {
            this.quad(x, z, 0.0f, 1, 0, 0, color);
        }
    }

    /** One face of the cell at (x, z), wound so its normal points along (nx, ny, nz). */
    private void quad(int x, int z, float y, int nx, int ny, int nz, int color) {
        float x0 = x * CELL_SIZE;
        float x1 = x0 + CELL_SIZE;
        float z0 = z * CELL_SIZE;
        float z1 = z0 + CELL_SIZE;
        float[][] corners;
        if (ny > 0) {
            corners = new float[][] {{x0, y, z0}, {x0, y, z1}, {x1, y, z1}, {x1, y, z0}};
        } else if (ny < 0) {
            corners = new float[][] {{x1, y, z0}, {x1, y, z1}, {x0, y, z1}, {x0, y, z0}};
        } else if (nz < 0) {
            corners = new float[][] {{x0, 0, z0}, {x0, CELL_HEIGHT, z0}, {x1, CELL_HEIGHT, z0}, {x1, 0, z0}};
        } else if (nz > 0) {
            corners = new float[][] {{x1, 0, z1}, {x1, CELL_HEIGHT, z1}, {x0, CELL_HEIGHT, z1}, {x0, 0, z1}};
        } else if (nx < 0) {
            corners = new float[][] {{x0, 0, z1}, {x0, CELL_HEIGHT, z1}, {x0, CELL_HEIGHT, z0}, {x0, 0, z0}};
        } else {
            corners = new float[][] {{x1, 0, z0}, {x1, CELL_HEIGHT, z0}, {x1, CELL_HEIGHT, z1}, {x1, 0, z1}};
        }
        for (float[] corner : corners) {
            this.writer.addVertex(corner[0], corner[1], corner[2]).setColor(color).setNormal(nx, ny, nz);
        }
    }

    private static int multiply(int a, int b) {
        int alpha = ((a >>> 24) * (b >>> 24)) / 255;
        int red = (((a >> 16) & 0xFF) * ((b >> 16) & 0xFF)) / 255;
        int green = (((a >> 8) & 0xFF) * ((b >> 8) & 0xFF)) / 255;
        int blue = ((a & 0xFF) * (b & 0xFF)) / 255;
        return alpha << 24 | red << 16 | green << 8 | blue;
    }
}
