#include "core/vulkan/device.hpp"

#include "core/render/framegen/reflex.hpp"

#include "core/render/modules/world/dlss/dlss_wrapper.hpp"
#include "core/render/modules/world/xess_upscaler/xess_wrapper.hpp"
#include "core/vulkan/instance.hpp"
#include "core/vulkan/physical_device.hpp"
#include "core/vulkan/queue_lock.hpp"

#include <chrono>
#include <cstring>
#include <fstream>
#include <iostream>
#include <list>
#include <unordered_set>
#include <vector>
#include "core/util/logging.hpp"

namespace {
std::ostream &deviceCout() {
    return radiante::out() << "[Device] ";
}

std::ostream &deviceCerr() {
    return radiante::err() << "[Device] ";
}

struct CreatedDeviceInfo {
    VkDevice device = VK_NULL_HANDLE;
    bool extendedDynamicState2LogicOp = false;
    bool dlssCompatible = false;
    bool dlssgCompatible = false;
    bool fsrgCompatible = false;
    bool serEnabled = false;
    bool xessCompatible = false;
};

CreatedDeviceInfo g_created{};

struct ChainHeader {
    VkStructureType sType;
    void *pNext;
};

constexpr size_t kBoolFieldsOffset = offsetof(VkPhysicalDeviceVulkan11Features, storageBuffer16BitAccess);

void orBoolFields(void *dst, const void *src, size_t structSize) {
    auto *d = static_cast<uint8_t *>(dst);
    auto *s = static_cast<const uint8_t *>(src);
    for (size_t off = kBoolFieldsOffset; off + sizeof(VkBool32) <= structSize; off += sizeof(VkBool32)) {
        VkBool32 value;
        std::memcpy(&value, s + off, sizeof(VkBool32));
        if (value) {
            VkBool32 one = VK_TRUE;
            std::memcpy(d + off, &one, sizeof(VkBool32));
        }
    }
}

size_t featureStructSize(VkStructureType sType) {
    switch (sType) {
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES: return sizeof(VkPhysicalDeviceVulkan11Features);
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES: return sizeof(VkPhysicalDeviceVulkan12Features);
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES: return sizeof(VkPhysicalDeviceVulkan13Features);
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SYNCHRONIZATION_2_FEATURES:
            return sizeof(VkPhysicalDeviceSynchronization2Features);
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_FEATURES:
            return sizeof(VkPhysicalDeviceDynamicRenderingFeatures);
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VERTEX_ATTRIBUTE_DIVISOR_FEATURES_EXT:
            return sizeof(VkPhysicalDeviceVertexAttributeDivisorFeaturesEXT);
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MULTI_DRAW_FEATURES_EXT:
            return sizeof(VkPhysicalDeviceMultiDrawFeaturesEXT);
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES:
            return sizeof(VkPhysicalDeviceTimelineSemaphoreFeatures);
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_HOST_QUERY_RESET_FEATURES:
            return sizeof(VkPhysicalDeviceHostQueryResetFeatures);
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_DRAW_PARAMETERS_FEATURES:
            return sizeof(VkPhysicalDeviceShaderDrawParametersFeatures);
        default: return 0;
    }
}
} // namespace

VkResult vk::Device::createMerged(VkPhysicalDevice physicalDeviceHandle,
                                  const VkDeviceCreateInfo *baseInfo,
                                  const VkAllocationCallbacks *allocator,
                                  VkDevice *outDevice) {
    auto instance = Instance::create(Instance::lastCreatedInstance());
    auto physicalDevice = PhysicalDevice::create(instance, physicalDeviceHandle, 0, 0);

    if (!PhysicalDevice::isRayTracingCapable(physicalDeviceHandle)) {
        deviceCerr() << "selected GPU does not support hardware ray tracing" << std::endl;
        return VK_ERROR_FEATURE_NOT_PRESENT;
    }

    std::vector<std::string> requested;
    for (uint32_t i = 0; i < baseInfo->enabledExtensionCount; i++) {
        requested.emplace_back(baseInfo->ppEnabledExtensionNames[i]);
    }
    for (const char *ext : {
             VK_KHR_SWAPCHAIN_EXTENSION_NAME,
             VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME,
             VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME,
             VK_KHR_SPIRV_1_4_EXTENSION_NAME,
             VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME,
             VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME,
             VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME,
             VK_EXT_DESCRIPTOR_INDEXING_EXTENSION_NAME,
             VK_EXT_EXTENDED_DYNAMIC_STATE_2_EXTENSION_NAME,
             VK_EXT_EXTENDED_DYNAMIC_STATE_3_EXTENSION_NAME,
             VK_EXT_VERTEX_INPUT_DYNAMIC_STATE_EXTENSION_NAME,
         }) {
        requested.emplace_back(ext);
    }

    // NVIDIA Reflex (VK_NV_low_latency2; the present ids tie its markers to presented frames).
    const bool reflexExtensions = framegen::Reflex::deviceSupports(physicalDeviceHandle);
    if (reflexExtensions) {
        requested.emplace_back(VK_NV_LOW_LATENCY_2_EXTENSION_NAME);
        requested.emplace_back(VK_KHR_PRESENT_ID_EXTENSION_NAME);
    }

    // DLSS Frame Generation, driven through NGX directly.
    std::vector<std::string> dlssgRequired;
    bool dlssgQueried = false;
    {
        std::vector<VkExtensionProperties> dlssgExtensions;
        if (NVSDK_NGX_SUCCEED(
                NgxContext::getDlssFrameGenerationRequiredDeviceExtensions(instance, physicalDevice, dlssgExtensions))) {
            dlssgQueried = true;
            for (const auto &ext : dlssgExtensions) {
                dlssgRequired.emplace_back(ext.extensionName);
                if (std::strcmp(ext.extensionName, "VK_EXT_buffer_device_address") == 0) continue;
                requested.emplace_back(ext.extensionName);
            }
        }
    }
    // FSR frame generation needs these (all core to the Radiante device already, except the format list).
    requested.emplace_back(VK_KHR_IMAGE_FORMAT_LIST_EXTENSION_NAME);

    std::vector<std::string> dlssRequired;
    bool dlssQueried = false;
    std::vector<VkExtensionProperties> dlssExtensions;
    if (NVSDK_NGX_FAILED(NgxContext::getDlssRRRequiredDeviceExtensions(instance, physicalDevice, dlssExtensions))) {
        deviceCout() << "dlss device extensions unavailable; skipping." << std::endl;
    } else {
        dlssQueried = true;
        for (const auto &ext : dlssExtensions) {
            dlssRequired.emplace_back(ext.extensionName);
            // Covered by the Vulkan 1.2 bufferDeviceAddress feature.
            if (std::strcmp(ext.extensionName, "VK_EXT_buffer_device_address") == 0) continue;
            requested.emplace_back(ext.extensionName);
        }
    }

#ifdef MCVR_ENABLE_XESS
    std::vector<std::string> xessRequired;
    bool xessQueried = false;
    std::vector<const char *> xessExtensions;
    if (mcvr::XeSSWrapper::getRequiredDeviceExtensions(instance->vkInstance(), physicalDeviceHandle, xessExtensions)) {
        xessQueried = true;
        for (const char *ext : xessExtensions) {
            xessRequired.emplace_back(ext);
            requested.emplace_back(ext);
        }
    } else {
        deviceCout() << "xess device extensions unavailable; skipping." << std::endl;
    }
#endif

    uint32_t deviceExtensionCount = 0;
    vkEnumerateDeviceExtensionProperties(physicalDeviceHandle, nullptr, &deviceExtensionCount, nullptr);
    std::vector<VkExtensionProperties> deviceExtensions(deviceExtensionCount);
    vkEnumerateDeviceExtensionProperties(physicalDeviceHandle, nullptr, &deviceExtensionCount, deviceExtensions.data());
    std::unordered_set<std::string> supported;
    for (const auto &ext : deviceExtensions) supported.insert(ext.extensionName);

    if (supported.contains(VK_AMD_DEVICE_COHERENT_MEMORY_EXTENSION_NAME)) {
        requested.emplace_back(VK_AMD_DEVICE_COHERENT_MEMORY_EXTENSION_NAME);
    }
    // Shader Execution Reordering: the GPU regroups rays by what they hit before shading them (RTX 40 and newer).
    const bool serExtension = supported.contains(VK_NV_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME);
    if (serExtension) {
        requested.emplace_back(VK_NV_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME);
    }

    auto allSupported = [&](const std::vector<std::string> &required) {
        for (const auto &ext : required) {
            if (ext == "VK_EXT_buffer_device_address") continue;
            if (!supported.contains(ext)) return false;
        }
        return true;
    };

    const bool dlssCompatible =
        instance->isDlssInstanceExtensionsCompatible() && dlssQueried && allSupported(dlssRequired);
    const bool dlssgCompatible = instance->isDlssFrameGenerationInstanceExtensionsCompatible() && dlssgQueried &&
                                 allSupported(dlssgRequired);
    const bool fsrgCompatible =
        allSupported({VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME, VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME,
                      VK_KHR_IMAGE_FORMAT_LIST_EXTENSION_NAME, VK_EXT_DESCRIPTOR_INDEXING_EXTENSION_NAME,
                      VK_EXT_EXTENDED_DYNAMIC_STATE_3_EXTENSION_NAME});
#ifdef MCVR_ENABLE_XESS
    bool xessCompatible = instance->isXessInstanceExtensionsCompatible() && xessQueried && allSupported(xessRequired);
#else
    bool xessCompatible = false;
#endif

    std::vector<std::string> selectedNames;
    std::unordered_set<std::string> seen;
    for (const auto &ext : requested) {
        if (!supported.contains(ext)) {
            deviceCerr() << "extension not supported, skipping: " << ext << std::endl;
            continue;
        }
        if (seen.insert(ext).second) selectedNames.push_back(ext);
    }
    std::vector<const char *> selected;
    for (const auto &ext : selectedNames) selected.push_back(ext.c_str());
    auto hasExtension = [&](const char *name) { return seen.contains(name); };

    // query supported features
    VkPhysicalDeviceMaintenance5Features supportedMaintenance5{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAINTENANCE_5_FEATURES};
    VkPhysicalDeviceCoherentMemoryFeaturesAMD supportedCoherentMemory{
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_COHERENT_MEMORY_FEATURES_AMD, &supportedMaintenance5};
    VkPhysicalDeviceVertexInputDynamicStateFeaturesEXT supportedVertexInputDynamicState{
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VERTEX_INPUT_DYNAMIC_STATE_FEATURES_EXT, &supportedCoherentMemory};
    VkPhysicalDeviceExtendedDynamicState3FeaturesEXT supportedEds3{
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTENDED_DYNAMIC_STATE_3_FEATURES_EXT, &supportedVertexInputDynamicState};
    VkPhysicalDeviceExtendedDynamicState2FeaturesEXT supportedEds2{
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTENDED_DYNAMIC_STATE_2_FEATURES_EXT, &supportedEds3};
    VkPhysicalDeviceVulkan13Features supportedVulkan13{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES,
                                                       &supportedEds2};
    VkPhysicalDeviceVulkan11Features supportedVulkan11{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES,
                                                       &supportedVulkan13};
    VkPhysicalDeviceVulkan12Features supportedVulkan12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES,
                                                       &supportedVulkan11};
    VkPhysicalDeviceAccelerationStructureFeaturesKHR supportedAccelerationStructure{
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR, &supportedVulkan12};
    VkPhysicalDeviceRayTracingPipelineFeaturesKHR supportedRayTracing{
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR, &supportedAccelerationStructure};
    VkPhysicalDeviceRayTracingInvocationReorderFeaturesNV supportedReorder{
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_INVOCATION_REORDER_FEATURES_NV, &supportedRayTracing};
    VkPhysicalDeviceFeatures2 supportedFeatures2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2,
                                                 serExtension ? static_cast<void *>(&supportedReorder)
                                                              : static_cast<void *>(&supportedRayTracing)};
    vkGetPhysicalDeviceFeatures2(physicalDeviceHandle, &supportedFeatures2);

    // enabled features
    VkPhysicalDeviceMaintenance5Features maintenance5{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAINTENANCE_5_FEATURES};
    maintenance5.maintenance5 = hasExtension(VK_KHR_MAINTENANCE_5_EXTENSION_NAME) ? supportedMaintenance5.maintenance5 :
                                                                                    VK_FALSE;

    VkPhysicalDeviceCoherentMemoryFeaturesAMD coherentMemory{
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_COHERENT_MEMORY_FEATURES_AMD, &maintenance5};
    coherentMemory.deviceCoherentMemory = hasExtension(VK_AMD_DEVICE_COHERENT_MEMORY_EXTENSION_NAME) ?
                                              supportedCoherentMemory.deviceCoherentMemory :
                                              VK_FALSE;

    VkPhysicalDeviceVertexInputDynamicStateFeaturesEXT vertexInputDynamicState{
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VERTEX_INPUT_DYNAMIC_STATE_FEATURES_EXT, &coherentMemory};
    vertexInputDynamicState.vertexInputDynamicState = hasExtension(VK_EXT_VERTEX_INPUT_DYNAMIC_STATE_EXTENSION_NAME) ?
                                                          supportedVertexInputDynamicState.vertexInputDynamicState :
                                                          VK_FALSE;

    VkPhysicalDeviceExtendedDynamicState3FeaturesEXT eds3{
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTENDED_DYNAMIC_STATE_3_FEATURES_EXT, &vertexInputDynamicState};
    if (hasExtension(VK_EXT_EXTENDED_DYNAMIC_STATE_3_EXTENSION_NAME)) {
        eds3.extendedDynamicState3PolygonMode = supportedEds3.extendedDynamicState3PolygonMode;
        eds3.extendedDynamicState3ColorBlendEnable = supportedEds3.extendedDynamicState3ColorBlendEnable;
        eds3.extendedDynamicState3ColorBlendEquation = supportedEds3.extendedDynamicState3ColorBlendEquation;
        eds3.extendedDynamicState3ColorWriteMask = supportedEds3.extendedDynamicState3ColorWriteMask;
        eds3.extendedDynamicState3LogicOpEnable = supportedEds3.extendedDynamicState3LogicOpEnable;
    }

    VkPhysicalDeviceExtendedDynamicState2FeaturesEXT eds2{
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTENDED_DYNAMIC_STATE_2_FEATURES_EXT, &eds3};
    bool logicOp = false;
    if (hasExtension(VK_EXT_EXTENDED_DYNAMIC_STATE_2_EXTENSION_NAME)) {
        eds2.extendedDynamicState2 = supportedEds2.extendedDynamicState2;
        eds2.extendedDynamicState2LogicOp = supportedEds2.extendedDynamicState2LogicOp;
        logicOp = eds2.extendedDynamicState2LogicOp == VK_TRUE;
    }

    VkPhysicalDeviceVulkan13Features vulkan13{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES, &eds2};
    vulkan13.shaderDemoteToHelperInvocation = supportedVulkan13.shaderDemoteToHelperInvocation;
    vulkan13.synchronization2 = supportedVulkan13.synchronization2;

    VkPhysicalDeviceVulkan11Features vulkan11{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES, &vulkan13};
    vulkan11.storageBuffer16BitAccess = supportedVulkan11.storageBuffer16BitAccess;

    VkPhysicalDeviceVulkan12Features vulkan12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES, &vulkan11};
    vulkan12.bufferDeviceAddress = supportedVulkan12.bufferDeviceAddress;
    vulkan12.descriptorBindingUpdateUnusedWhilePending = supportedVulkan12.descriptorBindingUpdateUnusedWhilePending;
    vulkan12.descriptorBindingPartiallyBound = supportedVulkan12.descriptorBindingPartiallyBound;
    vulkan12.descriptorIndexing = supportedVulkan12.descriptorIndexing;
    vulkan12.runtimeDescriptorArray = supportedVulkan12.runtimeDescriptorArray;
    vulkan12.descriptorBindingVariableDescriptorCount = supportedVulkan12.descriptorBindingVariableDescriptorCount;
    vulkan12.shaderSampledImageArrayNonUniformIndexing = supportedVulkan12.shaderSampledImageArrayNonUniformIndexing;
    vulkan12.descriptorBindingUniformBufferUpdateAfterBind =
        supportedVulkan12.descriptorBindingUniformBufferUpdateAfterBind;
    vulkan12.descriptorBindingSampledImageUpdateAfterBind =
        supportedVulkan12.descriptorBindingSampledImageUpdateAfterBind;
    vulkan12.descriptorBindingStorageImageUpdateAfterBind =
        supportedVulkan12.descriptorBindingStorageImageUpdateAfterBind;
    vulkan12.descriptorBindingStorageBufferUpdateAfterBind =
        supportedVulkan12.descriptorBindingStorageBufferUpdateAfterBind;
    vulkan12.shaderFloat16 = supportedVulkan12.shaderFloat16;
    vulkan12.shaderBufferInt64Atomics = supportedVulkan12.shaderBufferInt64Atomics;
    vulkan12.shaderStorageBufferArrayNonUniformIndexing = supportedVulkan12.shaderStorageBufferArrayNonUniformIndexing;
    vulkan12.shaderStorageImageArrayNonUniformIndexing = supportedVulkan12.shaderStorageImageArrayNonUniformIndexing;
    vulkan12.shaderUniformBufferArrayNonUniformIndexing = supportedVulkan12.shaderUniformBufferArrayNonUniformIndexing;

    VkPhysicalDeviceAccelerationStructureFeaturesKHR accelerationStructure{
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR, &vulkan12};
    accelerationStructure.accelerationStructure = supportedAccelerationStructure.accelerationStructure;
    accelerationStructure.descriptorBindingAccelerationStructureUpdateAfterBind =
        supportedAccelerationStructure.descriptorBindingAccelerationStructureUpdateAfterBind;

    VkPhysicalDeviceRayTracingPipelineFeaturesKHR rayTracing{
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR, &accelerationStructure};
    rayTracing.rayTracingPipeline = supportedRayTracing.rayTracingPipeline;

    VkPhysicalDeviceRayTracingInvocationReorderFeaturesNV reorder{
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_INVOCATION_REORDER_FEATURES_NV, &rayTracing};
    const bool serEnabled = serExtension && hasExtension(VK_NV_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME) &&
                            supportedReorder.rayTracingInvocationReorder == VK_TRUE;
    reorder.rayTracingInvocationReorder = serEnabled ? VK_TRUE : VK_FALSE;
    VkPhysicalDeviceFeatures2 features2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2,
                                        serEnabled ? static_cast<void *>(&reorder) : static_cast<void *>(&rayTracing)};
    auto &features = features2.features;
    const auto &sf = supportedFeatures2.features;
    features.independentBlend = sf.independentBlend;
    features.shaderClipDistance = sf.shaderClipDistance;
    features.shaderCullDistance = sf.shaderCullDistance;
    features.logicOp = sf.logicOp;
    features.fillModeNonSolid = sf.fillModeNonSolid;
    features.depthBiasClamp = sf.depthBiasClamp;
    features.shaderInt64 = sf.shaderInt64;
    features.shaderFloat64 = sf.shaderFloat64;
    features.shaderInt16 = sf.shaderInt16;
    features.shaderStorageImageReadWithoutFormat = sf.shaderStorageImageReadWithoutFormat;
    features.shaderStorageImageWriteWithoutFormat = sf.shaderStorageImageWriteWithoutFormat;

    // merge Minecraft's requested features
    if (baseInfo->pEnabledFeatures != nullptr) {
        auto *dst = reinterpret_cast<VkBool32 *>(&features);
        auto *src = reinterpret_cast<const VkBool32 *>(baseInfo->pEnabledFeatures);
        for (size_t i = 0; i < sizeof(VkPhysicalDeviceFeatures) / sizeof(VkBool32); i++) {
            if (src[i]) dst[i] = VK_TRUE;
        }
    }

    std::list<std::vector<uint8_t>> extraStructs;
    void *chainTail = &maintenance5;
    for (auto *node = static_cast<const ChainHeader *>(baseInfo->pNext); node != nullptr;
         node = static_cast<const ChainHeader *>(node->pNext)) {
        size_t size = featureStructSize(node->sType);
        switch (node->sType) {
            case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2: {
                auto *src = reinterpret_cast<const VkPhysicalDeviceFeatures2 *>(node);
                auto *dstBools = reinterpret_cast<VkBool32 *>(&features);
                auto *srcBools = reinterpret_cast<const VkBool32 *>(&src->features);
                for (size_t i = 0; i < sizeof(VkPhysicalDeviceFeatures) / sizeof(VkBool32); i++) {
                    if (srcBools[i]) dstBools[i] = VK_TRUE;
                }
                break;
            }
            case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES: orBoolFields(&vulkan11, node, size); break;
            case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES: orBoolFields(&vulkan12, node, size); break;
            case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES: orBoolFields(&vulkan13, node, size); break;
            case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SYNCHRONIZATION_2_FEATURES:
                if (reinterpret_cast<const VkPhysicalDeviceSynchronization2Features *>(node)->synchronization2)
                    vulkan13.synchronization2 = VK_TRUE;
                break;
            case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_FEATURES:
                if (reinterpret_cast<const VkPhysicalDeviceDynamicRenderingFeatures *>(node)->dynamicRendering)
                    vulkan13.dynamicRendering = VK_TRUE;
                break;
            case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES:
                if (reinterpret_cast<const VkPhysicalDeviceTimelineSemaphoreFeatures *>(node)->timelineSemaphore)
                    vulkan12.timelineSemaphore = VK_TRUE;
                break;
            case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_HOST_QUERY_RESET_FEATURES:
                if (reinterpret_cast<const VkPhysicalDeviceHostQueryResetFeatures *>(node)->hostQueryReset)
                    vulkan12.hostQueryReset = VK_TRUE;
                break;
            case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_DRAW_PARAMETERS_FEATURES:
                if (reinterpret_cast<const VkPhysicalDeviceShaderDrawParametersFeatures *>(node)->shaderDrawParameters)
                    vulkan11.shaderDrawParameters = VK_TRUE;
                break;
            default: {
                if (size == 0) {
                    deviceCerr() << "unknown feature struct in Minecraft device chain, sType=" << node->sType
                                 << "; skipping" << std::endl;
                    break;
                }
                auto &copy = extraStructs.emplace_back(size);
                std::memcpy(copy.data(), node, size);
                reinterpret_cast<ChainHeader *>(copy.data())->pNext = nullptr;
                static_cast<ChainHeader *>(chainTail)->pNext = copy.data();
                chainTail = copy.data();
                break;
            }
        }
    }

#ifdef MCVR_ENABLE_XESS
    if (xessCompatible) {
        void *featureChain = &features2;
        if (!mcvr::XeSSWrapper::getRequiredDeviceFeatures(instance->vkInstance(), physicalDeviceHandle,
                                                          &featureChain)) {
            xessCompatible = false;
            deviceCout() << "xess device feature requirements are not fully satisfied." << std::endl;
        }
    }
#endif

    // Reflex ties its latency markers to presented frames by present id.
    VkPhysicalDevicePresentIdFeaturesKHR supportedPresentId{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_ID_FEATURES_KHR};
    VkPhysicalDevicePresentIdFeaturesKHR presentId{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_ID_FEATURES_KHR};
    bool reflexEnabled = false;
    if (hasExtension(VK_NV_LOW_LATENCY_2_EXTENSION_NAME) && hasExtension(VK_KHR_PRESENT_ID_EXTENSION_NAME)) {
        VkPhysicalDeviceFeatures2 query{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2, &supportedPresentId};
        vkGetPhysicalDeviceFeatures2(physicalDeviceHandle, &query);
        if (supportedPresentId.presentId == VK_TRUE) {
            presentId.presentId = VK_TRUE;
            presentId.pNext = features2.pNext;
            features2.pNext = &presentId;
            reflexEnabled = true;
        }
    }

    std::vector<VkDeviceQueueCreateInfo> queueInfos(baseInfo->pQueueCreateInfos,
                                                    baseInfo->pQueueCreateInfos + baseInfo->queueCreateInfoCount);
    std::vector<std::vector<float>> queuePriorities;
    queuePriorities.reserve(queueInfos.size() + 2);
    for (const VkDeviceQueueCreateInfo &info : queueInfos) {
        queuePriorities.emplace_back(info.pQueuePriorities, info.pQueuePriorities + info.queueCount);
    }

    for (size_t i = 0; i < queueInfos.size(); i++) {
        queueInfos[i].pQueuePriorities = queuePriorities[i].data();
    }

    VkDeviceCreateInfo createInfo = *baseInfo;
    createInfo.queueCreateInfoCount = static_cast<uint32_t>(queueInfos.size());
    createInfo.pQueueCreateInfos = queueInfos.data();
    createInfo.pNext = &features2;
    createInfo.pEnabledFeatures = nullptr;
    createInfo.enabledExtensionCount = static_cast<uint32_t>(selected.size());
    createInfo.ppEnabledExtensionNames = selected.data();

    VkResult result = vkCreateDevice(physicalDeviceHandle, &createInfo, allocator, outDevice);
    if (result != VK_SUCCESS) {
        deviceCerr() << "vkCreateDevice failed: " << result << std::endl;
        return result;
    }

    volkLoadDevice(*outDevice);
    framegen::Reflex::setDevice(*outDevice, reflexEnabled);
    installQueueLockHooks();

    g_created.device = *outDevice;
    g_created.extendedDynamicState2LogicOp = logicOp;
    g_created.dlssCompatible = dlssCompatible;
    g_created.dlssgCompatible = dlssgCompatible;
    g_created.fsrgCompatible = fsrgCompatible;
    g_created.serEnabled = serEnabled;
    g_created.xessCompatible = xessCompatible;
    deviceCout() << "created shared device with " << selected.size() << " extensions (dlss=" << dlssCompatible
                 << ", dlss frame generation=" << dlssgCompatible << ", fsr frame generation=" << fsrgCompatible
                 << ", ser=" << serEnabled << ", xess=" << xessCompatible << ")" << std::endl;
    return VK_SUCCESS;
}

vk::Device::Device(std::shared_ptr<Instance> instance,
                   std::shared_ptr<PhysicalDevice> physicalDevice,
                   VkDevice device,
                   VkQueue mainQueue,
                   VkQueue secondaryQueue)
    : instance_(instance),
      physicalDevice_(physicalDevice),
      device_(device),
      mainQueue_(mainQueue),
      secondaryQueue_(secondaryQueue) {
    volkLoadDevice(device_);
    installQueueLockHooks();
}

std::filesystem::path vk::Device::pipelineCacheFile_;

void vk::Device::setPipelineCacheFile(const std::filesystem::path &file) {
    pipelineCacheFile_ = file;
}

VkPipelineCache vk::Device::pipelineCache() {
    std::lock_guard<std::mutex> lock(pipelineCacheMutex_);
    if (pipelineCache_ != VK_NULL_HANDLE) { return pipelineCache_; }

    std::vector<char> data;
    if (!pipelineCacheFile_.empty()) {
        std::ifstream in(pipelineCacheFile_, std::ios::binary | std::ios::ate);
        if (in) {
            std::streamsize size = in.tellg();
            if (size > 0) {
                data.resize(static_cast<size_t>(size));
                in.seekg(0);
                if (!in.read(data.data(), size)) { data.clear(); }
            }
        }
    }

    VkPipelineCacheCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO;
    info.initialDataSize = data.size();
    info.pInitialData = data.empty() ? nullptr : data.data();
    VkResult result = vkCreatePipelineCache(device_, &info, nullptr, &pipelineCache_);
    if (result != VK_SUCCESS && !data.empty()) {
        // Unusable data (another driver, a damaged file): start empty.
        info.initialDataSize = 0;
        info.pInitialData = nullptr;
        result = vkCreatePipelineCache(device_, &info, nullptr, &pipelineCache_);
    }
    if (result != VK_SUCCESS) {
        deviceCerr() << "cannot create the pipeline cache (" << result << "); pipelines are built without one"
                     << std::endl;
        pipelineCache_ = VK_NULL_HANDLE;
    } else {
        deviceCout() << "pipeline cache loaded: " << data.size() / 1024 << " KiB" << std::endl;
    }
    return pipelineCache_;
}

void vk::Device::savePipelineCache() {
    VkPipelineCache cache;
    {
        std::lock_guard<std::mutex> lock(pipelineCacheMutex_);
        cache = pipelineCache_;
    }
    if (cache == VK_NULL_HANDLE || pipelineCacheFile_.empty()) { return; }

    size_t size = 0;
    if (vkGetPipelineCacheData(device_, cache, &size, nullptr) != VK_SUCCESS || size == 0) { return; }
    std::vector<char> data(size);
    if (vkGetPipelineCacheData(device_, cache, &size, data.data()) != VK_SUCCESS) { return; }
    data.resize(size);

    // Written beside the file and moved over it, so a crash mid-write leaves the old cache.
    std::error_code ec;
    std::filesystem::create_directories(pipelineCacheFile_.parent_path(), ec);
    std::filesystem::path temp = pipelineCacheFile_;
    temp += ".tmp";
    {
        std::ofstream out(temp, std::ios::binary | std::ios::trunc);
        if (!out) { return; }
        out.write(data.data(), static_cast<std::streamsize>(data.size()));
        if (!out) { return; }
    }
    std::filesystem::rename(temp, pipelineCacheFile_, ec);
    if (ec) { std::filesystem::remove(temp, ec); }
}

vk::Device::~Device() {
    // The device itself is owned by Minecraft's backend; the cache is ours.
    savePipelineCache();
    if (pipelineCache_ != VK_NULL_HANDLE) {
        vkDestroyPipelineCache(device_, pipelineCache_, nullptr);
        pipelineCache_ = VK_NULL_HANDLE;
    }
}

VkDevice &vk::Device::vkDevice() {
    return device_;
}

VkQueue &vk::Device::mainVkQueue() {
    return mainQueue_;
}

VkQueue &vk::Device::secondaryQueue() {
    return secondaryQueue_;
}

bool vk::Device::hasExtendedDynamicState2LogicOp() const {
    return g_created.device == device_ && g_created.extendedDynamicState2LogicOp;
}

bool vk::Device::isDlssDeviceExtensionsCompatible() const {
    return g_created.device == device_ && g_created.dlssCompatible;
}

bool vk::Device::isXessDeviceExtensionsCompatible() const {
    return g_created.device == device_ && g_created.xessCompatible;
}

bool vk::Device::isDlssFrameGenerationDeviceExtensionsCompatible() const {
    return g_created.device == device_ && g_created.dlssgCompatible;
}

bool vk::Device::isShaderExecutionReorderingEnabled() const {
    return g_created.device == device_ && g_created.serEnabled;
}

bool vk::Device::isFsrFrameGenerationDeviceExtensionsCompatible() const {
    return g_created.device == device_ && g_created.fsrgCompatible;
}
