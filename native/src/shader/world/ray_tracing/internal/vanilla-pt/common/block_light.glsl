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
#ifndef VPT_CACHE_DEEP_BOUNCES
#    define VPT_CACHE_DEEP_BOUNCES 0
#endif
#ifndef VPT_BLOCK_LIGHT_LEAN_CANDIDATES
#    define VPT_BLOCK_LIGHT_LEAN_CANDIDATES 2
#endif

#include "common/block_light_data.glsl"

/**
 * A light the ray just hit that the surface it came from already sampled directly. Its emission is left out here,
 * or the light would be counted twice.
 */
bool vptBlockLightAlreadyCounted(vec3 originPos, vec3 hitPos) {
    return worldUBO.blockLightSampling != 0u && rayBlockLightSampled(mainRay) &&
           (rayBlockLightLean(mainRay) ? vptInNearBlockLightReach(originPos, hitPos) :
                                         vptInBlockLightReach(originPos, hitPos));
}

/** The diffuse share of a surface's reflection; the only part lit by the sampled lights. */
float vptBlockLightDiffuseWeight(LabPBRMat mat) {
    return (1.0 - mat.metallic) * (1.0 - mat.transmission);
}

/**
 * Light from the block lights around the surface, already multiplied by the path throughput. Picks among a few
 * candidate lights by how much each would contribute unshadowed (resampled importance sampling), then traces one
 * shadow ray to the winner.
 */
vec3 sampleBlockLight(vec3 worldPos, vec3 geometricNormal, vec3 shadingNormal, LabPBRMat mat, bool lean) {
    if (worldUBO.blockLightSampling == 0u) { return vec3(0.0); }
    float diffuseWeight = vptBlockLightDiffuseWeight(mat);
    if (diffuseWeight <= 1e-4) { return vec3(0.0); }

    // Bounced light (lean) looks at the eight sections nearest the point and fewer candidates: it only feeds the
    // indirect light, which averages a great deal anyway, and with hundreds of lights around (a city of neon) the
    // full search on every bounce was the largest single cost of a frame.
    int slots[28];
    int slotCount = lean ? vptGatherNearLightSlots(worldPos, slots) : vptGatherLightSlots(worldPos, slots);
    if (slotCount == 0) { return vec3(0.0); }
    int candidates = lean ? VPT_BLOCK_LIGHT_LEAN_CANDIDATES : VPT_BLOCK_LIGHT_CANDIDATES;

    vec3 diffuse = mat.albedo * (diffuseWeight / PI);
    vec3 cameraPos = vec3(worldUBO.cameraPos.xyz);
    float weightSum = 0.0;
    float chosenTarget = 0.0;
    vec3 chosenContribution = vec3(0.0);
    vec3 chosenDir = vec3(0.0);
    float chosenDistance = 0.0;

    for (int i = 0; i < candidates; i++) {
        int slot;
        uint lightIndex;
        float choices;
        bool picked;
        VPT_PICK_LIGHT(slots, slotCount, mainRay.seed, slot, lightIndex, choices, picked)
        if (!picked) { continue; }
        VptPackedLight light = VptPackedLightBuffer(vptSlotLightAddress(slot)).lights[lightIndex];

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
        float sourcePdf = 1.0 / (choices * area);
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

    vec3 estimate = chosenContribution / chosenTarget * (weightSum / float(candidates));
    return VPT_INDIRECT_LIGHT_STRENGTH * worldUBO.emissionBrightness * estimate * visibility * mainRay.throughput;
}

// ---------------------------------------------------------------------------------------------------------------------
// ReSTIR DI for block lights (spatiotemporal reservoir reuse), on the surface each pixel sees first.
//
// The plain sampler above looks at VPT_BLOCK_LIGHT_CANDIDATES random lights per pixel and frame. ReSTIR keeps the
// winner of each pixel in a reservoir and, next frame, merges it with the reservoirs of the same surface (found by
// reprojection) and of a few neighbours around it. After a few frames every pixel has effectively weighed hundreds of
// lights, so rooms full of torches, lanterns and glowstone settle far faster and with much less noise.
//
// Reservoirs keep which light was picked (its section slot and index in the light list, and where on it), never a
// copy of it: a reused sample is looked up again, so a light that was broken or moved simply stops being chosen.
// Each keeps its unbiased contribution weight W, the number of candidates M it stands for, and the surface it was
// made for (distance and normal), to refuse reuse across edges. Two halves ping-pong by frame parity.
// ---------------------------------------------------------------------------------------------------------------------

#ifndef VPT_RESTIR
#    define VPT_RESTIR 0
#endif

#if VPT_RESTIR != 0

#ifndef VPT_RESTIR_BINDING
#    define VPT_RESTIR_BINDING 39
#endif
#define VPT_RESTIR_SPATIAL_SAMPLES 3
#define VPT_RESTIR_SPATIAL_RADIUS 12.0
// Reuse makes up for fewer fresh candidates per frame, which is where ReSTIR saves time over the plain sampler.
#define VPT_RESTIR_CANDIDATES 2
#define VPT_RESTIR_MAX_M (20.0 * float(VPT_RESTIR_CANDIDATES))

layout(set = 5, binding = VPT_RESTIR_BINDING, rgba32f) uniform image2DArray vptRestirImage;
layout(set = 2, binding = 1) uniform VptLastWorldUniform {
    WorldUBO vptLastWorldUBO;
};

struct VptReservoir {
    int slot;       // -1: empty
    uint index;
    vec2 xi;        // where on the light's triangle
    float wSum;
    float M;
    float W;
    float target;   // p-hat of the chosen sample at the current surface
};

VptReservoir vptEmptyReservoir() {
    VptReservoir r;
    r.slot = -1;
    r.index = 0u;
    r.xi = vec2(0.0);
    r.wSum = 0.0;
    r.M = 0.0;
    r.W = 0.0;
    r.target = 0.0;
    return r;
}

/** A light sample looked up from the live light list; false when it is gone. */
bool vptRestirLight(int slot, uint index, vec2 xi, out vec3 point, out vec3 normal, out vec3 radiance) {
    point = vec3(0.0);
    normal = vec3(0.0, 1.0, 0.0);
    radiance = vec3(0.0);
    if (slot < 0) { return false; }
    if (slot == VPT_ENTITY_LIGHT_SLOT && !vptHasEntityLights()) { return false; }
    uint64_t address = vptSlotLightAddress(slot);
    if (address == 0ul || index >= vptSlotLightCount(slot)) { return false; }
    VptPackedLight light = VptPackedLightBuffer(address).lights[index];
    if (light.p0Area.w <= 1e-8) { return false; }
    point = vptSampleTrianglePoint(light.p0Area.xyz, light.p1.xyz, light.p2.xyz, xi) - vec3(worldUBO.cameraPos.xyz);
    normal = light.normal.xyz;
    radiance = light.radiance.rgb;
    return true;
}

/** Unshadowed contribution of a light sample at the surface, and its luminance (the target function). */
float vptRestirTarget(vec3 worldPos, vec3 geometricNormal, vec3 shadingNormal, vec3 diffuse, vec3 point,
                      vec3 lightNormal, vec3 radiance, out vec3 contribution, out vec3 dir, out float distance) {
    contribution = vec3(0.0);
    dir = vec3(0.0, 1.0, 0.0);
    distance = 0.0;
    vec3 toLight = point - worldPos;
    float distance2 = dot(toLight, toLight);
    if (distance2 <= 1e-6) { return 0.0; }
    distance = sqrt(distance2);
    dir = toLight / distance;
    if (dot(dir, geometricNormal) <= 0.0) { return 0.0; }
    float cosSurface = dot(dir, shadingNormal);
    float cosLight = dot(lightNormal, -dir);
    if (cosSurface <= 0.0 || cosLight <= 0.0) { return 0.0; }
    contribution = radiance * diffuse * (cosSurface * cosLight / distance2);
    return dot(contribution, vec3(0.2126, 0.7152, 0.0722));
}

/** Streams a reservoir from another pixel or frame into r, re-weighted for this surface. */
void vptRestirMerge(inout VptReservoir r, VptReservoir q, vec3 worldPos, vec3 geometricNormal, vec3 shadingNormal,
                    vec3 diffuse) {
    if (q.slot < 0 || q.M <= 0.0) { return; }
    vec3 point, lightNormal, radiance, contribution, dir;
    float distance;
    float target = 0.0;
    if (vptRestirLight(q.slot, q.index, q.xi, point, lightNormal, radiance)) {
        target = vptRestirTarget(worldPos, geometricNormal, shadingNormal, diffuse, point, lightNormal, radiance,
                                 contribution, dir, distance);
    }
    float weight = target * q.W * q.M;
    r.wSum += weight;
    r.M += q.M;
    if (weight > 0.0 && rand(mainRay.seed) * r.wSum < weight) {
        r.slot = q.slot;
        r.index = q.index;
        r.xi = q.xi;
        r.target = target;
    }
}

VptReservoir vptRestirLoad(ivec2 pixel, uint halfIndex, out float surfaceDistance, out vec3 surfaceNormal) {
    vec4 a = imageLoad(vptRestirImage, ivec3(pixel, int(halfIndex * 2u)));
    vec4 b = imageLoad(vptRestirImage, ivec3(pixel, int(halfIndex * 2u + 1u)));
    VptReservoir r = vptEmptyReservoir();
    surfaceDistance = b.z;
    surfaceNormal = unpackSnorm4x8(floatBitsToUint(b.w)).xyz;
    if (a.x < 0.0 || !(b.y > 0.0) || isnan(b.x) || isinf(b.x)) { return r; }
    r.slot = int(a.x + 0.5);
    r.index = uint(a.y + 0.5);
    r.xi = a.zw;
    r.W = max(b.x, 0.0);
    r.M = b.y;
    return r;
}

void vptRestirStore(ivec2 pixel, uint halfIndex, VptReservoir r, float surfaceDistance, vec3 surfaceNormal) {
    imageStore(vptRestirImage, ivec3(pixel, int(halfIndex * 2u)),
               vec4(r.slot < 0 ? -1.0 : float(r.slot), float(r.index), r.xi));
    imageStore(vptRestirImage, ivec3(pixel, int(halfIndex * 2u + 1u)),
               vec4(r.W, r.M, surfaceDistance, uintBitsToFloat(packSnorm4x8(vec4(surfaceNormal, 0.0)))));
}

/** Whether a stored reservoir was made for (about) this surface. */
bool vptRestirSimilar(float storedDistance, vec3 storedNormal, float expectedDistance, vec3 normal) {
    if (storedDistance <= 0.0) { return false; }
    if (abs(storedDistance - expectedDistance) > 0.1 * expectedDistance + 0.05) { return false; }
    return dot(storedNormal, normal) > 0.9;
}

/** The same light as sampleBlockLight, with reservoirs reused over time and between neighbouring pixels. */
vec3 sampleBlockLightRestir(vec3 worldPos, vec3 geometricNormal, vec3 shadingNormal, LabPBRMat mat) {
    ivec2 pixel = ivec2(gl_LaunchIDEXT.xy);
    ivec2 size = ivec2(gl_LaunchSizeEXT.xy);
    uint writeHalf = worldUBO.frameCounter & 1u;
    uint readHalf = writeHalf ^ 1u;
    float surfaceDistance = length(worldPos);
    vec3 storeNormal = geometricNormal;

    float diffuseWeight = vptBlockLightDiffuseWeight(mat);
    if (worldUBO.blockLightSampling == 0u || diffuseWeight <= 1e-4) {
        vptRestirStore(pixel, writeHalf, vptEmptyReservoir(), surfaceDistance, storeNormal);
        return vec3(0.0);
    }
    vec3 diffuse = mat.albedo * (diffuseWeight / PI);

    // 1. New candidates, as the plain sampler picks them.
    VptReservoir r = vptEmptyReservoir();
    int slots[28];
    int slotCount = vptGatherLightSlots(worldPos, slots);
    if (slotCount > 0) {
        for (int i = 0; i < VPT_RESTIR_CANDIDATES; i++) {
            int slot;
            uint lightIndex;
            float choices;
            bool picked;
            VPT_PICK_LIGHT(slots, slotCount, mainRay.seed, slot, lightIndex, choices, picked)
            vec2 xi = vec2(rand(mainRay.seed), rand(mainRay.seed));
            r.M += 1.0;
            if (!picked) { continue; }
            vec3 point, lightNormal, radiance, contribution, dir;
            float distance;
            if (!vptRestirLight(slot, lightIndex, xi, point, lightNormal, radiance)) { continue; }
            float area = VptPackedLightBuffer(vptSlotLightAddress(slot)).lights[lightIndex].p0Area.w;
            float target = vptRestirTarget(worldPos, geometricNormal, shadingNormal, diffuse, point, lightNormal,
                                           radiance, contribution, dir, distance);
            if (target <= 1e-10) { continue; }
            float sourcePdf = 1.0 / (choices * area);
            float weight = target / sourcePdf;
            r.wSum += weight;
            if (rand(mainRay.seed) * r.wSum < weight) {
                r.slot = slot;
                r.index = lightIndex;
                r.xi = xi;
                r.target = target;
            }
        }
    }
    r.W = r.target > 0.0 ? r.wSum / (r.M * r.target) : 0.0;

    // (No separate visibility ray for the new candidate: the final shadow ray below zeroes the stored weight of an
    // occluded winner, which keeps occluded lights from spreading at half the rays.)
    vec3 chosenPoint, chosenNormal, chosenRadiance, chosenContribution, chosenDir;
    float chosenDistance;

    // 3. Temporal and spatial reuse from the previous frame's reservoirs around where this surface was.
    vec3 cameraDelta = vec3(worldUBO.cameraPos.xyz - vptLastWorldUBO.cameraPos.xyz);
    vec3 previousScenePos = worldPos + cameraDelta;
    vec4 previousClip = vptLastWorldUBO.cameraProjMat * vptLastWorldUBO.cameraEffectedViewMat *
                        vec4(previousScenePos, 1.0);
    float expectedDistance = length(previousScenePos);
    VptReservoir combined = vptEmptyReservoir();
    // r first, with its own weight (target * W * M == its weight sum).
    combined.slot = r.slot;
    combined.index = r.index;
    combined.xi = r.xi;
    combined.target = r.target;
    combined.wSum = r.target * r.W * r.M;
    combined.M = r.M;
    if (previousClip.w > 1e-4) {
        vec2 previousPixel = (previousClip.xy / previousClip.w * 0.5 + 0.5) * vec2(size);
        float cap = VPT_RESTIR_MAX_M;
        for (int s = 0; s <= VPT_RESTIR_SPATIAL_SAMPLES; s++) {
            vec2 offset = vec2(0.0);
            if (s > 0) {
                float angle = rand(mainRay.seed) * 2.0 * PI;
                float radius = sqrt(rand(mainRay.seed)) * VPT_RESTIR_SPATIAL_RADIUS;
                offset = vec2(cos(angle), sin(angle)) * radius;
            }
            ivec2 samplePixel = ivec2(floor(previousPixel + offset));
            if (any(lessThan(samplePixel, ivec2(0))) || any(greaterThanEqual(samplePixel, size))) { continue; }
            float storedDistance;
            vec3 storedNormal;
            VptReservoir q = vptRestirLoad(samplePixel, readHalf, storedDistance, storedNormal);
            if (!vptRestirSimilar(storedDistance, storedNormal, expectedDistance, geometricNormal)) { continue; }
            // The previous frame's own reservoir counts for more than a neighbour's.
            q.M = min(q.M, s == 0 ? cap : cap * 0.25);
            vptRestirMerge(combined, q, worldPos, geometricNormal, shadingNormal, diffuse);
        }
    }
    combined.W = combined.target > 0.0 ? combined.wSum / (combined.M * combined.target) : 0.0;
    combined.M = min(combined.M, VPT_RESTIR_MAX_M);

    // 4. Shade with the winner: one shadow ray (reused from step 2 when the winner did not change).
    vec3 result = vec3(0.0);
    if (combined.target > 0.0 &&
        vptRestirLight(combined.slot, combined.index, combined.xi, chosenPoint, chosenNormal, chosenRadiance)) {
        float target = vptRestirTarget(worldPos, geometricNormal, shadingNormal, diffuse, chosenPoint, chosenNormal,
                                       chosenRadiance, chosenContribution, chosenDir, chosenDistance);
        if (target > 0.0) {
            shadowRay.radiance = vec3(0.0);
            shadowRay.throughput = vec3(1.0);
            shadowRay.insideBoat = rayInsideBoat(mainRay) ? 1u : 0u;
            shadowRay.pad0 = BLOCK_LIGHT_QUERY;
            traceRayEXT(topLevelAS, VPT_SHADOW_RAY_FLAGS, WORLD_MASK | PLAYER_MASK, 0, 0, 0,
                        worldPos + geometricNormal * 0.0002, 0.0001, chosenDir, max(chosenDistance - 0.02, 0.0002),
                        1);
            shadowRay.pad0 = 0u;
            vec3 visibility = shadowRay.radiance * shadowRay.throughput;
            if (dot(visibility, vec3(1.0)) <= 0.0) { combined.W = 0.0; }
            result = chosenContribution * combined.W * visibility;
        }
    }
    vptRestirStore(pixel, writeHalf, combined, surfaceDistance, storeNormal);
    return VPT_INDIRECT_LIGHT_STRENGTH * worldUBO.emissionBrightness * result * mainRay.throughput;
}

#endif

/** Whether this call shades the surface its pixel sees first, where ReSTIR runs. */
vec3 sampleBlockLightAt(vec3 worldPos, vec3 geometricNormal, vec3 shadingNormal, LabPBRMat mat, bool primary) {
#if VPT_RESTIR != 0
    if (primary && mat.transmission <= 0.0) { return sampleBlockLightRestir(worldPos, geometricNormal, shadingNormal, mat); }
#endif
    return sampleBlockLight(worldPos, geometricNormal, shadingNormal, mat, !primary);
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
