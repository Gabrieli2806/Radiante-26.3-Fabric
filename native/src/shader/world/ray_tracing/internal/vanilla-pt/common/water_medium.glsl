#ifndef VPT_WATER_MEDIUM_GLSL
#define VPT_WATER_MEDIUM_GLSL

// Water as a participating medium, the way Bedrock RTX treats it: along a path through the water light is absorbed and
// scattered per colour channel, so shallows stay clear and deep water turns blue or green. The coefficients come from a
// converted Bedrock pack's biome fog, or else from the biome's vanilla water colour (skyUBO.waterExtinction.w is 1 once
// either is set).
//
// Needs skyUBO, skyFull and the volumetric cloud helpers (for the sun or moon light) to be declared first.

bool waterMediumActive() {
    return skyUBO.waterExtinction.w > 0.5;
}

// Light the water scatters towards the viewer: the sky above and the sun or moon, both taken as they reach the surface.
// How deep the scattering happens is not known here, so light that has already crossed some water is not dimmed for it.
vec3 waterMediumIncomingLight() {
    vec3 zenith = texture(skyFull, vec3(0.0, 1.0, 0.0)).rgb;
    vec3 horizon = texture(skyFull, normalize(vec3(0.7, 0.35, 0.6))).rgb;
    // Isotropic phase: the sky fills about half the sphere, the sun is one direction of it.
    vec3 sky = (zenith + horizon) * 0.25;
    vec3 celestial = volumetricCloudPrimaryLightRadiance() * 0.0795775 * (1.0 - skyUBO.rainGradient * 0.65);
    return max(sky + celestial, vec3(0.0));
}

// What a stretch of water of the given length lets through, and the light it scatters into it on the way.
void waterMediumSegment(float distance, out vec3 transmittance, out vec3 inScatter) {
    vec3 sigmaT = max(skyUBO.waterExtinction.rgb, vec3(0.0));
    transmittance = exp(-sigmaT * max(distance, 0.0));
    inScatter = clamp(skyUBO.waterAlbedo.rgb, vec3(0.0), vec3(1.0)) * (vec3(1.0) - transmittance) *
                waterMediumIncomingLight();
}

#endif
