#version 460

// Bloom, downscale half of the pyramid. Bedrock
// structure (the pass layout) and what is a placeholder (every filter shape and weight in this file).
//
//   firstPass != 0 : the "uniform" downscale from the HDR image to half size. The source is multiplied by the same
//                    exposure the tone mapper uses, so what blooms is what is seen, and an optional threshold keeps
//                    only the bright part. [INFERRED order, APPROXIMATE filter]
//   firstPass == 0 : the Gaussian downscale between two pyramid levels (3x3 tent here). [APPROXIMATE filter]

layout(set = 0, binding = 0) uniform sampler2D srcA;

layout(set = 0, binding = 2) readonly buffer ExposureBuffer {
    float exposure;
    float avgLogLum;
    float padding0;
    float padding1;
}
expData;

layout(push_constant) uniform PushConstant {
    vec2 srcTexel;
    int firstPass;
    int autoExposure;
    float manualExposure;
    float exposureBias;
    float threshold;
    float scatter;
    float weight;
    float padding0;
}
pc;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

// The largest value a single texel may carry into the pyramid: a few texels of the sun disc would otherwise light
// the whole frame. [APPROXIMATE]
const float BLOOM_TEXEL_LIMIT = 64.0;

vec3 firstLevel(vec2 uv) {
    float exposure = pc.autoExposure != 0 ? expData.exposure : pc.manualExposure;
    if (isnan(exposure) || isinf(exposure) || exposure <= 0.0) { exposure = max(pc.manualExposure, 1e-6); }
    exposure *= exp2(pc.exposureBias);

    vec3 sum = vec3(0.0);
    for (int y = 0; y < 2; ++y) {
        for (int x = 0; x < 2; ++x) {
            vec2 offset = (vec2(x, y) - 0.5) * pc.srcTexel;
            vec3 c = texture(srcA, uv + offset).rgb;
            c = clamp(c * exposure, vec3(0.0), vec3(BLOOM_TEXEL_LIMIT));
            float peak = max(c.r, max(c.g, c.b));
            c *= peak > 1e-5 ? max(peak - pc.threshold, 0.0) / peak : 0.0;
            sum += c;
        }
    }
    return sum * 0.25;
}

vec3 gaussianLevel(vec2 uv) {
    vec3 sum = vec3(0.0);
    for (int y = -1; y <= 1; ++y) {
        for (int x = -1; x <= 1; ++x) {
            float w = (2.0 - abs(float(x))) * (2.0 - abs(float(y)));
            sum += w * texture(srcA, uv + vec2(x, y) * pc.srcTexel).rgb;
        }
    }
    return sum * (1.0 / 16.0);
}

void main() {
    fragColor = vec4(pc.firstPass != 0 ? firstLevel(texCoord) : gaussianLevel(texCoord), 1.0);
}
