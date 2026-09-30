#ifndef VPT_RAIN_WETNESS_GLSL
#define VPT_RAIN_WETNESS_GLSL

// Wet ground in rain, ported from Radiance 0.1.6's advanced pack (scene/materials/wetness.glsl). The Java side keeps
// a texture of the top rain-blocking block of every column around the camera (RainExposure): an upward face that is
// the top of its column, in a biome where it rains rather than snows, gets darker and glossier with the rain. Faces
// next to a wall or overhang dry off towards it, so wet patches end softly instead of at block edges.

ivec4 vptRainColumn(ivec2 column, uint textureId) {
    return ivec4(round(texelFetch(textures[nonuniformEXT(textureId)], column & ivec2(511), 0) * 255.0));
}

bool vptRainColumnBlocks(ivec4 data, int surfaceBlockY) {
    return data.a == 0 || (data.r + 256 * data.g - 32768) > surfaceBlockY;
}

float vptGroundWetness(vec3 position, vec3 outwardNormal) {
    float rain = skyUBO.rainWetness.x;
    uint textureId = uint(skyUBO.rainWetness.y + 0.5);
    if (textureId == 0u || rain <= 0.0001 || outwardNormal.y < 0.5) { return 0.0; }
    // The texture covers 256 blocks each way.
    if (max(abs(position.x), abs(position.z)) > 238.0) { return 0.0; }
    ivec3 cameraCell = ivec3(floor(worldUBO.cameraPos.xyz));
    vec3 relativePosition = position + vec3(worldUBO.cameraPos.xyz - dvec3(cameraCell));
    ivec2 column = cameraCell.xz + ivec2(floor(relativePosition.xz));
    int surfaceBlockY = cameraCell.y + int(floor(relativePosition.y - 0.001));
    ivec4 center = vptRainColumn(column, textureId);
    if (center.b == 0 || vptRainColumnBlocks(center, surfaceBlockY)) { return 0.0; }

    vec2 withinColumn = fract(relativePosition.xz);
    ivec2 nearestSide = ivec2(step(vec2(0.5), withinColumn)) * 2 - 1;
    vec2 edge = min(withinColumn, vec2(1.0) - withinColumn);
    float edgeDistanceSquared = 0.25;
    if (vptRainColumnBlocks(vptRainColumn(column + ivec2(nearestSide.x, 0), textureId), surfaceBlockY))
        edgeDistanceSquared = min(edgeDistanceSquared, edge.x * edge.x);
    if (vptRainColumnBlocks(vptRainColumn(column + ivec2(0, nearestSide.y), textureId), surfaceBlockY))
        edgeDistanceSquared = min(edgeDistanceSquared, edge.y * edge.y);
    if (vptRainColumnBlocks(vptRainColumn(column + nearestSide, textureId), surfaceBlockY))
        edgeDistanceSquared = min(edgeDistanceSquared, dot(edge, edge));
    rain *= 1.0 - smoothstep(220.0, 238.0, max(abs(position.x), abs(position.z)));
    return rain * smoothstep(0.0, 0.5, sqrt(edgeDistanceSquared)) * smoothstep(0.5, 0.95, outwardNormal.y);
}

/** Water fills the pores: rough, non-metal surfaces darken the most, and everything turns glossy. */
void vptApplyGroundWetness(inout LabPBRMat material, float wetness) {
    if (wetness <= 0.0 || material.transmission > 0.0) { return; }
    float absorbency = (1.0 - material.metallic) * clamp(material.roughness * 2.0, 0.0, 1.0);
    material.albedo *= 1.0 - 0.35 * wetness * absorbency;
    material.roughness = mix(material.roughness, min(material.roughness, 0.08), wetness);
    material.f0 = mix(material.f0, max(material.f0, vec3(0.02)), wetness);
}

#endif
