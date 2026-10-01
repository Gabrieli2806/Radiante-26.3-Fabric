#ifndef VPT_SEAMLESS_GLASS_GLSL
#define VPT_SEAMLESS_GLASS_GLSL

// Seamless glass: vanilla glass textures draw a frame around every block, so a wall of glass reads as a grid of
// separate blocks. With this on, a point of a glass face within the frame's width of its block's edge takes its
// colour from just inside the frame instead, and neighbouring blocks of the same glass join into one sheet.
//
// Worked out from where the point lies in its block, not from the texture: a pane's front shows only a strip of
// its texture, and the frame lies on the block's edges whichever part of the texture a face shows.

#ifndef VPT_SEAMLESS_GLASS
#    define VPT_SEAMLESS_GLASS 1
#endif

// Frame width, as a share of a block (one texel of a 16 pixel texture), and where a point inside it is moved to.
const float SEAMLESS_GLASS_FRAME = 1.5 / 16.0;

// uv at objectPos (block-aligned geometry space) on a face whose texture runs along dposdu and dposdv, moved off
// the frame along the block's edges.
vec2 seamlessGlassUV(vec2 uv, vec3 objectPos, vec3 dposdu, vec3 dposdv) {
#if VPT_SEAMLESS_GLASS != 0
    vec3 faceNormal = cross(dposdu, dposdv);
    float normalLength = length(faceNormal);
    if (normalLength <= 1e-8) { return uv; }
    vec3 axisWeight = abs(faceNormal) / normalLength;

    vec3 local = fract(objectPos + 1e-4);
    vec3 shift = vec3(0.0);
    for (int axis = 0; axis < 3; axis++) {
        // Only along the face, not across it.
        if (axisWeight[axis] > 0.5) { continue; }
        if (local[axis] < SEAMLESS_GLASS_FRAME) {
            shift[axis] = SEAMLESS_GLASS_FRAME - local[axis];
        } else if (local[axis] > 1.0 - SEAMLESS_GLASS_FRAME) {
            shift[axis] = (1.0 - SEAMLESS_GLASS_FRAME) - local[axis];
        }
    }
    if (dot(shift, shift) <= 0.0) { return uv; }

    // The texture's change for that move: shift = a * dposdu + b * dposdv, solved in the face's plane.
    float uu = dot(dposdu, dposdu);
    float uv2 = dot(dposdu, dposdv);
    float vv = dot(dposdv, dposdv);
    float determinant = uu * vv - uv2 * uv2;
    if (abs(determinant) <= 1e-12) { return uv; }
    float su = dot(shift, dposdu);
    float sv = dot(shift, dposdv);
    float a = (su * vv - sv * uv2) / determinant;
    float b = (sv * uu - su * uv2) / determinant;
    return uv + vec2(a, b);
#else
    return uv;
#endif
}

// The thin tops and bottoms of panes: between two panes stacked they stay inside the glass, and through its clear
// front they showed as a line along every join. With seamless glass they are left out, so a wall of panes is one
// clear sheet. A pane's upright thin faces stay: a pane standing on its own is only those.
bool seamlessGlassHidesFace(vec2 uvMin, vec2 uvMax, ivec2 atlasSize, vec3 faceNormal) {
#if VPT_SEAMLESS_GLASS != 0
    vec2 extent = (uvMax - uvMin) * vec2(max(atlasSize, ivec2(1)));
    float normalLength = length(faceNormal);
    bool horizontal = normalLength > 0.0 && abs(faceNormal.y) > 0.9 * normalLength;
    return horizontal && min(extent.x, extent.y) < 3.0;
#else
    return false;
#endif
}

#endif
