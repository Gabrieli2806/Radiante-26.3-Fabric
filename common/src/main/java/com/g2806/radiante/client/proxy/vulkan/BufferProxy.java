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

    private static final int WORLD_UBO_SIZE = 656;
    private static final int SKY_UBO_SIZE = 208;
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
                               int lightMapTextureId,
                               float handFovScale,
                               boolean glowOutline,
                               boolean blockLightSampling,
                               Vector4fc heldLightPos,
                               Vector4fc heldLightColor,
                               boolean parallaxTransparentEdges,
                               float sunBrightness,
                               float moonBrightness,
                               float emissionBrightness,
                               boolean pixelLighting,
                               float rainFallPerFrame,
                               float traceDistance,
                               float heldLightBrightness) {
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
            offset += Integer.BYTES;
            bb.putInt(offset, uniform.glowOutline() ? 1 : 0);
            offset += Integer.BYTES;
            bb.putInt(offset, uniform.blockLightSampling() ? 1 : 0);
            offset += Integer.BYTES;

            // cameraPos (dvec4), chunkGridInfo and chunkStorageSectionPos are filled in natively.
            offset += Double.BYTES * 4;
            offset += Integer.BYTES * 4;
            offset += Integer.BYTES * 4;

            bb.putInt(offset, uniform.endSkyTextureId());
            offset += Integer.BYTES;
            bb.putInt(offset, uniform.endPortalTextureId());
            offset += Integer.BYTES;
            bb.putInt(offset, uniform.lightMapTextureId());
            offset += Integer.BYTES;
            bb.putFloat(offset, uniform.handFovScale());
            offset += Float.BYTES;

            uniform.heldLightPos().get(offset, bb);
            offset += Float.BYTES * 4;
            uniform.heldLightColor().get(offset, bb);
            offset += Float.BYTES * 4;

            bb.putInt(offset, uniform.parallaxTransparentEdges() ? 1 : 0);
            offset += Integer.BYTES;
            bb.putFloat(offset, uniform.sunBrightness());
            offset += Float.BYTES;
            bb.putFloat(offset, uniform.moonBrightness());
            offset += Float.BYTES;
            bb.putFloat(offset, uniform.emissionBrightness());
            offset += Float.BYTES;
            bb.putInt(offset, uniform.pixelLighting() ? 1 : 0);
            offset += Integer.BYTES;
            bb.putFloat(offset, uniform.traceDistance());
            offset += Float.BYTES;
            bb.putFloat(offset, uniform.rainFallPerFrame());
            offset += Float.BYTES;
            bb.putFloat(offset, uniform.heldLightBrightness());
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
                             int moonTextureId,
                             Vector4fc sunUvRect,
                             Vector4fc moonUvRect,
                             Vector4fc biomeFog,
                             float nightVision,
                             Vector4fc celestialAxis,
                             Vector4fc biomeFogChroma,
                             Vector4fc biomeFogHeights,
                             Vector4fc waterExtinction,
                             Vector4fc waterAlbedo) {
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
            offset += Integer.BYTES;
            bb.putFloat(offset, uniform.nightVision());
            offset += Float.BYTES;

            bb.putFloat(offset, uniform.sunUvRect().x());
            offset += Float.BYTES;
            bb.putFloat(offset, uniform.sunUvRect().y());
            offset += Float.BYTES;
            bb.putFloat(offset, uniform.sunUvRect().z());
            offset += Float.BYTES;
            bb.putFloat(offset, uniform.sunUvRect().w());
            offset += Float.BYTES;

            bb.putFloat(offset, uniform.moonUvRect().x());
            offset += Float.BYTES;
            bb.putFloat(offset, uniform.moonUvRect().y());
            offset += Float.BYTES;
            bb.putFloat(offset, uniform.moonUvRect().z());
            offset += Float.BYTES;
            bb.putFloat(offset, uniform.moonUvRect().w());
            offset += Float.BYTES;

            bb.putFloat(offset, uniform.biomeFog().x());
            offset += Float.BYTES;
            bb.putFloat(offset, uniform.biomeFog().y());
            offset += Float.BYTES;
            bb.putFloat(offset, uniform.biomeFog().z());
            offset += Float.BYTES;
            bb.putFloat(offset, uniform.biomeFog().w());
            offset += Float.BYTES;

            bb.putFloat(offset, uniform.celestialAxis().x());
            offset += Float.BYTES;
            bb.putFloat(offset, uniform.celestialAxis().y());
            offset += Float.BYTES;
            bb.putFloat(offset, uniform.celestialAxis().z());
            offset += Float.BYTES;
            bb.putFloat(offset, uniform.celestialAxis().w());
            offset += Float.BYTES;

            offset = putVec4(bb, offset, uniform.biomeFogChroma());
            offset = putVec4(bb, offset, uniform.biomeFogHeights());
            offset = putVec4(bb, offset, uniform.waterExtinction());
            putVec4(bb, offset, uniform.waterAlbedo());

            updateSkyUniform(addr);
        }
    }

    private static int putVec4(ByteBuffer bb, int offset, Vector4fc value) {
        bb.putFloat(offset, value.x());
        bb.putFloat(offset + Float.BYTES, value.y());
        bb.putFloat(offset + 2 * Float.BYTES, value.z());
        bb.putFloat(offset + 3 * Float.BYTES, value.w());
        return offset + 4 * Float.BYTES;
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
            if (blocks > 0 && blocks < TEXTURE_MAPPING_ENTRIES) {
                int entry = blocks * 3 * Integer.BYTES;
                int specular = com.g2806.radiante.client.render.PbrAtlases.specularTextureFor(blocks);
                if (specular < 0) {
                    specular = com.g2806.radiante.client.render.EmissionTiles.specularTextureFor(blocks);
                }
                if (specular >= 0) {
                    bb.putInt(entry, specular);
                }
                int normal = com.g2806.radiante.client.render.PbrAtlases.normalTextureFor(blocks);
                if (normal >= 0) {
                    bb.putInt(entry + Integer.BYTES, normal);
                }
            }
            com.g2806.radiante.client.render.EntityPbr.writeMapping(bb, TEXTURE_MAPPING_ENTRIES);
            updateMapping(addr);
        }
    }
}
