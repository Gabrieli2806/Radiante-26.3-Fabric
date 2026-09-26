package com.g2806.radiante.client.gui;

/**
 * Quick quality levels for the settings measured to cost frame time (one scene, same chunks, DLSS Ultra
 * Performance as the baseline at about 150 fps): the DLSS mode (Performance -45 %, Quality -65 %), block light
 * sampling (-20 %), volumetric fog (-10 %), render distance (24 chunks -30 % against 16) and clouds (a few
 * percent). The held light, the first person shadow and the bounce count changed nothing measurable and are
 * left out. Any of these changed by hand afterwards shows as Custom.
 */
enum QualityPreset {
    LOW("options.radiante.quality.low", 0, false, 0, true, 8),
    MEDIUM("options.radiante.quality.medium", 1, false, 1, true, 12),
    HIGH("options.radiante.quality.high", 2, true, 1, true, 16),
    ULTRA("options.radiante.quality.ultra", 3, true, 2, true, 24),
    CUSTOM("options.radiante.quality.custom", -1, false, 0, false, 0);

    final String key;
    final int dlssMode;
    final boolean volumetricFog;
    final int cloudMode;
    final boolean blockLights;
    final int renderDistance;

    QualityPreset(String key, int dlssMode, boolean volumetricFog, int cloudMode, boolean blockLights,
        int renderDistance) {
        this.key = key;
        this.dlssMode = dlssMode;
        this.volumetricFog = volumetricFog;
        this.cloudMode = cloudMode;
        this.blockLights = blockLights;
        this.renderDistance = renderDistance;
    }
}
