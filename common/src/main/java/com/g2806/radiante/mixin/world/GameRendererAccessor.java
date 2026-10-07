package com.g2806.radiante.mixin.world;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 26.2 keeps the nausea/portal spin in GameRenderer rather than in an extracted render state. */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {

    @Accessor("spinningEffectTime")
    float radiante$spinningEffectTime();

    @Accessor("spinningEffectSpeed")
    float radiante$spinningEffectSpeed();
}
