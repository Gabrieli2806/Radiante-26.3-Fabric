#include "common/shared.hpp"
#include "util/ray.glsl"
#include "util/util.glsl"

layout(set = 0, binding = 0) uniform sampler2D textures[];
layout(set = 5, binding = 0) uniform sampler2D transLUT;
layout(set = 5, binding = 2) uniform samplerCube skyFull;

layout(set = 2, binding = 0) uniform WorldUniform {
    WorldUBO worldUBO;
};

layout(set = 2, binding = 1) uniform LastWorldUniform {
    WorldUBO lastWorldUbo;
};

layout(set = 2, binding = 2) uniform SkyUniform {
    SkyUBO skyUBO;
};

layout(location = 0) rayPayloadInEXT MainRay mainRay;

#include "common/volumetric_cloud.glsl"
#include "common/biome_fog_light.glsl"
#include "common/bedrock_atmosphere.glsl"
#include "common/biome_haze.glsl"

bool missIntersectSphere(vec3 rayOrigin, vec3 rayDir, float radius, out float tNear, out float tFar) {
    float b = dot(rayOrigin, rayDir);
    float c = dot(rayOrigin, rayOrigin) - radius * radius;
    float h = b * b - c;
    if (h < 0.0) return false;
    h = sqrt(h);
    tNear = -b - h;
    tFar = -b + h;
    return true;
}

void makeBasis(in vec3 n, out vec3 t, out vec3 b) {
    float s = (n.z >= 0.0) ? 1.0 : -1.0;
    float a = -1.0 / (s + n.z);
    float k = n.x * n.y * a;
    t = vec3(1.0 + s * n.x * n.x * a, s * k, -s * n.x);
    b = vec3(k, s + n.y * n.y * a, -n.y);
    t = normalize(t);
    b = normalize(b);
}

vec4 sampleTextureLod0(sampler2D tex, vec2 uv) {
    ivec2 texSize = textureSize(tex, 0);
    if (texSize.x <= 0 || texSize.y <= 0) return vec4(0.0);
    vec2 halfTexel = 0.5 / vec2(texSize);
    vec2 clampedUv = clamp(uv, halfTexel, vec2(1.0) - halfTexel);
    return sampleTexture(tex, clampedUv, 0.0, false);
}

vec4 sampleSpriteLod0(sampler2D tex, vec2 uv01, vec4 uvRect) {
    // The sun and the moon phases live in one runtime-stitched atlas, so each is a rectangle inside it rather
    // than a texture of its own or a tile of a fixed grid. Half a texel is trimmed off every edge so the sampler
    // cannot bleed in whatever was stitched next door.
    ivec2 texSize = textureSize(tex, 0);
    if (texSize.x <= 0 || texSize.y <= 0) return vec4(0.0);
    if (uvRect.z <= uvRect.x || uvRect.w <= uvRect.y) return vec4(0.0);
    vec2 halfTexel = 0.5 / vec2(texSize);
    vec2 minUv = uvRect.xy + halfTexel;
    vec2 maxUv = uvRect.zw - halfTexel;
    vec2 atlasUv = mix(minUv, maxUv, clamp(uv01, 0.0, 1.0));
    return sampleTexture(tex, atlasUv, 0.0, false);
}

vec4 sampleAtlasLod0(sampler2D tex, vec2 uv01, uvec2 tileCount, uvec2 tile) {
    ivec2 texSize = textureSize(tex, 0);
    if (texSize.x <= 0 || texSize.y <= 0) return vec4(0.0);

    vec2 invTileCount = 1.0 / vec2(tileCount);
    vec2 tileMin = vec2(tile) * invTileCount;
    vec2 tileMax = tileMin + invTileCount;
    vec2 halfTexel = 0.5 / vec2(texSize);
    vec2 minUv = tileMin + halfTexel;
    vec2 maxUv = tileMax - halfTexel;
    vec2 atlasUv = mix(minUv, maxUv, clamp(uv01, 0.0, 1.0));
    return sampleTexture(tex, atlasUv, 0.0, false);
}

// The sun and moon sprites' frame. Vanilla keeps one edge along the axis the sky turns around, so the squares
// never turn as they cross the sky; the free basis turns them with the direction.
void celestialBasis(vec3 dir, out vec3 right, out vec3 up) {
    if (skyUBO.celestialAxis.w > 0.5) {
        right = normalize(skyUBO.celestialAxis.xyz);
        up = normalize(cross(right, celestialSunDirection()));
        return;
    }
    makeBasis(dir, right, up);
}

vec4 evalSunBillboard(vec3 rayDir) {
    vec3 sunDir = celestialSunDirection();
    rayDir = normalize(rayDir);
    float z = dot(rayDir, sunDir);
    if (z <= 0.0) return vec4(0.0);

    vec3 right, up;
    celestialBasis(sunDir, right, up);
    vec2 p = vec2(dot(rayDir, right), dot(rayDir, up));
    vec2 q = p / max(z, 1e-4);
    // Vanilla draws the sun as a quad of half-width 30 at distance 100 (SkyRenderer.renderSun ->
    // applyCelestialBodyTransform(pose, 100, 30)), so the half-angle's tangent is 30/100. The old 0.03 was that
    // value ten times too small, which is why the disc read as a pinprick next to vanilla's.
    float tanHalf = 30.0 / 100.0;
    vec2 a = abs(q);
    if (a.x > tanHalf || a.y > tanHalf) return vec4(0.0);
    vec2 uv = q / tanHalf * 0.5 + 0.5;
    vec4 sun = sampleSpriteLod0(textures[nonuniformEXT(skyUBO.sunTextureID)], uv, skyUBO.sunUvRect);
    // sun.png is a small bright disc inside a faint glow. Vanilla adds the glow on top of the sky, where it stays
    // soft; scaled up by the sun's radiance it became a staircase of square rings. The sky model already draws the
    // glow around the sun, so only the disc itself is kept.
    if (max(sun.r, max(sun.g, sun.b)) < 0.5) return vec4(0.0);
    // The disc's outermost texels are a darker amber than the rest; traced, they read as a hard frame around it.
    // They take the colour of the ring inside them instead, so the edge is the disc's own.
    if (sun.b < 0.4) sun.rgb = vec3(1.0, 1.0, 0.667);
    return sun;
}

/** How much of a halo of the given angular width (radians) and strength reaches `rayDir`; 0 behind the body. */
#ifndef VPT_SKY_LIGHT_BOOST
#    define VPT_SKY_LIGHT_BOOST 1.0
#endif
#ifndef VPT_SUN_GLOW
#    define VPT_SUN_GLOW 0.08
#endif
#ifndef VPT_SUN_GLOW_WIDTH
#    define VPT_SUN_GLOW_WIDTH 0.15
#endif

float celestialHalo(vec3 rayDir, vec3 bodyDir, float width, float strength) {
    float c = dot(normalize(rayDir), bodyDir);
    if (c <= 0.0) return 0.0;
    float angle = acos(clamp(c, -1.0, 1.0));
    return strength * exp(-(angle * angle) / (width * width));
}

vec4 evalMoonBillboard(vec3 rayDir) {
    vec3 moonDir = celestialMoonDirection();
    rayDir = normalize(rayDir);
    float z = dot(rayDir, moonDir);
    if (z <= 0.0) return vec4(0.0);

    vec3 right, up;
    celestialBasis(moonDir, right, up);
    vec2 p = vec2(dot(rayDir, right), dot(rayDir, up));
    vec2 q = p / max(z, 1e-4);
    // Same derivation as the sun, with vanilla's moon half-width of 20 at distance 100.
    float tanHalf = 20.0 / 100.0;
    vec2 a = abs(q);
    if (a.x > tanHalf || a.y > tanHalf) return vec4(0.0);
    // The moon's frame comes from the sun's (celestialBasis), but vanilla draws it on the opposite side of the sky
    // turned half a circle; without this its face was upside down.
    vec2 uv = vec2(1.0) - (q / tanHalf * 0.5 + 0.5);
    vec4 moon = sampleSpriteLod0(textures[nonuniformEXT(skyUBO.moonTextureID)], uv, skyUBO.moonUvRect);
    // As with the sun: the moon texture is a small disc inside a faint square glow, and scaled up to moonlight that
    // glow drew a bright square frame around the moon. The sky model draws its halo; only the disc is kept, and it
    // is lifted so its craters read against the night sky instead of washing out.
    if (max(moon.r, max(moon.g, moon.b)) < 0.25) return vec4(0.0);
    return moon;
}

#ifndef END_VOID_TINT
// Violet of about the brightness of white (luminance near 1); also in world.rgen.
#    define END_VOID_TINT vec3(1.3, 0.8, 1.85)
#endif
// How bright the void looks to the camera against the light it casts; also in world.rgen (the islands' fog).
#ifndef END_VOID_SEEN
#    define END_VOID_SEEN 0.15
#endif

// The End's sky (see main). Its colour is the dimension's fog colour turned violet, as world.rgen fogs the islands,
// so distant ones fade into it seamlessly.
vec3 endSkyRadiance(vec3 dir, bool seen) {
    // Vanilla's End fog is nearly grey; the void reads as violet, as in Bedrock, at the same brightness.
    vec3 fog = dot(max(worldUBO.fogColor.rgb, vec3(0.0)), vec3(0.2126, 0.7152, 0.0722)) * END_VOID_TINT;
    float up = clamp(dir.y, -1.0, 1.0);
    // Brightest along the horizon, a third of that straight up and down.
    vec3 sky = fog * mix(0.35, 1.0, pow(1.0 - abs(up), 3.0));
    // The End flash: a violet glare round its direction and a faint glow over the whole sky while it lasts, seen
    // and lighting the islands alike (they also take its direct light, with shadows: shadow.rmiss).
    float flash = endFlashIntensity();
    if (flash > 0.0) {
        float towards = max(dot(dir, celestialSunDirection()), 0.0);
        sky += END_FLASH_RADIANCE * flash * (0.04 + 0.35 * pow(towards, 8.0) + 3.0 * pow(towards, 400.0));
    }
    if (seen) {
        // Seen, the void is much darker than the light it gives the islands, as in vanilla: a near-black violet
        // the lit end stone stands out against, not a pale haze brighter than the ground.
        sky *= END_VOID_SEEN;
        // Sparse stars, fixed to the sky: one candidate per cell of a grid on the direction.
        vec3 cell = floor(dir * 260.0);
        uvec3 h3 = uvec3(ivec3(cell) + ivec3(1 << 20));
        uint h = h3.x * 73856093u ^ h3.y * 19349663u ^ h3.z * 83492791u;
        h ^= h >> 15u;
        h *= 2246822519u;
        h ^= h >> 13u;
        float r = float(h & 0xFFFFu) / 65535.0;
        if (r > 0.9975) {
            vec3 centre = (cell + 0.5) / 260.0;
            float d = length(dir - normalize(centre)) * 260.0;
            float twinkle = float((h >> 16u) & 0xFFu) / 255.0;
            sky += vec3(0.85, 0.8, 1.0) * (0.6 + 0.8 * twinkle) * smoothstep(0.45, 0.0, d) * 0.08;
        }
    }
    return sky;
}

void main() {
    mainRay.directLightRadiance.x = 1.0;

    if (skyUBO.cameraSubmersionType == 0 || skyUBO.cameraSubmersionType == 2 || skyUBO.fogControls.x >= 0.999) {
        raySetStop(mainRay, true);
        mainRay.hitT = INF_DISTANCE;
        return;
    }

    switch (worldUBO.skyType) {
        case 2:
            // The End: a deep violet void, its own fog colour at the horizon darkening overhead, with faint stars
            // where the camera looks. Rays bouncing off the islands gather it as a dim, cool ambient light, so the
            // end stone is softly lit from above instead of flat.
            mainRay.radiance += endSkyRadiance(normalize(gl_WorldRayDirectionEXT),
                                               rayBounce(mainRay) == 0u || rayCameraPath(mainRay)) *
                                mainRay.throughput;
            raySetStop(mainRay, true);
            mainRay.hitT = INF_DISTANCE;
            return;
        case 0:
            raySetStop(mainRay, true);
            mainRay.hitT = INF_DISTANCE;
            return;
        case 1:
        default: break;
    }

    vec3 rayDir = normalize(gl_WorldRayDirectionEXT);
    vec3 sunDir = celestialSunDirection();
    float progress = skyUBO.rainGradient;
    vec3 rainyRadiance = mix(vec3(0.0), vec3(0.1), smoothstep(-0.3, 0.3, sunDir.y));
    vec3 backgroundRadiance = mix(texture(skyFull, rayDir).rgb, rainyRadiance, progress);

    if (worldUBO.skyType == 1) {
        float cameraHeight = worldUBO.cameraViewMatInv[3].y;
        vec3 pPlanet = vec3(0.0, VPT_ATMOSPHERE_RG + cameraHeight + 70.0, 0.0);
        float r = clamp(length(pPlanet), VPT_ATMOSPHERE_RG, VPT_ATMOSPHERE_RT);
        vec3 up = pPlanet / max(r, 1e-6);
        float mu = clamp(dot(up, rayDir), -1.0, 1.0);
        vec3 transmittance = sampleCloudAtmosphereTransmittance(r, mu);

        vec4 sunSample = evalSunBillboard(rayDir);
        if (sunSample.a > 1e-4) {
            float tG0, tG1;
            bool hitGround = missIntersectSphere(pPlanet, rayDir, VPT_ATMOSPHERE_RG, tG0, tG1);
            bool blocked = hitGround && (tG1 > 1e-3);
            if (!blocked) {
                vec3 sunRadiance = sunSample.rgb * (VPT_SUN_RADIANCE * worldUBO.sunBrightness) * transmittance * sunSample.a;
                backgroundRadiance += mix(sunRadiance, vec3(0.0), progress);
            }
        }

        vec4 moonSample = evalMoonBillboard(rayDir);
        if (moonSample.a > 1e-4) {
            float tG0, tG1;
            bool hitGround = missIntersectSphere(pPlanet, rayDir, VPT_ATMOSPHERE_RG, tG0, tG1);
            bool blocked = hitGround && (tG1 > 1e-3);
            if (!blocked) {
                vec3 moonRadiance = moonSample.rgb * (VPT_MOON_RADIANCE * worldUBO.moonBrightness) * max(transmittance, vec3(0.03));
                backgroundRadiance += mix(moonRadiance, vec3(0.0), progress);
            }
        }
    }

    // A soft halo around the sun and moon, as Bedrock RTX draws: faint, and only where the camera looks, so it
    // adds nothing to the light bounced around the world.
    if (worldUBO.skyType == 1 && (rayBounce(mainRay) == 0u || rayCameraPath(mainRay))) {
        backgroundRadiance += celestialHalo(rayDir, celestialSunDirection(), VPT_SUN_GLOW_WIDTH, VPT_SUN_GLOW) *
                              (VPT_SUN_RADIANCE * worldUBO.sunBrightness) * (1.0 - progress);
        backgroundRadiance += celestialHalo(rayDir, celestialMoonDirection(), 0.10, 0.10) *
                              (VPT_MOON_RADIANCE * worldUBO.moonBrightness) * (1.0 - progress);
    }

    // Sky seen off water, glass or any bounce sits behind the same biome fog as the sky the camera sees; without it
    // reflections showed the clear, dark blue sky above the fog.
    if (worldUBO.skyType == 1 && rayBounce(mainRay) > 0u && !rayCameraPath(mainRay) && biomeHazeActive()) {
        float hazeTransmittance;
        vec3 hazeAdditive;
        biomeHaze(rayDir, true, 0.0, hazeTransmittance, hazeAdditive);
        backgroundRadiance = backgroundRadiance * hazeTransmittance + hazeAdditive;
    }

#if VPT_ALLOW_VOLUMETRIC_CLOUD_MISS
    if (VPT_CLOUD_MODE == 2u) {
        VolumetricCloudResult cloudResult =
            rayUseIndirectVolumetricCloud(mainRay) ?
                applyVolumetricCloudBudgeted(gl_WorldRayOriginEXT, rayDir, backgroundRadiance,
                                             VPT_INDIRECT_VOLUMETRIC_CLOUD_VIEW_STEPS,
                                             VPT_INDIRECT_VOLUMETRIC_CLOUD_LIGHT_STEPS,
                                             VPT_INDIRECT_VOLUMETRIC_CLOUD_AMBIENT_STEPS) :
                applyVolumetricCloud(gl_WorldRayOriginEXT, rayDir, backgroundRadiance);
        backgroundRadiance = cloudResult.color;
        vec3 clampedBackground = max(backgroundRadiance, vec3(0.0));
        vec3 cloudOnly = max(cloudResult.color - clampedBackground * cloudResult.transmittance, vec3(0.0));
        float backgroundLum = volumetricCloudLuminance(clampedBackground);
        float cloudLum = volumetricCloudLuminance(cloudOnly);
        float cloudPresence = cloudLum / max(backgroundLum + cloudLum, 1e-4);
        float starVisibility = min(cloudResult.transmittance, 1.0 - cloudPresence * 1.35);
        mainRay.directLightRadiance.x = cloudSaturate(starVisibility);
    }
#endif

    // The sky lighting the world (every bounce after the first): Bedrock RTX's shade under a building is lit well
    // by the open sky around it, not left in deep shadow.
    // The camera's own ray, seen through glass or a sheet of water, is still the sky the camera sees, not light.
    // Nor is the sky mirrored in a window or on water: boosted like the light, every pane facing the open sky
    // showed it nearly twice as bright as the sky itself, a pale band over whatever was behind the glass.
    if (rayBounce(mainRay) > 0u && !rayCameraPath(mainRay) && worldUBO.skyType == 1 &&
        mainRay.pad0 != RAY_VIEW_BOUNCE_MARK) {
        backgroundRadiance *= max(VPT_SKY_LIGHT_BOOST, 0.0);
    }
    mainRay.radiance += backgroundRadiance * mainRay.throughput;
    raySetStop(mainRay, true);
    mainRay.hitT = INF_DISTANCE;
}
