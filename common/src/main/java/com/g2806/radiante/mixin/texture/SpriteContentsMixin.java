package com.g2806.radiante.mixin.texture;

import com.g2806.radiante.client.render.SpriteContentsAccess;
import com.mojang.blaze3d.platform.NativeImage;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(SpriteContents.class)
public class SpriteContentsMixin implements SpriteContentsAccess {

    @Shadow
    private NativeImage[] byMipLevel;

    @Override
    public ByteBuffer[] radiante$mipImages() {
        ByteBuffer[] buffers = new ByteBuffer[this.byMipLevel.length];
        for (int i = 0; i < buffers.length; i++) {
            buffers[i] = this.byMipLevel[i] == null ? null : this.byMipLevel[i].getPixelBytes();
        }
        return buffers;
    }

    @Override
    public int radiante$mipWidth(int level) {
        return level < this.byMipLevel.length && this.byMipLevel[level] != null ? this.byMipLevel[level].getWidth() : 1;
    }
}
