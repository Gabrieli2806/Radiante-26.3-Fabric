package com.g2806.radiante.client.profile;

import com.g2806.radiante.client.pipeline.Pipeline;

import java.util.List;

/**
 * Every setting the Bedrock RTX profile manages, in one place. Compatibility values are not scattered through the
 * renderer: the native code only reads attributes, and what makes it "Bedrock" is this table.
 *
 * <p>Each entry says how well its value is known ({@link ParameterStatus}). The rule while there are no reference
 * captures: structure that was read from Bedrock's files is implemented; behaviour inferred from it is implemented
 * and said to be inferred; numbers nobody has measured are exposed, kept conservative, and marked
 * {@link ParameterStatus#APPROXIMATE} or {@link ParameterStatus#UNKNOWN}. Nothing here is claimed to be Bedrock's
 * exact value unless its status says {@link ParameterStatus#VERIFIED} or {@link ParameterStatus#MEASURED}.
 *
 * <p>Groups that exist in Bedrock but have no verified knob yet (sun, sky, gi, reflections, transmission, temporal,
 * materials) are listed in {@link #PENDING_GROUPS} with the reason, and have no entries on purpose: lighting
 * constants are not guessed.
 */
public final class BedrockRtxProfile {

    /**
     * Bloom pyramid depth: one uniform downscale, Gaussian downscales and upscales; the repeat count of four comes
     * from the public mcrtx-shader-template's description of the pass order (INFERRED). Fixed in the native renderer ({@code ToneMappingModule::BLOOM_UP_LEVELS}); it is not a runtime
     * setting, so it is documented here rather than exposed.
     */
    public static final int BLOOM_MIP_COUNT = 4;

    private static final String TONE = Pipeline.TONE_MAPPING_MODULE_NAME;
    private static final String A = "render_pipeline.module.tone_mapping.attribute.";
    private static final String TRUE = "render_pipeline.true";
    private static final String FALSE = "render_pipeline.false";

    private static final String RT = "render_pipeline.module.ray_tracing.attribute.";

    private static final String NOT_MEASURED = "no reference capture yet";

    /** The managed settings, grouped by the logical sections of the profile. */
    public static final List<CompatParameter> PARAMETERS = List.of(
        // ---- exposure
        new CompatParameter("exposure", TONE, A + "enable_auto_exposure", TRUE, TRUE, ParameterStatus.VERIFIED,
            "Bedrock builds its exposure from a luminance histogram"),
        new CompatParameter("exposure", TONE, A + "exposure_metering_mode", A + "exposure_metering_mode.light_meter",
            A + "exposure_metering_mode.center", ParameterStatus.INFERRED,
            "Bedrock's light meter: power mean of 16 x 16 samples, brightest channel, white surface to 0.75 "
                + "(observed); Radiante meters the picture, not incident light, "
                + "so it aims at middle grey (INFERRED)"),

        // ---- tone mapping
        new CompatParameter("toneMapping", TONE, A + "method", A + "method.bedrock_provisional",
            A + "method.pbr_neutral", ParameterStatus.MATCHES_BEDROCK,
            "the curve is built from a luminance histogram as Bedrock's is, and applied with the tone mapping material's grade; its run-time "
                + "parameters below are not known"),
        new CompatParameter("toneMapping", TONE, A + "curve_dynamic_range", "8.0", "8.0", ParameterStatus.UNKNOWN,
            "toneMappingDynamicRange: stops from white to black of the curve (at least 2.56); value "
                + NOT_MEASURED),
        new CompatParameter("toneMapping", TONE, A + "curve_shift", "0.0", "0.0", ParameterStatus.UNKNOWN,
            "toneMappingCurveShift: added to the curve, in stops; neutral until measured"),
        new CompatParameter("toneMapping", TONE, A + "curve_max_exposure_increase", "2.0", "2.0",
            ParameterStatus.UNKNOWN,
            "toneMappingMaxExposureIncrease: how many stops the curve may brighten any luminance; " + NOT_MEASURED),
        new CompatParameter("toneMapping", TONE, A + "curve_shadow_min_slope", "0.0", "0.0", ParameterStatus.UNKNOWN,
            "toneMappingShadowMinSlope: the least contrast kept below -2.45 stops; neutral until measured"),
        new CompatParameter("toneMapping", TONE, A + "filmic_saturation", "0.0", "0.0", ParameterStatus.UNKNOWN,
            "the filmic saturation correction control: the correction's response is observed, its "
                + "strength is not known; off until measured"),
        new CompatParameter("toneMapping", TONE, A + "contrast", "1.0", "1.0", ParameterStatus.UNKNOWN,
            "the contrast control exists; its value and exact meaning are unknown"),
        new CompatParameter("toneMapping", TONE, A + "shadow_contrast", "0.0", "0.0", ParameterStatus.UNKNOWN,
            "toneMappingShadowContrast: stops taken off per stop below shadow_contrast_end (observed "
                + "meaning); value unknown, neutral until measured"),
        new CompatParameter("toneMapping", TONE, A + "shadow_contrast_end", "0.1", "0.1", ParameterStatus.UNKNOWN,
            "toneMappingShadowContrastEnd: in log2 luminance with the Bedrock curve; value unknown"),
        new CompatParameter("toneMapping", TONE, A + "gamma", "1.0", "2.2", ParameterStatus.INFERRED,
            "with the Bedrock curve this is the gamma control, a power on the graded colour before the exact sRGB "
                + "encoding (observed); 1 leaves the grade neutral, its real value " + NOT_MEASURED),
        new CompatParameter("toneMapping", TONE, A + "color_balance_r", "1.0", "1.0", ParameterStatus.UNKNOWN,
            "the color balance control exists; neutral until measured"),
        new CompatParameter("toneMapping", TONE, A + "color_balance_g", "1.0", "1.0", ParameterStatus.UNKNOWN,
            "the color balance control exists; neutral until measured"),
        new CompatParameter("toneMapping", TONE, A + "color_balance_b", "1.0", "1.0", ParameterStatus.UNKNOWN,
            "the color balance control exists; neutral until measured"),

        // ---- materials (shader pack attributes, module null). Only behaviour verified from the compiled shader.
        new CompatParameter("materials", null, RT + "albedo_srgb_exact", TRUE, FALSE, ParameterStatus.MATCHES_BEDROCK,
            "colour textures are decoded with the exact piecewise sRGB curve  "
                + "section 5)"),

        new CompatParameter("materials", null, RT + "bedrock_emission", TRUE, FALSE, ParameterStatus.MATCHES_BEDROCK,
            "emission follows MER green squared; exact up to green 0.25 with converted packs, brighter sources stay "
                + "at full strength (the multiplier is a run-time value, not known)"),

        // ---- transmission
        new CompatParameter("transmission", null, RT + "glass_reflection", "1.0", "0.5", ParameterStatus.INFERRED,
            "Bedrock does not dim the Fresnel reflection of glass; Radiante's default halves it"),
        new CompatParameter("transmission", null, RT + "bedrock_glass_tint", TRUE, FALSE, ParameterStatus.INFERRED,
            "the view through glass is coloured by colour x (1 - saturate(2 alpha - 1)), the rule Bedrock's sun "
                + "shadow uses (observed)"),
        new CompatParameter("transmission", null, RT + "water_surface_mode", RT + "water_surface_mode.bedrock",
            RT + "water_surface_mode.realistic", ParameterStatus.MATCHES_BEDROCK,
            "Bedrock's water normal: three scrolling layers of its water_n texture, faded with the pixel footprint; "
                + "needs a Bedrock install or radiante/bedrock/water_n.tga"),
        new CompatParameter("transmission", null, RT + "bedrock_fresnel", TRUE, FALSE, ParameterStatus.MATCHES_BEDROCK,
            "glass and water reflectance is Schlick with the cosine remapped to the critical angle; "
                + "the critical-angle cosine is derived from the "
                + "indices (INFERRED), Bedrock reads it from a table"),

        new CompatParameter("transmission", null, RT + "bedrock_shadow_tint", TRUE, FALSE,
            ParameterStatus.MATCHES_BEDROCK,
            "sunlight through a see-through surface = texture colour x (1 - saturate(2 alpha - 1)), shadow when the "
                + "remainder is nearly nothing"),

        new CompatParameter("transmission", null, RT + "bedrock_caustics", TRUE, FALSE, ParameterStatus.MATCHES_BEDROCK,
            "sunlight under water is scaled by 1 + exp(-0.1 depth) (7 c + 0.8 - 1); "
                + "the repeat size of the pattern is a run-time value"),
        new CompatParameter("transmission", null, RT + "bedrock_medium_clamp", TRUE, FALSE,
            ParameterStatus.MATCHES_BEDROCK,
            "attenuation inside a medium counts at most 50 blocks of distance"),
        new CompatParameter("transmission", null, RT + "water_density", "1.0", "0.1", ParameterStatus.INFERRED,
            "Bedrock attenuates the camera's view through water with the full extinction of the medium "
                + "(observed, no thinning factor); Radiante had thinned it to 0.1 by eye. Under water this "
                + "gives the murky, distance-limited look. Needs checking against captures (scene J3)"),

        // ---- fog: Bedrock's volumetric fog in place of every other fog of the air
        new CompatParameter("fog", null, RT + "volumetric_light_mode", RT + "volumetric_light_mode.volumetric",
            RT + "volumetric_light_mode.vanilla", ParameterStatus.MATCHES_BEDROCK,
            "Bedrock fogs the air with a froxel volume"),
        new CompatParameter("fog", null, RT + "froxel_fog", TRUE, TRUE, ParameterStatus.MATCHES_BEDROCK,
            "the volume is a froxel grid, as Radiante's"),
        new CompatParameter("fog", null, RT + "bedrock_volumetric_fog", TRUE, FALSE, ParameterStatus.MATCHES_BEDROCK,
            "50-block volume with 5 (11^w - 1) slices, the pack's air medium and height profile, sun through shadow "
                + "rays with 1/pi Henyey-Greenstein, sky light; replaces the biome haze, light shafts and Radiante's "
                + "air"),
        new CompatParameter("fog", null, RT + "bedrock_fog_anisotropy", "0.6", "0.6", ParameterStatus.UNKNOWN,
            "fogHenyeyGreensteinG is a run-time value; " + NOT_MEASURED),

        // ---- bloom
        new CompatParameter("bloom", TONE, A + "bloom_enable", TRUE, FALSE, ParameterStatus.INFERRED,
            "Bedrock RTX has a bloom stage; whether it is always on is not known"),
        new CompatParameter("bloom", TONE, A + "bloom_intensity", "0.04", "0.04", ParameterStatus.UNKNOWN,
            "gBloomMultiplier exists; its value is unknown. Conservative, " + NOT_MEASURED),
        new CompatParameter("bloom", TONE, A + "bloom_scatter", "0.5", "0.5", ParameterStatus.APPROXIMATE,
            "how much of the wider levels is carried back up; the filter shape is a placeholder"),
        new CompatParameter("bloom", TONE, A + "bloom_threshold", "0.0", "0.0", ParameterStatus.UNKNOWN,
            "no threshold uniform is visible in the Bloom material; 0 keeps everything"),
        new CompatParameter("bloom", TONE, A + "bloom_weight_1", "1.0", "1.0", ParameterStatus.UNKNOWN,
            "per-level weight, finest; " + NOT_MEASURED),
        new CompatParameter("bloom", TONE, A + "bloom_weight_2", "1.0", "1.0", ParameterStatus.UNKNOWN,
            "per-level weight; " + NOT_MEASURED),
        new CompatParameter("bloom", TONE, A + "bloom_weight_3", "1.0", "1.0", ParameterStatus.UNKNOWN,
            "per-level weight; " + NOT_MEASURED),
        new CompatParameter("bloom", TONE, A + "bloom_weight_4", "1.0", "1.0", ParameterStatus.UNKNOWN,
            "per-level weight, widest; " + NOT_MEASURED));

    /** Groups of the profile that have no entry yet, and why. */
    public static final List<String> PENDING_GROUPS = List.of(
        "materials: the loader converts Bedrock packs to LabPBR (BedrockPackConverter); emission is MER.g squared x 5 "
            + "x a run-time multiplier in Bedrock (observed) but is NOT applied yet: the converter's "
            + "LabPBR emission channel is lossy above g = 0.25, so a lossless raw encoding with a per-pack flag is "
            + "needed first; roughness transfer is unverified",
        "sun: sun/moon colour and strength come from Bedrock's own tables (bedrock_atmosphere); no extra knob",
        "sky: same tables; sky_intensity is the existing bedrock_sky_intensity attribute, value unknown",
        "gi: bounce count, irradiance-cache behaviour and importance sampling are not known",
        "reflections: roughness-to-BRDF mapping unknown",
        "transmission: glass and water Fresnel/tint need paired captures",
        "temporal: denoiser accumulation lengths (diffuseTemporalAlpha etc.) are runtime values, not read");

    private BedrockRtxProfile() {
    }

    /** Parameters of one group, in declaration order. */
    public static List<CompatParameter> group(String group) {
        return PARAMETERS.stream().filter(p -> p.group().equals(group)).toList();
    }
}
