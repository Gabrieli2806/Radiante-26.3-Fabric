#ifndef VPT_BLOCK_LIGHT_GLSL
#define VPT_BLOCK_LIGHT_GLSL

// Direct sampling of block lights (torches, lava, glowstone...). Without it their light only reaches a surface when
// a random bounce happens to hit the light, which is what makes small lights so noisy. Each surface instead picks
// a light from the sections around it and traces one shadow ray to it.
//
// The light list is built natively from chunk emission (one entry per emissive triangle, absolute world position)
// and is only filled with Block Emission on; worldUBO.blockLightSampling is zero otherwise, and nothing here reads
// the list then.
//
// Only the diffuse part of the surface is lit this way. A bounce that leaves through the diffuse lobe and then hits
// one of these lights must not add that light again: see vptBlockLightAlreadyCounted.

#ifndef VPT_BLOCK_LIGHT_CANDIDATES
#    define VPT_BLOCK_LIGHT_CANDIDATES 4
#endif

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

/** Whether a light hit at hitPos from a surface at originPos lies in the sections that surface samples. */
bool vptInBlockLightReach(vec3 originPos, vec3 hitPos) {
    ivec3 d = abs(vptSectionOf(hitPos) - vptSectionOf(originPos));
    return max(d.x, max(d.y, d.z)) <= 1;
}

/**
 * A light the ray just hit that the surface it came from already sampled directly. Its emission is left out here,
 * or the light would be counted twice.
 */
bool vptBlockLightAlreadyCounted(vec3 originPos, vec3 hitPos) {
    return worldUBO.blockLightSampling != 0u && rayBlockLightSampled(mainRay) && vptInBlockLightReach(originPos, hitPos);
}

/** The diffuse share of a surface's reflection; the only part lit by the sampled lights. */
float vptBlockLightDiffuseWeight(LabPBRMat mat) {
    return (1.0 - mat.metallic) * (1.0 - mat.transmission);
}

vec3 vptSampleTrianglePoint(vec3 a, vec3 b, vec3 c, vec2 xi) {
    float s = sqrt(xi.x);
    return a * (1.0 - s) + b * (s * (1.0 - xi.y)) + c * (s * xi.y);
}

/**
 * Light from the block lights around the surface, already multiplied by the path throughput. Picks among a few
 * candidate lights by how much each would contribute unshadowed (resampled importance sampling), then traces one
 * shadow ray to the winner.
 */
vec3 sampleBlockLight(vec3 worldPos, vec3 geometricNormal, vec3 shadingNormal, LabPBRMat mat) {
    if (worldUBO.blockLightSampling == 0u) { return vec3(0.0); }
    float diffuseWeight = vptBlockLightDiffuseWeight(mat);
    if (diffuseWeight <= 1e-4) { return vec3(0.0); }

    int slots[27];
    int slotCount = 0;
    ivec3 center = vptSectionOf(worldPos);
    for (int dz = -1; dz <= 1; dz++) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int slot = vptSectionLightSlot(center + ivec3(dx, dy, dz));
                if (slot >= 0) { slots[slotCount++] = slot; }
            }
        }
    }
    if (slotCount == 0) { return vec3(0.0); }

    vec3 diffuse = mat.albedo * (diffuseWeight / PI);
    vec3 cameraPos = vec3(worldUBO.cameraPos.xyz);
    float weightSum = 0.0;
    float chosenTarget = 0.0;
    vec3 chosenContribution = vec3(0.0);
    vec3 chosenDir = vec3(0.0);
    float chosenDistance = 0.0;

    for (int i = 0; i < VPT_BLOCK_LIGHT_CANDIDATES; i++) {
        int slot = slots[min(int(rand(mainRay.seed) * float(slotCount)), slotCount - 1)];
        VptChunkLightData section = vptChunkLights[slot];
        uint lightIndex = min(uint(rand(mainRay.seed) * float(section.lightCount)), section.lightCount - 1u);
        VptPackedLight light = VptPackedLightBuffer(section.lightBufferAddress).lights[lightIndex];

        float area = light.p0Area.w;
        if (area <= 1e-8) { continue; }
        vec3 point = vptSampleTrianglePoint(light.p0Area.xyz, light.p1.xyz, light.p2.xyz,
                                            vec2(rand(mainRay.seed), rand(mainRay.seed))) - cameraPos;
        vec3 toLight = point - worldPos;
        float distance2 = dot(toLight, toLight);
        if (distance2 <= 1e-6) { continue; }
        float lightDistance = sqrt(distance2);
        vec3 dir = toLight / lightDistance;
        if (dot(dir, geometricNormal) <= 0.0) { continue; }
        float cosSurface = dot(dir, shadingNormal);
        float cosLight = dot(light.normal.xyz, -dir);
        if (cosSurface <= 0.0 || cosLight <= 0.0) { continue; }

        vec3 contribution = light.radiance.rgb * diffuse * (cosSurface * cosLight / distance2);
        float target = dot(contribution, vec3(0.2126, 0.7152, 0.0722));
        if (target <= 1e-10) { continue; }
        float sourcePdf = 1.0 / (float(slotCount) * float(section.lightCount) * area);
        float weight = target / sourcePdf;
        weightSum += weight;
        if (rand(mainRay.seed) * weightSum < weight) {
            chosenTarget = target;
            chosenContribution = contribution;
            chosenDir = dir;
            chosenDistance = lightDistance;
        }
    }
    if (chosenTarget <= 0.0) { return vec3(0.0); }

    shadowRay.radiance = vec3(0.0);
    shadowRay.throughput = vec3(1.0);
    shadowRay.insideBoat = rayInsideBoat(mainRay) ? 1u : 0u;
    shadowRay.pad0 = BLOCK_LIGHT_QUERY;
    vec3 origin = worldPos + geometricNormal * 0.0002;
    // Stops just short of the light, so the light's own block does not shadow it.
    traceRayEXT(topLevelAS, VPT_SHADOW_RAY_FLAGS, WORLD_MASK | PLAYER_MASK, 0, 0, 0, origin, 0.0001, chosenDir,
                max(chosenDistance - 0.02, 0.0002), 1);
    shadowRay.pad0 = 0u;
    vec3 visibility = shadowRay.radiance * shadowRay.throughput;

    vec3 estimate = chosenContribution / chosenTarget * (weightSum / float(VPT_BLOCK_LIGHT_CANDIDATES));
    return VPT_INDIRECT_LIGHT_STRENGTH * worldUBO.emissionBrightness * estimate * visibility * mainRay.throughput;
}

/**
 * Light from whatever the player holds (worldUBO.heldLight*): a small spherical light near the player, shadowed
 * by the world but not by the player's own model or hands. Its reach fades the light out smoothly instead of
 * letting the inverse square law carry it across the whole scene. Already multiplied by the path throughput.
 */
vec3 sampleHeldLight(vec3 worldPos, vec3 geometricNormal, vec3 shadingNormal, LabPBRMat mat) {
    if (worldUBO.heldLightColor.w <= 0.0) { return vec3(0.0); }
    float diffuseWeight = vptBlockLightDiffuseWeight(mat);
    if (diffuseWeight <= 1e-4) { return vec3(0.0); }

    float reach = worldUBO.heldLightPos.w;
    // A 0.1 block sphere: soft enough shadow edges without looking like a big lamp.
    vec3 jitter = normalize(vec3(rand(mainRay.seed), rand(mainRay.seed), rand(mainRay.seed)) * 2.0 - 1.0 + 1e-4);
    vec3 lightPos = worldUBO.heldLightPos.xyz + jitter * 0.1;
    vec3 toLight = lightPos - worldPos;
    float distance2 = max(dot(toLight, toLight), 0.04);
    float lightDistance = sqrt(distance2);
    if (lightDistance >= reach) { return vec3(0.0); }
    vec3 dir = toLight / lightDistance;
    if (dot(dir, geometricNormal) <= 0.0) { return vec3(0.0); }
    float cosSurface = dot(dir, shadingNormal);
    if (cosSurface <= 0.0) { return vec3(0.0); }

    float fade = 1.0 - pow(lightDistance / reach, 4.0);
    fade *= fade;
    vec3 contribution =
        worldUBO.heldLightColor.rgb * mat.albedo * (diffuseWeight / PI) * cosSurface * fade / distance2;
    if (dot(contribution, vec3(1.0)) <= 1e-8) { return vec3(0.0); }

    shadowRay.radiance = vec3(0.0);
    shadowRay.throughput = vec3(1.0);
    shadowRay.insideBoat = rayInsideBoat(mainRay) ? 1u : 0u;
    shadowRay.pad0 = BLOCK_LIGHT_QUERY;
    vec3 origin = worldPos + geometricNormal * 0.0002;
    traceRayEXT(topLevelAS, VPT_SHADOW_RAY_FLAGS, WORLD_MASK, 0, 0, 0, origin, 0.0001, dir,
                max(lightDistance - 0.02, 0.0002), 1);
    shadowRay.pad0 = 0u;
    vec3 visibility = shadowRay.radiance * shadowRay.throughput;

    return VPT_INDIRECT_LIGHT_STRENGTH * worldUBO.heldLightBrightness * contribution * visibility * mainRay.throughput;
}

#endif
