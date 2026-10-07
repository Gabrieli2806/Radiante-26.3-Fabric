package com.g2806.radiante.client.render;

import com.g2806.radiante.client.proxy.vulkan.TextureProxy;
import com.g2806.radiante.mixin.backend.VulkanGpuBufferAccessor;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import java.nio.ByteBuffer;
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
        return sampled && cpuWritten && supportedFormat && texture.getDepthOrLayers() == 1
            && !isAnimationFrame(texture);
    }

    /**
     * Minecraft keeps every frame of an animated sprite in a texture of its own and copies it into the atlas on the
     * GPU. Nothing is ever drawn with them, and AnimationMirror uploads the frames itself, so they get no id: a
     * server pack with many animations would otherwise fill the whole table and leave skins and font pages out.
     */
    private static boolean isAnimationFrame(GpuTexture texture) {
        String label = texture.getLabel();
        return label != null && label.contains(" animation frame ");
    }

    private static final ThreadLocal<Boolean> SKIP_TRACKING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Atlas stitching creates one scratch texture per sprite; none of them are worth an id. */
    public static void setSkipTracking(boolean skip) {
        SKIP_TRACKING.set(skip);
    }


    /** Diagnostics (debug logging): textures the tracer could not take, a line each for the first few. */
    private static int reportedUntracked;
    private static boolean reportedFull;
    private static final java.util.Set<String> REPORTED_MISSING = new java.util.HashSet<>();

    /**
     * Says once, with debug logging on, that something is drawn with a texture the tracer does not have - it comes
     * out white, or not at all for text. {@code what} names the texture and the reason for looking it up.
     */
    public static synchronized void reportMissing(String what, GpuTexture texture) {
        if (!com.g2806.radiante.client.option.Options.debugLogging || REPORTED_MISSING.size() >= 60
            || !REPORTED_MISSING.add(what)) {
            return;
        }
        RadianteRenderer.LOGGER.info("texture not mirrored: {} ({}); {} of {} ids in use", what,
            texture == null ? "no GPU texture" : describe(texture), allocatedIds - FREE_IDS.size(), MAX_TEXTURES);
    }

    private static final java.util.Set<String> REPORTED_USES = new java.util.HashSet<>();

    /** Says once per distinct line, with debug logging on, how something is drawn: for bug reports from servers. */
    public static synchronized void reportUse(String what) {
        if (!com.g2806.radiante.client.option.Options.debugLogging || REPORTED_USES.size() >= 200
            || !REPORTED_USES.add(what)) {
            return;
        }
        RadianteRenderer.LOGGER.info("drawn: {}", what);
    }

    static String describe(GpuTexture texture) {
        return texture.getLabel() + " " + texture.getFormat() + " " + texture.getWidth(0) + "x" + texture.getHeight(0)
            + " mips=" + texture.getMipLevels() + " layers=" + texture.getDepthOrLayers() + " usage=" + texture.usage() + (texture.isClosed() ? " closed" : "");
    }

    public static synchronized void onCreated(GpuTexture texture) {
        if (SKIP_TRACKING.get() || !RadianteRenderer.isActive()) {
            return;
        }
        if (!isTracked(texture)) {
            // Sampled textures only: render targets and the like are never drawn with.
            if (com.g2806.radiante.client.option.Options.debugLogging && reportedUntracked < 40
                && (texture.usage() & GpuTexture.USAGE_TEXTURE_BINDING) != 0
                && (texture.usage() & GpuTexture.USAGE_RENDER_ATTACHMENT) == 0) {
                reportedUntracked++;
                RadianteRenderer.LOGGER.info("texture left untracked: {}", describe(texture));
            }
            return;
        }


        int id;
        if (!FREE_IDS.isEmpty()) {
            id = FREE_IDS.dequeueInt();
        } else if (allocatedIds < MAX_TEXTURES) {
            id = TextureProxy.generateTextureId();
            allocatedIds++;
        } else {
            if (!reportedFull) {
                reportedFull = true;
                RadianteRenderer.LOGGER.warn("Radiante's texture table is full ({} textures); further textures are "
                    + "drawn white. This one: {}", MAX_TEXTURES, describe(texture));
            }
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
