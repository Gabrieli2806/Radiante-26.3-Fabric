#ifndef VPT_BEDROCK_WATER_GLSL
#define VPT_BEDROCK_WATER_GLSL

// Bedrock RTX's water surface (water surface mode "bedrock"), an independent approximation of its observed
// behaviour. Its normal texture is Bedrock's own
// water_n.tga, read from a Bedrock install or radiante/bedrock/ (configs.json, bedrock_water_normals; it cannot ship
// in the pack). Without it the texture is a 1 x 1 stand-in and the surface stays flat.
#ifndef VPT_BEDROCK_WATER_FOOTPRINT_SCALE
#    define VPT_BEDROCK_WATER_FOOTPRINT_SCALE 0.15
#endif

layout(set = 5, binding = 44) uniform sampler2D bedrockWaterNormalTexture;

bool bedrockWaterAvailable() {
    return textureSize(bedrockWaterNormalTexture, 0).x > 1;
}

// The world's clock in seconds, as the wave animation runs on it (twenty game ticks a second).
float bedrockWaterSeconds() {
    return worldUBO.gameTime * 1200.0;
}

// One layer of ripples: the texture's red and green as a slope, scaled, its blue as the up component.
vec3 bedrockWaterLayer(vec2 coord, float scale, vec2 drift, float slopeWeight, float seconds) {
    vec3 texel = textureLod(bedrockWaterNormalTexture, coord * scale + drift * seconds, 0.0).rgb;
    return vec3((texel.rg * 2.0 - 1.0) * slopeWeight, texel.b);
}

// The bent normal of a water surface at absolute world position pos with geometric normal n. footprint is the width
// of the ray's cone at the hit: the ripples fade out where a pixel covers more than a few hundredths of a block.
vec3 bedrockWaterNormal(vec3 pos, vec3 n, vec3 viewDir, float footprint, bool fromBelow) {
    float cosView = max(abs(dot(n, viewDir)), 0.001);
    // Seen from under the water beyond the critical angle (cos 0.6594, n about 1.33) the surface is a mirror: Bedrock
    // treats it as such, and ripples there only scatter the reflection into sparkles.
    if (fromBelow && cosView < 0.6594) { return n; }
    // Bedrock's ray spread is per pixel of its (checkerboarded, lower) internal resolution and its ripples stay
    // visible well into the distance in side-by-side captures; Radiante's cone is wider, so it is scaled down to
    // match (MEASURED against Bedrock in the same scene).
    float t = clamp((footprint * VPT_BEDROCK_WATER_FOOTPRINT_SCALE / cosView - 0.01) / 0.02, 0.0, 1.0);
    float detail = 1.0 - t * t * (3.0 - 2.0 * t);
    if (detail <= 0.0) { return n; }

    // The ripples are built on the upward face; seen from below the result is turned over.
    float side = n.y < 0.0 ? -1.0 : 1.0;
    n *= side;
    vec3 tangent = n.y < 0.99 ? normalize(vec3(n.z, 0.0, -n.x)) : vec3(1.0, 0.0, 0.0);
    vec3 bitangent = cross(n, tangent);
    vec3 p = vec3(pos.x, pos.y * 0.1, pos.z);
    vec2 coord = vec2(dot(p, tangent), dot(p, bitangent));

    float seconds = bedrockWaterSeconds();
    vec3 sum = bedrockWaterLayer(coord, 0.6, vec2(0.025, 0.05), 0.3, seconds) +
               bedrockWaterLayer(coord, 0.3, vec2(0.0325, 0.035), 0.5, seconds) +
               bedrockWaterLayer(coord, 0.61, vec2(-0.025, -0.05), 0.3, seconds);
    vec3 local = normalize(vec3(sum.xy * detail, sum.z));
    return side * normalize(tangent * local.x + bitangent * local.y + n * local.z);
}

#endif
