package com.g2806.radiante.client.render;

import com.g2806.radiante.client.proxy.vulkan.TextureProxy;
import com.g2806.radiante.mixin.backend.VulkanGpuBufferAccessor;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.vulkan.VK12;

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

    private static synchronized boolean isFallback(GpuTexture texture) {
        return IDS.containsKey(texture);
    }

    /**
     * Mirrors a copy that travels through a GPU buffer rather than a plain byte array. Glyph sheets are filled this
     * way and only this way: {@code FontTexture} stages each glyph in a buffer and copies it across, so a renderer
     * that watches only the CPU writes ends up with font pages that were created and never filled - which is why
     * sign text had nothing to sample.
     */
    public static void onBufferCopy(GpuTexture destination, GpuBufferSlice source, int sourceX, int sourceY,
        int sourceWidth, int destX, int destY, int copyWidth, int copyHeight, int mipLevel) {
        int id = idOf(destination);
        if (id == 0) {
            return;
        }

        GpuBuffer buffer = source.buffer();
        // Only a host visible allocation can be read back here, which is what the staging buffers are.
        if (!(buffer instanceof VulkanGpuBuffer.Direct) || (buffer.usage() & 3) == 0 || buffer.isClosed()) {
            return;
        }

        VulkanGpuBufferAccessor accessor = (VulkanGpuBufferAccessor) buffer;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pointer = stack.callocPointer(1);
            if (Vma.vmaMapMemory(accessor.radiante$device().vma(), accessor.radiante$vmaAllocation(), pointer)
                != VK12.VK_SUCCESS) {
                return;
            }

            try {
                long address = pointer.get(0) + source.offset();
                TextureProxy.queueUpload(address, (int) source.length(), sourceWidth, id, sourceX, sourceY, destX,
                    destY, copyWidth, copyHeight, mipLevel);
            } finally {
                Vma.vmaUnmapMemory(accessor.radiante$device().vma(), accessor.radiante$vmaAllocation());
            }
        }
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
        GpuTexture gpuTexture = texture == null ? null : gpuTextureOrNull(texture);
        return gpuTexture == null ? 0 : idOf(gpuTexture);
    }

    /**
     * The texture's GPU image, or null before it has one. {@code getTexture} throws in that case rather than
     * returning null, and on NeoForge atlases are ticked while resources are still loading, before that point.
     */
    public static GpuTexture gpuTextureOrNull(AbstractTexture texture) {
        try {
            return texture.getTexture();
        } catch (IllegalStateException notCreatedYet) {
            return null;
        }
    }
}
