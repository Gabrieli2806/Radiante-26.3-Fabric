#pragma once
#include <atomic>
#include <functional>

#include "common/shared.hpp"
#include "common/singleton.hpp"
#include "core/all_extern.hpp"
#include "core/vulkan/all_core_vulkan.hpp"

#include "core/render/emission.hpp"
#include "core/render/world.hpp"

#include <chrono>
#include <condition_variable>
#include <deque>
#include <list>
#include <map>
#include <mutex>
#include <queue>
#include <set>
#include <string>
#include <unordered_map>
#include <vector>

class Framework;
class FrameResourceRetainer;

struct ChunkPackedData {
    int32_t x;
    int32_t y;
    int32_t z;
    uint32_t geometryCount;
    uint32_t lightCount = 0;
    VkDeviceAddress lightBufferAddress = 0;
};
static_assert(sizeof(ChunkPackedData) == 32);

struct ChunkBuildTask {
    int x = 0, y = 0, z = 0;
    int64_t id;
    int geometryCount;
    int *geometryTypes;
    const char **geometryGroupNames;
    int *geometryTextures;
    int *vertexFormats;
    int *vertexCounts;
    vk::VertexFormat::PBRVertex **vertices;
    bool isImportant;
};

struct ChunkBuildData : public SharedObject<ChunkBuildData> {
    int64_t id;
    int x, y, z;
    int64_t version;
    bool collectChunkEmission = false;
    uint32_t allVertexCount;
    uint32_t allIndexCount;
    uint32_t geometryCount;
    std::vector<World::GeometryTypes> geometryTypes;
    std::vector<std::string> geometryGroupNames;
    std::vector<std::vector<vk::VertexFormat::PBRVertex>> vertices;
    std::vector<std::vector<uint32_t>> indices;
    std::vector<VkDeviceAddress> indexBufferAddresses;
    std::vector<VkDeviceAddress> positionBufferAddresses;
    std::vector<VkDeviceAddress> materialBufferAddresses;
    std::shared_ptr<vk::DeviceLocalBuffer> indexBuffer;
    std::shared_ptr<vk::DeviceLocalBuffer> positionBuffer;
    std::shared_ptr<vk::DeviceLocalBuffer> materialBuffer;
    std::shared_ptr<vk::BLAS> blas;
    std::shared_ptr<vk::BLASBuilder> blasBuilder;
    std::vector<LightInfo> lightInfos;
    std::shared_ptr<vk::DeviceLocalBuffer> lightBuffer;
    uint32_t lightCount = 0;

    ChunkBuildData(int64_t id,
                   int x,
                   int y,
                   int z,
                   int64_t version,
                   bool collectChunkEmission,
                   uint32_t allVertexCount,
                   uint32_t allIndexCount,
                   uint32_t geometryCount,
                   std::vector<World::GeometryTypes> &&geometryTypes,
                   std::vector<std::string> &&geometryGroupNames,
                   std::vector<std::vector<vk::VertexFormat::PBRVertex>> &&vertices,
                   std::vector<std::vector<uint32_t>> &&indices);

    void buildLightInfos(const Emission &emission);
    void buildLightBuffer(const std::shared_ptr<vk::VMA> &vma,
                          const std::shared_ptr<vk::Device> &device,
                          bool persistStaging = true);
    void build(bool persistStaging = true);
    // Packs this chunk's geometry into one buffer in the compact chunk layout and defines its triangles on
    // `builder`. See the notes above packChunkGeometry in chunks.cpp.
    void packGeometry(const std::shared_ptr<vk::VMA> &vma,
                      const std::shared_ptr<vk::Device> &device,
                      bool persistStaging,
                      const std::shared_ptr<vk::BLASBuilder> &builder);
};

struct Chunk1;

// A chunk acceleration structure that has finished building, with the size it will have once compacted.
struct ChunkCompaction {
    int64_t chunkId;
    std::shared_ptr<vk::BLAS> source;
    VkDeviceSize compactedSize;
};

struct ChunkBuildDataBatch : public SharedObject<ChunkBuildDataBatch> {
    std::vector<std::shared_ptr<ChunkBuildData>> batchData;
    std::shared_ptr<vk::DeviceLocalBuffer> indexBuffer;
    std::shared_ptr<vk::DeviceLocalBuffer> positionBuffer;
    std::shared_ptr<vk::DeviceLocalBuffer> materialBuffer;
    std::shared_ptr<vk::BLASBatchBuilder> blasBatchBuilder;
    // The acceleration structures this batch built, in build order, and a query pool the build writes their
    // compacted sizes into, so they can be compacted once the build has finished.
    std::vector<std::shared_ptr<vk::BLAS>> builtBlases;
    VkQueryPool compactionQueryPool = VK_NULL_HANDLE;

    ChunkBuildDataBatch(std::vector<std::shared_ptr<ChunkBuildData>> &&batchData);
    ChunkBuildDataBatch(uint32_t maxBatchSize,
                        std::set<int64_t> &queuedIndex,
                        std::vector<std::shared_ptr<Chunk1>> &chunks,
                        std::vector<std::shared_ptr<ChunkBuildData>> &chunkBuildDatas,
                        glm::vec3 cameraPos);
    void build();
};

class ChunkBuildScheduler : public SharedObject<ChunkBuildScheduler> {
  public:
    ChunkBuildScheduler(std::set<int64_t> &queuedIndex,
                        std::vector<std::shared_ptr<Chunk1>> &chunks,
                        std::vector<std::shared_ptr<ChunkBuildData>> &chunkBuildDatas,
                        std::recursive_mutex &mutex,
                        std::vector<ChunkPackedData> &chunkPackedData,
                        uint32_t chunkBuildingBatchSize,
                        uint32_t chunkBuildingTotalBatches);

    void tryCheckBatchesFinish();
    void waitAllBatchesFinish();
    // Where finished batches report the compacted sizes of the structures they built.
    void setCompactionSink(std::function<void(std::vector<ChunkCompaction> &&)> sink) { compactionSink_ = std::move(sink); }
    void tryScheduleBatches(uint32_t maxBatchSize);

    uint32_t chunkBuildingBatchSize();
    uint32_t chunkBuildingTotalBatches();

  private:
    std::set<int64_t> &queuedIndex_;
    std::vector<std::shared_ptr<Chunk1>> &chunks_;
    std::vector<std::shared_ptr<ChunkBuildData>> &chunkBuildDatas_;
    std::recursive_mutex &mutex_;
    std::vector<ChunkPackedData> &chunkPackedData_;

    void collectCompactions(const std::shared_ptr<ChunkBuildDataBatch> &batch);
    std::function<void(std::vector<ChunkCompaction> &&)> compactionSink_;

    std::queue<std::shared_ptr<vk::Fence>> freeFences_;
    std::queue<std::shared_ptr<vk::CommandBuffer>> freeCommandBuffers_;
    std::list<std::shared_ptr<vk::Fence>> buildingFences_;
    std::list<std::shared_ptr<vk::CommandBuffer>> buildingCommandBuffers_;
    std::list<std::shared_ptr<ChunkBuildDataBatch>> buildingBatches_;
    std::vector<std::shared_ptr<ChunkBuildData>> pendingBatchData_;
    uint32_t pendingBatchFrames_ = 0;
    bool useSecondaryQueue_ = false;
    static constexpr uint32_t maxPendingBatchFrames_ = 3;

    uint32_t chunkBuildingBatchSize_;
    uint32_t chunkBuildingTotalBatches_;
};

struct ChunkRenderData : public SharedObject<ChunkRenderData> {
    int x, y, z;
    std::shared_ptr<vk::BLAS> blas;
    std::shared_ptr<std::vector<VkDeviceAddress>> indexBufferAddresses;
    std::shared_ptr<std::vector<VkDeviceAddress>> positionBufferAddresses;
    std::shared_ptr<std::vector<VkDeviceAddress>> materialBufferAddresses;
    std::shared_ptr<vk::DeviceLocalBuffer> indexBuffer;
    std::shared_ptr<vk::DeviceLocalBuffer> positionBuffer;
    std::shared_ptr<vk::DeviceLocalBuffer> materialBuffer;
    std::shared_ptr<std::vector<LightInfo>> lightInfos;
    std::shared_ptr<vk::DeviceLocalBuffer> lightBuffer;
    uint32_t lightCount = 0;
    uint32_t geometryCount;
    std::shared_ptr<std::vector<std::string>> geometryGroupNames;
};

struct Chunk1 : public SharedObject<Chunk1> {
    constexpr static float T_HALF = 200; // ms
    constexpr static float T_WEIGHT = 1.0;

    constexpr static float D_HALF = 128; // blocks
    constexpr static float D_SENSITIVITY = 1.35;
    constexpr static float D_PRIORITY_FLOOR = 0.4;
    constexpr static float D_WEIGHT = 1.1;

    int x, y, z;
    int64_t latestVersion = 0;
    std::chrono::steady_clock::time_point lastUpdate;

    std::shared_ptr<vk::BLAS> blas;
    int64_t blasVersion = -1;
    std::shared_ptr<std::vector<VkDeviceAddress>> indexBufferAddresses;
    std::shared_ptr<std::vector<VkDeviceAddress>> positionBufferAddresses;
    std::shared_ptr<std::vector<VkDeviceAddress>> materialBufferAddresses;
    std::shared_ptr<vk::DeviceLocalBuffer> indexBuffer;
    std::shared_ptr<vk::DeviceLocalBuffer> positionBuffer;
    std::shared_ptr<vk::DeviceLocalBuffer> materialBuffer;
    std::shared_ptr<std::vector<LightInfo>> lightInfos;
    std::shared_ptr<vk::DeviceLocalBuffer> lightBuffer;
    uint32_t lightCount = 0;
    uint32_t geometryCount;
    std::shared_ptr<std::vector<std::string>> geometryGroupNames;
    // This chunk's entry in Chunks::occupied(), kept equal to (blas != nullptr).
    uint8_t *occupiedFlag = nullptr;

    float buildFactor(std::chrono::steady_clock::time_point currentTime, glm::vec3 cameraPos, glm::vec3 chunkPos);

    bool enqueue(std::shared_ptr<ChunkBuildData> chunkBuildData);
    void invalidate();
    void retainResources(FrameResourceRetainer &frr);
    void releaseEmissionResources(FrameResourceRetainer &frr);
    std::shared_ptr<ChunkRenderData> tryGetValid();
};

class Chunks : public SharedObject<Chunks> {
    friend World;

  public:
    Chunks(std::shared_ptr<Framework> framework);

    void reset(uint32_t numChunks, uint32_t sizeX, uint32_t sizeY, uint32_t sizeZ, int32_t bottomSectionCoord);
    void resetScheduler();
    void resetFrame();
    void invalidateChunk(int id);
    void relocateChunk(int id, int x, int y, int z);
    void queueChunkBuild(ChunkBuildTask task);
    void setCollectChunkEmission(bool collect);

    bool isChunkReady(int64_t id);

    void close();

    std::recursive_mutex &mutex();
    std::vector<std::shared_ptr<Chunk1>> &chunks();
    // One byte per chunk slot, set while that slot has geometry. Reading this flat array lets the per-frame walk
    // skip the empty slots (most of them, far out) without touching each chunk object.
    const std::vector<uint8_t> &occupied();
    // Replaces freshly built chunk acceleration structures with compacted copies, recorded into `commandBuffer`
    // ahead of the TLAS build. Call under mutex(), after the scheduler has collected finished batches.
    void compactFinishedBlases(std::shared_ptr<vk::CommandBuffer> commandBuffer);

    using PendingCompaction = ChunkCompaction;
    void addPendingCompactions(std::vector<PendingCompaction> &&compactions);
    // Bumped whenever any chunk's geometry is replaced or dropped, so per-frame work can cache what depends on it.
    static uint64_t contentVersion();
    // Development counters: bytes ever allocated for chunk positions, materials, indices, BLAS, and triangles.
    static std::atomic<uint64_t> devBytes[5];
    static void bumpContentVersion();
    std::shared_ptr<ChunkBuildScheduler> chunkBuildScheduler();
    std::vector<std::shared_ptr<vk::BLASBuilder>> &importantBLASBuilders();
    std::shared_ptr<vk::DeviceLocalBuffer> chunkPackedData();
    glm::ivec4 chunkGridInfo();
    void setChunkStorageSectionPos(glm::ivec3 sectionPos);
    glm::ivec4 chunkStorageSectionPos();

  private:
    void allocateChunkPackedDataBuffers();
    void releaseEmissionResources();

    std::recursive_mutex mutex_;
    std::vector<std::shared_ptr<Chunk1>> chunks_;
    std::vector<uint8_t> occupied_;
    std::vector<PendingCompaction> pendingCompactions_;
    std::vector<ChunkPackedData> chunkPackedData_;
    std::vector<std::shared_ptr<vk::DeviceLocalBuffer>> chunkPackedDataBuffers_;
    std::vector<std::shared_ptr<ChunkBuildData>> chunkBuildDatas_;
    std::set<int64_t> queuedIndex_;
    std::shared_ptr<ChunkBuildScheduler> chunkBuildScheduler_;

    std::shared_ptr<std::vector<std::shared_ptr<vk::BLASBuilder>>> importantBLASBuilders_;
    int32_t sizeX_ = 0;
    int32_t sizeY_ = 0;
    int32_t sizeZ_ = 0;
    int32_t bottomSectionCoord_ = 0;
    glm::ivec3 chunkStorageSectionPos_ = glm::ivec3(0);
};
