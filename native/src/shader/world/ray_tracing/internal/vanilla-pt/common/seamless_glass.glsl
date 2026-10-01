#ifndef VPT_SEAMLESS_GLASS_GLSL
#define VPT_SEAMLESS_GLASS_GLSL

// Seamless glass: vanilla glass textures draw a frame around every block, so a wall of glass reads as a grid of
// separate blocks. With this on, the outermost texel of a glass face takes the one just inside it instead, and
// neighbouring blocks of the same glass join into one sheet. Only whole faces (a block's side, a pane's front) have
// room for it; the narrow edges of a pane keep their texture.

#ifndef VPT_SEAMLESS_GLASS
#    define VPT_SEAMLESS_GLASS 1
#endif

// uv inside the face's texture rectangle [uvMin, uvMax] of an atlas of atlasSize texels, pulled in off its border.
vec2 seamlessGlassUV(vec2 uv, vec2 uvMin, vec2 uvMax, ivec2 atlasSize) {
#if VPT_SEAMLESS_GLASS != 0
    vec2 texel = 1.0 / vec2(max(atlasSize, ivec2(1)));
    // The frame is a sixteenth of the texture across, whatever its resolution (a 32 or 64 pixel pack draws it 2 or
    // 4 texels wide); pulled in to the middle of the next sixteenth, so filtered sampling takes nothing of it.
    vec2 inset = max((uvMax - uvMin) * (1.5 / 16.0), texel * 1.5);
    vec2 lo = uvMin + inset;
    vec2 hi = uvMax - inset;
    // Faces under 4 texels across (a pane's edge) are left alone.
    if (any(lessThan(uvMax - uvMin, texel * 4.0))) { return uv; }
    return clamp(uv, lo, hi);
#else
    return uv;
#endif
}

#endif
