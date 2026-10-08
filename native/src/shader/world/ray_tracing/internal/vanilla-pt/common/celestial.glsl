#ifndef VPT_CELESTIAL_GLSL
#define VPT_CELESTIAL_GLSL

vec3 celestialNormalize(vec3 direction, vec3 fallback) {
    float len2 = dot(direction, direction);
    if (len2 <= 1e-8 || any(isnan(direction)) || any(isinf(direction))) { return fallback; }
    return direction * inversesqrt(len2);
}

vec3 celestialSunDirection() {
    // Any tilt of the sun's path (the custom inclination option) is already applied on the Java side.
    return celestialNormalize(skyUBO.sunDirection, vec3(0.0, 1.0, 0.0));
}

// The End flash (Minecraft 26.x): every 30 seconds or so a violet glare lights up the End sky for 5 to 19 seconds,
// rising and falling as a sine, from a direction picked at random each time. In the End the Java side hands its
// direction over in sunDirection, scaled by its intensity (0 when there is none), so it lights the islands as a
// sun would and casts their shadows.
const vec3 END_FLASH_RADIANCE = vec3(1.2, 0.7, 1.8);

float endFlashIntensity() {
    if (worldUBO.skyType != 2) { return 0.0; }
    float intensity = length(skyUBO.sunDirection);
    return isnan(intensity) || isinf(intensity) ? 0.0 : clamp(intensity, 0.0, 1.0);
}

vec3 celestialMoonDirection() {
    return -celestialSunDirection();
}

#endif
