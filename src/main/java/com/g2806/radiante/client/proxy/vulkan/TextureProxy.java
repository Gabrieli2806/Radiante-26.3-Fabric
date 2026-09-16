package com.g2806.radiante.client.proxy.vulkan;

public class TextureProxy {

    public synchronized static native int generateTextureId();

    public synchronized static native void prepareImage(int id, int mipLevels, int width, int height, int format);

    public synchronized static native void setFilter(int id, int samplingMode, int mipmapMode);

    public synchronized static native void setClamp(int id, int addressMode);

    public synchronized static native void queueUpload(long srcPointer,
        int srcSizeInBytes,
        int srcRowPixels,
        int dstId,
        int srcOffsetX,
        int srcOffsetY,
        int dstOffsetX,
        int dstOffsetY,
        int width,
        int height,
        int level);

    private static native void uploadEmissionTileNative(int textureId, long tileKey, long cellsPtr, int cellCount);

    /** Uploads emitter cells: 8 floats each (u0, v0, u1, v1, emission, red, green, blue) in atlas UV space. */
    public static void uploadEmissionTile(int textureId, long tileKey, long cellsPtr, int cellCount) {
        uploadEmissionTileNative(textureId, tileKey, cellsPtr, cellCount);
    }

    /** Registers the block emitters again the next frame, for example after emission collection is turned on. */
    public static void flushEmissionTiles() {
        com.g2806.radiante.client.render.EmissionTiles.invalidate();
    }
}
