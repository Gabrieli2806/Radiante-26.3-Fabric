package com.g2806.radiante.client.gui;

import java.util.Map;
import java.util.Objects;

/**
 * The look of the image as a whole: how the colours are mapped to the screen, how saturated they are, and how far
 * light carries after bouncing (Bedrock RTX fills rooms and shade with more of it than physics would). Changing any of
 * these by hand shows as Custom.
 */
enum PictureStyle {
    NATURAL("options.radiante.picture_style.natural", ToneMethod.PBR_NEUTRAL, 100, 100, 100),
    BEDROCK("options.radiante.picture_style.bedrock", ToneMethod.PBR_NEUTRAL, 120, 150, 180),
    VIVID("options.radiante.picture_style.vivid", ToneMethod.ACES, 130, 150, 150),
    CUSTOM("options.radiante.picture_style.custom", null, 0, 0, 0);

    final String key;
    final ToneMethod method;
    final int saturation;
    final int bounceLight;
    final int skyLight;

    PictureStyle(String key, ToneMethod method, int saturation, int bounceLight, int skyLight) {
        this.key = key;
        this.method = method;
        this.saturation = saturation;
        this.bounceLight = bounceLight;
        this.skyLight = skyLight;
    }

    boolean matches(ToneMethod currentMethod, Map<Tunable, Integer> values) {
        return this != CUSTOM && (currentMethod == null || currentMethod == this.method)
            && same(values.get(Tunable.SATURATION), this.saturation)
            && same(values.get(Tunable.BOUNCE_LIGHT), this.bounceLight)
            && same(values.get(Tunable.SKY_LIGHT), this.skyLight);
    }

    private static boolean same(Integer value, int expected) {
        return value == null || Objects.equals(value, expected);
    }

    void applyTo(Map<Tunable, Integer> values) {
        values.computeIfPresent(Tunable.SATURATION, (k, v) -> this.saturation);
        values.computeIfPresent(Tunable.BOUNCE_LIGHT, (k, v) -> this.bounceLight);
        values.computeIfPresent(Tunable.SKY_LIGHT, (k, v) -> this.skyLight);
    }

    /** The tone mapping curves offered; the pipeline has a few more, shown as they are when chosen elsewhere. */
    enum ToneMethod {
        PBR_NEUTRAL("pbr_neutral"),
        ACES("aces"),
        REINHARD("reinhard");

        static final String PREFIX = "render_pipeline.module.tone_mapping.attribute.method.";
        final String value;

        ToneMethod(String suffix) {
            this.value = PREFIX + suffix;
        }

        String key() {
            return "options.radiante.tone_method." + name().toLowerCase(java.util.Locale.ROOT);
        }

        static ToneMethod of(String value) {
            for (ToneMethod method : values()) {
                if (method.value.equals(value)) {
                    return method;
                }
            }
            return null;
        }
    }
}
