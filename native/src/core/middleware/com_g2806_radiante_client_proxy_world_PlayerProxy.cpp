#include <jni.h>

#include "core/render/chunks.hpp"
#include "core/render/renderer.hpp"

extern "C" {
JNIEXPORT void JNICALL
Java_com_g2806_radiante_client_proxy_world_PlayerProxy_setCameraPos(JNIEnv *, jclass, jdouble x, jdouble y, jdouble z) {
    auto world = Renderer::instance().world();
    if (world == nullptr) return;
    Renderer::instance().world()->setCameraPos(glm::dvec3{x, y, z});
}
}
