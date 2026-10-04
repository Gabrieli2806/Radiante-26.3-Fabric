package com.g2806.radiante.client.render;

import com.g2806.radiante.client.render.EntityManager.PendingEntity;
import com.g2806.radiante.client.render.EntityManager.PendingLayer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.LidBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.system.MemoryUtil;

/**
 * Keeps the geometry of block entities that are not changing. Minecraft has no notion of a block entity's model
 * staying the same, so each one was extracted, laid out and written again every frame; around a server spawn with
 * a thousand signs that alone took 40 ms a frame. Here an entry whose geometry came out the same twice running is
 * handed back as it is for a growing number of frames, and looked at again in between: one that starts to move is
 * found out at its next look and collected every frame from then on, until it rests again.
 */
final class BlockEntityCache {

    /** Frames between looks at a block entity whose geometry has stayed the same, at most. */
    private static final int MAX_INTERVAL = 8;
    /** The same, for the kinds that only change when their data does, which is checked on every use. */
    private static final int MAX_STATIC_INTERVAL = 64;
    /** Entries not asked for in this many frames are dropped. */
    private static final int UNUSED_FRAMES = 300;

    private static final class Entry {
        BlockEntity blockEntity;
        BlockState blockState;
        Object frontText;
        Object backText;
        List<PendingEntity> entities = List.of();
        long memory;
        String name;
        java.nio.ByteBuffer nameBuffer;
        long hash;
        int interval;
        int nextLook;
        int lastUsed;
    }

    private static final Map<BlockPos, Entry> ENTRIES = new HashMap<>();
    /** Native copies of the content names the kept layers carry, by name. */
    private static final Map<String, Long> NAME_ADDRESSES = new HashMap<>();
    static final String NAME_PREFIX = "be:";

    static long nameAddress(String name) {
        Long address = NAME_ADDRESSES.get(name);
        return address == null ? 0L : address;
    }
    private static int frame;
    private static Object level;
    private static boolean dirty;

    private BlockEntityCache() {
    }

    /** Texture ids and hit groups may have changed: everything is collected afresh. */
    static void invalidate() {
        dirty = true;
    }

    static void beginFrame(Object currentLevel) {
        frame++;
        if (dirty || currentLevel != level) {
            dirty = false;
            level = currentLevel;
            for (Entry entry : ENTRIES.values()) {
                free(entry);
            }
            ENTRIES.clear();
        }
        if ((frame & 255) == 0) {
            for (Iterator<Entry> iterator = ENTRIES.values().iterator(); iterator.hasNext();) {
                Entry entry = iterator.next();
                if (frame - entry.lastUsed > UNUSED_FRAMES) {
                    free(entry);
                    iterator.remove();
                }
            }
        }
    }

    /** Adds the kept geometry of this block entity to {@code pending}; false when it has to be collected. */
    static boolean reuse(BlockPos pos, BlockEntity blockEntity, List<PendingEntity> pending) {
        Entry entry = ENTRIES.get(pos);
        if (entry == null) {
            return false;
        }
        entry.lastUsed = frame;
        if (frame >= entry.nextLook || entry.interval == 0 || !sameData(entry, blockEntity)) {
            return false;
        }
        pending.addAll(entry.entities);
        for (PendingEntity entity : entry.entities) {
            int vertices = 0;
            for (PendingLayer layer : entity.layers()) {
                vertices += layer.vertexCount();
            }
            DevProfiler.count(entity.layers().size(), vertices);
        }
        DevProfiler.reused();
        return true;
    }

    private static boolean sameData(Entry entry, BlockEntity blockEntity) {
        if (entry.blockEntity != blockEntity || blockEntity.isRemoved()
            || entry.blockState != blockEntity.getBlockState()) {
            return false;
        }
        // A sign's text objects are replaced, never edited, so the same two objects mean the same writing.
        if (blockEntity instanceof SignBlockEntity sign
            && (sign.getText(SignTextSlot.FRONT) != entry.frontText || sign.getText(SignTextSlot.BACK) != entry.backText)) {
            return false;
        }
        // A chest about to open: its lid has to be followed from the first frame.
        return !(blockEntity instanceof LidBlockEntity lid) || lid.getOpenNess(1.0f) == 0.0f;
    }

    /**
     * Called with what was just collected for a block entity, {@code collected} being this frame's entries (their
     * vertices still in the frame's arena, at {@code arenaBase}).
     */
    static void collected(BlockPos pos, BlockEntity blockEntity, List<PendingEntity> collected, long arenaBase) {
        long hash = 0xCBF29CE484222325L;
        long size = 0L;
        for (PendingEntity entity : collected) {
            hash = (hash ^ entity.rayTracingFlag()) * 0x100000001B3L;
            for (PendingLayer layer : entity.layers()) {
                long bytes = (long) layer.vertexCount() * PBRVertexWriter.STRIDE;
                long address = arenaBase + layer.vertices();
                for (long offset = 0L; offset < bytes; offset += Long.BYTES) {
                    hash = (hash ^ MemoryUtil.memGetLong(address + offset)) * 0x100000001B3L;
                }
                hash = (hash ^ layer.textureId()) * 0x100000001B3L;
                size += bytes;
            }
        }

        Entry entry = ENTRIES.get(pos.immutable());
        if (entry == null) {
            entry = new Entry();
            ENTRIES.put(pos.immutable(), entry);
        }
        boolean same = entry.blockEntity == blockEntity && entry.hash == hash && entry.memory != 0L;
        entry.lastUsed = frame;
        if (!same) {
            free(entry);
            entry.blockEntity = blockEntity;
            entry.hash = hash;
            entry.interval = 0;
            if (size == 0L) {
                return;
            }
            // The kept copy goes to the renderer under a name of its own: with a name the native side takes the
            // content as unchanged without copying and hashing its vertices again (Entities::KEYED_BLAS).
            entry.name = NAME_PREFIX + Long.toHexString(hash) + "@" + Long.toHexString(pos.asLong());
            entry.nameBuffer = MemoryUtil.memUTF8(entry.name, true);
            NAME_ADDRESSES.put(entry.name, MemoryUtil.memAddress(entry.nameBuffer));
            entry.memory = MemoryUtil.nmemAllocChecked(size);
            List<PendingEntity> kept = new ArrayList<>(collected.size());
            long cursor = entry.memory;
            for (PendingEntity entity : collected) {
                List<PendingLayer> layers = new ArrayList<>(entity.layers().size());
                for (PendingLayer layer : entity.layers()) {
                    long bytes = (long) layer.vertexCount() * PBRVertexWriter.STRIDE;
                    MemoryUtil.memCopy(arenaBase + layer.vertices(), cursor, bytes);
                    layers.add(new PendingLayer(layer.geometryType(), layer.textureId(), layer.vertexCount(), 0L,
                        layer.name(), cursor, entry.name));
                    cursor += bytes;
                }
                kept.add(new PendingEntity(entity.id(), entity.x(), entity.y(), entity.z(), entity.rayTracingFlag(),
                    layers, EntityManager.PREBUILT_BLAS_KEYED));
            }
            entry.entities = kept;
        } else {
            int limit = blockEntity instanceof SignBlockEntity || blockEntity instanceof SkullBlockEntity
                || blockEntity instanceof LidBlockEntity
                ? MAX_STATIC_INTERVAL : MAX_INTERVAL;
            entry.interval = Math.min(limit, Math.max(1, entry.interval * 2));
            // Unchanged: the kept copy goes in place of the one just collected, so the renderer sees the same
            // named content it already has rather than something to compare.
            collected.clear();
            collected.addAll(entry.entities);
        }
        entry.blockState = blockEntity.getBlockState();
        if (blockEntity instanceof SignBlockEntity sign) {
            entry.frontText = sign.getText(SignTextSlot.FRONT);
            entry.backText = sign.getText(SignTextSlot.BACK);
        }
        // Spread out, so a thousand signs first seen together are not all looked at again in the same frame.
        int spread = entry.interval > 1 ? Math.floorMod(pos.hashCode(), entry.interval) : 0;
        entry.nextLook = frame + 1 + entry.interval / 2 + spread / 2;
    }

    private static void free(Entry entry) {
        if (entry.memory != 0L) {
            MemoryUtil.nmemFree(entry.memory);
            entry.memory = 0L;
        }
        if (entry.nameBuffer != null) {
            NAME_ADDRESSES.remove(entry.name);
            MemoryUtil.memFree(entry.nameBuffer);
            entry.nameBuffer = null;
            entry.name = null;
        }
        entry.entities = List.of();
    }
}
