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

    enum Format { PERCENT, EV }

    final String module;
    final String attribute;
    final double unit;
    final int min;
    final int max;
    final int defaultSteps;
    final Format format;

    Tunable(String module, String attribute, double unit, int min, int max, int defaultSteps, Format format) {
        this.module = module;
        this.attribute = attribute;
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
        String value = String.format(Locale.ROOT, "%.5f", clamp(steps) * this.unit).replaceAll("0+$", "")
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
        return this.module == null ? Pipeline.setShaderPackValue(this.attribute, value)
            : Pipeline.setModuleValue(this.module, this.attribute, value);
    }

    int clamp(int steps) {
        return Math.max(this.min, Math.min(this.max, steps));
    }

    static final List<Tunable> ALL = List.of(values());
}
