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

JNIEXPORT void JNICALL Java_com_g2806_radiante_client_proxy_world_ChunkProxy_setLodCoverage(
    JNIEnv *env, jclass, jint originX, jint originZ, jint size, jintArray words) {
    auto world = Renderer::instance().world();
    if (world == nullptr || world->chunks() == nullptr) return;
    if (words == nullptr || size <= 0) {
        world->chunks()->setLodCoverage(originX, originZ, 0, nullptr, 0);
        return;
    }
    jsize count = env->GetArrayLength(words);
    jint *data = env->GetIntArrayElements(words, nullptr);
    if (data == nullptr) return;
    world->chunks()->setLodCoverage(originX, originZ, size, reinterpret_cast<const uint32_t *>(data),
                                    static_cast<size_t>(count));
    env->ReleaseIntArrayElements(words, data, JNI_ABORT);
}
}
