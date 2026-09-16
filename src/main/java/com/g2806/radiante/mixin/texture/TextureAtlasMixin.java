package com.g2806.radiante.mixin.texture;

import com.g2806.radiante.client.render.AtlasMirror;
import com.g2806.radiante.client.render.TextureTracker;
import java.util.List;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureAtlas.class)
public class TextureAtlasMixin {

    @Shadow
    @Final
    private List<TextureAtlasSprite> sprites;

    @Shadow
    private int maxMipLevel;

    @Inject(method = "uploadInitialContents", at = @At("HEAD"))
    private void radiante$skipScratchTextures(CallbackInfo ci) {
        TextureTracker.setSkipTracking(true);
    }

    @Inject(method = "uploadInitialContents", at = @At("TAIL"))
    private void radiante$mirrorAtlas(CallbackInfo ci) {
        TextureTracker.setSkipTracking(false);
        AtlasMirror.mirror((TextureAtlas) (Object) this, this.sprites, this.maxMipLevel);
    }
}
