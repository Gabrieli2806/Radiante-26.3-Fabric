#include <jni.h>

#include "core/render/modules/world/ray_tracing/ray_tracing_module.hpp"
#include "core/render/pipeline.hpp"
#include "core/render/render_framework.hpp"
#include "core/render/renderer.hpp"

#include <iostream>
#include <vector>

extern "C" {
JNIEXPORT void JNICALL Java_com_g2806_radiante_client_pipeline_Pipeline_buildNative(JNIEnv *, jclass, jlong paramsLongPtr) {
    WorldPipelineBuildParams *params = reinterpret_cast<WorldPipelineBuildParams *>(paramsLongPtr);
    auto pipeline = Renderer::instance().framework()->pipeline();
    if (pipeline != nullptr) Renderer::instance().framework()->pipeline()->buildWorldPipelineBlueprint(params);
}

JNIEXPORT void JNICALL Java_com_g2806_radiante_client_pipeline_Pipeline_collectNativeModules(JNIEnv *, jclass) {
    Pipeline::collectWorldModules();
}

JNIEXPORT jboolean JNICALL Java_com_g2806_radiante_client_pipeline_Pipeline_isNativeModuleAvailable(JNIEnv *env,
                                                                                              jclass,
                                                                                              jstring name) {
    if (name == nullptr) return JNI_FALSE;
    const char *nativeString = env->GetStringUTFChars(name, nullptr);
    if (nativeString == nullptr) return JNI_FALSE;
    bool available = Pipeline::worldModuleConstructors.find(nativeString) != Pipeline::worldModuleConstructors.end();
    env->ReleaseStringUTFChars(name, nativeString);
    return available ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_com_g2806_radiante_client_pipeline_Pipeline_getAttributes(JNIEnv *env,
                                                                                   jclass,
                                                                                   jstring name,
                                                                                   jobjectArray attributes,
                                                                                   jstring languageObject) {
    if (name == nullptr) {
        return env->NewStringUTF("{}");
    }

    const char *moduleNameChars = env->GetStringUTFChars(name, nullptr);
    if (moduleNameChars == nullptr) {
        return env->NewStringUTF("{}");
    }

    std::vector<std::string> attributeList;
    if (attributes != nullptr) {
        jsize count = env->GetArrayLength(attributes);
        attributeList.reserve(static_cast<size_t>(count));
        for (jsize i = 0; i < count; i++) {
            auto *entry = static_cast<jstring>(env->GetObjectArrayElement(attributes, i));
            if (entry == nullptr) {
                attributeList.emplace_back();
                continue;
            }

            const char *entryChars = env->GetStringUTFChars(entry, nullptr);
            attributeList.emplace_back(entryChars != nullptr ? entryChars : "");
            if (entryChars != nullptr) {
                env->ReleaseStringUTFChars(entry, entryChars);
            }
            env->DeleteLocalRef(entry);
        }
    }

    std::string language = "en_us";
    if (languageObject != nullptr) {
        const char *languageChars = env->GetStringUTFChars(languageObject, nullptr);
        if (languageChars != nullptr) {
            language = languageChars;
            env->ReleaseStringUTFChars(languageObject, languageChars);
        }
    }

    std::string moduleName(moduleNameChars);
    env->ReleaseStringUTFChars(name, moduleNameChars);

    std::string result = "{}";
    if (moduleName == std::string(RayTracingModule::NAME)) {
        RayTracingModule rayTracingModule;
        result = rayTracingModule.getAttributes(attributeList, language);
    }

    return env->NewStringUTF(result.c_str());
}
}
