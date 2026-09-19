#pragma once

#include "common/shared.hpp"
#include "common/singleton.hpp"
#include "core/all_extern.hpp"
#include "core/vulkan/all_core_vulkan.hpp"

#include <map>
#include <mutex>
#include <queue>
#include <unordered_map>

class Framework;
class FrameworkContext;
class RayTracingModule;
struct RayTracingModuleContext;
struct Entity;

struct WorldPrepareContext;

class WorldPrepare : public SharedObject<WorldPrepare> {
    friend RayTracingModule;
    friend RayTracingModuleContext;
    friend WorldPrepareContext;

  public:
    WorldPrepare();

    void init(std::shared_ptr<Framework> framework, std::shared_ptr<RayTracingModule> rayTracingModule);

    void build();

  private:
    using EntityRenderDataBatch = std::map<int, std::pair<std::shared_ptr<Entity>, VkTransformMatrixKHR>>;

    std::weak_ptr<Framework> framework_;
    std::weak_ptr<RayTracingModule> rayTracingModule_;

    // Everything about the chunks' instances except their camera-relative transforms, rebuilt only when
    // Chunks::contentVersion() changes. See WorldPrepareContext::render.
    struct ChunkInstance {
        std::shared_ptr<vk::BLAS> blas;
        int x, y, z;
        uint32_t groupOffset;
    };
    struct ChunkInstanceCache {
        uint64_t version = ~0ull;
        size_t slotCount = 0;
        uint64_t namesVersion = 0;
        std::vector<ChunkInstance> entries;
        std::vector<const std::string *> hitGroupNames;
        std::vector<uint32_t> blasOffsets;
        std::vector<uint64_t> indexBufferAddrs;
        std::vector<uint64_t> positionBufferAddrs;
        std::vector<uint64_t> materialBufferAddrs;
        uint32_t blasAccu = 0;
        uint32_t groupAccu = 0;
        // Hit group indices resolved for the names above, and the table they were resolved against.
        std::vector<uint32_t> resolvedIndices;
        uint64_t resolvedVersion = ~0ull;
        const void *resolvedMap = nullptr;
        size_t resolvedMapSize = 0;
        uint32_t resolvedFallback = 0;
        uint32_t resolvedShadow = 0;
    };
    ChunkInstanceCache chunkCache_;
    size_t chunkNameCount_ = 0;

    std::queue<EntityRenderDataBatch> previousEntityRenderDataBatches_;
    EntityRenderDataBatch emptyEntityRenderDataBatch_;
    std::recursive_mutex entityRenderDataBatchesMtx_;

    std::vector<std::shared_ptr<WorldPrepareContext>> contexts_;
};

struct WorldPrepareContext : public SharedObject<WorldPrepareContext> {
    std::weak_ptr<FrameworkContext> frameworkContext;
    std::weak_ptr<RayTracingModuleContext> rayTracingModuleContext;
    std::weak_ptr<WorldPrepare> worldPrepare;

    std::shared_ptr<vk::TLAS> tlas;
    std::shared_ptr<vk::TLASBuilder> tlasBuilder;

    std::shared_ptr<vk::DeviceLocalBuffer> blasOffsetsBuffer;
    std::shared_ptr<vk::DeviceLocalBuffer> indexBufferAddr;
    std::shared_ptr<vk::DeviceLocalBuffer> positionBufferAddr;
    std::shared_ptr<vk::DeviceLocalBuffer> materialBufferAddr;
    std::shared_ptr<vk::DeviceLocalBuffer> lastIndexBufferAddr;
    std::shared_ptr<vk::DeviceLocalBuffer> lastPositionBufferAddr;
    std::shared_ptr<vk::DeviceLocalBuffer> lastObjToWorldMat;
    // Points into each geometry's own name list, which the frame retainer keeps alive for the frame; copying the
    // strings cost an allocation per geometry.
    std::vector<const std::string *> hitGroupNames;
    static const std::string kShadowGroup;
    static const std::string kDefaultGroup;

    WorldPrepareContext(std::shared_ptr<FrameworkContext> frameworkContext, std::shared_ptr<WorldPrepare> worldprepare);

    void uploadBuffer(std::vector<uint32_t> &blasOffsets,
                      std::vector<uint64_t> &indexBufferAddrs,
                      std::vector<uint64_t> &positionBufferAddrs,
                      std::vector<uint64_t> &materialBufferAddrs,
                      std::vector<uint64_t> &lastIndexBufferAddrs,
                      std::vector<uint64_t> &lastPositionBufferAddrs,
                      std::vector<glm::mat4> &lastObjToWorldMats);
    void setupHitGroupSbt(const std::unordered_map<std::string, uint32_t> &hitGroupNameToIndex,
                          uint32_t fallbackHitGroupIndex,
                          uint32_t shadowHitGroupIndex,
                          std::shared_ptr<vk::CommandBuffer> commandBuffer,
                          std::shared_ptr<vk::SBT> updateSbt,
                          std::shared_ptr<vk::SBT> querySbt);
    void render();
};
