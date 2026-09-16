#include "core/render/framegen/streamline.hpp"

#ifdef MCVR_ENABLE_STREAMLINE

#include <sl.h>
#include <sl_consts.h>
#include <sl_core_api.h>
#include <sl_dlss_g.h>
#include <sl_helpers_vk.h>

#include <cstdlib>
#include <filesystem>
#include <iostream>
#include <windows.h>

namespace {

std::ostream &slCout() {
    return std::cout << "[Streamline] ";
}

struct Api {
    HMODULE module = nullptr;
    PFun_slInit *init = nullptr;
    PFun_slShutdown *shutdown = nullptr;
    PFun_slIsFeatureSupported *isFeatureSupported = nullptr;
    PFun_slSetVulkanInfo *setVulkanInfo = nullptr;
    PFun_slGetFeatureFunction *getFeatureFunction = nullptr;
    PFun_slGetFeatureRequirements *getFeatureRequirements = nullptr;
    PFN_vkGetInstanceProcAddr getInstanceProcAddr = nullptr;
    PFun_slIsFeatureLoaded *isFeatureLoaded = nullptr;
};

Api g_api;
bool g_initialised = false;
bool g_supported = false;
uint32_t g_maxGeneratedFrames = 0;
uint32_t g_generatedFrames = 0;
VkDevice g_device = VK_NULL_HANDLE;
bool g_createdThroughProxies = false;
std::string g_folder;

template <typename T>
T resolve(HMODULE module, const char *name) {
    return reinterpret_cast<T>(GetProcAddress(module, name));
}

} // namespace

bool framegen::Streamline::init(const std::string &folder) {
    if (g_initialised) return true;

    std::filesystem::path path = std::filesystem::path(folder) / "sl.interposer.dll";
    if (!std::filesystem::exists(path)) {
        slCout() << "not installed (" << path.string() << " missing)" << std::endl;
        return false;
    }

    g_api.module = LoadLibraryW(path.wstring().c_str());
    if (g_api.module == nullptr) {
        slCout() << "failed to load " << path.string() << std::endl;
        return false;
    }

    g_api.init = resolve<PFun_slInit *>(g_api.module, "slInit");
    g_api.shutdown = resolve<PFun_slShutdown *>(g_api.module, "slShutdown");
    g_api.isFeatureSupported = resolve<PFun_slIsFeatureSupported *>(g_api.module, "slIsFeatureSupported");
    g_api.setVulkanInfo = resolve<PFun_slSetVulkanInfo *>(g_api.module, "slSetVulkanInfo");
    g_api.getFeatureFunction = resolve<PFun_slGetFeatureFunction *>(g_api.module, "slGetFeatureFunction");
    g_api.getFeatureRequirements = resolve<PFun_slGetFeatureRequirements *>(g_api.module, "slGetFeatureRequirements");
    g_api.getInstanceProcAddr = resolve<PFN_vkGetInstanceProcAddr>(g_api.module, "vkGetInstanceProcAddr");
    g_api.isFeatureLoaded = resolve<PFun_slIsFeatureLoaded *>(g_api.module, "slIsFeatureLoaded");
    if (g_api.init == nullptr || g_api.shutdown == nullptr || g_api.setVulkanInfo == nullptr) {
        slCout() << "sl.interposer.dll is missing the expected entry points" << std::endl;
        FreeLibrary(g_api.module);
        g_api.module = nullptr;
        return false;
    }

    std::wstring pluginPath = std::filesystem::path(folder).wstring();
    const wchar_t *paths[] = {pluginPath.c_str()};
    sl::Feature features[] = {sl::kFeatureDLSS_G, sl::kFeatureReflex, sl::kFeaturePCL};

    sl::Preferences preferences{};
    preferences.logLevel = sl::LogLevel::eOff;
    // Streamline is silent about why frame generation stays idle unless its own log is turned on.
    if (std::getenv("RADIANTE_STREAMLINE_LOG") != nullptr) {
        preferences.logLevel = sl::LogLevel::eVerbose;
        preferences.logMessageCallback = [](sl::LogType type, const char *message) {
            std::cout << "[SL " << static_cast<int>(type) << "] " << message << std::flush;
        };
    }
    preferences.pathsToPlugins = paths;
    preferences.numPathsToPlugins = 1;
    preferences.pathToLogsAndData = nullptr;
    preferences.featuresToLoad = features;
    preferences.numFeaturesToLoad = static_cast<uint32_t>(std::size(features));
    preferences.engine = sl::EngineType::eCustom;
    preferences.engineVersion = "26.3";
    // Resources are handed over with slSetTagForFrame, which Streamline rejects unless this is declared up front.
    preferences.flags = preferences.flags | sl::PreferenceFlags::eUseFrameBasedResourceTagging;
    preferences.projectId = "7b8f1d64-2c3a-4d55-9a6e-1f0c5e7d2ab3";
    preferences.renderAPI = sl::RenderAPI::eVulkan;

    sl::Result result = g_api.init(preferences, sl::kSDKVersion);
    if (result != sl::Result::eOk) {
        slCout() << "initialisation failed: " << static_cast<int>(result) << std::endl;
        FreeLibrary(g_api.module);
        g_api.module = nullptr;
        return false;
    }

    g_initialised = true;
    g_folder = folder;
    bool loaded = false;
    if (g_api.isFeatureLoaded != nullptr) {
        g_api.isFeatureLoaded(sl::kFeatureDLSS_G, loaded);
    }
    sl::FeatureRequirements requirements{};
    bool haveRequirements = g_api.getFeatureRequirements != nullptr &&
                            g_api.getFeatureRequirements(sl::kFeatureDLSS_G, requirements) == sl::Result::eOk;
    slCout() << "loaded from " << folder << " (dlss_g plugin loaded=" << loaded
             << ", requirements=" << haveRequirements << ", instance extensions="
             << (haveRequirements ? requirements.vkNumInstanceExtensions : 0) << ", device extensions="
             << (haveRequirements ? requirements.vkNumDeviceExtensions : 0) << ", extra compute queues="
             << (haveRequirements ? requirements.vkNumComputeQueuesRequired : 0) << ", driver "
             << (haveRequirements ? requirements.driverVersionDetected.major : 0) << "."
             << (haveRequirements ? requirements.driverVersionDetected.minor : 0) << ", required "
             << (haveRequirements ? requirements.driverVersionRequired.major : 0) << "."
             << (haveRequirements ? requirements.driverVersionRequired.minor : 0) << ")" << std::endl;
    return true;
}

void framegen::Streamline::shutdown() {
    if (!g_initialised) return;
    g_api.shutdown();
    g_initialised = false;
    g_supported = false;
    g_generatedFrames = 0;
    if (g_api.module != nullptr) {
        FreeLibrary(g_api.module);
        g_api.module = nullptr;
    }
}

bool framegen::Streamline::isLoaded() {
    return g_initialised;
}

const std::string &framegen::Streamline::folder() {
    return g_folder;
}

bool framegen::Streamline::isSupported() {
    return g_supported;
}

uint32_t framegen::Streamline::maxGeneratedFrames() {
    return g_supported ? g_maxGeneratedFrames : 0;
}

void framegen::Streamline::setVulkanInfo(VkInstance instance,
                                         VkPhysicalDevice physicalDevice,
                                         VkDevice device,
                                         uint32_t graphicsQueueFamily,
                                         uint32_t graphicsQueueIndex,
                                         uint32_t computeQueueFamily,
                                         uint32_t computeQueueIndex) {
    if (!g_initialised || device == g_device) return;

    sl::VulkanInfo info{};
    info.instance = instance;
    info.physicalDevice = physicalDevice;
    info.device = device;
    info.graphicsQueueFamily = graphicsQueueFamily;
    info.graphicsQueueIndex = graphicsQueueIndex;
    info.computeQueueFamily = computeQueueFamily;
    info.computeQueueIndex = computeQueueIndex;

    // slSetVulkanInfo is only for integrations that hook Vulkan by hand. The instance and device were created
    // through Streamline's own entry points, so it already knows them and the call would fail with a missing proxy.
    if (!g_createdThroughProxies) {
        sl::Result info_result = g_api.setVulkanInfo(info);
        if (info_result != sl::Result::eOk) {
            slCout() << "could not use the Vulkan device: " << static_cast<int>(info_result) << std::endl;
            return;
        }
    }
    g_device = device;

    sl::Result result;
    sl::AdapterInfo adapter{};
    adapter.vkPhysicalDevice = physicalDevice;
    result = g_api.isFeatureSupported(sl::kFeatureDLSS_G, adapter);
    g_supported = result == sl::Result::eOk;
    if (!g_supported) {
        slCout() << "frame generation is not supported here: " << static_cast<int>(result) << std::endl;
        return;
    }

    // Multi frame generation reports how many frames it can add; older GPUs only do one.
    g_maxGeneratedFrames = 1;
    if (g_api.getFeatureFunction != nullptr) {
        void *fn = nullptr;
        if (g_api.getFeatureFunction(sl::kFeatureDLSS_G, "slDLSSGGetState", fn) == sl::Result::eOk && fn != nullptr) {
            auto getState = reinterpret_cast<PFun_slDLSSGGetState *>(fn);
            sl::DLSSGState state{};
            sl::DLSSGOptions options{};
            if (getState(sl::ViewportHandle(0), state, &options) == sl::Result::eOk &&
                state.numFramesToGenerateMax > 0) {
                g_maxGeneratedFrames = state.numFramesToGenerateMax;
            }
        }
    }
    slCout() << "frame generation available, up to " << (g_maxGeneratedFrames + 1) << "x" << std::endl;
}

namespace {
bool featureRequirements(sl::FeatureRequirements &requirements) {
    if (!g_initialised || g_api.getFeatureRequirements == nullptr) return false;
    return g_api.getFeatureRequirements(sl::kFeatureDLSS_G, requirements) == sl::Result::eOk;
}
} // namespace

std::vector<std::string> framegen::Streamline::requiredInstanceExtensions() {
    std::vector<std::string> extensions;
    sl::FeatureRequirements requirements{};
    if (!featureRequirements(requirements)) return extensions;
    for (uint32_t i = 0; i < requirements.vkNumInstanceExtensions; i++) {
        extensions.emplace_back(requirements.vkInstanceExtensions[i]);
    }
    return extensions;
}

std::vector<std::string> framegen::Streamline::requiredDeviceExtensions() {
    std::vector<std::string> extensions;
    sl::FeatureRequirements requirements{};
    if (!featureRequirements(requirements)) return extensions;
    for (uint32_t i = 0; i < requirements.vkNumDeviceExtensions; i++) {
        extensions.emplace_back(requirements.vkDeviceExtensions[i]);
    }
    return extensions;
}

uint32_t framegen::Streamline::requiredExtraComputeQueues() {
    sl::FeatureRequirements requirements{};
    return featureRequirements(requirements) ? requirements.vkNumComputeQueuesRequired : 0;
}

uint32_t framegen::Streamline::requiredExtraGraphicsQueues() {
    sl::FeatureRequirements requirements{};
    return featureRequirements(requirements) ? requirements.vkNumGraphicsQueuesRequired : 0;
}

PFN_vkCreateInstance framegen::Streamline::createInstanceProxy() {
    if (!g_initialised || g_api.getInstanceProcAddr == nullptr) return nullptr;
    auto create = reinterpret_cast<PFN_vkCreateInstance>(g_api.getInstanceProcAddr(VK_NULL_HANDLE, "vkCreateInstance"));
    if (create == nullptr) {
        slCout() << "loader has no vkCreateInstance; frame generation will stay off" << std::endl;
    }
    return create;
}

PFN_vkCreateDevice framegen::Streamline::createDeviceProxy(VkInstance instance) {
    if (!g_initialised || g_api.getInstanceProcAddr == nullptr) return nullptr;
    auto create = reinterpret_cast<PFN_vkCreateDevice>(g_api.getInstanceProcAddr(instance, "vkCreateDevice"));
    g_createdThroughProxies = create != nullptr;
    if (create == nullptr) {
        slCout() << "loader has no vkCreateDevice; frame generation will stay off" << std::endl;
    }
    return create;
}

void *framegen::Streamline::procAddress(const char *name) {
    if (!g_initialised || g_api.module == nullptr) return nullptr;
    return reinterpret_cast<void *>(GetProcAddress(g_api.module, name));
}

void *framegen::Streamline::featureFunction(uint32_t feature, const char *name) {
    if (!g_initialised || g_api.getFeatureFunction == nullptr) return nullptr;
    void *function = nullptr;
    if (g_api.getFeatureFunction(static_cast<sl::Feature>(feature), name, function) != sl::Result::eOk) {
        return nullptr;
    }
    return function;
}

void framegen::Streamline::setGeneratedFrames(uint32_t generatedFrames) {
    g_generatedFrames = g_supported ? generatedFrames : 0;
}

uint32_t framegen::Streamline::generatedFrames() {
    return g_generatedFrames;
}

#else

bool framegen::Streamline::init(const std::string &) {
    return false;
}

void framegen::Streamline::shutdown() {}

bool framegen::Streamline::isLoaded() {
    return false;
}

const std::string &framegen::Streamline::folder() {
    static const std::string empty;
    return empty;
}

bool framegen::Streamline::isSupported() {
    return false;
}

uint32_t framegen::Streamline::maxGeneratedFrames() {
    return 0;
}

void framegen::Streamline::setVulkanInfo(VkInstance, VkPhysicalDevice, VkDevice, uint32_t, uint32_t, uint32_t,
                                         uint32_t) {}

namespace {
bool featureRequirements(sl::FeatureRequirements &requirements) {
    if (!g_initialised || g_api.getFeatureRequirements == nullptr) return false;
    return g_api.getFeatureRequirements(sl::kFeatureDLSS_G, requirements) == sl::Result::eOk;
}
} // namespace

std::vector<std::string> framegen::Streamline::requiredInstanceExtensions() {
    std::vector<std::string> extensions;
    sl::FeatureRequirements requirements{};
    if (!featureRequirements(requirements)) return extensions;
    for (uint32_t i = 0; i < requirements.vkNumInstanceExtensions; i++) {
        extensions.emplace_back(requirements.vkInstanceExtensions[i]);
    }
    return extensions;
}

std::vector<std::string> framegen::Streamline::requiredDeviceExtensions() {
    std::vector<std::string> extensions;
    sl::FeatureRequirements requirements{};
    if (!featureRequirements(requirements)) return extensions;
    for (uint32_t i = 0; i < requirements.vkNumDeviceExtensions; i++) {
        extensions.emplace_back(requirements.vkDeviceExtensions[i]);
    }
    return extensions;
}

uint32_t framegen::Streamline::requiredExtraComputeQueues() {
    sl::FeatureRequirements requirements{};
    return featureRequirements(requirements) ? requirements.vkNumComputeQueuesRequired : 0;
}

uint32_t framegen::Streamline::requiredExtraGraphicsQueues() {
    sl::FeatureRequirements requirements{};
    return featureRequirements(requirements) ? requirements.vkNumGraphicsQueuesRequired : 0;
}

PFN_vkCreateInstance framegen::Streamline::createInstanceProxy() {
    return nullptr;
}

PFN_vkCreateDevice framegen::Streamline::createDeviceProxy(VkInstance) {
    return nullptr;
}

std::vector<std::string> framegen::Streamline::requiredInstanceExtensions() {
    return {};
}

std::vector<std::string> framegen::Streamline::requiredDeviceExtensions() {
    return {};
}

uint32_t framegen::Streamline::requiredExtraComputeQueues() {
    return 0;
}

uint32_t framegen::Streamline::requiredExtraGraphicsQueues() {
    return 0;
}

void *framegen::Streamline::procAddress(const char *) {
    return nullptr;
}

void *framegen::Streamline::featureFunction(uint32_t, const char *) {
    return nullptr;
}

void framegen::Streamline::setGeneratedFrames(uint32_t) {}

uint32_t framegen::Streamline::generatedFrames() {
    return 0;
}

#endif
