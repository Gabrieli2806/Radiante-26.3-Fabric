package com.g2806.radiante.client.proxy.vulkan;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.system.MemoryUtil.memSet;

import java.nio.ByteBuffer;
import org.joml.Matrix4f;
import org.joml.Vector3fc;
import org.joml.Vector4fc;
import org.lwjgl.system.MemoryStack;

/** Uploads the uniform blocks the ray tracing shaders read (see native common/shared.hpp). */
public class BufferProxy {

    private static final int WORLD_UBO_SIZE = 592;
    private static final int SKY_UBO_SIZE = 80;
    private static final int TEXTURE_MAPPING_ENTRIES = 4096;

    private static native void updateWorldUniform(long ptr);

    private static native void updateSkyUniform(long ptr);

    private static native void updateMapping(long ptr);

    public record WorldUniform(Matrix4f viewMatrix,
                               Matrix4f effectedViewMatrix,
                               Matrix4f projectionMatrix,
                               Matrix4f glintTextureMatrix,
                               float gameTime,
                               int overlayTextureId,
                               boolean firstPerson,
                               float fogStart,
                               float fogEnd,
                               Vector4fc fogColor,
                               int skyType,
                               int endSkyTextureId,
                               int endPortalTextureId,
                               int lightMapTextureId) {
    }

    public static void updateWorldUniform(WorldUniform uniform) {
        try (MemoryStack stack = stackPush()) {
            ByteBuffer bb = stack.calloc(WORLD_UBO_SIZE);
            long addr = memAddress(bb);
            int offset = 0;

            uniform.viewMatrix().get(offset, bb);
            offset += Float.BYTES * 16;
            uniform.effectedViewMatrix().get(offset, bb);
            offset += Float.BYTES * 16;
            uniform.projectionMatrix().get(offset, bb);
            offset += Float.BYTES * 16;

            // The three inverse matrices and the jitter are filled in natively.
            offset += Float.BYTES * 16 * 3;
            offset += Float.BYTES * 2;

            bb.putFloat(offset, uniform.gameTime());
            offset += Float.BYTES;
            offset += Integer.BYTES; // seed, filled in natively

            uniform.glintTextureMatrix().get(offset, bb);
            offset += Float.BYTES * 16;

            bb.putInt(offset, uniform.overlayTextureId());
            offset += Integer.BYTES;
            bb.putInt(offset, uniform.firstPerson() ? 1 : 0);
            offset += Integer.BYTES;
            bb.putFloat(offset, uniform.fogStart());
            offset += Float.BYTES;
            bb.putFloat(offset, uniform.fogEnd());
            offset += Float.BYTES;

            bb.putFloat(offset, uniform.fogColor().x());
            offset += Float.BYTES;
            bb.putFloat(offset, uniform.fogColor().y());
            offset += Float.BYTES;
            bb.putFloat(offset, uniform.fogColor().z());
            offset += Float.BYTES;
            bb.putFloat(offset, uniform.fogColor().w());
            offset += Float.BYTES;

            bb.putInt(offset, 0); // fog shape: sphere
            offset += Integer.BYTES;
            bb.putInt(offset, uniform.skyType());
            offset += Integer.BYTES * 3; // + two padding words

            // cameraPos (dvec4), chunkGridInfo and chunkStorageSectionPos are filled in natively.
            offset += Double.BYTES * 4;
            offset += Integer.BYTES * 4;
            offset += Integer.BYTES * 4;

            bb.putInt(offset, uniform.endSkyTextureId());
            offset += Integer.BYTES;
            bb.putInt(offset, uniform.endPortalTextureId());
            offset += Integer.BYTES;
            bb.putInt(offset, uniform.lightMapTextureId());

            updateWorldUniform(addr);
        }
    }

    public record SkyUniform(Vector3fc baseColor,
                             Vector4fc horizonColor,
                             Vector3fc sunDirection,
                             int skyType,
                             boolean sunRisingOrSetting,
                             boolean skyDark,
                             boolean blindnessOrDarkness,
                             int submersionType,
                             int moonPhase,
                             float rainGradient,
                             int sunTextureId,
                             int moonTextureId) {
    }

    public static void updateSkyUniform(SkyUniform uniform) {
        try (MemoryStack stack = stackPush()) {
            ByteBuffer bb = stack.calloc(SKY_UBO_SIZE);
            long addr = memAddress(bb);
            int offset = 0;

            bb.putFloat(offset, uniform.baseColor().x());
            offset += Float.BYTES;
            bb.putFloat(offset, uniform.baseColor().y());
            offset += Float.BYTES;
            bb.putFloat(offset, uniform.baseColor().z());
            offset += Float.BYTES;
            bb.putInt(offset, uniform.skyType());
            offset += Integer.BYTES;

            bb.putFloat(offset, uniform.horizonColor().x());
            offset += Float.BYTES;
            bb.putFloat(offset, uniform.horizonColor().y());
            offset += Float.BYTES;
            bb.putFloat(offset, uniform.horizonColor().z());
            offset += Float.BYTES;
            bb.putFloat(offset, uniform.horizonColor().w());
            offset += Float.BYTES;

            bb.putFloat(offset, uniform.sunDirection().x());
            offset += Float.BYTES;
            bb.putFloat(offset, uniform.sunDirection().y());
            offset += Float.BYTES;
            bb.putFloat(offset, uniform.sunDirection().z());
            offset += Float.BYTES;
            bb.putInt(offset, uniform.sunRisingOrSetting() ? 1 : 0);
            offset += Integer.BYTES;

            bb.putInt(offset, uniform.skyDark() ? 1 : 0);
            offset += Integer.BYTES;
            bb.putInt(offset, uniform.blindnessOrDarkness() ? 1 : 0);
            offset += Integer.BYTES;
            bb.putInt(offset, uniform.submersionType());
            offset += Integer.BYTES;
            bb.putInt(offset, uniform.moonPhase());
            offset += Integer.BYTES;
            bb.putFloat(offset, uniform.rainGradient());
            offset += Float.BYTES;
            bb.putInt(offset, uniform.sunTextureId());
            offset += Integer.BYTES;
            bb.putInt(offset, uniform.moonTextureId());

            updateSkyUniform(addr);
        }
    }

    /** Uploads the specular/normal/flag texture mapping used by PBR resource packs. */
    public static void updateMapping() {
        try (MemoryStack stack = stackPush()) {
            int size = TEXTURE_MAPPING_ENTRIES * Integer.BYTES * 3;
            ByteBuffer bb = stack.malloc(size);
            long addr = memAddress(bb);
            memSet(addr, -1, size);
            int blocks = com.g2806.radiante.client.render.TextureTracker.idOf(
                net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
            int specular = com.g2806.radiante.client.render.EmissionTiles.specularTextureFor(blocks);
            if (blocks > 0 && blocks < TEXTURE_MAPPING_ENTRIES && specular >= 0) {
                bb.putInt(blocks * 3 * Integer.BYTES, specular);
            }
            updateMapping(addr);
        }
    }
}
