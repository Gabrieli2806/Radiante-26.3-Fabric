#pragma once

#include "common/shared.hpp"
#include "common/singleton.hpp"
#include "core/all_extern.hpp"
#include "core/vulkan/all_core_vulkan.hpp"

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

struct EntitiesBuildTask {
    float lineWidth;
    World::Coordinates coordinate;
    bool normalOffset;
    int entityCount;
    int *entityHashCodes;
    double *entityXs;
    double *entityYs;
    double *entityZs;
    int *entityRayTracingFlags;
    int *entityPostRenderFlags;
    int *entityPrebuiltBLASs;
    int *entityPosts;
    int *entityGeometryCounts;
    int *geometryTypes;
    const char **geometryGroupNames;
    const char **geometryContentNames;
    int *geometryTextures;
    int *vertexFormats;
    int *indexFormats;
    int *vertexCounts;
    void **vertices;
};

struct EntityBuildData : public SharedObject<EntityBuildData> {
    int hashCode;
    double x, y, z;
    int rayTracingFlag;
    int postRenderFlag;
    int prebuiltBLAS;
    World::Coordinates coordinate;
    uint32_t geometryCount;
    std::vector<World::GeometryTypes> geometryTypes;
    std::vector<std::string> geometryGroupNames;
    std::vector<std::string> geometryContentNames;
    std::vector<std::vector<vk::VertexFormat::PBRVertex>> vertices;
    std::vector<std::vector<uint32_t>> indices;
    std::vector<VkDeviceAddress> indexBufferAddresses;
    std::vector<VkDeviceAddress> positionBufferAddresses;
    std::vector<VkDeviceAddress> materialBufferAddresses;
    std::shared_ptr<vk::BLAS> blas;

    EntityBuildData(int hashCode,
                    double x,
                    double y,
                    double z,
                    int rayTracingFlag,
                    int postRenderFlag,
                    int prebuiltBLAS,
                    World::Coordinates coordinate,
                    uint32_t geometryCount,
                    std::vector<World::GeometryTypes> &&geometryTypes,
                    std::vector<std::string> &&geometryGroupNames,
                    std::vector<std::string> &&geometryContentNames,
                    std::vector<std::vector<vk::VertexFormat::PBRVertex>> &&vertices,
                    std::vector<std::vector<uint32_t>> &&indices);
};

struct EntityBuildDataBatch : public SharedObject<EntityBuildDataBatch> {
    std::vector<std::shared_ptr<EntityBuildData>> datas;

    std::shared_ptr<vk::DeviceLocalBuffer> indexBuffer;
    std::shared_ptr<vk::DeviceLocalBuffer> positionBuffer;
    std::shared_ptr<vk::DeviceLocalBuffer> materialBuffer;
    std::shared_ptr<vk::BLASBatchBuilder> blasBatchBuilder;

    void addData(std::shared_ptr<EntityBuildData> data);
    void build();
};

struct EntityPostBuildDataBatch : public SharedObject<EntityPostBuildDataBatch> {
    std::vector<std::shared_ptr<EntityBuildData>> datas;

    void addData(std::shared_ptr<EntityBuildData> data);
};

struct Entity;
struct EntityBatch;
struct EntityPost;
struct EntityPostBatch;

struct Entity : public SharedObject<Entity> {
    int hashCode;
    double x, y, z;
    int rayTracingFlag;
    int prebuiltBLAS;
    World::Coordinates coordinate;

    std::shared_ptr<vk::BLAS> blas;
    std::shared_ptr<std::vector<VkDeviceAddress>> indexBufferAddresses;
    std::shared_ptr<std::vector<VkDeviceAddress>> positionBufferAddresses;
    std::shared_ptr<std::vector<VkDeviceAddress>> materialBufferAddresses;
    std::shared_ptr<vk::DeviceLocalBuffer> indexBuffer;
    std::shared_ptr<vk::DeviceLocalBuffer> positionBuffer;
    std::shared_ptr<vk::DeviceLocalBuffer> materialBuffer;

    uint32_t geometryCount;
    std::shared_ptr<std::vector<std::string>> geometryGroupNames;
    std::shared_ptr<std::vector<std::string>> geometryContentNames;
    std::shared_ptr<std::vector<uint32_t>> vertexCounts;
    std::shared_ptr<std::vector<uint32_t>> indexCounts;

    Entity(std::shared_ptr<EntityBuildData> entityBuildData);
};

struct EntityBatch : public SharedObject<EntityBatch> {
    std::vector<std::shared_ptr<Entity>> entities;

    std::shared_ptr<vk::DeviceLocalBuffer> indexBuffer;
    std::shared_ptr<vk::DeviceLocalBuffer> positionBuffer;
    std::shared_ptr<vk::DeviceLocalBuffer> materialBuffer;

    EntityBatch(std::shared_ptr<EntityBuildDataBatch> entityBuildDataBatch);
};

struct EntityPost : public SharedObject<EntityPost> {
    int postRenderFlag;
    double x, y, z;

    uint32_t geometryCount;
    std::vector<std::string> geometryContentNames;
    std::vector<uint32_t> indexCounts;

    std::vector<std::shared_ptr<vk::DeviceLocalBuffer>> vertexBuffers;
    std::vector<std::shared_ptr<vk::DeviceLocalBuffer>> indexBuffers;

    EntityPost(std::shared_ptr<EntityBuildData> entityBuildData);
};

struct EntityPostBatch : public SharedObject<EntityPostBatch> {
    std::vector<std::shared_ptr<EntityPost>> entities;

    EntityPostBatch(std::shared_ptr<EntityPostBuildDataBatch> entityPostBuildDataBatch);
};

// A block entity whose geometry has not changed keeps its own acceleration structure between frames instead of
// having it rebuilt every frame with everything else.
struct StaticEntityCacheEntry {
    uint64_t contentHash = 0;
    bool seen = false;
    uint64_t lastSeenFrame = 0;
    std::shared_ptr<Entity> entity;
};

class Entities : public SharedObject<Entities> {
    friend World;

  public:
    Entities(std::shared_ptr<Framework> framework);

    void resetFrame();
    void queueBuild(EntitiesBuildTask task);
    void build();
    void close();
    std::shared_ptr<EntityBatch> entityBatch();
    std::shared_ptr<EntityPostBatch> entityPostBatch();
    std::shared_ptr<vk::BLASBatchBuilder> blasBatchBuilder();
    // Builds for newly cached entities, submitted alongside the frame's batch.
    std::vector<std::shared_ptr<vk::BLASBatchBuilder>> &staticBlasBatchBuilders();
    // Called once the static builders are recorded into a frame; until then they are kept.
    void staticBuildersSubmitted();

    // Value the Java side puts in prebuiltBLAS for geometry that may be cached (see EntityManager).
    static constexpr int CACHEABLE_BLAS = -2;
    // Like CACHEABLE_BLAS, but the Java side names the content (in the geometry content names) and moves it by
    // position alone: a match reuses the structure without reading the vertices at all, and only moves the instance.
    // For large meshes that drift but rarely change shape - the vanilla clouds.
    static constexpr int KEYED_BLAS = -3;
    // Frames an entity may go unseen before its cached structure is dropped.
    static constexpr uint64_t STATIC_CACHE_EVICT_FRAMES = 120;

  private:
    std::unordered_map<int, StaticEntityCacheEntry> staticCache_;
    std::vector<std::shared_ptr<Entity>> reusedEntities_;
    std::vector<std::shared_ptr<EntityBuildDataBatch>> newStaticBuilds_;
    std::vector<std::shared_ptr<vk::BLASBatchBuilder>> staticBlasBatchBuilders_;
    uint64_t frameCounter_ = 0;

    std::shared_ptr<EntityBatch> entityBatch_;
    std::shared_ptr<EntityPostBatch> entityPostBatch_;
    std::shared_ptr<EntityBuildDataBatch> entityBuildDataBatch_;
    std::shared_ptr<EntityPostBuildDataBatch> entityPostBuildDataBatch_;

    std::shared_ptr<vk::BLASBatchBuilder> blasBatchBuilder_;
};
