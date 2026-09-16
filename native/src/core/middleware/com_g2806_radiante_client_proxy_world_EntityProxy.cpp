#include <jni.h>

#include "core/render/entities.hpp"
#include "core/render/renderer.hpp"

extern "C" {
JNIEXPORT void JNICALL Java_com_g2806_radiante_client_proxy_world_EntityProxy_queueBuild(JNIEnv *,
                                                                                   jclass,
                                                                                   jfloat lineWidth,
                                                                                   jint coordinate,
                                                                                   jboolean normalOffset,
                                                                                   jint size,
                                                                                   jlong entityHashCodes,
                                                                                   jlong entityPosXs,
                                                                                   jlong entityPosYs,
                                                                                   jlong entityPosZs,
                                                                                   jlong entityRayTracingFlags,
                                                                                   jlong entityPostRenderFlags,
                                                                                   jlong entityPrebuiltBLASs,
                                                                                   jlong entityPosts,
                                                                                   jlong entityLayerCounts,
                                                                                   jlong geometryTypes,
                                                                                   jlong geometryGroupNames,
                                                                                   jlong geometryContentNames,
                                                                                   jlong geometryTextures,
                                                                                   jlong vertexFormats,
                                                                                   jlong indexFormats,
                                                                                   jlong vertexCounts,
                                                                                   jlong vertices) {
    auto world = Renderer::instance().world();
    if (world == nullptr) return;
    world->entities()->queueBuild(EntitiesBuildTask{
        .lineWidth = lineWidth,
        .coordinate = static_cast<World::Coordinates>(coordinate),
        .normalOffset = static_cast<bool>(normalOffset),
        .entityCount = size,
        .entityHashCodes = reinterpret_cast<int *>(entityHashCodes),
        .entityXs = reinterpret_cast<double *>(entityPosXs),
        .entityYs = reinterpret_cast<double *>(entityPosYs),
        .entityZs = reinterpret_cast<double *>(entityPosZs),
        .entityRayTracingFlags = reinterpret_cast<int *>(entityRayTracingFlags),
        .entityPostRenderFlags = reinterpret_cast<int *>(entityPostRenderFlags),
        .entityPrebuiltBLASs = reinterpret_cast<int *>(entityPrebuiltBLASs),
        .entityPosts = reinterpret_cast<int *>(entityPosts),
        .entityGeometryCounts = reinterpret_cast<int *>(entityLayerCounts),
        .geometryTypes = reinterpret_cast<int *>(geometryTypes),
        .geometryGroupNames = reinterpret_cast<const char **>(geometryGroupNames),
        .geometryContentNames = reinterpret_cast<const char **>(geometryContentNames),
        .geometryTextures = reinterpret_cast<int *>(geometryTextures),
        .vertexFormats = reinterpret_cast<int *>(vertexFormats),
        .indexFormats = reinterpret_cast<int *>(indexFormats),
        .vertexCounts = reinterpret_cast<int *>(vertexCounts),
        .vertices = reinterpret_cast<void **>(vertices),
    });
}

JNIEXPORT void JNICALL Java_com_g2806_radiante_client_proxy_world_EntityProxy_build(JNIEnv *, jclass) {
    auto world = Renderer::instance().world();
    if (world == nullptr) return;
    world->entities()->build();
}
}
