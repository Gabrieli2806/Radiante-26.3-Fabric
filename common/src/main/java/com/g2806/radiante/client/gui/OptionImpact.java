package com.g2806.radiante.client.gui;

import java.util.Map;

/**
 * How much an option costs in frame time, shown under its description as Sodium does. Measured where the code
 * comments say so (QualityPreset); the rest is what the option does per pixel. Options not listed cost nothing
 * noticeable and show no line.
 */
enum OptionImpact {
    LOW("options.radiante.impact.low", 0xFF7FD17F),
    MEDIUM("options.radiante.impact.medium", 0xFFE8C860),
    HIGH("options.radiante.impact.high", 0xFFE86060),
    VARIES("options.radiante.impact.varies", 0xFFB0B0B0);

    final String key;
    final int color;

    OptionImpact(String key, int color) {
        this.key = key;
        this.color = color;
    }

    private static final Map<String, OptionImpact> BY_OPTION = Map.ofEntries(
        Map.entry("options.radiante.quality", VARIES),
        Map.entry("options.radiante.preset", HIGH),
        Map.entry("options.radiante.dlss_mode", HIGH),
        Map.entry("options.radiante.frame_generation", VARIES),
        Map.entry("options.radiante.ray_bounces", HIGH),
        Map.entry("options.radiante.volumetric_fog", MEDIUM),
        Map.entry("options.radiante.volumetric_fog_samples", MEDIUM),
        Map.entry("options.radiante.cloud_mode", MEDIUM),
        Map.entry("options.radiante.block_light_sampling", MEDIUM),
        Map.entry("options.radiante.parallax", LOW),
        Map.entry("options.radiante.motion_blur", LOW),
        Map.entry("options.radiante.depth_of_field", LOW),
        Map.entry("options.radiante.tunable.light_shafts", LOW),
        Map.entry("options.radiante.tunable.water_god_rays", LOW),
        Map.entry("options.radiante.chunk_building_threads", VARIES),
        Map.entry("options.radiante.hdr_output", LOW));

    static OptionImpact of(String optionKey) {
        return BY_OPTION.get(optionKey);
    }
}
