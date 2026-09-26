package com.g2806.radiante.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.lwjgl.system.MemoryUtil;

/**
 * Writes vertices in the renderer's 128-byte {@code PBRVertex} layout (see native common/shared.hpp) into
 * a growable off-heap buffer. Quads are expected (4 vertices per face).
 */
public final class PBRVertexWriter implements VertexConsumer, AutoCloseable {

    public static final int STRIDE = 128;
    /** Set in the alpha mode word for water surfaces; see {@link #water}. */
    private static final int WATER_FLAG = 0x10;
    /** Set in the alpha mode word for rain sheets; see {@link #rain}. */
    private static final int RAIN_FLAG = 0x20;
    /** Set in the alpha mode word for what the player holds in first person; see {@link #held}. */
    private static final int HELD_FLAG = 0x40;

    public static final int ALPHA_MODE_OPAQUE = 0;
    public static final int ALPHA_MODE_CUTOUT = 1;
    public static final int ALPHA_MODE_TRANSPARENT = 2;
    /** Kept by the any-hit shader with probability alpha, then shaded opaque; see util/alpha_mode.glsl. */
    public static final int ALPHA_MODE_STOCHASTIC = 9;
    /** A multiplicative decal such as block cracks; see util/alpha_mode.glsl. */
    public static final int ALPHA_MODE_DECAL = 10;
    /** An additive layer: only its coloured texels are surface; see util/alpha_mode.glsl. */
    public static final int ALPHA_MODE_ADDITIVE = 11;

    private static final int OFF_POS = 0;
    private static final int OFF_USE_NORM = 12;
    private static final int OFF_NORM = 16;
    private static final int OFF_USE_COLOR = 28;
    private static final int OFF_COLOR = 32;
    private static final int OFF_USE_TEXTURE = 48;
    private static final int OFF_USE_OVERLAY = 52;
    private static final int OFF_TEXTURE_UV = 56;
    private static final int OFF_OVERLAY_UV = 64;
    private static final int OFF_USE_GLINT = 72;
    private static final int OFF_TEXTURE_ID = 76;
    private static final int OFF_GLINT_UV = 80;
    private static final int OFF_GLINT_TEXTURE = 88;
    private static final int OFF_USE_LIGHT = 92;
    private static final int OFF_LIGHT_UV = 96;
    private static final int OFF_COORDINATE = 104;
    private static final int OFF_ALBEDO_EMISSION = 108;
    private static final int OFF_ALPHA_MODE = 124;

    /**
     * Furthest a vertex may sit from the origin of its coordinate space. Minecraft occasionally poses something at
     * a position it never meant to draw - a block-attached entity whose support block is gone, a model scaled by a
     * degenerate matrix - and the result is an infinite or astronomically large corner. The rasteriser shrugs those
     * off; an acceleration structure does not, and the driver answers with a lost device.
     */
    private static final float POSITION_LIMIT = 1.0E6f;

    private long address;
    private long capacity;
    private int vertexCount;
    private long current = -1L;
    private boolean quadHasUnplaceableVertex;
    private int droppedQuads;

    private int textureId;
    private int glintTextureId;
    private int alphaMode;
    private int coordinate;
    private boolean water;
    private boolean rain;
    private boolean held;
    private float albedoEmission;
    /**
     * How far, in blocks, each vertex is pushed out along its normal. Rasterised, a layer drawn over the same
     * model - armor trims, elytra trims, dyed overlays - sits exactly on it and wins the depth test by being drawn
     * later; traced, two coplanar surfaces tie and the one underneath can come out on top, hiding the layer.
     */
    private float layerOffset;
    private boolean computeQuadNormals;
    private boolean overlayEnabled;
    private boolean glintEnabled;
    private int colorOverride;
    private int colorTint = 0xFFFFFF;
    private float uOffset;
    private float vOffset;

    public PBRVertexWriter(int initialVertices) {
        this.capacity = Math.max(4, initialVertices) * (long) STRIDE;
        this.address = MemoryUtil.nmemAllocChecked(this.capacity);
    }

    public PBRVertexWriter textureId(int textureId) {
        this.textureId = textureId;
        return this;
    }

    public PBRVertexWriter glintTextureId(int glintTextureId) {
        this.glintTextureId = glintTextureId;
        return this;
    }

    /**
     * Whether every vertex from here on carries the enchantment glint, sampled at its own texture coordinate -
     * the standard glint, the same way vanilla's own glint.vsh reuses UV0. Vanilla's screen-locked "special" foil
     * decal (compasses, a few other models) is approximated the same way rather than reprojected; it still glints,
     * just not locked to the screen.
     */
    public PBRVertexWriter glintEnabled(boolean glintEnabled) {
        this.glintEnabled = glintEnabled;
        return this;
    }

    /** An opaque RGB every vertex takes in place of the colour it is given, keeping its alpha; 0 turns it off. */
    public PBRVertexWriter colorOverride(int colorOverride) {
        this.colorOverride = colorOverride;
        return this;
    }

    /** An RGB every vertex colour is multiplied by; 0xFFFFFF leaves it alone. Emission follows it. */
    public PBRVertexWriter colorTint(int colorTint) {
        this.colorTint = colorTint;
        return this;
    }

    /** Added to every texture coordinate: vanilla's scrolling texture transform (charged creeper, wither armor). */
    public PBRVertexWriter uvOffset(float uOffset, float vOffset) {
        this.uOffset = uOffset;
        this.vOffset = vOffset;
        return this;
    }

    public PBRVertexWriter alphaMode(int alphaMode) {
        this.alphaMode = alphaMode;
        return this;
    }

    /**
     * Whether the vertices from here on are the surface of water. Carried to the shaders in a bit above the alpha
     * mode, which only uses the low four, so they can treat it as water: refraction, waves, caustics and the pack's
     * water medium.
     */
    /** Whether the vertices from here on are what the player holds, whose glow has a brightness of its own. */
    public PBRVertexWriter held(boolean held) {
        this.held = held;
        return this;
    }

    /** Whether the vertices from here on are rain sheets, whose texture falls: their motion vectors follow it. */
    public PBRVertexWriter rain(boolean rain) {
        this.rain = rain;
        return this;
    }

    public PBRVertexWriter water(boolean water) {
        this.water = water;
        return this;
    }

    public PBRVertexWriter coordinate(int coordinate) {
        this.coordinate = coordinate;
        return this;
    }

    public PBRVertexWriter layerOffset(float layerOffset) {
        this.layerOffset = layerOffset;
        return this;
    }

    public PBRVertexWriter albedoEmission(float albedoEmission) {
        this.albedoEmission = albedoEmission;
        return this;
    }

    /**
     * Terrain has no overlay colour, and flagging it would make the shaders blend against the (empty) overlay
     * texture. Entities that flash red or white when hurt turn this on.
     */
    public PBRVertexWriter overlayEnabled(boolean overlayEnabled) {
        this.overlayEnabled = overlayEnabled;
        return this;
    }

    /** Derives face normals from quad positions instead of the values the caller supplies. */
    public PBRVertexWriter computeQuadNormals(boolean computeQuadNormals) {
        this.computeQuadNormals = computeQuadNormals;
        return this;
    }

    public int vertexCount() {
        return this.vertexCount;
    }

    public long address() {
        return this.address;
    }

    /** Quads dropped since the last reset because a corner could not be placed. */
    public int droppedQuads() {
        return this.droppedQuads;
    }

    public void reset() {
        this.layerOffset = 0.0f;
        this.vertexCount = 0;
        this.current = -1L;
        this.quadHasUnplaceableVertex = false;
        this.droppedQuads = 0;
    }

    private void ensureCapacity(long required) {
        if (required <= this.capacity) {
            return;
        }
        long newCapacity = this.capacity;
        while (newCapacity < required) {
            newCapacity *= 2;
        }
        this.address = MemoryUtil.nmemReallocChecked(this.address, newCapacity);
        this.capacity = newCapacity;
    }

    private void finishQuadIfComplete() {
        if (this.vertexCount % 4 != 0 || this.vertexCount == 0) {
            return;
        }
        if (this.quadHasUnplaceableVertex) {
            // Rewinding is the only honest answer. Clamping the corner to the origin would stretch the quad from
            // wherever the model really is all the way to the camera, which is worse than not drawing it at all.
            this.vertexCount -= 4;
            this.quadHasUnplaceableVertex = false;
            this.droppedQuads++;
            return;
        }
        if (!this.computeQuadNormals) {
            return;
        }
        long v0 = this.address + (long) (this.vertexCount - 4) * STRIDE;
        long v1 = v0 + STRIDE;
        long v2 = v1 + STRIDE;
        float ax = MemoryUtil.memGetFloat(v1) - MemoryUtil.memGetFloat(v0);
        float ay = MemoryUtil.memGetFloat(v1 + 4) - MemoryUtil.memGetFloat(v0 + 4);
        float az = MemoryUtil.memGetFloat(v1 + 8) - MemoryUtil.memGetFloat(v0 + 8);
        float bx = MemoryUtil.memGetFloat(v2) - MemoryUtil.memGetFloat(v0);
        float by = MemoryUtil.memGetFloat(v2 + 4) - MemoryUtil.memGetFloat(v0 + 4);
        float bz = MemoryUtil.memGetFloat(v2 + 8) - MemoryUtil.memGetFloat(v0 + 8);
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1.0E-8f) {
            return;
        }
        nx /= length;
        ny /= length;
        nz /= length;
        for (int i = 0; i < 4; i++) {
            long v = v0 + (long) i * STRIDE;
            MemoryUtil.memPutInt(v + OFF_USE_NORM, 1);
            MemoryUtil.memPutFloat(v + OFF_NORM, nx);
            MemoryUtil.memPutFloat(v + OFF_NORM + 4, ny);
            MemoryUtil.memPutFloat(v + OFF_NORM + 8, nz);
        }
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        finishQuadIfComplete();
        ensureCapacity((long) (this.vertexCount + 1) * STRIDE);
        long v = this.address + (long) this.vertexCount * STRIDE;
        this.vertexCount++;
        this.current = v;

        MemoryUtil.memSet(v, 0, STRIDE);
        if (!placeable(x) || !placeable(y) || !placeable(z)) {
            // Written as zeroes only so the buffer holds no rubbish; the whole quad goes away once it completes.
            this.quadHasUnplaceableVertex = true;
            x = 0.0f;
            y = 0.0f;
            z = 0.0f;
        }
        MemoryUtil.memPutFloat(v + OFF_POS, x);
        MemoryUtil.memPutFloat(v + OFF_POS + 4, y);
        MemoryUtil.memPutFloat(v + OFF_POS + 8, z);
        MemoryUtil.memPutInt(v + OFF_TEXTURE_ID, this.textureId);
        MemoryUtil.memPutInt(v + OFF_GLINT_TEXTURE, this.glintTextureId);
        MemoryUtil.memPutInt(v + OFF_COORDINATE, this.coordinate);
        MemoryUtil.memPutFloat(v + OFF_ALBEDO_EMISSION, this.albedoEmission);
        MemoryUtil.memPutInt(v + OFF_ALPHA_MODE,
            this.alphaMode | (this.water ? WATER_FLAG : 0) | (this.rain ? RAIN_FLAG : 0) | (this.held ? HELD_FLAG : 0));
        if (this.colorOverride != 0) {
            setColor(255, 255, 255, 255);
        }
        return this;
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
        if (this.colorOverride != 0) {
            r = this.colorOverride >> 16 & 0xFF;
            g = this.colorOverride >> 8 & 0xFF;
            b = this.colorOverride & 0xFF;
        }
        if (this.colorTint != 0xFFFFFF) {
            r = r * (this.colorTint >> 16 & 0xFF) / 255;
            g = g * (this.colorTint >> 8 & 0xFF) / 255;
            b = b * (this.colorTint & 0xFF) / 255;
        }
        MemoryUtil.memPutInt(this.current + OFF_USE_COLOR, 1);
        MemoryUtil.memPutFloat(this.current + OFF_COLOR, r / 255.0f);
        MemoryUtil.memPutFloat(this.current + OFF_COLOR + 4, g / 255.0f);
        MemoryUtil.memPutFloat(this.current + OFF_COLOR + 8, b / 255.0f);
        MemoryUtil.memPutFloat(this.current + OFF_COLOR + 12, a / 255.0f);
        return this;
    }

    @Override
    public VertexConsumer setColor(int color) {
        return setColor(color >> 16 & 0xFF, color >> 8 & 0xFF, color & 0xFF, color >>> 24);
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        MemoryUtil.memPutInt(this.current + OFF_USE_TEXTURE, 1);
        MemoryUtil.memPutFloat(this.current + OFF_TEXTURE_UV, u + this.uOffset);
        MemoryUtil.memPutFloat(this.current + OFF_TEXTURE_UV + 4, v + this.vOffset);
        if (this.glintEnabled) {
            // The standard glint has no UV of its own in vanilla either - glint.vsh scrolls the block or item's
            // own texture coordinate through a shared animation matrix (WorldUBO.textureMat here), so the glint
            // rides the quad's UV rather than reading anything new.
            MemoryUtil.memPutInt(this.current + OFF_USE_GLINT, 1);
            MemoryUtil.memPutFloat(this.current + OFF_GLINT_UV, u);
            MemoryUtil.memPutFloat(this.current + OFF_GLINT_UV + 4, v);
        }
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        if (!this.overlayEnabled) {
            return this;
        }
        MemoryUtil.memPutInt(this.current + OFF_USE_OVERLAY, 1);
        MemoryUtil.memPutInt(this.current + OFF_OVERLAY_UV, u);
        MemoryUtil.memPutInt(this.current + OFF_OVERLAY_UV + 4, v);
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        MemoryUtil.memPutInt(this.current + OFF_USE_LIGHT, 1);
        MemoryUtil.memPutInt(this.current + OFF_LIGHT_UV, u);
        MemoryUtil.memPutInt(this.current + OFF_LIGHT_UV + 4, v);
        return this;
    }

    @Override
    public VertexConsumer setUv3(float u, float v) {
        MemoryUtil.memPutInt(this.current + OFF_USE_GLINT, 1);
        MemoryUtil.memPutFloat(this.current + OFF_GLINT_UV, u);
        MemoryUtil.memPutFloat(this.current + OFF_GLINT_UV + 4, v);
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        if (this.layerOffset != 0.0f) {
            // A layer drawn over the same model (see layerOffset): the vertex moves out along its own normal.
            MemoryUtil.memPutFloat(this.current + OFF_POS, MemoryUtil.memGetFloat(this.current + OFF_POS) + x * this.layerOffset);
            MemoryUtil.memPutFloat(this.current + OFF_POS + 4,
                MemoryUtil.memGetFloat(this.current + OFF_POS + 4) + y * this.layerOffset);
            MemoryUtil.memPutFloat(this.current + OFF_POS + 8,
                MemoryUtil.memGetFloat(this.current + OFF_POS + 8) + z * this.layerOffset);
        }
        MemoryUtil.memPutInt(this.current + OFF_USE_NORM, 1);
        MemoryUtil.memPutFloat(this.current + OFF_NORM, x);
        MemoryUtil.memPutFloat(this.current + OFF_NORM + 4, y);
        MemoryUtil.memPutFloat(this.current + OFF_NORM + 8, z);
        return this;
    }

    @Override
    public VertexConsumer setLineWidth(float width) {
        return this;
    }
    /** Call once all vertices are written so a trailing quad gets its computed normal. */
    /** A coordinate the acceleration structure can hold: finite, and inside the world the renderer traces. */
    private static boolean placeable(float value) {
        return Float.isFinite(value) && Math.abs(value) <= POSITION_LIMIT;
    }

    public void finish() {
        finishQuadIfComplete();
    }

    @Override
    public void close() {
        if (this.address != 0L) {
            MemoryUtil.nmemFree(this.address);
            this.address = 0L;
        }
    }
}
