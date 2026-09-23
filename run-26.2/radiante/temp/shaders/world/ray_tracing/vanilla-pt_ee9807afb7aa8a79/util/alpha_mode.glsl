#ifndef ALPHA_MODE_GLSL
#define ALPHA_MODE_GLSL

const uint ALPHA_MODE_OPAQUE = 0u;
const uint ALPHA_MODE_CUTOUT = 1u;
const uint ALPHA_MODE_TRANSPARENT = 2u;
// Thin see-through geometry that must stay visible (rain, snow): the any-hit shader keeps a hit with probability
// alpha, and a kept hit is shaded as an opaque surface. Plain transparency would shade it as clear glass, which is
// invisible. Numbered past the text modes (1-8), which share this field.
const uint ALPHA_MODE_STOCHASTIC = 9u;
// A multiplicative decal (block breaking cracks). Vanilla blends it as dst * src * 2, so a mid-grey texel changes
// nothing and only darker texels darken. The any-hit shader keeps a hit with probability alpha * (1 - 2 * luma),
// and a kept hit is shaded opaque in the texel's own dark colour.
const uint ALPHA_MODE_DECAL = 10u;

const float CUTOUT_ALPHA_THRESHOLD = 0.5;

float resolveSurfaceAlpha(float alpha, uint alphaMode) {
    alpha = clamp(alpha, 0.0, 1.0);

    if (alphaMode == ALPHA_MODE_OPAQUE) { return 1.0; }

    if (alphaMode == ALPHA_MODE_STOCHASTIC || alphaMode == ALPHA_MODE_DECAL) { return alpha >= 0.05 ? 1.0 : 0.0; }

    if (alphaMode == ALPHA_MODE_CUTOUT) {
        return alpha >= CUTOUT_ALPHA_THRESHOLD ? 1.0 : 0.0;
    }

    return alpha;
}

#endif
