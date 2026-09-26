package com.g2806.radiante.client.proxy.world;

public class ChunkProxy {

    private static native void initNative(int numChunks, int sizeX, int sizeY, int sizeZ, int bottomSectionCoord);

    private static native void updateSectionPosNative(int sectionX, int sectionY, int sectionZ);

    private static native void rebuildSingle(int originX,
        int originY,
        int originZ,
        long index,
        int size,
        long geometryTypes,
        long geometryGroupNames,
        long geometryTextures,
        long vertexFormats,
        long vertexCounts,
        long vertices,
        boolean important);


    public static native void relocateSingle(long index, int originX, int originY, int originZ);

    public static native void invalidateSingle(long index);

    public static void init(int numChunks, int sizeX, int sizeY, int sizeZ, int bottomSectionCoord) {
        initNative(numChunks, sizeX, sizeY, sizeZ, bottomSectionCoord);
    }

    public static void updateSectionPos(int sectionX, int sectionY, int sectionZ) {
        updateSectionPosNative(sectionX, sectionY, sectionZ);
    }

    public static void rebuild(int originX, int originY, int originZ, long index, int geometryCount,
        long geometryTypes, long geometryGroupNames, long geometryTextures, long vertexFormats, long vertexCounts,
        long vertices, boolean important) {
        rebuildSingle(originX, originY, originZ, index, geometryCount, geometryTypes, geometryGroupNames,
            geometryTextures, vertexFormats, vertexCounts, vertices, important);
    }

    /** No-op kept for options that ask for a full world rebuild. */
    public static void rebuildAll() {
        com.g2806.radiante.client.render.ChunkManager.markAllDirty();
    }
}
