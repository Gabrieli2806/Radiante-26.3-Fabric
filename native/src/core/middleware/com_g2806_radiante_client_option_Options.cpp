#include <jni.h>

#include "core/all_extern.hpp"
#include "core/render/buffers.hpp"
#include "core/render/chunks.hpp"
#include "core/render/render_framework.hpp"
#include "core/render/renderer.hpp"
#include "core/render/textures.hpp"
#include "core/render/world.hpp"

extern "C" {
JNIEXPORT void JNICALL Java_com_g2806_radiante_client_option_Options_nativeSetMaxFps(JNIEnv *,
                                                                               jclass,
                                                                               jint maxFps,
                                                                               jboolean write) {
    Renderer::options.maxFps = maxFps;
}

JNIEXPORT void JNICALL Java_com_g2806_radiante_client_option_Options_nativeSetInactivityFpsLimit(JNIEnv *,
                                                                                           jclass,
                                                                                           jint inactivityFpsLimit,
                                                                                           jboolean write) {
    Renderer::options.inactivityFpsLimit = inactivityFpsLimit;
}

JNIEXPORT void JNICALL Java_com_g2806_radiante_client_option_Options_nativeSetVsync(JNIEnv *,
                                                                              jclass,
                                                                              jboolean vsync,
                                                                              jboolean write) {
    Renderer::options.vsync = vsync;
    if (write) Renderer::options.needRecreate = true;
}

JNIEXPORT void JNICALL Java_com_g2806_radiante_client_option_Options_nativeSetChunkBuildingBatchSize(
    JNIEnv *, jclass, jint chunkBuildingBatchSize, jboolean write) {
    Renderer::options.chunkBuildingBatchSize = chunkBuildingBatchSize;
    if (write) Renderer::instance().world()->chunks()->resetScheduler();
}

JNIEXPORT void JNICALL Java_com_g2806_radiante_client_option_Options_nativeSetChunkBuildingTotalBatches(
    JNIEnv *, jclass, jint chunkBuildingTotalBatches, jboolean write) {
    Renderer::options.chunkBuildingTotalBatches = chunkBuildingTotalBatches;
    if (write) Renderer::instance().world()->chunks()->resetScheduler();
}

JNIEXPORT void JNICALL Java_com_g2806_radiante_client_option_Options_nativeSetCollectChunkEmission(
    JNIEnv *, jclass, jboolean collectChunkEmission, jboolean write) {
    (void)write;
    bool collect = static_cast<bool>(collectChunkEmission);
    if (Renderer::options.collectChunkEmission == collect) {
        return;
    }

    Renderer::options.collectChunkEmission = collect;
    if (!Renderer::is_initialized()) {
        return;
    }

    auto world = Renderer::instance().world();
    if (world != nullptr && world->chunks() != nullptr) {
        world->chunks()->setCollectChunkEmission(collect);
    }
    if (!collect) {
        auto textures = Renderer::instance().textures();
        if (textures != nullptr) {
            textures->releaseEmission();
        }
    }
}
}
