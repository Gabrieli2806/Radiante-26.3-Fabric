package com.g2806.radiante.client.proxy.world;

public class EntityProxy {

    private static native void queueBuild(float lineWidth,
        int coordinate,
        boolean normalOffset,
        int size,
        long entityHashCodes,
        long entityPosXs,
        long entityPosYs,
        long entityPosZs,
        long entityRayTracingFlags,
        long entityPostRenderFlags,
        long entityPrebuiltBLASs,
        long entityPosts,
        long entityLayerCounts,
        long geometryTypes,
        long geometryGroupNames,
        long geometryContentNames,
        long geometryTextures,
        long vertexFormats,
        long indexFormats,
        long vertexCounts,
        long vertices);

    public static native void build();

    public static void queue(float lineWidth, int coordinate, boolean normalOffset, int entityCount,
        long entityHashCodes, long entityPosXs, long entityPosYs, long entityPosZs, long entityRayTracingFlags,
        long entityPostRenderFlags, long entityPrebuiltBLASs, long entityPosts, long entityLayerCounts,
        long geometryTypes, long geometryGroupNames, long geometryContentNames, long geometryTextures,
        long vertexFormats, long indexFormats, long vertexCounts, long vertices) {
        queueBuild(lineWidth, coordinate, normalOffset, entityCount, entityHashCodes, entityPosXs, entityPosYs,
            entityPosZs, entityRayTracingFlags, entityPostRenderFlags, entityPrebuiltBLASs, entityPosts,
            entityLayerCounts, geometryTypes, geometryGroupNames, geometryContentNames, geometryTextures,
            vertexFormats, indexFormats, vertexCounts, vertices);
    }
}
