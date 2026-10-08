#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_GOOGLE_include_directive : require

#include "util/ray.glsl"
#include "util/util.glsl"
#include "common/shared.hpp"

layout(set = 5, binding = 0) uniform sampler2D transLUT;

layout(set = 2, binding = 0) uniform WorldUniform {
    WorldUBO worldUBO;
};

layout(set = 2, binding = 2) uniform SkyUniform {
    SkyUBO skyUBO;
};

layout(location = 1) rayPayloadInEXT ShadowRay shadowRay;

#include "common/celestial.glsl"
#include "common/bedrock_atmosphere.glsl"
#include "common/biome_fog_light.glsl"

vec2 transmittanceUv(float r, float mu) {
    float u = clamp(mu * 0.5 + 0.5, 0.0, 1.0);
    float v = clamp((r - VPT_ATMOSPHERE_RG) / (VPT_ATMOSPHERE_RT - VPT_ATMOSPHERE_RG), 0.0, 1.0);
    return vec2(u, v);
}

vec3 sampleTransmittance(float r, float mu) {
    vec2 uv = transmittanceUv(r, mu);
    return sampleTexture(transLUT, uv, false).rgb;
}

void main() {
    if (shadowRay.pad0 == GLOW_OUTLINE_QUERY) { return; }
    if (shadowRay.pad0 == BLOCK_LIGHT_QUERY) {
        shadowRay.radiance = vec3(1.0);
        return;
    }
    if (worldUBO.skyType == 2) {
        // The End: lit only by its flash.
        shadowRay.radiance += END_FLASH_RADIANCE * endFlashIntensity() * shadowRay.throughput;
        return;
    }
    vec3 toSun = celestialSunDirection();
    vec3 radiance;

    if (toSun.y > 0) {
        vec3 atmosphereCenter = vec3(0.0, -VPT_ATMOSPHERE_RG, 0.0);
        vec3 pWorld = gl_WorldRayOriginEXT;
        vec3 pPlanet = pWorld - atmosphereCenter;

        float r = length(pPlanet);
        vec3 up = pPlanet / max(r, 1e-6);
        float muSun = dot(up, toSun);

        muSun = clamp(muSun, -1.0, 1.0);
        r = clamp(r, VPT_ATMOSPHERE_RG, VPT_ATMOSPHERE_RT);

        vec3 transmittance = bedrockAtmosphereActive() ? bedrockSunlight(toSun.y) : sampleTransmittance(r, muSun);

        radiance = (VPT_SUN_RADIANCE * worldUBO.sunBrightness) * transmittance;
    } else {
        radiance = (VPT_MOON_RADIANCE * worldUBO.moonBrightness);
    }

    radiance *= biomeFogLightTransmittance(gl_WorldRayOriginEXT.y + float(worldUBO.cameraPos.y), toSun);

    float factor = 1.0;
    float threshold = 0.3;
    // Bedrock's table already fades the sun out towards the horizon.
    if (abs(toSun.y) < threshold && !(bedrockAtmosphereActive() && toSun.y > 0)) {
        factor = sin(PI / (2 * threshold) * abs(toSun.y));
    }
    radiance *= factor;

    shadowRay.radiance += radiance * shadowRay.throughput;
}
