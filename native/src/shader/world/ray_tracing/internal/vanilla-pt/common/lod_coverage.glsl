#ifndef VPT_LOD_COVERAGE_GLSL
#define VPT_LOD_COVERAGE_GLSL

// Distant Horizons terrain stays where it is while chunks load in and out; it is hidden per ray instead, in the chunk
// columns whose own terrain is built (Chunks::updateLodCoverage). After Radiance's Vista coverage.
//   [0] origin x, [1] origin z, in chunks; [2] window size in chunks (0: none); [3, 4) instance range of far terrain;
//   [8...] one bit per column, row by row along x.
// Needs GL_EXT_buffer_reference and the WorldUBO.
layout(std430, buffer_reference, buffer_reference_align = 4) readonly buffer VptLodCoverage {
    uint words[];
};

bool vptLodCovered(uint addressLo, uint addressHi, uint instance, vec3 cameraRelativePos, dvec3 cameraPos) {
    if ((addressLo | addressHi) == 0u) return false;
    VptLodCoverage coverage = VptLodCoverage(packUint2x32(uvec2(addressLo, addressHi)));
    if (instance < coverage.words[3] || instance >= coverage.words[4]) return false;
    int size = int(coverage.words[2]);
    if (size <= 0) return false;
    // A hair along the ray: a far face on a column edge belongs to the column the ray goes on into.
    dvec3 worldPos = dvec3(cameraRelativePos) + cameraPos;
    ivec2 column = ivec2(floor(worldPos.xz / 16.0LF)) - ivec2(int(coverage.words[0]), int(coverage.words[1]));
    if (any(lessThan(column, ivec2(0))) || any(greaterThanEqual(column, ivec2(size)))) return false;
    uint bit = uint(column.y * size + column.x);
    return (coverage.words[8u + (bit >> 5u)] & (1u << (bit & 31u))) != 0u;
}

// For an any-hit shader: true when this hit is far terrain standing where the near terrain is built.
#define VPT_LOD_COVERED(uboRef)                                                                                       \
    vptLodCovered((uboRef).lodCoverageAddressLo, (uboRef).lodCoverageAddressHi, uint(gl_InstanceCustomIndexEXT),           \
                  gl_WorldRayOriginEXT + gl_WorldRayDirectionEXT * (gl_HitTEXT + 0.01), (uboRef).cameraPos.xyz)

#endif
