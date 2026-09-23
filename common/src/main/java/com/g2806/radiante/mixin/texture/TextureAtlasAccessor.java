package com.g2806.radiante.mixin.texture;

import java.util.Map;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Every sprite on an atlas, so the PBR maps of a resource pack can be stitched the same way. */
@Mixin(TextureAtlas.class)
public interface TextureAtlasAccessor {

    @Accessor("texturesByName")
    Map<Identifier, TextureAtlasSprite> radiante$texturesByName();
}
