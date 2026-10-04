package com.g2806.radiante.client.gui;

import com.g2806.radiante.client.pipeline.Pipeline;
import java.util.List;
import java.util.Locale;

/**
 * A numeric pipeline setting the Radiante screen shows as a slider: a shader pack attribute (module null) or another
 * module's. The slider runs in whole steps; the stored value is {@code steps * unit}. Several are also part of the
 * picture style (see {@link PictureStyle}).
 */
enum Tunable {
    SATURATION(Pipeline.TONE_MAPPING_MODULE_NAME, "render_pipeline.module.tone_mapping.attribute.saturation",
        0.01, 50, 200, 120, Format.PERCENT),
    EXPOSURE_ADAPTATION(Pipeline.TONE_MAPPING_MODULE_NAME,
        "render_pipeline.module.tone_mapping.attribute.exposure_adaptation", 0.01, 0, 100, 50, Format.PERCENT),
    EXPOSURE_BIAS(Pipeline.TONE_MAPPING_MODULE_NAME, "render_pipeline.module.tone_mapping.attribute.exposure_bias",
        0.1, -30, 30, 7, Format.EV),
    // One slider for both directions: how fast the exposure follows going into the light and into the dark.
    EXPOSURE_SPEED(Pipeline.TONE_MAPPING_MODULE_NAME, "render_pipeline.module.tone_mapping.attribute.exposure_up_speed",
        "render_pipeline.module.tone_mapping.attribute.exposure_down_speed", 0.08, 5, 250, 100, Format.PERCENT),
    DOF_STRENGTH(null, "render_pipeline.module.ray_tracing.attribute.post_dof_max_radius", 0.04, 25, 400, 100,
        Format.PERCENT),
    DOF_RANGE(null, "render_pipeline.module.ray_tracing.attribute.post_dof_focus_range", 0.1, 10, 400, 100,
        Format.PERCENT),
    DOF_FOCUS_DISTANCE(null, "render_pipeline.module.ray_tracing.attribute.post_dof_focus_distance", 1.0, 1, 256, 12,
        Format.BLOCKS),
    MOTION_BLUR_STRENGTH(null, "render_pipeline.module.ray_tracing.attribute.post_motion_blur_strength", 0.0135, 10,
        220, 100, Format.PERCENT),
    FSR_SHARPNESS(Pipeline.FSR_MODULE_NAME, "render_pipeline.module.fsr_upscaler.attribute.sharpness", 0.01, 0, 100,
        70, Format.PERCENT),
    // The most the exposure may brighten a dark scene.
    LOW_LIGHT_BOOST(Pipeline.TONE_MAPPING_MODULE_NAME, "render_pipeline.module.tone_mapping.attribute.max_exposure",
        0.02, 25, 400, 100, Format.PERCENT),
    CLOUD_COVERAGE(null, "render_pipeline.module.ray_tracing.attribute.volumetric_cloud_coverage", 0.01, 0, 100, 50,
        Format.PERCENT),
    CLOUD_DENSITY(null, "render_pipeline.module.ray_tracing.attribute.volumetric_cloud_density", 0.01, 0, 400, 100,
        Format.PERCENT),
    CLOUD_QUALITY(null, "render_pipeline.module.ray_tracing.attribute.volumetric_cloud_view_steps", 1.0, 8, 256, 64,
        Format.NUMBER),
    FOG_DISTANCE(null, "render_pipeline.module.ray_tracing.attribute.volumetric_light_max_distance", 1.0, 48, 192,
        128, Format.BLOCKS),
    // Reflections inside reflections allowed on top of the light bounces.
    MIRROR_BOUNCES(null, "render_pipeline.module.ray_tracing.attribute.mirror_extra_bounces", 1.0, 0, 64, 8,
        Format.NUMBER),
    STARS(Pipeline.POST_RENDER_MODULE_NAME, "render_pipeline.module.post_render.attribute.star_count", 100.0, 1, 200,
        30, Format.NUMBER),
    BOUNCE_LIGHT(null, "render_pipeline.module.ray_tracing.attribute.bounce_light_boost", 0.01, 50, 200, 150,
        Format.PERCENT),
    SKY_LIGHT(null, "render_pipeline.module.ray_tracing.attribute.sky_light_boost", 0.01, 50, 400, 180, Format.PERCENT),
    SUN_GLOW(null, "render_pipeline.module.ray_tracing.attribute.sun_glow", 0.0008, 0, 300, 100, Format.PERCENT),
    LIGHT_SHAFTS(null, "render_pipeline.module.ray_tracing.attribute.light_shafts", 0.00015, 0, 400, 100,
        Format.PERCENT),
    WATER_WAVES(null, "render_pipeline.module.ray_tracing.attribute.water_wave_strength", 0.0035, 0, 400, 100,
        Format.PERCENT),
    WATER_DENSITY(null, "render_pipeline.module.ray_tracing.attribute.water_density", 0.001, 0, 500, 100,
        Format.PERCENT),
    WATER_GOD_RAYS(null, "render_pipeline.module.ray_tracing.attribute.water_god_rays", 0.0012, 0, 400, 100,
        Format.PERCENT);

    /** NUMBER is a whole number, written without a decimal point; BLOCKS is a distance in blocks. */
    enum Format { PERCENT, EV, NUMBER, BLOCKS }

    final String module;
    final String attribute;
    /** A second attribute of the same module kept at the same value, or null. */
    final String twin;
    final double unit;
    final int min;
    final int max;
    final int defaultSteps;
    final Format format;

    Tunable(String module, String attribute, double unit, int min, int max, int defaultSteps, Format format) {
        this(module, attribute, null, unit, min, max, defaultSteps, format);
    }

    Tunable(String module, String attribute, String twin, double unit, int min, int max, int defaultSteps,
        Format format) {
        this.module = module;
        this.attribute = attribute;
        this.twin = twin;
        this.unit = unit;
        this.min = min;
        this.max = max;
        this.defaultSteps = defaultSteps;
        this.format = format;
    }

    String key() {
        return "options.radiante.tunable." + name().toLowerCase(Locale.ROOT);
    }

    /** The current value in slider steps, or null when the pipeline does not have the setting. */
    Integer read() {
        String value = this.module == null ? Pipeline.getShaderPackValue(this.attribute)
            : Pipeline.getModuleValue(this.module, this.attribute);
        if (value == null) {
            return null;
        }
        try {
            return clamp((int) Math.round(Double.parseDouble(value.trim()) / this.unit));
        } catch (NumberFormatException notANumber) {
            return this.defaultSteps;
        }
    }

    /** Writes the value; true when it changed and the pipeline has to be rebuilt. */
    boolean write(int steps) {
        String value = this.format == Format.NUMBER ? String.valueOf(Math.round(clamp(steps) * this.unit))
            : String.format(Locale.ROOT, "%.5f", clamp(steps) * this.unit).replaceAll("0+$", "")
                .replaceAll("\\.$", ".0");
        String current = this.module == null ? Pipeline.getShaderPackValue(this.attribute)
            : Pipeline.getModuleValue(this.module, this.attribute);
        try {
            if (current != null && Math.abs(Double.parseDouble(current.trim()) - clamp(steps) * this.unit) < this.unit * 0.5) {
                return false;
            }
        } catch (NumberFormatException ignored) {
            // Written below.
        }
        boolean changed = this.module == null ? Pipeline.setShaderPackValue(this.attribute, value)
            : Pipeline.setModuleValue(this.module, this.attribute, value);
        if (this.twin != null) {
            changed |= this.module == null ? Pipeline.setShaderPackValue(this.twin, value)
                : Pipeline.setModuleValue(this.module, this.twin, value);
        }
        return changed;
    }

    int clamp(int steps) {
        return Math.max(this.min, Math.min(this.max, steps));
    }

    static final List<Tunable> ALL = List.of(values());
}
