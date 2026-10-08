#ifndef LABPBR_GLSL
#define LABPBR_GLSL

#define EPS 1e-6

struct LabPBRMat {
    vec3 albedo;
    vec3 f0;
    float roughness;
    float metallic;
    float subSurface;
    float transmission;
    float ior;
    float emission;
    vec3 normal;
    float ao;
    float height;
};

vec3 CalculateF0(vec3 n, vec3 k) {
    vec3 k2 = max(k * k, vec3(1e-6));
    vec3 r = ((n - 1.0) * (n - 1.0) + k2) / ((n + 1.0) * (n + 1.0) + k2);
    return r;
}

// The colour light takes through a see-through block, as Bedrock RTX filters it: the texture's hue at full
// brightness, as strongly as its alpha says it is coloured. A stained pane's texture is a dark colour; filtering by
// it as it stands let almost no light through, where Bedrock's tinted glass throws its colour bright and clear.
// How much a glass colour reads as dull, dark glass (tinted glass) rather than coloured glass: 0 to 1.
float glassDarkness(vec3 colour) {
    float peak = max(colour.r, max(colour.g, colour.b));
    float low = min(colour.r, min(colour.g, colour.b));
    float saturation = peak > 1e-3 ? (peak - low) / peak : 0.0;
    return (1.0 - smoothstep(0.4, 0.65, saturation)) * (1.0 - smoothstep(0.35, 0.6, peak));
}

vec3 glassTint(vec3 colour, float alpha) {
    float peak = max(colour.r, max(colour.g, colour.b));
    vec3 hue = peak > 1e-3 ? colour / peak : vec3(1.0);
    // A dull, dark glass (tinted glass) is dark on purpose: it keeps some of its darkness, so it shows the world
    // dimly instead of as clear as stained glass. Coloured glass filters by its hue alone.
    // Light crosses two faces of a block, each squaring this: only a few percent get through tinted glass.
    hue *= mix(1.0, pow(max(peak, 0.0), 0.45), glassDarkness(colour));
    return mix(vec3(1.0), hue, smoothstep(0.0, 0.3, alpha));
}

#ifndef VPT_GLASS_TINT_MODEL
#    define VPT_GLASS_TINT_MODEL 0
#endif

// The colour a view ray takes through one see-through surface. Bedrock compatibility (VPT_GLASS_TINT_MODEL): the
// rule its sun shadow applies to the same surfaces (observed; that camera rays are filtered
// alike is INFERRED): the texture colour as it is, times how clear the surface is with the lower half of the alpha
// range counting as fully clear, colour * (1 - saturate(2 alpha - 1)).
vec3 viewGlassTintBedrock(vec3 colour, float alpha) {
    // A texel with no coverage at all carries no colour of its own (Java textures leave it black or arbitrary where
    // Bedrock's are authored): it is clear.
    if (alpha <= 1.0 / 255.0) { return vec3(1.0); }
    return colour * (1.0 - clamp(alpha * 2.0 - 1.0, 0.0, 1.0));
}

vec3 viewGlassTint(vec3 colour, float alpha) {
#if VPT_GLASS_TINT_MODEL != 0
    return viewGlassTintBedrock(colour, alpha);
#else
    return glassTint(colour, alpha);
#endif
}

LabPBRMat convertLabPBRMaterial(vec4 texAlbedo, vec4 texSpecular, vec4 texNormal) {
    LabPBRMat mat;

    mat.roughness = pow(1.0 - texSpecular.r, 2.0);
    // LabPBR: A value of 255 (100%) results in a very smooth material (e.g. polished granite)
    mat.roughness = mix(0.01, 1.0, mat.roughness);

    float sssOffset = 65.0 / 255.0;
    mat.subSurface = texSpecular.b < sssOffset ? 0.0 : (texSpecular.b - sssOffset) / (1.0 - sssOffset);

    int metalIdx = int(round(texSpecular.g * 255.0));

    mat.metallic = 0.0;
    mat.transmission = 0.0;
    mat.ior = 1.5;
    mat.f0 = vec3(0.04);

    int intEmission = int(round(texSpecular.a * 255.0));
    if (intEmission == 255) {
        mat.emission = 0;
    } else {
        mat.emission = intEmission / 254.0;
#if defined(VPT_EMISSION_MODEL) && VPT_EMISSION_MODEL != 0
        // Bedrock compatibility (observed: emission = MER green squared). Converted Bedrock packs store
        // 4 x green, capped at the top of LabPBR's range, so squaring the stored value restores Bedrock's square law
        // exactly up to green 0.25 and keeps brighter sources at full strength (the multiplier above that is a
        // run-time value, not known).
        mat.emission *= mat.emission;
#endif
        // Lamps, torches and glowstone a little softer than the texture says: they lit rooms too strongly at night.
        mat.emission *= 0.65;
    }

    if (metalIdx < 230) {
        mat.metallic = 0.0;
        mat.albedo = texAlbedo.rgb;

        float specularValue = texSpecular.g;
        float F0 = max(specularValue, 0.02); // LabPBR clamp
        mat.f0 = vec3(F0);

        float sqrtF0 = sqrt(F0);
        mat.ior = (1.0 + sqrtF0) / max(1.0 - sqrtF0, EPS);

        if (texAlbedo.a < 1.0 - EPS) {
            mat.transmission = 1.0;
            // Vanilla blends see-through blocks by their alpha, so a half-opaque red pane still passes half the scene
            // behind it untinted. Filtering the view by the full saturated colour made stained and tinted glass read
            // near-opaque; the alpha sets how strongly the colour filters instead.
            mat.albedo = glassTint(texAlbedo.rgb, texAlbedo.a);
            // A see-through block with subsurface scattering authored (Radiante's own ice maps) is a translucent
            // solid, not glass: its alpha is how much of it is solid. The surface is always hit and shaded, and only
            // the rest of the light goes through, which is vanilla's blend without per-pixel hit-or-miss noise.
            if (mat.subSurface > 0.0) {
                mat.transmission = 1.0 - texAlbedo.a;
                mat.albedo = texAlbedo.rgb;
            }
            // Vanilla glass, stained glass and panes have no specular map, and an absent one decodes as a fully
            // rough surface: the light passing through them scatters and they read as frosted rather than clear.
            // With nothing authored for them, see-through blocks are treated as smooth glass instead.
            if (texSpecular.r <= 0.0 && texSpecular.g <= 0.0 && texSpecular.b <= 0.0 && texSpecular.a <= 0.0) {
                mat.roughness = 0.02;
                mat.f0 = vec3(0.04);
                mat.ior = 1.5;
            }
        }
    } else if (metalIdx <= 237) {
        vec3 n = vec3(1.0);
        vec3 k = vec3(0.0);

        if (metalIdx == 230) { // Iron
            n = vec3(2.9114, 2.9497, 2.5845);
            k = vec3(3.0893, 2.9318, 2.7670);
        } else if (metalIdx == 231) { // Gold
            n = vec3(0.18299, 0.42108, 1.3734);
            k = vec3(3.4242, 2.3459, 1.7704);
        } else if (metalIdx == 232) { // Aluminium
            n = vec3(1.3456, 0.96521, 0.61722);
            k = vec3(7.4746, 6.3995, 5.3031);
        } else if (metalIdx == 233) { // Chrome
            n = vec3(3.1071, 3.1812, 2.3230);
            k = vec3(3.3314, 3.3291, 3.1350);
        } else if (metalIdx == 234) { // Copper
            n = vec3(0.27105, 0.67693, 1.3164);
            k = vec3(3.6092, 2.6248, 2.2921);
        } else if (metalIdx == 235) { // Lead
            n = vec3(1.9100, 1.8300, 1.4400);
            k = vec3(3.5100, 3.4000, 3.1800);
        } else if (metalIdx == 236) { // Platinum
            n = vec3(2.3757, 2.0847, 1.8453);
            k = vec3(4.2655, 3.7153, 3.1365);
        } else if (metalIdx == 237) { // Silver
            n = vec3(0.15943, 0.14512, 0.13547);
            k = vec3(3.9291, 3.1900, 2.3808);
        }

        mat.metallic = 1.0;
        mat.f0 = CalculateF0(n, k);
        mat.albedo = mat.f0;
    } else if (metalIdx < 255) {
        // 238-254, which LabPBR leaves unassigned: partly metallic, (value - 237) / 18. Bedrock RTX packs blend
        // metalness smoothly (gems sit around half), and converted with only "metal or not" their surfaces broke up
        // into mirror and plain texels. Only Radiante's Bedrock conversion writes these.
        mat.metallic = float(metalIdx - 237) / 18.0;
        mat.albedo = texAlbedo.rgb;
        mat.f0 = mix(vec3(0.04), texAlbedo.rgb, mat.metallic);
    } else {
        mat.metallic = 1.0;
        mat.albedo = texAlbedo.rgb;
        mat.f0 = texAlbedo.rgb;
    }

    mat.normal.xy = texNormal.xy * 2.0 - 1.0;
    mat.normal.z = sqrt(1.0 - dot(mat.normal.xy, mat.normal.xy));

    mat.ao = texNormal.x;
    mat.height = texNormal.w;

    return mat;
}

#endif