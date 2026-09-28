package com.g2806.radiante.mixin.gui;

import java.util.function.Function;
import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets the Radiante settings screen draw an option its own way (name on the left, value or checkbox on the right, the
 * description beside the list) instead of as a vanilla button.
 */
@Mixin(OptionInstance.class)
public interface OptionInstanceAccessor {

    @Accessor("caption")
    Component radiante$caption();

    @Accessor("tooltip")
    OptionInstance.TooltipSupplier<Object> radiante$tooltip();

    @Accessor("toString")
    Function<Object, Component> radiante$toString();
}
