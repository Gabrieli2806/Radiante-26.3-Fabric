#ifndef VPT_BEDROCK_ATMOSPHERE_GLSL
#define VPT_BEDROCK_ATMOSPHERE_GLSL

// The Bedrock style atmosphere (VPT_BEDROCK_ATMOSPHERE): Bedrock RTX does not scatter light through an atmosphere, it
// looks its sky and its sunlight up in two small tables, and these are them, read from the player's own Bedrock
// install (configs.json, bedrock_sky and bedrock_luts; they cannot ship in the pack). Without them the textures are
// 1 x 1 stand-ins and the Java style sky is used.
//
// sky.png, 64 x 256: eight strips of 32 rows, one per sun height (0 the sun overhead, 3 on the horizon, 7 midnight).
// Across a strip, the direction's angle from the sun's around the horizon (left towards the sun); down it, the
// direction's height from the zenith to straight down, the horizon at the middle.
// look_up_tables.png, 128 x 4: across, the sun's angle from the zenith (the horizon at the middle); row 0 the colour
// of the sunlight, row 1 its strength.
layout(set = 5, binding = 37) uniform sampler2D bedrockSkyTexture;
layout(set = 5, binding = 38) uniform sampler2D bedrockLutTexture;

#ifndef VPT_BEDROCK_ATMOSPHERE
#    define VPT_BEDROCK_ATMOSPHERE 0
#endif
#ifndef VPT_BEDROCK_SKY_INTENSITY
#    define VPT_BEDROCK_SKY_INTENSITY 1.0
#endif

bool bedrockAtmosphereActive() {
    return VPT_BEDROCK_ATMOSPHERE != 0 && textureSize(bedrockSkyTexture, 0) == ivec2(64, 256) &&
           textureSize(bedrockLutTexture, 0).x == 128;
}

vec3 bedrockSrgbToLinear(vec3 c) {
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c));
}

// Which strip, fractional, for a sun this high: overhead to the horizon over strips 0 to 3, then down to the
// antipode over 3 to 7.
float bedrockSkyStrip(float sunHeight) {
    float elevation = asin(clamp(sunHeight, -1.0, 1.0)) / (0.5 * PI);
    return elevation >= 0.0 ? (1.0 - elevation) * 3.0 : 3.0 + min(-elevation, 1.0) * 4.0;
}

vec3 bedrockSkyStripSample(float strip, float across, float down) {
    vec2 uv = vec2((across * 63.0 + 0.5) / 64.0, (strip * 32.0 + clamp(down * 32.0, 0.5, 31.5)) / 256.0);
    return texture(bedrockSkyTexture, uv).rgb;
}

// The sky's radiance in this direction, in the units of the Java sky (VPT_BEDROCK_SKY_INTENSITY scales it).
vec3 bedrockSkyRadiance(vec3 dir, vec3 sunDir) {
    dir = normalize(dir);
    vec2 flatDir = dir.xz;
    vec2 flatSun = sunDir.xz;
    float across = 0.0;
    if (dot(flatDir, flatDir) > 1e-8 && dot(flatSun, flatSun) > 1e-8) {
        across = acos(clamp(dot(normalize(flatDir), normalize(flatSun)), -1.0, 1.0)) / PI;
    }
    float down = 0.5 - asin(clamp(dir.y, -1.0, 1.0)) / PI;
    float strip = bedrockSkyStrip(sunDir.y);
    float first = floor(strip);
    vec3 a = bedrockSkyStripSample(min(first, 7.0), across, down);
    vec3 b = bedrockSkyStripSample(min(first + 1.0, 7.0), across, down);
    return bedrockSrgbToLinear(mix(a, b, strip - first)) * VPT_BEDROCK_SKY_INTENSITY;
}

// The sunlight's colour and strength for a sun this high, as a factor on the sun's radiance.
vec3 bedrockSunlight(float sunHeight) {
    float x = acos(clamp(sunHeight, -1.0, 1.0)) / PI;
    float u = (x * 127.0 + 0.5) / 128.0;
    vec3 colour = bedrockSrgbToLinear(texture(bedrockLutTexture, vec2(u, 0.125)).rgb);
    float strength = texture(bedrockLutTexture, vec2(u, 0.375)).r;
    return colour * strength;
}

#endif
