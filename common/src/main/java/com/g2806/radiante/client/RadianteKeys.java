package com.g2806.radiante.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/** Radiante's key bindings, in their own section of the Controls screen. Each loader registers them. */
public final class RadianteKeys {

    public static final KeyMapping.Category CATEGORY =
        KeyMapping.Category.register(Identifier.fromNamespaceAndPath("radiante", "radiante"));

    public static final KeyMapping OPEN_SETTINGS =
        new KeyMapping("key.radiante.open_settings", InputConstants.KEY_F6, CATEGORY);
    // Unbound by default: switching rebuilds the whole world, too costly to sit on a key pressed by accident, and
    // the settings screen has the same switch.
    public static final KeyMapping TOGGLE_RAY_TRACING =
        new KeyMapping("key.radiante.toggle_ray_tracing", InputConstants.UNKNOWN.getValue(), CATEGORY);

    public static final KeyMapping[] ALL = {OPEN_SETTINGS, TOGGLE_RAY_TRACING};

    private RadianteKeys() {
    }
}
