package com.g2806.radiante.client.render;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.List;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Minecraft animates atlas sprites (fire, lava, water, portals…) on the GPU by redrawing frames into its atlas. The
 * ray tracer samples the mirrored atlas instead, so after every animation tick the frame each sprite now shows is
 * copied from the sprite sheet into the mirror.
 */
public final class AnimationMirror {

    private static Field stateFrame;
    private static Field stateDirty;
    private static Field stateAnimation;
    private static Field animationFrames;
    private static Field animationRowSize;
    private static Method frameIndex;
    private static boolean unavailable;

    private AnimationMirror() {
    }

    /** Uploads changed frames; {@code states} are the atlas animation states, in the order of its animated sprites. */
    public static void onAnimationsTicked(TextureAtlas atlas, List<TextureAtlasSprite> sprites, List<?> states,
        int maxMipLevel) {
        if (!RadianteRenderer.isActive() || atlas.getTexture() == null || states.isEmpty() || !resolve(states)) {
            return;
        }

        int stateIndex = 0;
        for (TextureAtlasSprite sprite : sprites) {
            if (!sprite.isAnimated()) {
                continue;
            }
            if (stateIndex >= states.size()) {
                break;
            }

            Object state = states.get(stateIndex++);
            try {
                if (!stateDirty.getBoolean(state)) {
                    continue;
                }
                Object animation = stateAnimation.get(state);
                List<?> frames = (List<?>) animationFrames.get(animation);
                int frame = stateFrame.getInt(state);
                int index = (int) frameIndex.invoke(frames.get(frame));
                int rowSize = animationRowSize.getInt(animation);
                uploadFrame(atlas, sprite, index % rowSize, index / rowSize, maxMipLevel);
            } catch (ReflectiveOperationException | RuntimeException e) {
                RadianteRenderer.LOGGER.warn("Stopping sprite animation mirroring", e);
                unavailable = true;
                return;
            }
        }
    }

    private static void uploadFrame(TextureAtlas atlas, TextureAtlasSprite sprite, int frameX, int frameY,
        int maxMipLevel) {
        SpriteContents contents = sprite.contents();
        ByteBuffer[] mips = ((SpriteContentsAccess) contents).radiante$mipImages();
        int atlasWidth = atlas.getTexture().getWidth(0);
        int atlasHeight = atlas.getTexture().getHeight(0);
        int paddingX = Math.round(sprite.getU0() * atlasWidth) - sprite.getX();
        int paddingY = Math.round(sprite.getV0() * atlasHeight) - sprite.getY();
        int levels = Math.min(maxMipLevel + 1, mips.length);

        for (int level = 0; level < levels; level++) {
            ByteBuffer pixels = mips[level];
            if (pixels == null) {
                continue;
            }
            int width = Math.max(1, contents.width() >> level);
            int height = Math.max(1, contents.height() >> level);
            int rowPixels = Math.max(1, ((SpriteContentsAccess) contents).radiante$mipWidth(level));
            TextureTracker.uploadRegion(atlas.getTexture(), pixels, rowPixels, frameX * width, frameY * height,
                (sprite.getX() + paddingX) >> level, (sprite.getY() + paddingY) >> level, width, height, level);
        }
    }

    private static boolean resolve(List<?> states) {
        if (unavailable) {
            return false;
        }
        if (stateFrame != null) {
            return true;
        }

        try {
            Class<?> stateClass = states.getFirst().getClass();
            stateFrame = accessible(stateClass.getDeclaredField("frame"));
            stateDirty = accessible(stateClass.getDeclaredField("isDirty"));
            stateAnimation = accessible(stateClass.getDeclaredField("animationInfo"));
            Class<?> animationClass = stateAnimation.getType();
            animationFrames = accessible(animationClass.getDeclaredField("frames"));
            animationRowSize = accessible(animationClass.getDeclaredField("frameRowSize"));
            Class<?> frameInfoClass = Class.forName(SpriteContents.class.getName() + "$FrameInfo", false,
                SpriteContents.class.getClassLoader());
            frameIndex = frameInfoClass.getDeclaredMethod("index");
            frameIndex.setAccessible(true);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            RadianteRenderer.LOGGER.warn("Sprite animations will not be mirrored", e);
            unavailable = true;
            return false;
        }
    }

    private static Field accessible(Field field) {
        field.setAccessible(true);
        return field;
    }
}
