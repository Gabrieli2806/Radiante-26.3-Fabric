package com.g2806.radiante.client.render;

import java.nio.ByteBuffer;
import java.util.List;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Minecraft stitches atlases on the GPU, so the mirrored copy the ray tracer samples is rebuilt here from the
 * sprite images instead of from texture writes.
 */
public final class AtlasMirror {

    private AtlasMirror() {
    }

    public static void mirror(TextureAtlas atlas, List<TextureAtlasSprite> sprites, int maxMipLevel) {
        if (!RadianteRenderer.isActive() || TextureTracker.gpuTextureOrNull(atlas) == null) {
            return;
        }

        int atlasWidth = atlas.getTexture().getWidth(0);
        int atlasHeight = atlas.getTexture().getHeight(0);

        for (TextureAtlasSprite sprite : sprites) {
            SpriteContents contents = sprite.contents();
            // The stitcher reserves a padding border around every sprite, so the pixels live at the UV origin
            // rather than at the slot origin the sprite reports.
            int paddingX = Math.round(sprite.getU0() * atlasWidth) - sprite.getX();
            int paddingY = Math.round(sprite.getV0() * atlasHeight) - sprite.getY();
            ByteBuffer[] mips = ((SpriteContentsAccess) contents).radiante$mipImages();
            int levels = Math.min(maxMipLevel + 1, mips.length);

            for (int level = 0; level < levels; level++) {
                ByteBuffer pixels = mips[level];
                if (pixels == null) {
                    continue;
                }

                int width = Math.max(1, contents.width() >> level);
                int height = Math.max(1, contents.height() >> level);
                int rowPixels = Math.max(1, ((SpriteContentsAccess) contents).radiante$mipWidth(level));
                TextureTracker.uploadRegion(atlas.getTexture(), pixels, rowPixels, 0, 0,
                    (sprite.getX() + paddingX) >> level, (sprite.getY() + paddingY) >> level, width, height, level);
            }
        }
    }
}
