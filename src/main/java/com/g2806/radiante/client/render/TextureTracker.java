package com.g2806.radiante.client.render;

import com.g2806.radiante.client.proxy.vulkan.TextureProxy;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

/**
 * Mirrors CPU-written Minecraft textures into the native renderer and assigns them the integer ids the ray
 * tracing shaders index textures by. Ids of closed textures are reused.
 */
public final class TextureTracker {

    /** The native texture mapping table holds this many entries. */
    public static final int MAX_TEXTURES = 4096;

    private static final Map<GpuTexture, Integer> IDS = new IdentityHashMap<>();
    private static final IntArrayFIFOQueue FREE_IDS = new IntArrayFIFOQueue();
    private static int allocatedIds = 0;

    private TextureTracker() {
    }

    private static boolean isTracked(GpuTexture texture) {
        int usage = texture.usage();
        boolean sampled = (usage & GpuTexture.USAGE_TEXTURE_BINDING) != 0;
        boolean cpuWritten = (usage & GpuTexture.USAGE_COPY_DST) != 0;
        GpuFormat format = texture.getFormat();
        boolean supportedFormat = format == GpuFormat.RGBA8_UNORM || format == GpuFormat.R8_UNORM;
        return sampled && cpuWritten && supportedFormat && texture.getDepthOrLayers() == 1;
    }

    private static final ThreadLocal<Boolean> SKIP_TRACKING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Atlas stitching creates one scratch texture per sprite; none of them are worth an id. */
    public static void setSkipTracking(boolean skip) {
        SKIP_TRACKING.set(skip);
    }

    public static synchronized void onCreated(GpuTexture texture) {
        if (SKIP_TRACKING.get() || !RadianteRenderer.isActive() || !isTracked(texture)) {
            return;
        }

        int id;
        if (!FREE_IDS.isEmpty()) {
            id = FREE_IDS.dequeueInt();
        } else if (allocatedIds < MAX_TEXTURES) {
            id = TextureProxy.generateTextureId();
            allocatedIds++;
        } else {
            return;
        }

        IDS.put(texture, id);
        TextureProxy.prepareImage(id, texture.getMipLevels(), texture.getWidth(0), texture.getHeight(0),
            VulkanConst.toVk(texture.getFormat()));
    }

    public static synchronized void onClosed(GpuTexture texture) {
        Integer id = IDS.remove(texture);
        if (id != null) {
            FREE_IDS.enqueue(id);
        }
    }

    public static void onWrite(GpuTexture texture, ByteBuffer source, int mipLevel, int destX, int destY, int width,
        int height) {
        int id = idOf(texture);
        if (id == 0 && !isFallback(texture)) {
            return;
        }


        int bytesPerPixel = texture.getFormat() == GpuFormat.R8_UNORM ? 1 : 4;
        int size = Math.min(source.remaining(), width * height * bytesPerPixel);
        TextureProxy.queueUpload(MemoryUtil.memAddress(source), size, width, id, 0, 0, destX, destY, width, height,
            mipLevel);
    }

    /**
     * Minecraft has three ways to write a texture and fonts use one of the two that hand over a NativeImage, not the
     * raw buffer. Left unmirrored, the renderer's copy of a font page stays blank, every glyph samples an alpha of
     * zero and the alpha test throws all the letters away.
     */
    public static void onWriteImage(GpuTexture texture, NativeImage source, int mipLevel, int destX, int destY) {
        int id = idOf(texture);
        if (id == 0 && !isFallback(texture)) {
            return;
        }

        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
        try {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixels.putInt((y * width + x) * 4, toRgba(source.getPixel(x, y)));
                }
            }
            TextureProxy.queueUpload(MemoryUtil.memAddress(pixels), width * height * 4, width, id, 0, 0, destX, destY,
                width, height, mipLevel);
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    /** NativeImage hands out ARGB; the mirrored texture stores RGBA in memory order. */
    private static int toRgba(int argb) {
        return (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16);
    }

    private static synchronized boolean isFallback(GpuTexture texture) {
        return IDS.containsKey(texture);
    }

    /** Uploads a region of a source image into a mirrored texture (used to rebuild atlases). */
    public static void uploadRegion(GpuTexture destination, ByteBuffer source, int sourceRowPixels, int sourceX,
        int sourceY, int destX, int destY, int width, int height, int mipLevel) {
        int id = idOf(destination);
        if (id == 0 && !isFallback(destination)) {
            return;
        }

        TextureProxy.queueUpload(MemoryUtil.memAddress(source), source.remaining(), sourceRowPixels, id, sourceX,
            sourceY, destX, destY, width, height, mipLevel);
    }

    /** Returns the renderer id of a texture, or 0 when it is not mirrored. */
    public static synchronized int idOf(GpuTexture texture) {
        if (texture == null) {
            return 0;
        }
        Integer id = IDS.get(texture);
        return id == null ? 0 : id;
    }

    public static int idOf(Identifier location) {
        AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(location);
        return texture == null ? 0 : idOf(texture.getTexture());
    }
}
