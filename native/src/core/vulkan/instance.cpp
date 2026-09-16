#include "core/vulkan/instance.hpp"

#include "core/render/modules/world/dlss/dlss_wrapper.hpp"
#include "core/render/modules/world/xess_upscaler/xess_wrapper.hpp"

#include <algorithm>
#include <iostream>
#include <set>
#include <string>
#include <unordered_set>
#include <vector>

namespace {
std::ostream &instanceCout() {
    return std::cout << "[Instance] ";
}

std::ostream &instanceCerr() {
    return std::cerr << "[Instance] ";
}

struct CreatedInstanceInfo {
    VkInstance instance = VK_NULL_HANDLE;
    uint32_t apiVersion = VK_API_VERSION_1_3;
    bool dlssCompatible = false;
    bool xessCompatible = false;
};

CreatedInstanceInfo g_created{};
} // namespace

VkResult vk::Instance::createMerged(const VkInstanceCreateInfo *baseInfo,
                                    const VkAllocationCallbacks *allocator,
                                    VkInstance *outInstance) {
    if (volkGetLoadedInstance() == VK_NULL_HANDLE && volkInitialize() != VK_SUCCESS) {
        instanceCerr() << "volkInitialize failed" << std::endl;
        return VK_ERROR_INITIALIZATION_FAILED;
    }

    VkApplicationInfo appInfo{};
    if (baseInfo->pApplicationInfo != nullptr) appInfo = *baseInfo->pApplicationInfo;
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.apiVersion = std::max<uint32_t>(appInfo.apiVersion, VK_API_VERSION_1_3);

    std::set<std::string> extStorage;
    for (uint32_t i = 0; i < baseInfo->enabledExtensionCount; i++) {
        extStorage.insert(baseInfo->ppEnabledExtensionNames[i]);
    }

    std::vector<std::string> dlssRequired;
    bool dlssQueried = false;
    std::vector<VkExtensionProperties> dlssExtensions;
    if (NVSDK_NGX_SUCCEED(NgxContext::getDlssRRRequiredInstanceExtensions(dlssExtensions))) {
        dlssQueried = true;
        for (const auto &ext : dlssExtensions) {
            extStorage.insert(ext.extensionName);
            dlssRequired.emplace_back(ext.extensionName);
        }
    } else {
        instanceCerr() << "failed to query dlss instance extensions; skipping." << std::endl;
    }

#ifdef MCVR_ENABLE_XESS
    std::vector<std::string> xessRequired;
    bool xessQueried = false;
    std::vector<const char *> xessExtensions;
    uint32_t xessMinApiVersion = 0;
    if (mcvr::XeSSWrapper::getRequiredInstanceExtensions(xessExtensions, &xessMinApiVersion)) {
        xessQueried = true;
        for (const char *ext : xessExtensions) {
            extStorage.insert(ext);
            xessRequired.emplace_back(ext);
        }
        appInfo.apiVersion = std::max(appInfo.apiVersion, xessMinApiVersion);
    } else {
        instanceCerr() << "xess instance extensions unavailable; skipping." << std::endl;
    }
#endif

    extStorage.insert(VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME);

    uint32_t extensionCount = 0;
    vkEnumerateInstanceExtensionProperties(nullptr, &extensionCount, nullptr);
    std::vector<VkExtensionProperties> available(extensionCount);
    vkEnumerateInstanceExtensionProperties(nullptr, &extensionCount, available.data());
    std::unordered_set<std::string> availableSet;
    for (const auto &ext : available) availableSet.insert(ext.extensionName);

    auto allSupported = [&](const std::vector<std::string> &required) {
        return std::all_of(required.begin(), required.end(),
                           [&](const std::string &name) { return availableSet.contains(name); });
    };

    g_created.dlssCompatible = dlssQueried && allSupported(dlssRequired);
#ifdef MCVR_ENABLE_XESS
    g_created.xessCompatible = xessQueried && allSupported(xessRequired);
#endif

    std::vector<const char *> extensions;
    for (const auto &ext : extStorage) {
        if (!availableSet.contains(ext)) {
            instanceCerr() << "extension not supported, skipping: " << ext << std::endl;
            continue;
        }
        extensions.push_back(ext.c_str());
    }

    VkInstanceCreateInfo createInfo = *baseInfo;
    createInfo.pApplicationInfo = &appInfo;
    createInfo.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
    createInfo.ppEnabledExtensionNames = extensions.data();

    VkResult result = vkCreateInstance(&createInfo, allocator, outInstance);
    if (result != VK_SUCCESS) {
        instanceCerr() << "vkCreateInstance failed: " << result << std::endl;
        return result;
    }

    volkLoadInstanceOnly(*outInstance);
    g_created.instance = *outInstance;
    g_created.apiVersion = appInfo.apiVersion;
    instanceCout() << "created shared instance (api " << VK_API_VERSION_MAJOR(appInfo.apiVersion) << "."
                   << VK_API_VERSION_MINOR(appInfo.apiVersion) << ", dlss=" << g_created.dlssCompatible
                   << ", xess=" << g_created.xessCompatible << ")" << std::endl;
    return VK_SUCCESS;
}

VkInstance vk::Instance::lastCreatedInstance() {
    return g_created.instance;
}

vk::Instance::Instance(VkInstance instance) : instance_(instance) {
    volkLoadInstanceOnly(instance_);
}

vk::Instance::~Instance() {
    // Owned by Minecraft's backend.
}

VkInstance &vk::Instance::vkInstance() {
    return instance_;
}

uint32_t vk::Instance::apiVersion() const {
    return g_created.apiVersion;
}

bool vk::Instance::isDlssInstanceExtensionsCompatible() const {
    return g_created.instance == instance_ && g_created.dlssCompatible;
}

bool vk::Instance::isXessInstanceExtensionsCompatible() const {
    return g_created.instance == instance_ && g_created.xessCompatible;
}
