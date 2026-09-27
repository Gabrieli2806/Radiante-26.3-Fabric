#ifndef VPT_WATER_MEDIUM_GLSL
#define VPT_WATER_MEDIUM_GLSL

// Water as a participating medium, the way Bedrock RTX treats it: along a path through the water light is absorbed and
// scattered per colour channel, so shallows stay clear and deep water turns blue or green. The coefficients come from a
// converted Bedrock pack's biome fog, or else from the biome's vanilla water colour (skyUBO.waterExtinction.w is 1 once
// either is set).
//
// Needs skyUBO, skyFull and the volumetric cloud helpers (for the sun or moon light) to be declared first.

#ifndef VPT_WATER_SCATTERING
#    define VPT_WATER_SCATTERING 8.0
#endif
#ifndef VPT_WATER_DENSITY
#    define VPT_WATER_DENSITY 0.3
#endif

// How thick the water is to look through, against its coefficients: Bedrock RTX's water is clear up close and
// only fogs out far away, while the light through it still takes on its colour. Only the camera's own stretch of
// water is thinned; the light's paths (bounces, and the sun in shadow.rahit) cross it at full strength.
vec3 waterMediumExtinction(float density) {
    return max(skyUBO.waterExtinction.rgb, vec3(0.0)) * max(density, 0.0);
}

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

// The colour the scattered light takes: white light after this many blocks of the water. Scattering alone (its
// albedo, bluest of all) left Bedrock packs' water a deep navy, where Bedrock RTX shows it turquoise: most of the
// light the water glows with has crossed some of it first, off the bottom and the walls.
const float WATER_MEDIUM_HUE_DEPTH = 8.0;

// What a stretch of water of the given length lets through, and the light it scatters into it on the way; density
// scales the water (VPT_WATER_DENSITY for the camera's view, 1 for light).
void waterMediumSegment(float distance, float density, out vec3 transmittance, out vec3 inScatter) {
    vec3 sigmaT = waterMediumExtinction(density);
    transmittance = exp(-sigmaT * max(distance, 0.0));
    const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
    vec3 hue = exp(-max(skyUBO.waterExtinction.rgb, vec3(0.0)) * WATER_MEDIUM_HUE_DEPTH);
    hue /= max(dot(hue, LUMA), 1e-4);
    float albedo = clamp(dot(skyUBO.waterAlbedo.rgb, LUMA) * max(VPT_WATER_SCATTERING, 0.0), 0.0, 1.0);
    float light = dot(waterMediumIncomingLight(), LUMA);
    inScatter = hue * albedo * light * (vec3(1.0) - transmittance);
}

#endif
