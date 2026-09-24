#include <algorithm>
#include <chrono>
#include <cstdlib>
#include <iostream>
#include "core/render/modules/world/ray_tracing/submodules/world_prepare.hpp"

#include "core/render/buffers.hpp"
#include "core/render/chunks.hpp"
#include "core/render/entities.hpp"
#include "core/render/modules/world/ray_tracing/ray_tracing_module.hpp"
#include "core/render/render_framework.hpp"
#include "core/render/renderer.hpp"
#include "core/render/world.hpp"

#include <filesystem>
#include <glm/gtc/type_ptr.hpp>

WorldPrepare::WorldPrepare() {}

void WorldPrepare::init(std::shared_ptr<Framework> framework, std::shared_ptr<RayTracingModule> rayTracingModule) {
    framework_ = framework;
    rayTracingModule_ = rayTracingModule;
}

void WorldPrepare::build() {
    auto framework = framework_.lock();
    auto rayTracingModule = rayTracingModule_.lock();
    uint32_t size = framework->swapchain()->imageCount();

    contexts_.resize(size);

    for (int i = 0; i < size; i++) {
        contexts_[i] = WorldPrepareContext::create(framework->contexts()[i], shared_from_this());
    }
}

WorldPrepareContext::WorldPrepareContext(std::shared_ptr<FrameworkContext> frameworkContext,
                                         std::shared_ptr<WorldPrepare> worldPrepare)
    : frameworkContext(frameworkContext), worldPrepare(worldPrepare) {}

void WorldPrepareContext::uploadBuffer(std::vector<uint32_t> &blasOffsets,
                                       std::vector<uint64_t> &indexBufferAddrs,
                                       std::vector<uint64_t> &positionBufferAddrs,
                                       std::vector<uint64_t> &materialBufferAddrs,
                                       std::vector<uint64_t> &lastIndexBufferAddrs,
                                       std::vector<uint64_t> &lastPositionBufferAddrs,
                                       std::vector<glm::mat4> &lastObjToWorldMats) {
    auto context = frameworkContext.lock();
    auto framework = context->framework.lock();
    auto vma = framework->vma();
    auto device = framework->device();
    auto physicalDevice = framework->physicalDevice();
    auto mainQueueIndex = physicalDevice->mainQueueIndex();
    auto cmdBuffer = context->worldCommandBuffer;

    // These seven buffers are rewritten every frame but only change size when chunks load or unload. Creating
    // them (and a staging buffer for each) from scratch every frame cost several milliseconds at far render
    // distances, so each frame context keeps its own and only grows them, with persistent staging.
    constexpr VkBufferUsageFlags kUsage = VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT |
                                          VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR |
                                          VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    auto upload = [&](std::shared_ptr<vk::DeviceLocalBuffer> &buffer, const void *data, size_t bytes) -> size_t {
        size_t needed = std::max<size_t>(bytes, 16);
        if (buffer == nullptr || buffer->size() < needed) {
            buffer = vk::DeviceLocalBuffer::create(vma, device, true, needed + needed / 2, kUsage);
        }
        if (bytes > 0) { buffer->uploadToStagingBuffer(const_cast<void *>(data), bytes, 0); }
        return bytes;
    };
    size_t uploadBytes[7] = {
        upload(blasOffsetsBuffer, blasOffsets.data(), blasOffsets.size() * sizeof(uint32_t)),
        upload(indexBufferAddr, indexBufferAddrs.data(), indexBufferAddrs.size() * sizeof(uint64_t)),
        upload(positionBufferAddr, positionBufferAddrs.data(), positionBufferAddrs.size() * sizeof(uint64_t)),
        upload(materialBufferAddr, materialBufferAddrs.data(), materialBufferAddrs.size() * sizeof(uint64_t)),
        upload(lastIndexBufferAddr, lastIndexBufferAddrs.data(), lastIndexBufferAddrs.size() * sizeof(uint64_t)),
        upload(lastPositionBufferAddr, lastPositionBufferAddrs.data(),
               lastPositionBufferAddrs.size() * sizeof(uint64_t)),
        upload(lastObjToWorldMat, lastObjToWorldMats.data(), lastObjToWorldMats.size() * sizeof(glm::mat4)),
    };

    std::vector<std::shared_ptr<vk::DeviceLocalBuffer>> rayTracingMetaData{{
        blasOffsetsBuffer,
        indexBufferAddr,
        positionBufferAddr,
        materialBufferAddr,
        lastIndexBufferAddr,
        lastPositionBufferAddr,
        lastObjToWorldMat,
    }};

    std::vector<vk::CommandBuffer::BufferMemoryBarrier> uploadPreBufferBarriers, uploadPostBufferBarriers;

    for (auto buffer : rayTracingMetaData) {
        if (buffer == nullptr) continue;
        uploadPreBufferBarriers.push_back({
            .srcStageMask = VK_PIPELINE_STAGE_2_TRANSFER_BIT,
            .srcAccessMask = VK_ACCESS_2_MEMORY_READ_BIT,
            .dstStageMask = VK_PIPELINE_STAGE_2_TRANSFER_BIT,
            .dstAccessMask = VK_ACCESS_2_MEMORY_WRITE_BIT,
            .srcQueueFamilyIndex = mainQueueIndex,
            .dstQueueFamilyIndex = mainQueueIndex,
            .buffer = buffer,
        });
        uploadPostBufferBarriers.push_back({
            .srcStageMask = VK_PIPELINE_STAGE_2_TRANSFER_BIT,
            .srcAccessMask = VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT,
            .dstStageMask = VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT |
                            VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR |
                            VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
            .dstAccessMask = VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT,
            .srcQueueFamilyIndex = mainQueueIndex,
            .dstQueueFamilyIndex = mainQueueIndex,
            .buffer = buffer,
        });
    }

    cmdBuffer->barriersBufferImage(uploadPreBufferBarriers, {});
    for (size_t i = 0; i < rayTracingMetaData.size(); i++) {
        if (rayTracingMetaData[i] == nullptr || uploadBytes[i] == 0) continue;
        rayTracingMetaData[i]->uploadToBuffer(cmdBuffer, uploadBytes[i], 0, 0);
    }
    cmdBuffer->barriersBufferImage(uploadPostBufferBarriers, {});
}

namespace {
struct DevPrepareProfile {
    bool enabled = std::getenv("RADIANTE_DEV_PROFILE") != nullptr;
    double schedule = 0, entities = 0, chunks = 0, tlas = 0, upload = 0, sbt = 0;
    int frames = 0, instances = 0, rebuilds = 0;
};
DevPrepareProfile g_prep;
double prepMs(std::chrono::steady_clock::time_point &last) {
    auto now = std::chrono::steady_clock::now();
    double ms = std::chrono::duration<double, std::milli>(now - last).count();
    last = now;
    return ms;
}
} // namespace

const std::string WorldPrepareContext::kShadowGroup = "shadow";
const std::string WorldPrepareContext::kDefaultGroup = "default";

void WorldPrepareContext::render() {
    auto prepT = std::chrono::steady_clock::now();
    auto rayTracingContext = rayTracingModuleContext.lock();
    auto rayTracingModule = rayTracingContext != nullptr ? rayTracingContext->rayTracingModule.lock() : nullptr;
    auto worldPrepare1 = worldPrepare.lock();
    if (rayTracingModule == nullptr) { return; }
    if (worldPrepare1 == nullptr) { return; }

    std::shared_ptr<Framework> framework = Renderer::instance().framework();
    std::shared_ptr<FrameworkContext> context = frameworkContext.lock();
    std::shared_ptr<vk::VMA> vma = framework->vma();
    std::shared_ptr<vk::Device> device = framework->device();
    std::shared_ptr<vk::PhysicalDevice> physicalDevice = framework->physicalDevice();
    std::shared_ptr<vk::CommandBuffer> worldCommandBuffer = context->worldCommandBuffer;

    auto chunks = Renderer::instance().world()->chunks();
    auto entities = Renderer::instance().world()->entities();
    auto cameraPos = Renderer::instance().world()->getCameraPos();

    auto chunkBuildScheduler = chunks->chunkBuildScheduler();
    if (chunkBuildScheduler != nullptr) {
        chunkBuildScheduler->tryCheckBatchesFinish();
        chunkBuildScheduler->tryScheduleBatches(chunkBuildScheduler->chunkBuildingBatchSize());
    }

    std::unique_lock<std::recursive_mutex> lock(chunks->mutex());
    if (g_prep.enabled) g_prep.schedule += prepMs(prepT);

    // Freshly built chunks trade their acceleration structures for compacted copies, typically half the size.
    // At far render distances these structures are gigabytes; left uncompacted they pushed the renderer past the
    // card's memory, and once video memory spills the frame rate collapses.
    chunks->compactFinishedBlases(worldCommandBuffer);

    if (chunks->importantBLASBuilders().size() > 0) {
        vk::BLASBuilder::batchSubmit(chunks->importantBLASBuilders(), worldCommandBuffer);
    }

    if (entities->blasBatchBuilder() != nullptr) { entities->blasBatchBuilder()->submit(worldCommandBuffer); }
    for (auto &builder : entities->staticBlasBatchBuilders()) { builder->submit(worldCommandBuffer); }

    worldCommandBuffer->barriersMemory({vk::CommandBuffer::MemoryBarrier{
        .srcStageMask = VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
        .srcAccessMask =
            VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR | VK_ACCESS_2_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
        .dstStageMask = VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
        .dstAccessMask = VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR,
    }});

    uint32_t blasAccu = 0, blasGroupAccu = 0;
    std::vector<uint32_t> blasOffset;
    hitGroupNames.clear();
    std::vector<uint64_t> indexBufferAddrs;
    std::vector<uint64_t> positionBufferAddrs, materialBufferAddrs;
    std::vector<uint64_t> lastIndexBufferAddrs;
    std::vector<uint64_t> lastPositionBufferAddrs;
    std::vector<glm::mat4> lastObjToWorldMats;

    {
        size_t expected = chunks->chunks().size() / 2 + 1024;
        hitGroupNames.reserve(expected * 4);
        blasOffset.reserve(expected);
        indexBufferAddrs.reserve(expected * 3);
        positionBufferAddrs.reserve(expected * 3);
        materialBufferAddrs.reserve(expected * 3);
        lastIndexBufferAddrs.reserve(expected * 3);
        lastPositionBufferAddrs.reserve(expected * 3);
        lastObjToWorldMats.reserve(expected);
    }

    tlasBuilder = vk::TLASBuilder::create();
    auto &instanceBuilder = tlasBuilder->beginInstanceBuilder();
    int blasIndex = 0;

    // Chunks come first and are cached. Their buffers, names and table offsets only change when a chunk is built,
    // invalidated or the grid is reset, so all of that is gathered once per change instead of every frame; only the
    // camera-relative transforms are redone each frame. Walking every chunk each frame was the largest cost of a far
    // render distance. Entities follow, so their offsets start where the chunks end.
    {
        auto &cache = worldPrepare1->chunkCache_;
        auto &chunk1s = chunks->chunks();
        uint64_t version = Chunks::contentVersion();
        if (cache.version != version || cache.slotCount != chunk1s.size()) {
            cache.entries.clear();
            cache.hitGroupNames.clear();
            cache.blasOffsets.clear();
            cache.indexBufferAddrs.clear();
            cache.positionBufferAddrs.clear();
            cache.materialBufferAddrs.clear();
            uint32_t accu = 0, groupAccu = 0;
            const auto &occupied = chunks->occupied();
            const bool useOccupied = occupied.size() == chunk1s.size();
            for (size_t i = 0; i < chunk1s.size(); i++) {
                if (useOccupied && occupied[i] == 0) continue;
                auto &chunk1 = chunk1s[i];
                if (chunk1->blas == nullptr) continue;

                cache.entries.push_back({chunk1->blas, chunk1->x, chunk1->y, chunk1->z, groupAccu});
                cache.hitGroupNames.push_back(&kShadowGroup);
                for (uint32_t j = 0; j < chunk1->geometryCount; j++) {
                    cache.hitGroupNames.push_back(chunk1->geometryGroupNames != nullptr &&
                                                          j < chunk1->geometryGroupNames->size() ?
                                                      &(*chunk1->geometryGroupNames)[j] :
                                                      &kDefaultGroup);
                    cache.indexBufferAddrs.push_back((*chunk1->indexBufferAddresses)[j]);
                    cache.positionBufferAddrs.push_back((*chunk1->positionBufferAddresses)[j]);
                    cache.materialBufferAddrs.push_back((*chunk1->materialBufferAddresses)[j]);
                }
                cache.blasOffsets.push_back(accu);
                accu += chunk1->geometryCount;
                groupAccu += chunk1->geometryCount + 1;
            }
            cache.blasAccu = accu;
            cache.groupAccu = groupAccu;
            cache.version = version;
            cache.slotCount = chunk1s.size();
            cache.namesVersion++;
            g_prep.rebuilds++;
        }

        hitGroupNames.assign(cache.hitGroupNames.begin(), cache.hitGroupNames.end());
        blasOffset.assign(cache.blasOffsets.begin(), cache.blasOffsets.end());
        indexBufferAddrs.assign(cache.indexBufferAddrs.begin(), cache.indexBufferAddrs.end());
        positionBufferAddrs.assign(cache.positionBufferAddrs.begin(), cache.positionBufferAddrs.end());
        materialBufferAddrs.assign(cache.materialBufferAddrs.begin(), cache.materialBufferAddrs.end());
        lastIndexBufferAddrs.assign(cache.indexBufferAddrs.size(), 0);
        lastPositionBufferAddrs.assign(cache.indexBufferAddrs.size(), 0);

        for (const auto &entry : cache.entries) {
            float tx = static_cast<float>(static_cast<double>(entry.x) - cameraPos.x);
            float ty = static_cast<float>(static_cast<double>(entry.y) - cameraPos.y);
            float tz = static_cast<float>(static_cast<double>(entry.z) - cameraPos.z);
            VkTransformMatrixKHR transform = {
                1, 0, 0, tx, //
                0, 1, 0, ty, //
                0, 0, 1, tz, //
            };
            instanceBuilder.defineInstance(transform, blasIndex, 0x01, entry.groupOffset, 0, entry.blas);
            glm::mat4 objToWorld(1.0f);
            objToWorld[3] = glm::vec4(tx, ty, tz, 1.0f);
            lastObjToWorldMats.push_back(objToWorld);
            blasIndex++;
        }
        blasAccu = cache.blasAccu;
        blasGroupAccu = cache.groupAccu;
        worldPrepare1->chunkNameCount_ = cache.hitGroupNames.size();
    }
    if (g_prep.enabled) g_prep.chunks += prepMs(prepT);

    // Entity
    {
        auto entityBatch = entities->entityBatch();

        if (entityBatch != nullptr) {
            std::unique_lock<std::recursive_mutex> entityHistoryLock(worldPrepare1->entityRenderDataBatchesMtx_);
            auto &previousEntityRenderDataBatches = worldPrepare1->previousEntityRenderDataBatches_;
            auto &emptyEntityRenderDataBatch = worldPrepare1->emptyEntityRenderDataBatch_;

            auto &previousEntityRenderDataBatch = previousEntityRenderDataBatches.empty() ?
                                                      emptyEntityRenderDataBatch :
                                                      previousEntityRenderDataBatches.back();
            if (previousEntityRenderDataBatches.size() > Renderer::instance().framework()->swapchain()->imageCount())
                previousEntityRenderDataBatches.pop();
            auto &currentEntityRenderDataBatch = previousEntityRenderDataBatches.emplace();

            auto worldUniformBuffer = Renderer::instance().buffers()->worldUniformBuffer();
            auto ubo = static_cast<vk::Data::WorldUBO *>(worldUniformBuffer->mappedPtr());

            auto &entities1 = entityBatch->entities;
            for (int i = 0; i < entities1.size(); i++) {
                VkGeometryInstanceFlagsKHR flags = VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR;
                // VkGeometryInstanceFlagsKHR flags = 0;
                VkTransformMatrixKHR transform;

                if (entities1[i]->prebuiltBLAS < 0) {
                    if (entities1[i]->coordinate == World::Coordinates::WORLD || !ubo) {
                        transform = {
                            1, 0, 0, static_cast<float>(entities1[i]->x - cameraPos.x), //
                            0, 1, 0, static_cast<float>(entities1[i]->y - cameraPos.y), //
                            0, 0, 1, static_cast<float>(entities1[i]->z - cameraPos.z), //
                        };
                    } else if (entities1[i]->coordinate == World::Coordinates::CAMERA) {
                        glm::mat4 viewMat = glm::transpose(ubo->cameraViewMatInv); // column major to row major

                        transform = {
                            viewMat[0][0], viewMat[0][1], viewMat[0][2], viewMat[0][3], //
                            viewMat[1][0], viewMat[1][1], viewMat[1][2], viewMat[1][3], //
                            viewMat[2][0], viewMat[2][1], viewMat[2][2], viewMat[2][3], //
                        };
                    } else if (entities1[i]->coordinate == World::Coordinates::CAMERA_SHIFT) {
                        glm::vec3 shift = glm::vec3(ubo->cameraViewMatInv[3]);
                        transform = {
                            1, 0, 0, shift.x, //
                            0, 1, 0, shift.y, //
                            0, 0, 1, shift.z, //
                        };
                    }

                    instanceBuilder.defineInstance(transform, blasIndex, entities1[i]->rayTracingFlag, blasGroupAccu,
                                                   flags, entities1[i]->blas);
                } else {
                    // auto &prebuiltBLAS =
                    //     Renderer::instance().framework()->prebuiltBLASs()[entityRenderData->prebuiltBLAS];
                    // transform = prebuiltBLAS.align(*entityRenderData->vertices, *entityRenderData->indices);

                    // instanceBuilder.defineInstance(transform, blasIndex, entityRenderData->rayTracingFlag,
                    // blasGroupAccu, flags,
                    //                                prebuiltBLAS.blas);
                    throw std::runtime_error("prebuilt blas not implemented yet!");
                }

                hitGroupNames.push_back(&kShadowGroup);
                for (int j = 0; j < entities1[i]->geometryCount; j++) {
                    hitGroupNames.push_back(entities1[i]->geometryGroupNames != nullptr &&
                                                    j < static_cast<int>(entities1[i]->geometryGroupNames->size()) ?
                                                &(*entities1[i]->geometryGroupNames)[j] :
                                                &kDefaultGroup);
                }

                for (int j = 0; j < entities1[i]->geometryCount; j++) {
                    indexBufferAddrs.push_back((*entities1[i]->indexBufferAddresses)[j]);
                    positionBufferAddrs.push_back((*entities1[i]->positionBufferAddresses)[j]);
                    materialBufferAddrs.push_back((*entities1[i]->materialBufferAddresses)[j]);
                }

                {
                    if (entities1[i]->hashCode) {
                        currentEntityRenderDataBatch[entities1[i]->hashCode].first = entities1[i];
                        currentEntityRenderDataBatch[entities1[i]->hashCode].second = transform;
                    }
                }

                {
                    glm::mat4 lastObjToWorldMat(1);
                    auto iter = previousEntityRenderDataBatch.find(entities1[i]->hashCode);
                    if (iter != previousEntityRenderDataBatch.end()) {
                        auto &previousEntityRenderData = (*iter).second.first;
                        if (previousEntityRenderData->geometryCount == entities1[i]->geometryCount) {
                            for (int j = 0; j < entities1[i]->geometryCount; j++) {
                                if ((*previousEntityRenderData->vertexCounts)[j] == (*entities1[i]->vertexCounts)[j] &&
                                    (*previousEntityRenderData->indexCounts)[j] == (*entities1[i]->indexCounts)[j]) {
                                    lastIndexBufferAddrs.push_back(
                                        (*previousEntityRenderData->indexBufferAddresses)[j]);
                                    lastPositionBufferAddrs.push_back(
                                        (*previousEntityRenderData->positionBufferAddresses)[j]);
                                } else {
                                    lastIndexBufferAddrs.push_back(0);
                                    lastPositionBufferAddrs.push_back(0);
                                }
                            }
                        } else {
                            for (int j = 0; j < entities1[i]->geometryCount; j++) {
                                lastIndexBufferAddrs.push_back(0);
                                lastPositionBufferAddrs.push_back(0);
                            }
                        }

                        VkTransformMatrixKHR lastObjToWorldVkMat = iter->second.second;
                        lastObjToWorldMat = glm::transpose(glm::mat4(glm::make_vec4(lastObjToWorldVkMat.matrix[0]), //
                                                                     glm::make_vec4(lastObjToWorldVkMat.matrix[1]), //
                                                                     glm::make_vec4(lastObjToWorldVkMat.matrix[2]), //
                                                                     glm::vec4(0.0f, 0.0f, 0.0f, 1.0f)));
                    } else {
                        for (int j = 0; j < entities1[i]->geometryCount; j++) {
                            lastIndexBufferAddrs.push_back(0);
                            lastPositionBufferAddrs.push_back(0);
                        }
                    }
                    lastObjToWorldMats.push_back(lastObjToWorldMat);
                }

                blasOffset.push_back(blasAccu);
                blasAccu += entities1[i]->geometryCount;
                blasGroupAccu += entities1[i]->geometryCount + 1;

                blasIndex++;
            }
        }
    }

    if (g_prep.enabled) {
        g_prep.entities += prepMs(prepT);
        g_prep.instances += static_cast<int>(instanceBuilder.instances.size());
    }
    if (instanceBuilder.instances.empty()) {
        tlas = nullptr;
        return;
    }

    tlas = instanceBuilder.endInstanceBuilder(device, vma)
               ->defineBuildProperty(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
               ->querySizeInfo(device)
               ->allocateBuffers(physicalDevice, device, vma)
               ->buildAndSubmit(device, worldCommandBuffer);

    worldCommandBuffer->barriersMemory({vk::CommandBuffer::MemoryBarrier{
        .srcStageMask = VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
        .srcAccessMask = VK_ACCESS_2_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
        .dstStageMask = VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
        .dstAccessMask = VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR,
    }});

    if (g_prep.enabled) g_prep.tlas += prepMs(prepT);
    uploadBuffer(blasOffset, indexBufferAddrs, positionBufferAddrs, materialBufferAddrs, lastIndexBufferAddrs,
                 lastPositionBufferAddrs, lastObjToWorldMats);
    if (g_prep.enabled) {
        g_prep.upload += prepMs(prepT);
        if (++g_prep.frames == 300) {
            std::cout << "[prepare profile] schedule=" << g_prep.schedule / 300 << "ms entities="
                      << g_prep.entities / 300 << "ms chunks=" << g_prep.chunks / 300 << "ms tlas="
                      << g_prep.tlas / 300 << "ms upload=" << g_prep.upload / 300 << "ms sbt=" << g_prep.sbt / 300
                      << "ms instances=" << g_prep.instances / 300 << " rebuilds=" << g_prep.rebuilds << " MB pos/mat/idx/blas=" << Chunks::devBytes[0] / 1048576 << "/"
                      << Chunks::devBytes[1] / 1048576 << "/" << Chunks::devBytes[2] / 1048576 << "/"
                      << Chunks::devBytes[3] / 1048576 << " tris=" << Chunks::devBytes[4] << std::endl;
            g_prep = DevPrepareProfile{};
        }
    }
}

void WorldPrepareContext::setupHitGroupSbt(const std::unordered_map<std::string, uint32_t> &hitGroupNameToIndex,
                                           uint32_t fallbackHitGroupIndex,
                                           uint32_t shadowHitGroupIndex,
                                           std::shared_ptr<vk::CommandBuffer> commandBuffer,
                                           std::shared_ptr<vk::SBT> updateSbt,
                                           std::shared_ptr<vk::SBT> querySbt) {
    auto sbtT = std::chrono::steady_clock::now();
    std::vector<uint32_t> hitGroupIndices;
    hitGroupIndices.reserve(hitGroupNames.size());

    // The chunks' part of the table is resolved once per chunk change and reused; only the entities' names are
    // looked up every frame. A handful of distinct names repeat across all geometries, so lookups go through a tiny
    // list of names already resolved before falling back to hashing into the map.
    auto prepare = worldPrepare.lock();
    size_t start = 0;
    if (prepare != nullptr) {
        auto &cache = prepare->chunkCache_;
        bool sameTable = cache.resolvedMap == &hitGroupNameToIndex && cache.resolvedMapSize == hitGroupNameToIndex.size() &&
                         cache.resolvedFallback == fallbackHitGroupIndex && cache.resolvedShadow == shadowHitGroupIndex;
        size_t chunkNames = std::min(prepare->chunkNameCount_, hitGroupNames.size());
        if (sameTable && cache.resolvedVersion == cache.namesVersion && cache.resolvedIndices.size() == chunkNames) {
            hitGroupIndices.assign(cache.resolvedIndices.begin(), cache.resolvedIndices.end());
            start = chunkNames;
        }
    }

    std::vector<std::pair<const std::string *, uint32_t>> resolved;
    for (size_t k = start; k < hitGroupNames.size(); k++) {
        const std::string *groupName = hitGroupNames[k];
        if (groupName == &kShadowGroup) {
            hitGroupIndices.push_back(shadowHitGroupIndex);
            continue;
        }

        uint32_t index = fallbackHitGroupIndex;
        bool found = false;
        for (const auto &[name, cachedIndex] : resolved) {
            if (name == groupName || *name == *groupName) {
                index = cachedIndex;
                found = true;
                break;
            }
        }
        if (!found) {
            auto iter = hitGroupNameToIndex.find(*groupName);
            index = iter == hitGroupNameToIndex.end() ? fallbackHitGroupIndex : iter->second;
            resolved.emplace_back(groupName, index);
        }
        hitGroupIndices.push_back(index);
    }

    if (prepare != nullptr && start == 0) {
        auto &cache = prepare->chunkCache_;
        size_t chunkNames = std::min(prepare->chunkNameCount_, hitGroupIndices.size());
        cache.resolvedIndices.assign(hitGroupIndices.begin(), hitGroupIndices.begin() + chunkNames);
        cache.resolvedVersion = cache.namesVersion;
        cache.resolvedMap = &hitGroupNameToIndex;
        cache.resolvedMapSize = hitGroupNameToIndex.size();
        cache.resolvedFallback = fallbackHitGroupIndex;
        cache.resolvedShadow = shadowHitGroupIndex;
    }

    if (updateSbt != nullptr) { updateSbt->setupHitSBT(hitGroupIndices, commandBuffer); }
    if (querySbt != nullptr) { querySbt->setupHitSBT(hitGroupIndices, commandBuffer); }
    if (g_prep.enabled) g_prep.sbt += prepMs(sbtT);
}
