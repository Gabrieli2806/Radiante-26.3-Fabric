#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_GOOGLE_include_directive : require
#extension GL_EXT_nonuniform_qualifier : require
#extension GL_EXT_buffer_reference2 : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require

#include "util/disney.glsl"
#include "util/random.glsl"
#include "util/ray.glsl"
#include "util/util.glsl"
#include "common/shared.hpp"

layout(set = 0, binding = 0) uniform sampler2D textures[];
layout(set = 5, binding = 2) uniform samplerCube skyFull;

layout(set = 1, binding = 0) uniform accelerationStructureEXT topLevelAS;

layout(set = 1, binding = 1) readonly buffer BLASOffsets {
    uint offsets[];
}
blasOffsets;

layout(set = 1, binding = 2) readonly buffer IndexBufferAddr {
    uint64_t addrs[];
}
indexBufferAddrs;

layout(set = 1, binding = 3) readonly buffer LastIndexBufferAddr {
    uint64_t addrs[];
}
lastIndexBufferAddrs;

layout(set = 1, binding = 8) readonly buffer LastObjToWorldMat {
    mat4 mat[];
}
lastObjToWorldMats;

layout(set = 2, binding = 0) uniform WorldUniform {
    WorldUBO worldUBO;
};

layout(set = 2, binding = 1) uniform LastWorldUniform {
    WorldUBO lastWorldUbo;
};

layout(set = 2, binding = 2) uniform SkyUniform {
    SkyUBO skyUBO;
};

#include "common/celestial.glsl"

layout(set = 3, binding = 1, rgba8) uniform image2D diffuseAlbedoImage;
layout(set = 3, binding = 2, rgba8) uniform image2D specularAlbedoImage;
layout(set = 3, binding = 3, rgba16f) uniform image2D normalRoughnessImage;
layout(set = 3, binding = 4, rg16f) uniform image2D motionVectorImage;
layout(set = 3, binding = 5, r16f) uniform image2D linearDepthImage;

layout(std430, buffer_reference, buffer_reference_align = 8) readonly buffer IndexBuffer {
    uint indices[];
}
indexBuffer;

#include "util/vertex.glsl"

layout(location = 0) rayPayloadInEXT MainRay mainRay;
layout(location = 1) rayPayloadEXT ShadowRay shadowRay;
hitAttributeEXT vec2 attribs;

// Share of the light behind a cloud it hides. Bedrock's clouds are translucent rather than solid white blocks; a
// ray entering a cloud takes this much of the cloud's colour and carries on through it with the rest.
#ifndef VPT_VANILLA_CLOUD_OPACITY
#    define VPT_VANILLA_CLOUD_OPACITY 0.6
#endif
#ifndef VPT_VANILLA_CLOUD_SKY_LIGHT
#    define VPT_VANILLA_CLOUD_SKY_LIGHT 1.6
#endif

void main() {
    vec3 viewDir = -mainRay.direction;

    uint instanceID = gl_InstanceCustomIndexEXT;
    uint geometryID = gl_GeometryIndexEXT;

    uint geometryBufferIndex = getGeometryBufferIndex(instanceID, geometryID);

    uint i0;
    uint i1;
    uint i2;
    loadTriangleIndices(geometryBufferIndex, gl_PrimitiveID, i0, i1, i2);

    MaterialVertex m0;
    MaterialVertex m1;
    MaterialVertex m2;
    loadTriangleMaterial(geometryBufferIndex, i0, i1, i2, m0, m1, m2);

    vec3 baryCoords = vec3(1.0 - (attribs.x + attribs.y), attribs.x, attribs.y);
    vec3 worldPos = gl_WorldRayOriginEXT + gl_WorldRayDirectionEXT * gl_HitTEXT;
    uint coordinate = getCoordinate(m0.packedData);
    vec3 normal = baryCoords.x * m0.norm + baryCoords.y * m1.norm + baryCoords.z * m2.norm;
    if (coordinate == 1) {
        normal = normalize(mat3(worldUBO.cameraViewMatInv) * normal);
    } else {
        normal = normalize(normal);
    }
    bool useColorLayer = hasColorLayer(m0.packedData);
    vec3 colorLayer;
    if (useColorLayer) {
        colorLayer = (baryCoords.x * m0.colorLayer + baryCoords.y * m1.colorLayer + baryCoords.z * m2.colorLayer).rgb;
    } else {
        colorLayer = vec3(1.0);
    }

    bool useTexture = hasTexture(m0.packedData);
    vec2 textureUV = baryCoords.x * m0.textureUV + baryCoords.y * m1.textureUV + baryCoords.z * m2.textureUV;
    uint textureID = m0.textureID;

    vec4 albedoSample = vec4(1.0);
    if (useTexture) { albedoSample = sampleTexture(textures[nonuniformEXT(textureID)], textureUV, false); }

    vec3 albedo = albedoSample.rgb;
    float alpha = albedoSample.a;
    // Vanilla darkens the cloud colour itself at dusk and at night, to stand in for the light it cannot compute.
    // Here the light does that, so only the colour's hue is kept: darkened twice, clouds turned into black
    // silhouettes against the afterglow.
    float colorPeak = max(colorLayer.r, max(colorLayer.g, colorLayer.b));
    vec3 tint = albedo * (colorPeak > 1e-3 ? colorLayer / colorPeak : vec3(1.0));

    LabPBRMat mat;
    mat.albedo = tint;
    mat.f0 = vec3(0.04);
    mat.roughness = 1.0;
    mat.metallic = 0.0;
    mat.subSurface = 0.0;
    mat.transmission = 0.0;
    mat.ior = 1.5;
    mat.emission = 0.0;

    mainRay.hitT = gl_HitTEXT;

    vec3 rayOrigin = worldPos;

    // shadow ray for direct lighting
    vec3 sunDir = celestialSunDirection();
    vec3 sampledLightDir = sunDir;
    if (sampledLightDir.y < 0) { sampledLightDir = -sampledLightDir; }

    // Clouds are mostly viewed from below; keep underside lit via two-sided + backlit response.
    float ndotl = dot(normal, sampledLightDir);
    float ndotv = dot(normal, viewDir);
    float frontLit = max(ndotl, 0.0);
    float wrappedLit = clamp((abs(ndotl) + 0.4) / 1.4, 0.0, 1.0);
    float backLit = max(-ndotl, 0.0) * max(-ndotv, 0.0);
    float cloudPhase = max(frontLit, max(wrappedLit * 0.6, backLit * 1.35));
    vec3 lightBRDF = tint * (INV_PI * cloudPhase);

    shadowRay.radiance = vec3(0.0);
    shadowRay.throughput = vec3(1.0);
    shadowRay.insideBoat = 0u;
    shadowRay.pad0 = 0u;
    traceRayEXT(topLevelAS, VPT_SHADOW_RAY_FLAGS,
                WORLD_MASK, // masks
                0,          // sbtRecordOffset
                0,          // sbtRecordStride
                0,          // missIndex
                rayOrigin, 0.001, sampledLightDir, 1000, 1);

    vec3 lightContribution = shadowRay.radiance;
    bool leaving = dot(normal, mainRay.direction) > 0.0;

    float progress = skyUBO.rainGradient;
    vec3 lightRadiance = lightContribution * mainRay.throughput * lightBRDF;
    lightRadiance *= alpha * 0.65;
    
    float dayFactor = smoothstep(-0.3, 0.3, sunDir.y);
    vec3 skyAmbient = texture(skyFull, normalize(viewDir)).rgb;
    // Light a cloud scatters from the whole sky, not just the sun: what keeps an overcast underside pale grey
    // rather than dark, and a translucent cloud reading as white against the blue behind it.
    // The sky above, and the horizon on the sun's side: at sunset and in the blue hour the zenith is already
    // dark while the horizon still glows, and that glow is what lights the underside of clouds orange and pink.
    vec3 skyAbove = texture(skyFull, vec3(0.0, 1.0, 0.0)).rgb;
    vec3 lightHorizon = texture(skyFull, normalize(vec3(sunDir.x, 0.08, sunDir.z))).rgb;
    // Only clouds on the sun's side of the sky catch that glow, and the more so the closer they are to it.
    vec2 cloudSide = normalize(mainRay.direction.xz + vec2(1e-5));
    vec2 sunSide = normalize(sunDir.xz + vec2(1e-5));
    float glowSide = max(dot(cloudSide, sunSide), 0.0);
    // Around sunrise and sunset only: at noon the zenith already lights them, and deep in the night there is no glow.
    float glowTime = smoothstep(-0.35, -0.05, sunDir.y) * (1.0 - smoothstep(0.2, 0.45, sunDir.y));
    float horizonWeight = 0.3 * glowSide * glowSide * glowTime;
    vec3 skyLight = skyAbove * (1.0 - horizonWeight) + lightHorizon * horizonWeight;
    lightRadiance += tint * skyLight * VPT_VANILLA_CLOUD_SKY_LIGHT * mainRay.throughput;
    vec3 rainyRadiance = mix(skyAmbient * 0.12, vec3(0.08), dayFactor);
    vec3 wetCloudRadiance = lightRadiance * mix(0.2, 0.35, dayFactor) + rainyRadiance;
    // Only the face the ray enters through adds the cloud; the one it leaves through just lets it out again.
    if (!leaving) {
        mainRay.radiance += VPT_VANILLA_CLOUD_OPACITY * mix(lightRadiance, wetCloudRadiance, progress);
        mainRay.throughput *= 1.0 - VPT_VANILLA_CLOUD_OPACITY;
    }

    mainRay.hitT = gl_HitTEXT;
    mainRay.normal = vec3(0.0);
    // Stored as fully transmissive so the bounce loop passes through it as it does through glass.
    rayStoreMaterial(mainRay, vec4(tint, 1.0 - VPT_VANILLA_CLOUD_OPACITY), vec3(0.04), 1.0, 0.0, 1.0, 1.0, 0.0);
    raySetNoisy(mainRay, false);
    mainRay.hasPrevScenePos = 0u;
    mainRay.origin = worldPos + mainRay.direction * 0.001;
    mainRay.coneWidth += gl_HitTEXT * mainRay.coneSpread;
    raySetContinue(mainRay, true);
    raySetStop(mainRay, false);
}
