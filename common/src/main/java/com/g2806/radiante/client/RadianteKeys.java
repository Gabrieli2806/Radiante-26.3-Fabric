package com.g2806.radiante.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;

/** Radiante's key bindings. Each loader registers them with its own key mapping API. */
public final class RadianteKeys {

    public static final KeyMapping OPEN_SETTINGS =
        new KeyMapping("key.radiante.open_settings", InputConstants.KEY_F6, KeyMapping.Category.MISC);
    public static final KeyMapping TOGGLE_RAY_TRACING =
        new KeyMapping("key.radiante.toggle_ray_tracing", InputConstants.KEY_F7, KeyMapping.Category.MISC);

    public static final KeyMapping[] ALL = {OPEN_SETTINGS, TOGGLE_RAY_TRACING};

    private RadianteKeys() {
    }
}
