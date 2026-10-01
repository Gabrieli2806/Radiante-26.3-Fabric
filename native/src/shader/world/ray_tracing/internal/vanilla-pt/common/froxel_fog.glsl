#ifndef VPT_FROXEL_FOG_GLSL
#define VPT_FROXEL_FOG_GLSL

// Froxel fog, after Radiance's (MCVR 0.1.6, advanced/volume): the air in front of the camera is cut into a grid of
// frustum-aligned cells ("froxels"), each lit once by the sun or moon through a shadow ray, by the sky and by the block
// lights around it, and kept over frames. A compute pass then integrates the grid front to back, and each pixel reads
// the fog up to its surface from that integral instead of marching the air itself. Light shafts come out sharper and
// steadier than the per-pixel march, torches glow in the fog, and it costs far fewer rays.
//
// froxel_light holds two grids stacked in z (this frame's and last frame's, by frame parity): rgb is the light the
// cell scatters towards the camera per block, a its extinction per block. froxel_integral holds, per cell, the light
// scattered in from the camera to the cell's far edge (rgb) and the transmittance over that stretch (a).
//
// Needs worldUBO, lastWorldUBO and skyUBO.

#ifndef VPT_VOLUMETRIC_LIGHT_MAX_DISTANCE
#    define VPT_VOLUMETRIC_LIGHT_MAX_DISTANCE 128.0
#endif

const ivec3 FROXEL_EXTENT = ivec3(160, 90, 64);
const float FROXEL_SLICE_CURVATURE = 10.0;

float froxelRange() {
    return clamp(VPT_VOLUMETRIC_LIGHT_MAX_DISTANCE, 48.0, 192.0);
}

// Slices are thin near the camera, where shafts are sharp, and thicker further out.
float froxelDistanceFromW(float w) {
    float coordinate = clamp(w, 0.0, 1.0);
    return froxelRange() * (pow(FROXEL_SLICE_CURVATURE + 1.0, coordinate) - 1.0) / FROXEL_SLICE_CURVATURE;
}

float froxelWFromDistance(float distance) {
    float normalized = max(distance, 0.0) * (FROXEL_SLICE_CURVATURE / froxelRange()) + 1.0;
    return clamp(log(normalized) / log(FROXEL_SLICE_CURVATURE + 1.0), 0.0, 1.0);
}

// The z offset of the grid written this frame (0 or FROXEL_EXTENT.z); the other one holds last frame's.
int froxelWriteOffset() {
    return int(worldUBO.frameCounter & 1u) * FROXEL_EXTENT.z;
}

int froxelReadOffset() {
    return FROXEL_EXTENT.z - froxelWriteOffset();
}

vec3 froxelCameraOrigin() {
    return worldUBO.cameraEffectedViewMatInv[3].xyz;
}

// uv as the ray generation shader's pixels have it: (0,0) at the first pixel.
vec3 froxelRayDirection(vec2 uv) {
    vec2 ndc = uv * 2.0 - 1.0;
    vec4 viewPosition = worldUBO.cameraProjMatInv * vec4(ndc, 0.0, 1.0);
    viewPosition /= viewPosition.w;
    return normalize(vec3(worldUBO.cameraEffectedViewMatInv * vec4(viewPosition.xyz, 0.0)));
}

// Where a point (camera-relative, this frame) sat in last frame's grid, as uvw; false off its frustum or range.
bool froxelReproject(vec3 scenePosition, out vec3 previousUvw) {
    previousUvw = vec3(0.0);
    vec3 previousScenePosition = scenePosition + vec3(worldUBO.cameraPos.xyz - lastWorldUBO.cameraPos.xyz);
    vec4 previousView = lastWorldUBO.cameraEffectedViewMat * vec4(previousScenePosition, 1.0);
    vec4 previousClip = lastWorldUBO.cameraProjMat * previousView;
    if (previousClip.w <= 1e-6) { return false; }
    vec2 previousNdc = previousClip.xy / previousClip.w;
    if (any(greaterThan(abs(previousNdc), vec2(1.0)))) { return false; }
    float previousDistance = length(previousView.xyz);
    previousUvw = vec3(previousNdc * 0.5 + 0.5, froxelWFromDistance(previousDistance));
    return previousDistance < froxelRange();
}

// Which cells are lit afresh this frame: a quarter of them, in a 2x2 pattern that shifts every frame and slice.
uint froxelFreshPattern(int slice) {
    return (worldUBO.frameCounter + uint(slice)) & 3u;
}

bool froxelIsFresh(ivec3 cell) {
    return (uint(cell.x & 1) | (uint(cell.y & 1) << 1u)) == froxelFreshPattern(cell.z);
}

// The centre of a cell, camera-relative, and the direction to it.
vec3 froxelCellCenter(ivec3 cell, out vec3 rayDir) {
    rayDir = froxelRayDirection((vec2(cell.xy) + 0.5) / vec2(FROXEL_EXTENT.xy));
    return froxelCameraOrigin() + rayDir * froxelDistanceFromW((float(cell.z) + 0.5) / float(FROXEL_EXTENT.z));
}

#ifndef VPT_ATMOSPHERE_BETA_R
#    define VPT_ATMOSPHERE_BETA_R vec3(0.000005802, 0.000013558, 0.0000331)
#endif
#ifndef VPT_ATMOSPHERE_BETA_M
#    define VPT_ATMOSPHERE_BETA_M vec3(0.000021)
#endif

// The medium's extinction per block at a world height, as the per-pixel march has it (volumetricAirMedium and the
// biome fog in world.rgen), as one luminance.
float froxelExtinction(float worldY) {
    float sunHeight = abs(normalize(skyUBO.sunDirection).y);
    float airDensity = mix(1.0, 0.7, smoothstep(0.05, 0.35, sunHeight));
    vec3 betaR = VPT_ATMOSPHERE_BETA_R * 18.0;
    vec3 betaM = VPT_ATMOSPHERE_BETA_M * mix(18.0, 54.0, skyUBO.rainGradient);
    vec3 air = (betaR + betaM * 1.15) * airDensity * 0.45 * max(skyUBO.fogControls.z, 0.0);
    float biomeDensity = worldUBO.skyType == 1 ? max(skyUBO.biomeFog.a, 0.0) : 0.0;
    vec2 heights = skyUBO.biomeFogHeights.xy;
    float heightProfile = clamp((heights.y - worldY) / max(heights.y - heights.x, 1.0), 0.0, 1.0);
    vec3 biome = max(skyUBO.biomeFogChroma.rgb, vec3(0.0)) * biomeDensity * heightProfile;
    return max(dot(air + biome, vec3(0.2126, 0.7152, 0.0722)), 1e-6);
}

// Last frame's camera moved too far, turned too much or changed dimension: nothing of its grid carries over.
bool froxelHistoryUsable() {
    if (worldUBO.skyType != lastWorldUBO.skyType) { return false; }
    vec3 cameraDelta = vec3(worldUBO.cameraPos.xyz - lastWorldUBO.cameraPos.xyz);
    if (any(isnan(cameraDelta)) || length(cameraDelta) > 8.0) { return false; }
    vec3 forward = normalize(vec3(worldUBO.cameraEffectedViewMatInv * vec4(0.0, 0.0, -1.0, 0.0)));
    vec3 previousForward = normalize(vec3(lastWorldUBO.cameraEffectedViewMatInv * vec4(0.0, 0.0, -1.0, 0.0)));
    return dot(forward, previousForward) > 0.5;
}

#endif
