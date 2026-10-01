#ifndef VPT_BLOCK_LIGHT_DATA_GLSL
#define VPT_BLOCK_LIGHT_DATA_GLSL

// The block light list (see block_light.glsl): one entry per emissive triangle, grouped by chunk section. Shared with
// the froxel fog, which lights the air with the same lights.

struct VptChunkLightData {
    int x;
    int y;
    int z;
    uint geometryCount;
    uint lightCount;
    uint64_t lightBufferAddress;
};

struct VptPackedLight {
    vec4 p0Area;
    vec4 p1;
    vec4 p2;
    vec4 p3;
    vec4 normal;
    vec4 radiance;
    vec4 sourceIDData;
};

layout(std430, set = 1, binding = 9) readonly buffer VptChunkPackedDataBuffer {
    VptChunkLightData vptChunkLights[];
};

layout(std430, buffer_reference, buffer_reference_align = 16) readonly buffer VptPackedLightBuffer {
    VptPackedLight lights[];
};

ivec3 vptSectionOf(vec3 scenePos) {
    return ivec3(floor((scenePos + vec3(worldUBO.cameraPos.xyz)) / 16.0));
}

int vptFloorMod(int value, int divisor) {
    return value - divisor * int(floor(float(value) / float(divisor)));
}

/** The loaded section's slot in the light list, or -1 when it is outside the grid or holds no lights. */
int vptSectionLightSlot(ivec3 section) {
    int sizeX = worldUBO.chunkGridInfo.x;
    int sizeY = worldUBO.chunkGridInfo.y;
    int sizeZ = worldUBO.chunkGridInfo.z;
    int bottom = worldUBO.chunkGridInfo.w;
    if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) { return -1; }
    if (section.y < bottom || section.y >= bottom + sizeY) { return -1; }
    int slot = (vptFloorMod(section.z, sizeZ) * sizeY + (section.y - bottom)) * sizeX + vptFloorMod(section.x, sizeX);
    VptChunkLightData data = vptChunkLights[slot];
    // A slot is reused as the grid scrolls; one still holding another section is no use here.
    if (data.x != section.x * 16 || data.y != section.y * 16 || data.z != section.z * 16) { return -1; }
    if (data.lightCount == 0u || data.lightBufferAddress == 0ul) { return -1; }
    return slot;
}

// Lights in emissive entities (a dropped glowstone, an item frame holding a sea lantern) are one more slot next to
// the sections': the list the renderer rebuilds every frame with the ones near the camera (WorldUBO.entityLight*).
const int VPT_ENTITY_LIGHT_SLOT = 1 << 30;

uint vptSlotLightCount(int slot) {
    return slot == VPT_ENTITY_LIGHT_SLOT ? worldUBO.entityLightCount : vptChunkLights[slot].lightCount;
}

uint64_t vptSlotLightAddress(int slot) {
    return slot == VPT_ENTITY_LIGHT_SLOT ?
               packUint2x32(uvec2(worldUBO.entityLightAddressLo, worldUBO.entityLightAddressHi)) :
               vptChunkLights[slot].lightBufferAddress;
}

bool vptHasEntityLights() {
    return worldUBO.entityLightCount > 0u && (worldUBO.entityLightAddressLo | worldUBO.entityLightAddressHi) != 0u;
}

/** The light list slots a surface at scenePos picks from: the 27 sections around it, then the entities'. */
int vptGatherLightSlots(vec3 scenePos, out int slots[28]) {
    int slotCount = 0;
    ivec3 center = vptSectionOf(scenePos);
    for (int dz = -1; dz <= 1; dz++) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int slot = vptSectionLightSlot(center + ivec3(dx, dy, dz));
                if (slot >= 0) { slots[slotCount++] = slot; }
            }
        }
    }
    if (vptHasEntityLights()) { slots[slotCount++] = VPT_ENTITY_LIGHT_SLOT; }
    return slotCount;
}

/** The eight sections nearest scenePos (the 2x2x2 block around its closest section corner), then the entities'. */
int vptGatherNearLightSlots(vec3 scenePos, out int slots[28]) {
    int slotCount = 0;
    ivec3 base = ivec3(floor((scenePos + vec3(worldUBO.cameraPos.xyz) - 8.0) / 16.0));
    for (int corner = 0; corner < 8; corner++) {
        int slot = vptSectionLightSlot(base + ivec3(corner & 1, (corner >> 1) & 1, corner >> 2));
        if (slot >= 0) { slots[slotCount++] = slot; }
    }
    if (vptHasEntityLights()) { slots[slotCount++] = VPT_ENTITY_LIGHT_SLOT; }
    return slotCount;
}

// One candidate light from the gathered slots: a slot at random, then a light in it. choices is how many lights it
// was drawn among, so a point on it has the probability 1 / (choices * area). A macro, not a function: a function
// copies the slot array in on every candidate, and that costs more than the pick itself.
#define VPT_PICK_LIGHT(slots, slotCount, seed, slot, index, choices, picked)                                         \
    {                                                                                                                 \
        slot = slots[min(int(rand(seed) * float(slotCount)), slotCount - 1)];                                         \
        uint pickSlotLights = vptSlotLightCount(slot);                                                                \
        index = min(uint(rand(seed) * float(pickSlotLights)), pickSlotLights - 1u);                                    \
        choices = float(slotCount) * float(pickSlotLights);                                                           \
        picked = pickSlotLights > 0u;                                                                                 \
    }
/** Whether a light hit at hitPos lies in the eight sections vptGatherNearLightSlots picks for originPos. */
bool vptInNearBlockLightReach(vec3 originPos, vec3 hitPos) {
    ivec3 base = ivec3(floor((originPos + vec3(worldUBO.cameraPos.xyz) - 8.0) / 16.0));
    ivec3 d = vptSectionOf(hitPos) - base;
    return all(greaterThanEqual(d, ivec3(0))) && all(lessThanEqual(d, ivec3(1)));
}

/** Whether a light hit at hitPos from a surface at originPos lies in the sections that surface samples. */
bool vptInBlockLightReach(vec3 originPos, vec3 hitPos) {
    ivec3 d = abs(vptSectionOf(hitPos) - vptSectionOf(originPos));
    return max(d.x, max(d.y, d.z)) <= 1;
}

vec3 vptSampleTrianglePoint(vec3 a, vec3 b, vec3 c, vec2 xi) {
    float s = sqrt(xi.x);
    return a * (1.0 - s) + b * (s * (1.0 - xi.y)) + c * (s * xi.y);
}

#endif
