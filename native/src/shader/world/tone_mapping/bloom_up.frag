#version 460

// Bloom, upscale half of the pyramid: this level's downscaled image plus the smaller level above it, spread back
// out. Structure (four of these) is INFERRED from the pass layout of Bedrock's bloom material; the 3x3 tent and the
// scatter weight are APPROXIMATE.

layout(set = 0, binding = 0) uniform sampler2D srcA; // the smaller (coarser) level, already combined
layout(set = 0, binding = 1) uniform sampler2D srcB; // this level as downscaled

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

vec3 tentUpsample(vec2 uv) {
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
    vec3 level = texture(srcB, texCoord).rgb;
    fragColor = vec4(pc.weight * level + pc.scatter * tentUpsample(texCoord), 1.0);
}
