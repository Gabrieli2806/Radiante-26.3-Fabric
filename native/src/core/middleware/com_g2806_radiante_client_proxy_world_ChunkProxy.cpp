#include <jni.h>

#include "core/render/chunks.hpp"
#include "core/render/renderer.hpp"

#include <iostream>

extern "C" {
JNIEXPORT void JNICALL Java_com_g2806_radiante_client_proxy_world_ChunkProxy_initNative(JNIEnv *,
                                                                                  jclass,
                                                                                  jint chunkNum,
                                                                                  jint sizeX,
                                                                                  jint sizeY,
                                                                                  jint sizeZ,
                                                                                  jint bottomSectionCoord) {
    Renderer::instance().world()->chunks()->reset(chunkNum, sizeX, sizeY, sizeZ, bottomSectionCoord);
}

JNIEXPORT void JNICALL Java_com_g2806_radiante_client_proxy_world_ChunkProxy_updateSectionPosNative(JNIEnv *,
                                                                                              jclass,
                                                                                              jint sectionX,
                                                                                              jint sectionY,
                                                                                              jint sectionZ) {
    auto world = Renderer::instance().world();
    if (world == nullptr) return;
    world->chunks()->setChunkStorageSectionPos(glm::ivec3(sectionX, sectionY, sectionZ));
}

JNIEXPORT void JNICALL Java_com_g2806_radiante_client_proxy_world_ChunkProxy_rebuildSingle(JNIEnv *,
                                                                                     jclass,
                                                                                     jint originX,
                                                                                     jint originY,
                                                                                     jint originZ,
                                                                                     jlong index,
                                                                                     jint geometryCount,
                                                                                     jlong geometryTypes,
                                                                                     jlong geometryGroupNames,
                                                                                     jlong geometryTextures,
                                                                                     jlong vertexFormats,
                                                                                     jlong vertexCounts,
                                                                                     jlong vertexAddrs,
                                                                                     jboolean important) {
    auto world = Renderer::instance().world();
    if (world == nullptr) return;
    world->chunks()->queueChunkBuild(ChunkBuildTask{
        .x = originX,
        .y = originY,
        .z = originZ,
        .id = index,
        .geometryCount = geometryCount,
        .geometryTypes = reinterpret_cast<int *>(geometryTypes),
        .geometryGroupNames = reinterpret_cast<const char **>(geometryGroupNames),
        .geometryTextures = reinterpret_cast<int *>(geometryTextures),
        .vertexFormats = reinterpret_cast<int *>(vertexFormats),
        .vertexCounts = reinterpret_cast<int *>(vertexCounts),
        .vertices = reinterpret_cast<vk::VertexFormat::PBRVertex **>(vertexAddrs),
        .isImportant = static_cast<bool>(important),
    });
}

JNIEXPORT jboolean JNICALL Java_com_g2806_radiante_client_proxy_world_ChunkProxy_isChunkReady(JNIEnv *, jclass, jlong id) {
    auto world = Renderer::instance().world();
    if (world == nullptr)
        return false;
    else
        return world->chunks()->isChunkReady(id);
}

JNIEXPORT void JNICALL Java_com_g2806_radiante_client_proxy_world_ChunkProxy_relocateSingle(JNIEnv *,
                                                                                      jclass,
                                                                                      jlong index,
                                                                                      jint originX,
                                                                                      jint originY,
                                                                                      jint originZ) {
    auto world = Renderer::instance().world();
    if (world == nullptr) return;
    world->chunks()->relocateChunk(index, originX, originY, originZ);
}

JNIEXPORT void JNICALL Java_com_g2806_radiante_client_proxy_world_ChunkProxy_invalidateSingle(JNIEnv *, jclass, jlong index) {
    auto world = Renderer::instance().world();
    if (world == nullptr) return;
    world->chunks()->invalidateChunk(index);
}
}
