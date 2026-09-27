#ifndef VPT_BIOME_FOG_LIGHT_GLSL
#define VPT_BIOME_FOG_LIGHT_GLSL

// Sunlight on its way down through a Bedrock pack's biome fog, as Bedrock RTX lights the world through it: a fog that
// absorbs more blue than red turns the light warm, the more so the lower the sun. The path runs to the height the fog
// thins out to nothing, capped (BIOME_FOG_LIGHT_MAX_PATH) so a sun at the horizon still lights; the density along it
// is taken as half the full one, the fog thinning linearly on the way up.
//
// Needs skyUBO and worldUBO to be declared first.

const float BIOME_FOG_LIGHT_MAX_PATH = 256.0;

vec3 biomeFogLightTransmittance(float worldY, vec3 lightDir) {
    float density = skyUBO.biomeFog.a;
    // Only a pack's fog has a top; Radiante's own haze lights nothing.
    if (worldUBO.skyType != 1 || density <= 0.0 || skyUBO.biomeFogHeights.y > 50000.0) { return vec3(1.0); }
    float above = max(skyUBO.biomeFogHeights.y - worldY, 0.0);
    float path = min(above / max(abs(lightDir.y), 1e-3), BIOME_FOG_LIGHT_MAX_PATH);
    return exp(-max(skyUBO.biomeFogChroma.rgb, vec3(0.0)) * density * 0.5 * path);
}

#endif
