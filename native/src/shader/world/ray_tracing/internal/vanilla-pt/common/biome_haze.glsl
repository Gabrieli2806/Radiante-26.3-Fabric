#ifndef VPT_BIOME_HAZE_GLSL
#define VPT_BIOME_HAZE_GLSL

// Needs skyUBO, worldUBO, skyFull, the celestial and volumetric cloud helpers, biome_fog_light.glsl and
// bedrock_atmosphere.glsl first.

#ifndef VPT_BIOME_FOG_SCATTERING
#    define VPT_BIOME_FOG_SCATTERING 0.4
#endif
#ifndef VPT_BIOME_FOG_FORWARD
#    define VPT_BIOME_FOG_FORWARD 0.75
#endif

// Per-biome haze (skyUBO.biomeFog: a = extinction per block, 0 when off). In the overworld rgb is a tint and the
// haze is lit by the sky along the view's horizon, thickest at the horizon and clear at the zenith. The Nether and
// the End have no sky to light it, so there rgb is the haze's own colour, worked out on the Java side from the
// biome's vanilla fog colour.
const float BIOME_HAZE_MAX_DISTANCE = 320.0;

// How thick the haze is at a world height: full below biomeFogHeights.x, thinning to nothing at .y. Bedrock RTX fogs
// settle in the valleys this way; Radiante's own haze puts both heights far above the world.
float biomeFogHeightProfile(float worldY) {
    vec2 heights = skyUBO.biomeFogHeights.xy;
    return clamp((heights.y - worldY) / max(heights.y - heights.x, 1.0), 0.0, 1.0);
}

// Extinction per channel relative to biomeFog.a: a fog that absorbs more blue than red turns the distance warm.
vec3 biomeFogChroma() {
    return max(skyUBO.biomeFogChroma.rgb, vec3(0.0));
}

// How far a sky ray runs through the biome fog: up to the height where it thins out to nothing (biomeFogHeights.y),
// as Bedrock RTX fogs the sky itself, so a low sun sits in a pale, hazy sky and only a steep look up stays clear.
// Along the horizon the fog reaches out to BIOME_HAZE_SKY_DISTANCE.
const float BIOME_HAZE_SKY_DISTANCE = 1200.0;

float biomeFogSkyReach(vec3 rayDir) {
    // Radiante's own haze has no top: long at the horizon, short towards the zenith.
    if (skyUBO.biomeFogHeights.y > 50000.0) {
        return BIOME_HAZE_MAX_DISTANCE * (1.0 - smoothstep(0.0, 0.45, rayDir.y));
    }
    float above = max(skyUBO.biomeFogHeights.y - float(worldUBO.cameraPos.y), 0.0);
    // A camera ray pointing down that still ends in the sky came off water or a mirror: the sky it shows lies
    // above, as far up as the ray pointed down.
    float rise = abs(rayDir.y);
    return rise > 1e-3 ? min(above / rise, BIOME_HAZE_SKY_DISTANCE) : BIOME_HAZE_SKY_DISTANCE;
}

bool biomeHazeActive() {
#if defined(VPT_BEDROCK_FOG) && VPT_BEDROCK_FOG != 0
    // Bedrock's volumetric fog (froxel_fog.glsl) is the only fog of the air.
    return false;
#endif
    return skyUBO.biomeFog.a > 0.0 && skyUBO.cameraSubmersionType == 3 && skyUBO.hasBlindnessOrDarkness == 0;
}

void biomeHaze(vec3 rayDir, bool hitSky, float hitDistance, out float transmittance, out vec3 additive) {
    bool overworld = worldUBO.skyType == 1;
    float skyDistance = overworld ? biomeFogSkyReach(rayDir) : BIOME_HAZE_MAX_DISTANCE;
    float hazeDistance = hitSky ? skyDistance : min(hitDistance, BIOME_HAZE_MAX_DISTANCE);
    float density = skyUBO.biomeFog.a * biomeFogHeightProfile(float(worldUBO.cameraPos.y));
    // Per channel, as the biome's fog absorbs (a desert's haze takes more blue than red, so the distance turns warm);
    // handed on as one transmittance for the picture behind, and the colour it keeps goes into what the fog adds.
    vec3 chroma = biomeFogChroma();
    if (dot(chroma, chroma) <= 0.0) { chroma = vec3(1.0); }
    vec3 transmittance3 = exp(-density * chroma * max(hazeDistance, 0.0));
    transmittance = dot(transmittance3, vec3(0.2126, 0.7152, 0.0722));
    vec3 fogAmount = 1.0 - transmittance3;

    vec3 hazeRadiance = skyUBO.biomeFog.rgb;
    if (overworld) {
        // The fog's scattering colour (its albedo): a swamp's or a dark forest's fog mostly absorbs and reads as a
        // dim, coloured murk, a desert's scatters warm, a plain's is near white. Drawn as sky-lit grey, every biome's
        // fog was the same bright white haze. Lifted a little (power 0.6): the darkest albedos read as near black.
        vec3 albedo = pow(clamp(skyUBO.biomeFog.rgb, 0.0, 1.5), vec3(0.6));
        vec3 horizonDir = normalize(vec3(rayDir.x, 0.12, rayDir.z));
        vec3 skyLight = texture(skyFull, horizonDir).rgb;
        float skyLuma = dot(skyLight, vec3(0.2126, 0.7152, 0.0722));
        hazeRadiance = mix(vec3(skyLuma), skyLight, 0.5) * albedo;
        // The sun or moon lights the haze too, most of all looking towards it: the pale glow a low sun sits in
        // through Bedrock RTX's fog. Unshadowed; the volumetric mode traces that.
        vec3 lightDir = celestialSunDirection();
        if (lightDir.y < 0.0) { lightDir = -lightDir; }
        float cosTheta = clamp(dot(rayDir, lightDir), -1.0, 1.0);
        float g = clamp(VPT_BIOME_FOG_FORWARD, 0.0, 0.95);
        float gg = g * g;
        float denom = max(1.0 + gg - 2.0 * g * cosTheta, 1e-4);
        float phase = mix(INV_4_PI, min((1.0 - gg) * INV_4_PI / (denom * sqrt(denom)), 4.0), 0.6);
        vec3 light = volumetricCloudPrimaryLightRadiance() * (1.0 - skyUBO.rainGradient);
        light *= (bedrockAtmosphereActive() ?
                      bedrockSunlight(lightDir.y) :
                      sampleCloudAtmosphereTransmittance(VPT_ATMOSPHERE_RG + float(worldUBO.cameraPos.y) + 70.0, lightDir.y)) *
                 biomeFogLightTransmittance(float(worldUBO.cameraPos.y), lightDir);
        hazeRadiance += light * phase * max(VPT_BIOME_FOG_SCATTERING, 0.0) * albedo;
    }
    additive = hazeRadiance * fogAmount;
}

#endif
