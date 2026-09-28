#ifndef VPT_BEDROCK_CAUSTICS_GLSL
#define VPT_BEDROCK_CAUSTICS_GLSL

// Bedrock RTX's own caustics animation, when the player has one (see configs.json, water_caustics: taken from a
// Bedrock install or radiante/bedrock/caustics.png; it cannot ship in the pack): 64 frames of 256 x 256 stacked in
// one column, a bright net of focused light on black. Without it the texture is a 1 x 1 stand-in and the waves'
// own caustics (fft_water.glsl) are used.
layout(set = 5, binding = 36) uniform sampler2D waterCausticsTexture;

const float BEDROCK_CAUSTICS_FRAMES = 64.0;
// Blocks per repeat of the pattern, frames per second of its animation.
const float BEDROCK_CAUSTICS_TILE = 4.0;
const float BEDROCK_CAUSTICS_FPS = 20.0;
// The texture's mean brightness: dividing by it keeps the light reaching the bottom the same on average.
const float BEDROCK_CAUSTICS_MEAN = 0.07;
// How much of the light goes into the pattern, near the surface and fading with depth.
const float BEDROCK_CAUSTICS_STRENGTH = 0.15;

bool bedrockCausticsAvailable() {
    ivec2 size = textureSize(waterCausticsTexture, 0);
    return size.x > 1 && size.y == size.x * int(BEDROCK_CAUSTICS_FRAMES);
}

float bedrockCausticFrame(vec2 uv, float frame) {
    vec2 cell = fract(uv);
    // Kept half a texel off the frame edges so filtering does not bleed into the next frame.
    float texel = 1.0 / float(textureSize(waterCausticsTexture, 0).x);
    cell.y = clamp(cell.y, texel * 0.5, 1.0 - texel * 0.5);
    return texture(waterCausticsTexture, vec2(cell.x, (mod(frame, BEDROCK_CAUSTICS_FRAMES) + cell.y) /
                                                        BEDROCK_CAUSTICS_FRAMES)).r;
}

// gameTime is the day's fraction (24000 ticks, 20 a second).
float sampleBedrockCaustic(vec2 surfaceXZ, float gameTime, float waterDepth) {
    float frame = gameTime * 24000.0 / 20.0 * BEDROCK_CAUSTICS_FPS;
    vec2 uv = surfaceXZ / BEDROCK_CAUSTICS_TILE;
    float a = bedrockCausticFrame(uv, floor(frame));
    float b = bedrockCausticFrame(uv, floor(frame) + 1.0);
    float value = mix(a, b, fract(frame)) / BEDROCK_CAUSTICS_MEAN;
    // Faint, fading fast with depth, and not always there: a slow drift in strength (a minute or so) stands in for
    // the calm and choppy spells of the surface above, so the net comes and goes.
    float drift = 0.35 + 0.65 * smoothstep(0.2, 0.9, 0.5 + 0.5 * sin(gameTime * 24000.0 / 20.0 * 0.1 +
                                                                        dot(surfaceXZ, vec2(0.013, 0.009))));
    float strength = BEDROCK_CAUSTICS_STRENGTH * drift * exp(-waterDepth * 0.1);
    return max(mix(1.0, value, strength), 0.0);
}

#endif
