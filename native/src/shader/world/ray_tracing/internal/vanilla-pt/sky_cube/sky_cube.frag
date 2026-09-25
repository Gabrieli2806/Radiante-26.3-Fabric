#version 460
#extension GL_GOOGLE_include_directive : require

#include "common/shared.hpp"

layout(set = 2, binding = 0) uniform WorldUniform {
    WorldUBO worldUBO;
};

layout(set = 2, binding = 2) uniform SkyUniform {
    SkyUBO skyUBO;
};

#include "common/celestial.glsl"

layout(set = 5, binding = 0) uniform sampler2D transLUT;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 outColor;

bool intersectSphere(vec3 rayOrigin, vec3 rayDir, float radius, out float tNear, out float tFar) {
    float b = dot(rayOrigin, rayDir);
    float c = dot(rayOrigin, rayOrigin) - radius * radius;
    float height = b * b - c;
    if (height < 0.0) return false;
    height = sqrt(height);
    tNear = -b - height;
    tFar = -b + height;
    return true;
}

vec2 transmittanceUv(float r, float mu) {
    float u = clamp(mu * 0.5 + 0.5, 0.0, 1.0);
    float v = clamp((r - VPT_ATMOSPHERE_RG) / (VPT_ATMOSPHERE_RT - VPT_ATMOSPHERE_RG), 0.0, 1.0);
    return vec2(u, v);
}

float densityExp(float height, float scaleHeight) {
    return exp(-max(height, 0.0) / scaleHeight);
}

vec3 sampleTransmittance(float r, float mu) {
    vec2 uv = transmittanceUv(r, mu);
    vec2 invSize = 1.0 / vec2(textureSize(transLUT, 0));
    uv = clamp(uv, 0.5 * invSize, 1.0 - 0.5 * invSize);
    return texture(transLUT, uv).rgb;
}

// Scattering of one light across the sky. mieScale weighs the aerosol (Mie) part, which is what draws the bright halo
// around a light: the full sun has it, the moon only faintly, as a real moon does.
vec3 integrateSingleScattering(vec3 rayOrigin, vec3 rayDir, vec3 lightDir, vec3 lightRadiance, float mieScale) {
    float tAtm0, tAtm1;
    if (!intersectSphere(rayOrigin, rayDir, VPT_ATMOSPHERE_RT, tAtm0, tAtm1)) return vec3(0.0);
    tAtm0 = max(tAtm0, 0.0);

    float tG0, tG1;
    if (intersectSphere(rayOrigin, rayDir, VPT_ATMOSPHERE_RG, tG0, tG1)) {
        float tHitG = tG0 > 0.0 ? tG0 : tG1;
        if (tHitG > 0.0) tAtm1 = min(tAtm1, tHitG);
    }

    const int steps = 32;
    float dt = (tAtm1 - tAtm0) / float(steps);

    vec3 scatteredRadiance = vec3(0.0);
    vec3 viewTransmittance = vec3(1.0);

    float cosTheta = dot(lightDir, rayDir);
    float rayleighPhase = 3.0 / (16.0 * PI) * (1.0 + cosTheta * cosTheta);
    float clampedCosTheta = clamp(cosTheta, -1.0, 1.0);
    // Held to a soft forward lobe: at the configured anisotropy (0.8) the aerosols drew a bright white disc around the
    // sun several times its size, so the sun read as a blurry ball instead of the square sprite. A broad lobe still
    // warms the sky towards the sun without drawing a halo.
    float mieG = clamp(min(VPT_ATMOSPHERE_MIE_G, 0.45), -0.999, 0.999);
    float mieG2 = mieG * mieG;
    float mieDenominatorBase = 1.0 + mieG2 - 2.0 * mieG * clampedCosTheta;
    float mieDenominator = pow(mieDenominatorBase + 1e-6, 1.5);
    float miePhase = (1.0 - mieG2) / (4.0 * PI * mieDenominator);

    for (int i = 0; i < steps; i++) {
        float t = tAtm0 + (float(i) + 0.5) * dt;
        vec3 x = rayOrigin + rayDir * t;

        float r = length(x);
        float height = r - VPT_ATMOSPHERE_RG;

        float dR = densityExp(height, VPT_ATMOSPHERE_HR);
        float dM = densityExp(height, VPT_ATMOSPHERE_HM);

        vec3 sigmaSR = VPT_ATMOSPHERE_BETA_R * dR;
        vec3 sigmaSM = VPT_ATMOSPHERE_BETA_M * dM;
        vec3 sigmaT = sigmaSR + sigmaSM;

        vec3 up = x / r;
        float muS = dot(up, lightDir);
        vec3 lightTransmittance = sampleTransmittance(r, muS);

        vec3 scattering = sigmaSR * rayleighPhase + sigmaSM * miePhase * mieScale;
        vec3 scatteredSample = viewTransmittance * (lightTransmittance * (scattering * lightRadiance)) * dt;

        scatteredRadiance += scatteredSample;
        viewTransmittance *= exp(-sigmaT * dt);
    }

    return scatteredRadiance;
}

void main() {
    vec2 cubeUv = texCoord * 2.0 - 1.0;
    vec3 rayDir = normalize(vec3(-cubeUv.x, -cubeUv.y, -1));
    if (FACE == 0) {
        rayDir = normalize(vec3(1, -cubeUv.y, -cubeUv.x));
    } else if (FACE == 1) {
        rayDir = normalize(vec3(-1, -cubeUv.y, cubeUv.x));
    } else if (FACE == 2) {
        rayDir = normalize(vec3(cubeUv.x, 1, cubeUv.y));
    } else if (FACE == 3) {
        rayDir = normalize(vec3(cubeUv.x, -1, -cubeUv.y));
    } else if (FACE == 4) {
        rayDir = normalize(vec3(cubeUv.x, -cubeUv.y, 1));
    }
    float blend = smoothstep(-0.05, 0.02, rayDir.y);
    rayDir.y = max(rayDir.y, VPT_ATMOSPHERE_MIN_VIEW_COS);
    rayDir = normalize(mix(vec3(rayDir.x, VPT_ATMOSPHERE_MIN_VIEW_COS, rayDir.z), rayDir, blend));
    float cameraHeight = worldUBO.cameraViewMatInv[3].y;
    vec3 rayOrigin = vec3(0.0, VPT_ATMOSPHERE_RG + cameraHeight + 70.0, 0.0);

    vec3 sunDir = celestialSunDirection();
    vec3 moonDir = celestialMoonDirection();
    vec3 sunRadiance = VPT_SUN_RADIANCE * worldUBO.sunBrightness;
    vec3 moonRadiance = VPT_MOON_RADIANCE * worldUBO.moonBrightness;

    // Twilight. Single scattering alone goes dark the moment the sun is below the horizon, while the moon is still
    // too low to light the sky: for a while the sky went grey-black, darker than the middle of the night, before
    // turning night blue. Light that has gone round the curve of the Earth keeps lighting the sky after sunset, as in
    // Bedrock RTX: the sun is held at the horizon and fades out as it sinks, leaving the afterglow and the blue hour.
    float twilight = 1.0;
    if (sunDir.y < 0.0) {
        twilight = smoothstep(-0.32, 0.0, sunDir.y);
        twilight *= twilight;
        sunDir = normalize(vec3(sunDir.x, 0.0, sunDir.z) + vec3(0.0, 0.015, 0.0));
    }
    if (moonDir.y < 0.0) {
        float moonTwilight = smoothstep(-0.2, 0.0, moonDir.y);
        moonRadiance *= moonTwilight * moonTwilight;
        moonDir = normalize(vec3(moonDir.x, 0.0, moonDir.z) + vec3(0.0, 0.015, 0.0));
    }

    vec3 scatteredRadiance = vec3(0.0);
    if (twilight > 1e-4) {
        scatteredRadiance += integrateSingleScattering(rayOrigin, rayDir, sunDir, sunRadiance * twilight, 1.0);
    }
    if (max(moonRadiance.r, max(moonRadiance.g, moonRadiance.b)) > 1e-5) {
        scatteredRadiance += integrateSingleScattering(rayOrigin, rayDir, moonDir, moonRadiance, 0.05);
    }
    outColor = vec4(scatteredRadiance, 1.0);
}
