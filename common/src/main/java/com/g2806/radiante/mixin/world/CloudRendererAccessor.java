package com.g2806.radiante.mixin.world;

import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(CloudRenderer.class)
public interface CloudRendererAccessor {

    @Accessor("texture")
    CloudRenderer.TextureData radiante$texture();
}
