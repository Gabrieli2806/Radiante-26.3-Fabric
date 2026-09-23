#ifndef ADV_CELESTIAL_GLSL
#define ADV_CELESTIAL_GLSL

vec3 celestialNormalize(vec3 direction, vec3 fallback) {
    float len2 = dot(direction, direction);
    if (len2 <= 1e-8 || any(isnan(direction)) || any(isinf(direction))) { return fallback; }
    return direction * inversesqrt(len2);
}

vec3 celestialSunDirection() {
    // Any tilt of the sun's path (the custom inclination option) is already applied on the Java side.
    return celestialNormalize(skyUBO.sunDirection, vec3(0.0, 1.0, 0.0));
}

vec3 celestialMoonDirection() {
    return -celestialSunDirection();
}

#endif
