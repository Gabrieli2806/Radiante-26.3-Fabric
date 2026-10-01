#version 460
#extension GL_EXT_nonuniform_qualifier : enable
#extension GL_GOOGLE_include_directive : require

// Rain streaks as little lenses, after Radiance (MCVR 0.1.6, output/post/particle_refraction.frag): each streak is
// taken as a cylinder of water across its width, so it bends what lies behind it sideways, brightest at its edges.
// What lies behind is post_scene_color, a copy of the frame taken just before the weather is drawn. Snow, told apart
// by its colour (white flakes against rain's blue-grey streaks), is drawn as before.

#include "common/shared.hpp"

#ifndef VPT_RAIN_REFRACTION
#    define VPT_RAIN_REFRACTION 1
#endif
#ifndef VPT_POST_SCENE_COLOR_BINDING
#    define VPT_POST_SCENE_COLOR_BINDING 43
#endif

// How far a streak bends the view, as a share of the screen width, and how much light it scatters itself.
const float RAIN_REFRACTION_OFFSET = 0.05;
const float RAIN_SCATTERING = 0.15;

layout(set = 0, binding = 0) uniform sampler2D textures[];

layout(set = 1, binding = 0) uniform WorldUniform {
    WorldUBO worldUBO;
};

layout(set = 1, binding = 1) uniform SkyUniform {
    SkyUBO skyUBO;
};

layout(set = 4, binding = VPT_POST_SCENE_COLOR_BINDING) uniform sampler2D postSceneColor;

layout(location = 0) in vec3 pos;
layout(location = 3) flat in uint useColorLayer;
layout(location = 4) in vec4 colorLayer;
layout(location = 5) flat in uint useTexture;
layout(location = 7) in vec2 textureUV;
layout(location = 10) flat in uint textureID;
layout(location = 13) flat in uint useLight;
layout(location = 15) in vec4 lightMapColor;

layout(location = 0) out vec4 fragColor;

bool looksLikeRain(vec4 texel) {
    // Rain's streaks are blue-grey; snow's flakes are white.
    return texel.b > texel.r + 0.04;
}

void main() {
    if (useTexture == 0u) { discard; }
    vec4 texel = texture(textures[nonuniformEXT(textureID)], textureUV);
    if (texel.a < 0.1) { discard; }
    vec4 color = texel;
    if (useColorLayer > 0u) { color *= colorLayer; }
    if (useLight != 0u) { color.rgb *= lightMapColor.rgb; }

    float linearDepth = -(mat4(mat3(worldUBO.cameraEffectedViewMat)) * vec4(pos, 1.0)).z;
    gl_FragDepth = clamp(linearDepth / 1000.0, 0.0, 1.0);

    ivec2 resolution = textureSize(postSceneColor, 0);
    if (VPT_RAIN_REFRACTION == 0 || !looksLikeRain(texel) || resolution.x <= 0) {
        fragColor = color;
        return;
    }

    // Across the streak's own texel: the rain texture packs many thin streaks side by side.
    ivec2 textureResolution = textureSize(textures[nonuniformEXT(textureID)], 0);
    float lensCoordinate = textureResolution.x > 0 ? fract(textureUV.x * float(textureResolution.x)) : textureUV.x;
    float cylinderX = clamp(lensCoordinate * 2.0 - 1.0, -0.985, 0.985);
    float cylinderZ = sqrt(max(1.0 - cylinderX * cylinderX, 1e-4));
    float cylinderSlope = clamp(cylinderX / max(cylinderZ, 0.16), -5.0, 5.0);
    float bend = sign(cylinderSlope) * pow(abs(cylinderSlope) / 5.0, 0.62);
    float coverage = smoothstep(0.04, 0.45, color.a);
    float lensMask = coverage * (1.0 - smoothstep(0.985, 1.0, abs(cylinderX)));
    int offsetPixels = int(round(bend * lensMask * float(resolution.x) * RAIN_REFRACTION_OFFSET));

    ivec2 basePixel = clamp(ivec2(gl_FragCoord.xy), ivec2(0), resolution - 1);
    ivec2 samplePixel = clamp(basePixel + ivec2(offsetPixels, 0), ivec2(0), resolution - 1);
    vec3 baseColor = texelFetch(postSceneColor, basePixel, 0).rgb;
    vec3 refractedColor = texelFetch(postSceneColor, samplePixel, 0).rgb;
    float edge = pow(abs(cylinderX), 3.0) * lensMask;
    float center = pow(1.0 - abs(cylinderX), 2.5) * lensMask;
    vec3 incident = max(baseColor, refractedColor);
    vec3 tint = incident * color.rgb * RAIN_SCATTERING * (0.45 * coverage + 0.55 * edge);
    vec3 highlight = incident * (RAIN_SCATTERING * (0.20 * edge + 0.05 * center));
    // Blended over the frame by the streak's coverage, as the weather pass blends.
    fragColor = vec4(clamp(refractedColor + tint + highlight, vec3(0.0), vec3(1.0)), coverage);
}
