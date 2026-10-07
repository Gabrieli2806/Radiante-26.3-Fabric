#version 460
#extension GL_EXT_nonuniform_qualifier : enable
#extension GL_GOOGLE_include_directive : require

#include "common/shared.hpp"

layout(set = 0, binding = 0) uniform sampler2D HDR;
// The bloom pyramid's result at half size (or the HDR image again when bloom is off; bloomIntensity is 0 then).
layout(set = 0, binding = 3) uniform sampler2D BloomTexture;

// The Bedrock-style tone curve built by bedrock_curve.comp (method 6 only).
layout(std430, set = 0, binding = 4) readonly buffer BedrockCurveBuffer {
    uint histogram[256];
    float curve[256];
}
bedrock;

layout(set = 0, binding = 2) readonly buffer ExposureBuffer {
    float exposure;
    float avgLogLum;
    float padding0;
    float padding1;
}
expData;

layout(push_constant) uniform PushConstant {
    float log2Min;
    float log2Max;
    float epsilon;
    float lowPercent;
    float highPercent;
    float middleGrey;
    float dt;
    float speedUp;
    float speedDown;
    float minExposure;
    float maxExposure;
    float manualExposure;
    float exposureBias;
    float whitePoint;
    float saturation;
    int toneMappingMethod;
    int autoExposure;
    int clampOutput;
    int exposureMeteringMode;
    float centerMeteringPercent;
    float padding0;
    // The Bedrock-style grade. The neutral values (bloom 0,
    // contrast 1, shadow contrast 0, gamma 2.2, balance 1) leave the picture exactly as it was before they existed.
    float bloomIntensity;
    float contrast;
    float shadowContrast;
    float shadowContrastEnd;
    float gamma;
    float balanceR;
    float balanceG;
    float balanceB;
    // Bedrock's filmic saturation correction: 0 off.
    float filmicSaturation;
}
pc;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

// https://github.com/KhronosGroup/ToneMapping/tree/main/PBR_Neutral
vec3 pbrNeutralToneMap(vec3 color) {
    float startCompression = 0.76;
    float desaturation = 0.01;

    float x = min(color.r, min(color.g, color.b));
    float offset = (x < 0.08) ? (x - 6.25 * x * x) : 0.04;
    color -= offset;

    float peak = max(color.r, max(color.g, color.b));
    if (peak < startCompression) return color;

    float d = 1.0 - startCompression;
    float newPeak = 1.0 - d * d / (peak + d - startCompression);
    color *= newPeak / peak;

    float g = 1.0 - 1.0 / (desaturation * (peak - newPeak) + 1.0);
    return mix(color, newPeak * vec3(1.0), g);
}

// all following
// https://64.github.io/tonemapping/
vec3 reinhardToneMap(vec3 color) {
    return color / (1.0 + color);
}

vec3 reinhardWhitePointToneMap(vec3 color, float whitePoint) {
    float w2 = max(whitePoint * whitePoint, 1e-6);
    return (color * (1.0 + color / w2)) / (1.0 + color);
}

vec3 acesFittedRaw(vec3 color) {
    vec3 a = color * (2.51 * color + 0.03);
    vec3 b = color * (2.43 * color + 0.59) + 0.14;
    return a / max(b, vec3(1e-6));
}

vec3 acesFittedToneMap(vec3 color) {
    return clamp(acesFittedRaw(color), 0.0, 1.0);
}

vec3 acesFittedWhitePointToneMap(vec3 color, float whitePoint) {
    float whiteScale = 1.0 / max(acesFittedRaw(vec3(whitePoint)).r, 1e-6);
    return clamp(acesFittedRaw(color) * whiteScale, 0.0, 1.0);
}

vec3 uncharted2Partial(vec3 x) {
    const float A = 0.15;
    const float B = 0.50;
    const float C = 0.10;
    const float D = 0.20;
    const float E = 0.02;
    const float F = 0.30;
    return ((x * (A * x + C * B) + D * E) / (x * (A * x + B) + D * F)) - E / F;
}

vec3 uncharted2ToneMap(vec3 color, float whitePoint) {
    vec3 mapped = uncharted2Partial(color);
    float whiteScale = 1.0 / max(uncharted2Partial(vec3(whitePoint)).r, 1e-6);
    return mapped * whiteScale;
}

vec3 applyToneMapping(vec3 color) {
    switch (pc.toneMappingMethod) {
        case 1: return reinhardToneMap(color);
        case 2: return reinhardWhitePointToneMap(color, pc.whitePoint);
        case 3: return acesFittedToneMap(color);
        case 4: return acesFittedWhitePointToneMap(color, pc.whitePoint);
        case 5: return uncharted2ToneMap(color, pc.whitePoint);
        case 0:
        default: return pbrNeutralToneMap(color);
    }
}

vec3 applySaturation(vec3 color, float saturation) {
    float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
    return mix(vec3(luma), color, saturation);
}

// The controls Bedrock's tone mapping material exposes (the color balance control, the contrast control,
// the shadow contrast control, the shadow contrast end control). What each
// does here is INFERRED from the names and APPROXIMATE until measured. All are neutral at their defaults.
vec3 applyBedrockGrade(vec3 color) {
    color *= vec3(pc.balanceR, pc.balanceG, pc.balanceB);
    if (pc.contrast != 1.0) {
        // Contrast about middle grey, in linear light.
        color = pc.middleGrey * pow(max(color, vec3(0.0)) / pc.middleGrey, vec3(pc.contrast));
    }
    if (pc.shadowContrast != 0.0 && pc.shadowContrastEnd > 0.0) {
        // Deepens (positive) or lifts (negative) everything darker than shadowContrastEnd; continuous at the end.
        float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
        color *= pow(clamp(luma / pc.shadowContrastEnd, 1e-3, 1.0), pc.shadowContrast);
    }
    return color;
}

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

// Displayed log2 luminance for an input log2 luminance, read from the curve with linear filtering; the curve's
// entries cover -24 .. +4 in 256 steps.
float bedrockCurveAt(float log2Luminance) {
    float position = clamp((log2Luminance + 24.0) / 28.0 * 256.0 - 0.5, 0.0, 255.0);
    uint lower = uint(position);
    uint upper = min(lower + 1u, 255u);
    return mix(bedrock.curve[lower], bedrock.curve[upper], fract(position));
}

vec3 encodeSrgb(vec3 linear) {
    vec3 low = linear * 12.92;
    vec3 high = 1.055 * pow(max(linear, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055;
    return mix(high, low, lessThan(linear, vec3(0.0031308)));
}

// Bedrock's tone mapping material, in its order: the luminance goes through the tone curve with the hue kept, the
// filmic saturation correction, then the grade (colour balance, contrast about 0.18, clamp, saturation, gamma) and
// the sRGB encoding. 
vec3 bedrockToneMap(vec3 color) {
    float luminance = dot(color, LUMA);
    vec3 mapped = luminance > 0.0
        ? color * (exp2(bedrockCurveAt(log2(max(luminance, exp2(-24.0))))) / luminance)
        : vec3(0.0);

    if (pc.filmicSaturation != 0.0) {
        float mean = dot(mapped, vec3(1.0 / 3.0));
        float response = ((1486.4 - 1489.7 * mean) * mean - 3.3) / ((0.15 * mean + 944.2) * mean + 1.0);
        mapped = mean + (1.0 + pc.filmicSaturation * max(response - 1.0, 0.0)) * (mapped - mean);
    }

    vec3 graded = mapped * vec3(pc.balanceR, pc.balanceG, pc.balanceB);
    graded = clamp((graded - 0.18) * pc.contrast + 0.18, 0.0, 1.0);
    float gradedLuma = dot(graded, LUMA);
    graded = max(gradedLuma + (graded - gradedLuma) * max(pc.saturation, 0.0), vec3(0.0));
    graded = pow(graded, vec3(pc.gamma));
    return clamp(encodeSrgb(graded), 0.0, 1.0);
}

void main() {
    vec3 hdr = texture(HDR, texCoord).rgb;

    float exposure = (pc.autoExposure != 0) ? expData.exposure : pc.manualExposure;
    if (isnan(exposure) || isinf(exposure) || exposure <= 0.0) { exposure = max(pc.manualExposure, 1e-6); }
    exposure *= exp2(pc.exposureBias);

    vec3 expColor = max(hdr * max(exposure, 0.0), vec3(0.0));
    if (pc.bloomIntensity > 0.0) { expColor += max(texture(BloomTexture, texCoord).rgb, vec3(0.0)) * pc.bloomIntensity; }
    if (pc.toneMappingMethod == 6) {
        fragColor = vec4(bedrockToneMap(expColor), 1.0);
        return;
    }
    expColor = applyBedrockGrade(expColor);
    vec3 mapped = applyToneMapping(expColor);
    mapped = max(mapped, vec3(0.0));
    mapped = max(applySaturation(mapped, max(pc.saturation, 0.0)), vec3(0.0));
    mapped = pow(mapped, vec3(1.0 / max(pc.gamma, 1e-3)));
    if (pc.clampOutput != 0) mapped = clamp(mapped, vec3(0.0), vec3(1.0));

    fragColor = vec4(mapped, 1.0);
}
