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
                int x = (sprite.getX() + paddingX) >> level;
                int y = (sprite.getY() + paddingY) >> level;
                TextureTracker.uploadRegion(atlas.getTexture(), pixels, rowPixels, 0, 0, x, y, width, height, level);

                // The border the stitcher leaves round a sprite repeats the sprite's edge in Minecraft's atlas.
                // Left empty here, a ray hitting a face on its very edge sampled it - and on a cut out texture
                // empty is a hole: a hairline along the top of a grass block where the fringe let the layer under
                // it through. One texel of each edge is repeated outwards, which is as far as such a sample strays.
                int levelWidth = Math.max(1, atlasWidth >> level);
                int levelHeight = Math.max(1, atlasHeight >> level);
                if (paddingY >> level > 0 || level == 0 && paddingY > 0) {
                    if (y > 0) {
                        TextureTracker.uploadRegion(atlas.getTexture(), pixels, rowPixels, 0, 0, x, y - 1, width, 1,
                            level);
                    }
                    if (y + height < levelHeight) {
                        TextureTracker.uploadRegion(atlas.getTexture(), pixels, rowPixels, 0, height - 1, x,
                            y + height, width, 1, level);
                    }
                }
                if (paddingX >> level > 0 || level == 0 && paddingX > 0) {
                    if (x > 0) {
                        TextureTracker.uploadRegion(atlas.getTexture(), pixels, rowPixels, 0, 0, x - 1, y, 1, height,
                            level);
                    }
                    if (x + width < levelWidth) {
                        TextureTracker.uploadRegion(atlas.getTexture(), pixels, rowPixels, width - 1, 0, x + width, y,
                            1, height, level);
                    }
                }
            }
        }
    }
}
