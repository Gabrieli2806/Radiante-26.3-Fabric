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
     * Bloom pyramid depth. VERIFIED: the pass names of the unencrypted {@code RTXPostFX.Bloom} material
     * ({@code BloomDownscaleUniformPass}, {@code BloomDownscaleGaussianPass}, {@code BloomUpscalePass}); the
     * repeat count of four comes from the public mcrtx-shader-template's description of the pass order (INFERRED
     * for the count). Fixed in the native renderer ({@code ToneMappingModule::BLOOM_UP_LEVELS}); it is not a runtime
     * setting, so it is documented here rather than exposed.
     */
    public static final int BLOOM_MIP_COUNT = 4;

    private static final String TONE = Pipeline.TONE_MAPPING_MODULE_NAME;
    private static final String A = "render_pipeline.module.tone_mapping.attribute.";
    private static final String TRUE = "render_pipeline.true";
    private static final String FALSE = "render_pipeline.false";

    private static final String RT = "render_pipeline.module.ray_tracing.attribute.";

    private static final String MATERIAL_NAMES =
        "names read from the unencrypted RTXPostFX.Tonemapping material";
    private static final String NOT_MEASURED = "no reference capture yet";

    /** The managed settings, grouped by the logical sections of the profile. */
    public static final List<CompatParameter> PARAMETERS = List.of(
        // ---- exposure
        new CompatParameter("exposure", TONE, A + "enable_auto_exposure", TRUE, TRUE, ParameterStatus.VERIFIED,
            "Bedrock builds its exposure from a luminance histogram (passes ToneMappingHistogram, ToneCurve, "
                + "IncidentLightMeterInline, ResolveLightMeasurement)"),

        // ---- tone mapping
        new CompatParameter("toneMapping", TONE, A + "method", A + "method.bedrock_provisional",
            A + "method.pbr_neutral", ParameterStatus.UNKNOWN,
            "the stages exist in Bedrock (" + MATERIAL_NAMES + "); the curve itself is a placeholder in "
                + "tone_mapping.frag bedrockToneCurve, " + NOT_MEASURED),
        new CompatParameter("toneMapping", TONE, A + "contrast", "1.0", "1.0", ParameterStatus.UNKNOWN,
            "gToneMappingContrast exists (" + MATERIAL_NAMES + "); its value and exact meaning are unknown"),
        new CompatParameter("toneMapping", TONE, A + "shadow_contrast", "0.0", "0.0", ParameterStatus.UNKNOWN,
            "gToneMappingShadowContrast exists; value unknown, neutral until measured"),
        new CompatParameter("toneMapping", TONE, A + "shadow_contrast_end", "0.1", "0.1", ParameterStatus.UNKNOWN,
            "gToneMappingShadowContrastEnd exists; value unknown"),
        new CompatParameter("toneMapping", TONE, A + "gamma", "2.2", "2.2", ParameterStatus.APPROXIMATE,
            "gToneMappingGamma exists; 2.2 is the sRGB approximation Radiante already used"),
        new CompatParameter("toneMapping", TONE, A + "color_balance_r", "1.0", "1.0", ParameterStatus.UNKNOWN,
            "gToneMappingColorBalance exists; neutral until measured"),
        new CompatParameter("toneMapping", TONE, A + "color_balance_g", "1.0", "1.0", ParameterStatus.UNKNOWN,
            "gToneMappingColorBalance exists; neutral until measured"),
        new CompatParameter("toneMapping", TONE, A + "color_balance_b", "1.0", "1.0", ParameterStatus.UNKNOWN,
            "gToneMappingColorBalance exists; neutral until measured"),

        // ---- materials (shader pack attributes, module null). Only behaviour verified from the compiled shader.
        new CompatParameter("materials", null, RT + "albedo_srgb_exact", TRUE, FALSE, ParameterStatus.VERIFIED_FROM_DXIL,
            "colour textures are decoded with the exact piecewise sRGB curve (research/PrimaryCheckerboardRayGenInline.md "
                + "section 5)"),

        // ---- transmission
        new CompatParameter("transmission", null, RT + "bedrock_fresnel", TRUE, FALSE, ParameterStatus.VERIFIED_FROM_DXIL,
            "glass and water reflectance is Schlick with the cosine remapped to the critical angle (research/"
                + "PrimaryCheckerboardRayGenInline.md section 6); the critical-angle cosine is derived from the "
                + "indices (INFERRED), Bedrock reads it from a table"),

        new CompatParameter("transmission", null, RT + "bedrock_shadow_tint", TRUE, FALSE,
            ParameterStatus.VERIFIED_FROM_DXIL,
            "sunlight through a see-through surface = texture colour x (1 - saturate(2 alpha - 1)), shadow when the "
                + "remainder is nearly nothing (research/SunShadowRayGenInline.md)"),

        // ---- bloom
        new CompatParameter("bloom", TONE, A + "bloom_enable", TRUE, FALSE, ParameterStatus.INFERRED,
            "Bedrock RTX has a bloom stage (RTXPostFX.Bloom material); whether it is always on is not known"),
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
            + "x a run-time multiplier in Bedrock (VERIFIED_FROM_DXIL) but is NOT applied yet: the converter's "
            + "LabPBR emission channel is lossy above g = 0.25, so a lossless raw encoding with a per-pack flag is "
            + "needed first; roughness transfer is unverified (see research/PrimaryCheckerboardRayGenInline.md)",
        "sun: sun/moon colour and strength come from Bedrock's own tables (bedrock_atmosphere); no extra knob",
        "sky: same tables; sky_intensity is the existing bedrock_sky_intensity attribute, value unknown",
        "gi: bounce count, irradiance-cache behaviour and importance sampling are inside encrypted shaders",
        "reflections: roughness-to-BRDF mapping unknown",
        "transmission: glass and water Fresnel/tint need paired captures (REFERENCE_CAPTURE_PROTOCOL.md)",
        "temporal: denoiser accumulation lengths (diffuseTemporalAlpha etc.) are runtime values, not read");

    private BedrockRtxProfile() {
    }

    /** Parameters of one group, in declaration order. */
    public static List<CompatParameter> group(String group) {
        return PARAMETERS.stream().filter(p -> p.group().equals(group)).toList();
    }
}
