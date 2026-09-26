#ifndef VERTEX_GLSL
#define VERTEX_GLSL

#include "common/shared.hpp"
#include "common/constants.glsl"

const uint USE_COLOR_LAYER_BIT = 1u << 0u;
const uint USE_TEXTURE_BIT = 1u << 1u;
const uint USE_OVERLAY_BIT = 1u << 2u;
const uint USE_GLINT_BIT = 1u << 3u;
const uint USE_NORM_BIT = 1u << 4u;
const uint USE_LIGHT_BIT = 1u << 5u;
const uint ALPHA_MODE_SHIFT = 8u;
const uint COORDINATE_SHIFT = 12u;
const uint NO_HEIGHT_SURFACE_BIT = 1u << 16u;
// The surface of water, marked by the section builder.
const uint WATER_SURFACE_BIT = 1u << 17u;
// Rain sheets, marked by the weather collector: their texture falls while the sheet stands still.
const uint RAIN_SURFACE_BIT = 1u << 18u;
// What the player holds in first person; its glow follows worldUBO.heldLightBrightness.
const uint HELD_SURFACE_BIT = 1u << 19u;

#ifndef CONST_ONLY
layout(set = 1, binding = 4) readonly buffer PositionBufferAddr {
    uint64_t addrs[];
}
positionBufferAddrs;

layout(set = 1, binding = 5) readonly buffer MaterialBufferAddr {
    uint64_t addrs[];
}
materialBufferAddrs;

layout(std430, buffer_reference, buffer_reference_align = 8) readonly buffer PositionBuffer {
    PositionVertex vertices[];
}
positionBuffer;

layout(std430, buffer_reference, buffer_reference_align = 8) readonly buffer MaterialBuffer {
    PackedMaterialVertex vertices[];
}
materialBuffer;

// Chunk geometry uses a compact layout, marked by the lowest bit of its buffer addresses (see packChunkGeometry in
// core/render/chunks.cpp). These words give 4-byte access into it.
layout(std430, buffer_reference, buffer_reference_align = 4) readonly buffer CompactWords {
    uint w[];
};

const uint64_t COMPACT_ADDRESS_TAG = 1ul;

bool isCompactAddress(uint64_t address) {
    return (address & COMPACT_ADDRESS_TAG) != 0ul;
}

uint64_t untagAddress(uint64_t address) {
    return address & ~COMPACT_ADDRESS_TAG;
}

vec2 materialSignNotZero(vec2 v) {
    return vec2(v.x >= 0.0 ? 1.0 : -1.0, v.y >= 0.0 ? 1.0 : -1.0);
}

// See PackedMaterialVertex in common/shared.hpp for the layout.
MaterialVertex unpackMaterialVertex(PackedMaterialVertex p) {
    MaterialVertex m;
    if (p.normal == 0x80008000u) {
        m.norm = vec3(0.0);
    } else {
        vec2 e = unpackSnorm2x16(p.normal);
        vec3 n = vec3(e, 1.0 - abs(e.x) - abs(e.y));
        if (n.z < 0.0) { n.xy = (1.0 - abs(n.yx)) * materialSignNotZero(n.xy); }
        m.norm = normalize(n);
    }
    m.textureID = p.textures & 0xFFFFu;
    m.colorLayer = unpackUnorm4x8(p.color);
    m.textureUV = vec2(p.u, p.v);
    m.overlayUV = ivec2(int(p.overlay << 16u) >> 16, int(p.overlay) >> 16);
    m.glintUV = unpackHalf2x16(p.glintUV);
    m.glintTexture = p.textures >> 16u;
    m.albedoEmission = p.albedoEmission;
    m.lightUV = ivec2(int(p.light & 0xFFFFu), int(p.light >> 16u));
    m.packedData = p.packedData;
    m.pad0 = 0u;
    return m;
}

uint getGeometryBufferIndex(uint instanceID, uint geometryID) {
    return blasOffsets.offsets[instanceID] + geometryID;
}

bool hasColorLayer(uint packedData) {
    return (packedData & USE_COLOR_LAYER_BIT) != 0u;
}

bool hasTexture(uint packedData) {
    return (packedData & USE_TEXTURE_BIT) != 0u;
}

bool hasOverlay(uint packedData) {
    return (packedData & USE_OVERLAY_BIT) != 0u;
}

bool hasGlint(uint packedData) {
    return (packedData & USE_GLINT_BIT) != 0u;
}

bool hasNorm(uint packedData) {
    return (packedData & USE_NORM_BIT) != 0u;
}

bool hasLight(uint packedData) {
    return (packedData & USE_LIGHT_BIT) != 0u;
}

bool isHeldSurface(uint packedData) {
    return (packedData & HELD_SURFACE_BIT) != 0u;
}

bool isRainSurface(uint packedData) {
    return (packedData & RAIN_SURFACE_BIT) != 0u;
}

bool isWaterSurface(uint packedData) {
    return (packedData & WATER_SURFACE_BIT) != 0u;
}

bool hasNoHeightSurface(uint packedData) {
    return (packedData & NO_HEIGHT_SURFACE_BIT) != 0u;
}

uint getAlphaMode(uint packedData) {
    return (packedData >> ALPHA_MODE_SHIFT) & 0xFu;
}

uint getCoordinate(uint packedData) {
    return (packedData >> COORDINATE_SHIFT) & 0xFu;
}

uint compactIndex16(CompactWords words, uint k) {
    return (words.w[k >> 1u] >> ((k & 1u) * 16u)) & 0xFFFFu;
}

void loadTriangleIndices(uint geometryBufferIndex, uint primitiveID, out uint i0, out uint i1, out uint i2) {
    uint64_t indexAddress = indexBufferAddrs.addrs[geometryBufferIndex];
    uint indexBaseID = 3u * primitiveID;
    if (isCompactAddress(indexAddress)) {
        CompactWords words = CompactWords(untagAddress(indexAddress));
        i0 = compactIndex16(words, indexBaseID);
        i1 = compactIndex16(words, indexBaseID + 1u);
        i2 = compactIndex16(words, indexBaseID + 2u);
        return;
    }
    IndexBuffer indexBuffer = IndexBuffer(indexAddress);
    i0 = indexBuffer.indices[indexBaseID];
    i1 = indexBuffer.indices[indexBaseID + 1u];
    i2 = indexBuffer.indices[indexBaseID + 2u];
}

void loadTrianglePositions(uint geometryBufferIndex,
                           uint i0,
                           uint i1,
                           uint i2,
                           out PositionVertex p0,
                           out PositionVertex p1,
                           out PositionVertex p2) {
    uint64_t positionAddress = positionBufferAddrs.addrs[geometryBufferIndex];
    if (isCompactAddress(positionAddress)) {
        CompactWords words = CompactWords(untagAddress(positionAddress));
        p0.pos = vec3(unpackHalf2x16(words.w[i0 * 2u]), unpackHalf2x16(words.w[i0 * 2u + 1u]).x);
        p1.pos = vec3(unpackHalf2x16(words.w[i1 * 2u]), unpackHalf2x16(words.w[i1 * 2u + 1u]).x);
        p2.pos = vec3(unpackHalf2x16(words.w[i2 * 2u]), unpackHalf2x16(words.w[i2 * 2u + 1u]).x);
        p0.pad0 = 0u;
        p1.pad0 = 0u;
        p2.pad0 = 0u;
        return;
    }
    PositionBuffer positionBufferRef = PositionBuffer(positionAddress);
    p0 = positionBufferRef.vertices[i0];
    p1 = positionBufferRef.vertices[i1];
    p2 = positionBufferRef.vertices[i2];
}

void loadTriangleMaterial(uint geometryBufferIndex,
                          uint i0,
                          uint i1,
                          uint i2,
                          out MaterialVertex m0,
                          out MaterialVertex m1,
                          out MaterialVertex m2) {
    uint64_t materialAddress = materialBufferAddrs.addrs[geometryBufferIndex];
    if (isCompactAddress(materialAddress)) {
        CompactWords words = CompactWords(untagAddress(materialAddress));
        PackedMaterialVertex perGeometry;
        perGeometry.textures = words.w[0];
        perGeometry.overlay = words.w[1];
        perGeometry.glintUV = words.w[2];
        perGeometry.albedoEmission = uintBitsToFloat(words.w[3]);
        perGeometry.packedData = words.w[4];
        uint indicesOf[3] = uint[3](i0, i1, i2);
        MaterialVertex result[3];
        for (int k = 0; k < 3; k++) {
            uint base = 5u + indicesOf[k] * 5u;
            PackedMaterialVertex p = perGeometry;
            p.normal = words.w[base];
            p.color = words.w[base + 1u];
            p.u = uintBitsToFloat(words.w[base + 2u]);
            p.v = uintBitsToFloat(words.w[base + 3u]);
            p.light = words.w[base + 4u];
            result[k] = unpackMaterialVertex(p);
        }
        m0 = result[0];
        m1 = result[1];
        m2 = result[2];
        return;
    }
    MaterialBuffer materialBufferRef = MaterialBuffer(materialAddress);
    m0 = unpackMaterialVertex(materialBufferRef.vertices[i0]);
    m1 = unpackMaterialVertex(materialBufferRef.vertices[i1]);
    m2 = unpackMaterialVertex(materialBufferRef.vertices[i2]);
}

void loadTriangle(uint geometryBufferIndex,
                  uint primitiveID,
                  out uint i0,
                  out uint i1,
                  out uint i2,
                  out PositionVertex p0,
                  out PositionVertex p1,
                  out PositionVertex p2,
                  out MaterialVertex m0,
                  out MaterialVertex m1,
                  out MaterialVertex m2) {
    loadTriangleIndices(geometryBufferIndex, primitiveID, i0, i1, i2);
    loadTrianglePositions(geometryBufferIndex, i0, i1, i2, p0, p1, p2);
    loadTriangleMaterial(geometryBufferIndex, i0, i1, i2, m0, m1, m2);
}
#endif

#endif
