#include <atomic>
#include <glm/gtc/packing.hpp>
#include "core/render/chunks.hpp"

#include "core/render/buffers.hpp"
#include "core/render/render_framework.hpp"
#include "core/render/renderer.hpp"
#include "core/vulkan/vertex.hpp"

#include <algorithm>
#include <chrono>
#include <array>
#include <cassert>
#include <cmath>
#include <cstring>
#include <limits>
#include <stdexcept>

struct LightData {
    glm::vec4 p0Area;
    glm::vec4 p1;
    glm::vec4 p2;
    glm::vec4 p3;
    glm::vec4 normal;
    glm::vec4 radiance;
    glm::vec4 sourceIDData;
};
static_assert(sizeof(LightData) == sizeof(glm::vec4) * 7);

static void buildChunkPackedVertices(const std::vector<std::vector<vk::VertexFormat::PBRVertex>> &vertices,
                                     const std::vector<std::vector<uint32_t>> &indices,
                                     std::vector<vk::VertexFormat::PositionVertex> &packedPositions,
                                     std::vector<vk::VertexFormat::PackedMaterialVertex> &packedMaterials,
                                     std::vector<uint32_t> &packedIndices) {
    for (int i = 0; i < static_cast<int>(vertices.size()); i++) {
        const auto &geometryVertices = vertices[i];
        const auto &geometryIndices = indices[i];

        packedIndices.insert(packedIndices.end(), geometryIndices.begin(), geometryIndices.end());

        for (const auto &vertex : geometryVertices) {
            packedPositions.push_back(vk::Vertex::makePositionVertex(vertex));
            packedMaterials.push_back(vk::Vertex::makeMaterialVertex(vertex));
        }
    }
}

static uint64_t hashCombine64(uint64_t seed, uint64_t value) {
    seed ^= value + 0x9e3779b97f4a7c15ull + (seed << 6) + (seed >> 2);
    return seed;
}

static float packUintBits(uint32_t value) {
    float packed = 0.0f;
    std::memcpy(&packed, &value, sizeof(uint32_t));
    return packed;
}

static float triangleArea(const glm::vec3 &a, const glm::vec3 &b, const glm::vec3 &c) {
    return 0.5f * glm::length(glm::cross(b - a, c - a));
}

static float cross2D(const glm::vec2 &a, const glm::vec2 &b) {
    return a.x * b.y - a.y * b.x;
}

static void clipUvPolygonAgainstEdge(const std::vector<glm::vec2> &input,
                                     std::vector<glm::vec2> &output,
                                     int axis,
                                     float value,
                                     bool keepGreater) {
    output.clear();
    if (input.empty()) {
        return;
    }

    auto coordinate = [&](const glm::vec2 &uv) -> float { return axis == 0 ? uv.x : uv.y; };
    auto inside = [&](const glm::vec2 &uv) -> bool {
        return keepGreater ? coordinate(uv) >= value : coordinate(uv) <= value;
    };
    auto intersect = [&](const glm::vec2 &a, const glm::vec2 &b) -> glm::vec2 {
        float delta = coordinate(b) - coordinate(a);
        if (std::abs(delta) <= 1e-8f) {
            return a;
        }
        float t = (value - coordinate(a)) / delta;
        return glm::mix(a, b, std::clamp(t, 0.0f, 1.0f));
    };

    glm::vec2 previous = input.back();
    bool previousInside = inside(previous);
    for (const glm::vec2 &current : input) {
        bool currentInside = inside(current);
        if (currentInside != previousInside) {
            output.push_back(intersect(previous, current));
        }
        if (currentInside) {
            output.push_back(current);
        }
        previous = current;
        previousInside = currentInside;
    }
}

static std::vector<glm::vec2> clipUvTriangleToRect(const std::array<glm::vec2, 3> &triangle,
                                                   const glm::vec2 &rectMin,
                                                   const glm::vec2 &rectMax) {
    std::vector<glm::vec2> polygon(triangle.begin(), triangle.end());
    std::vector<glm::vec2> scratch;

    clipUvPolygonAgainstEdge(polygon, scratch, 0, rectMin.x, true);
    polygon.swap(scratch);
    clipUvPolygonAgainstEdge(polygon, scratch, 0, rectMax.x, false);
    polygon.swap(scratch);
    clipUvPolygonAgainstEdge(polygon, scratch, 1, rectMin.y, true);
    polygon.swap(scratch);
    clipUvPolygonAgainstEdge(polygon, scratch, 1, rectMax.y, false);
    polygon.swap(scratch);
    return polygon;
}

static void releaseChunkBuildDataStaging(const std::shared_ptr<ChunkBuildData> &chunkBuildData) {
    if (chunkBuildData->indexBuffer != nullptr) {
        chunkBuildData->indexBuffer->releaseStaging();
    }

    if (chunkBuildData->lightBuffer != nullptr) {
        chunkBuildData->lightBuffer->releaseStaging();
    }
}

static bool barycentricFromUv(const glm::vec2 &uv,
                              const std::array<glm::vec2, 3> &triangleUv,
                              glm::vec3 &outBarycentric) {
    glm::vec2 e0 = triangleUv[1] - triangleUv[0];
    glm::vec2 e1 = triangleUv[2] - triangleUv[0];
    float denominator = cross2D(e0, e1);
    if (std::abs(denominator) <= 1e-8f) {
        return false;
    }

    glm::vec2 relative = uv - triangleUv[0];
    float b1 = cross2D(relative, e1) / denominator;
    float b2 = cross2D(e0, relative) / denominator;
    outBarycentric = glm::vec3(1.0f - b1 - b2, b1, b2);
    return true;
}

static bool uvToTrianglePosition(const glm::vec2 &uv,
                                 const std::array<glm::vec2, 3> &triangleUv,
                                 const std::array<glm::vec3, 3> &trianglePos,
                                 glm::vec3 &outPosition) {
    glm::vec3 barycentric;
    if (!barycentricFromUv(uv, triangleUv, barycentric)) {
        return false;
    }

    outPosition = trianglePos[0] * barycentric.x + trianglePos[1] * barycentric.y + trianglePos[2] * barycentric.z;
    return true;
}

static bool uvToTriangleTint(const glm::vec2 &uv,
                             const std::array<glm::vec2, 3> &triangleUv,
                             const std::array<glm::vec3, 3> &triangleTint,
                             glm::vec3 &outTint) {
    glm::vec3 barycentric;
    if (!barycentricFromUv(uv, triangleUv, barycentric)) {
        return false;
    }

    outTint = triangleTint[0] * barycentric.x + triangleTint[1] * barycentric.y + triangleTint[2] * barycentric.z;
    return true;
}

static glm::vec3 emissionVertexTint(const vk::VertexFormat::PBRVertex &vertex) {
    if (vertex.useColorLayer == 0) {
        return glm::vec3(1.0f);
    }

    return glm::clamp(glm::vec3(vertex.colorLayer), glm::vec3(0.0f), glm::vec3(1.0f));
}

static uint64_t buildChunkLightStableID(int64_t chunkId,
                                        uint32_t geometryIndex,
                                        uint32_t quadIndex,
                                        uint64_t cellStableKey) {
    uint64_t seed = 0xcbf29ce484222325ull;
    seed = hashCombine64(seed, static_cast<uint64_t>(chunkId));
    seed = hashCombine64(seed, static_cast<uint64_t>(geometryIndex));
    seed = hashCombine64(seed, static_cast<uint64_t>(quadIndex));
    seed = hashCombine64(seed, cellStableKey);
    return seed;
}

static ChunkPackedData makeChunkPackedData(int32_t x,
                                           int32_t y,
                                           int32_t z,
                                           uint32_t geometryCount,
                                           uint32_t lightCount,
                                           VkDeviceAddress lightBufferAddress) {
    return {
        .x = x,
        .y = y,
        .z = z,
        .geometryCount = geometryCount,
        .lightCount = lightCount,
        .lightBufferAddress = lightBufferAddress,
    };
}

static void storeChunkPackedData(std::vector<ChunkPackedData> &chunkPackedData,
                                 int64_t id,
                                 int32_t x,
                                 int32_t y,
                                 int32_t z,
                                 uint32_t geometryCount,
                                 uint32_t lightCount,
                                 VkDeviceAddress lightBufferAddress) {
    if (!Renderer::options.collectChunkEmission) {
        return;
    }

    chunkPackedData[id] = makeChunkPackedData(x, y, z, geometryCount, lightCount, lightBufferAddress);
}

static LightData packLight(const LightInfo &light, const glm::vec3 &chunkOrigin) {
    LightData gpuLight{};
    glm::vec3 p0 = light.p0 + chunkOrigin;
    glm::vec3 p1 = light.p1 + chunkOrigin;
    glm::vec3 p2 = light.p2 + chunkOrigin;
    glm::vec3 p3 = light.p3 + chunkOrigin;

    gpuLight.p0Area = glm::vec4(p0, light.area);
    gpuLight.p1 = glm::vec4(p1, 0.0f);
    gpuLight.p2 = glm::vec4(p2, 0.0f);
    gpuLight.p3 = glm::vec4(p3, 0.0f);
    gpuLight.normal = glm::vec4(light.normal, 0.0f);
    gpuLight.radiance = glm::vec4(light.radiance, 0.0f);
    uint32_t stableIDLow = static_cast<uint32_t>(light.stableID & 0xffffffffull);
    uint32_t stableIDHigh = static_cast<uint32_t>((light.stableID >> 32u) & 0xffffffffull);
    gpuLight.sourceIDData = glm::vec4(packUintBits(stableIDLow), packUintBits(stableIDHigh), 0.0f, 0.0f);
    return gpuLight;
}

void ChunkBuildData::buildLightBuffer(const std::shared_ptr<vk::VMA> &vma,
                                      const std::shared_ptr<vk::Device> &device,
                                      bool persistStaging) {
    lightBuffer = nullptr;
    lightCount = 0;

    if (!collectChunkEmission) {
        (void)vma;
        (void)device;
        return;
    }

    if (lightInfos.empty()) {
        return;
    }

    std::vector<LightData> gpuLights;
    gpuLights.reserve(lightInfos.size());

    glm::vec3 chunkOrigin(static_cast<float>(x), static_cast<float>(y), static_cast<float>(z));
    for (const auto &light : lightInfos) {
        float maxRadiance = std::max({light.radiance.r, light.radiance.g, light.radiance.b});
        if (light.area <= 1e-6f || maxRadiance <= 1e-6f) {
            continue;
        }

        gpuLights.push_back(packLight(light, chunkOrigin));
    }

    if (gpuLights.empty()) {
        return;
    }

    lightCount = static_cast<uint32_t>(gpuLights.size());

    size_t lightBytes = static_cast<size_t>(lightCount) * sizeof(LightData);
    lightBuffer = vk::DeviceLocalBuffer::create(vma, device, persistStaging, lightBytes,
                                                VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT |
                                                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                                                0, VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE,
                                                16);
#ifdef DEBUG
    if ((lightBuffer->bufferAddress() & (16 - 1)) != 0) {
        throw std::runtime_error("Chunk light buffer address is not 16-byte aligned");
    }
#endif
    lightBuffer->uploadToStagingBuffer(gpuLights.data(), lightBytes, 0);
}

ChunkBuildData::ChunkBuildData(int64_t id,
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
                               std::vector<std::vector<uint32_t>> &&indices)
    : id(id),
      x(x),
      y(y),
      z(z),
      version(version),
      collectChunkEmission(collectChunkEmission),
      allVertexCount(allVertexCount),
      allIndexCount(allIndexCount),
      geometryCount(geometryCount),
      geometryTypes(std::move(geometryTypes)),
      geometryGroupNames(std::move(geometryGroupNames)),
      vertices(std::move(vertices)),
      indices(std::move(indices)),
      indexBufferAddresses(),
      positionBufferAddresses(),
      materialBufferAddresses(),
      indexBuffer(nullptr),
      positionBuffer(nullptr),
      materialBuffer(nullptr),
      blas(nullptr),
      blasBuilder(nullptr),
      lightInfos(),
      lightBuffer(nullptr),
      lightCount(0) {}

void ChunkBuildData::buildLightInfos(const Emission &emission) {
    lightInfos.clear();

    if (!collectChunkEmission) {
        (void)emission;
        return;
    }

    for (uint32_t geometryIndex = 0; geometryIndex < vertices.size(); geometryIndex++) {
        auto &geometryVertices = vertices[geometryIndex];
        if (geometryVertices.size() < 4) {
            continue;
        }

        uint32_t quadIndex = 0;
        for (size_t vertexIndex = 0; vertexIndex + 3 < geometryVertices.size(); vertexIndex += 4, quadIndex++) {
            const auto &v0 = geometryVertices[vertexIndex + 0];
            const auto &v1 = geometryVertices[vertexIndex + 1];
            const auto &v2 = geometryVertices[vertexIndex + 2];
            const auto &v3 = geometryVertices[vertexIndex + 3];

            glm::vec3 tint0 = emissionVertexTint(v0);
            glm::vec3 tint1 = emissionVertexTint(v1);
            glm::vec3 tint2 = emissionVertexTint(v2);
            glm::vec3 tint3 = emissionVertexTint(v3);

            if (v0.useTexture == 0 || v1.useTexture == 0 || v2.useTexture == 0 || v3.useTexture == 0) {
                continue;
            }

            std::array<glm::vec2, 4> quadUv = {v0.textureUV, v1.textureUV, v2.textureUV, v3.textureUV};
            glm::vec2 uvMin(std::numeric_limits<float>::max());
            glm::vec2 uvMax(std::numeric_limits<float>::lowest());
            for (glm::vec2 uv : quadUv) {
                uvMin = glm::min(uvMin, uv);
                uvMax = glm::max(uvMax, uv);
            }

            glm::vec2 uvSize = uvMax - uvMin;
            if (uvSize.x <= 1e-6f || uvSize.y <= 1e-6f) {
                continue;
            }

            glm::vec3 triNormal0 = glm::cross(v1.pos - v0.pos, v2.pos - v0.pos);
            glm::vec3 triNormal1 = glm::cross(v2.pos - v0.pos, v3.pos - v0.pos);
            glm::vec3 baseNormal = triNormal0 + triNormal1;
            float baseNormalLength = glm::length(baseNormal);
            if (baseNormalLength <= 1e-6f) {
                continue;
            }

            glm::vec3 normal = baseNormal / baseNormalLength;
            float quadArea = triangleArea(v0.pos, v1.pos, v2.pos) + triangleArea(v0.pos, v2.pos, v3.pos);
            if (quadArea <= 1e-6f) {
                continue;
            }

            thread_local std::vector<std::shared_ptr<const EmissionCell>> candidateCells;
            emission.collectCells(v0.textureID, uvMin, uvMax, candidateCells);
            for (const auto &candidateCell : candidateCells) {
                if (candidateCell == nullptr) {
                    continue;
                }

                const auto &cell = *candidateCell;
                glm::vec2 overlapMin = glm::max(uvMin, cell.uvMin);
                glm::vec2 overlapMax = glm::min(uvMax, cell.uvMax);
                glm::vec2 overlapSize = overlapMax - overlapMin;
                if (overlapSize.x <= 1e-6f || overlapSize.y <= 1e-6f || cell.avgEmission <= 0.0f) {
                    continue;
                }

                uint64_t baseStableID = buildChunkLightStableID(id, geometryIndex, quadIndex, cell.stableKey);
                std::array<std::array<glm::vec2, 3>, 2> triangleUv = {{
                    {v0.textureUV, v1.textureUV, v2.textureUV},
                    {v0.textureUV, v2.textureUV, v3.textureUV},
                }};
                std::array<std::array<glm::vec3, 3>, 2> trianglePos = {{
                    {v0.pos, v1.pos, v2.pos},
                    {v0.pos, v2.pos, v3.pos},
                }};
                std::array<std::array<glm::vec3, 3>, 2> triangleTint = {{
                    {tint0, tint1, tint2},
                    {tint0, tint2, tint3},
                }};

                uint64_t trianglePieceIndex = 0;
                for (int triangleIndex = 0; triangleIndex < 2; ++triangleIndex) {
                    std::vector<glm::vec2> clippedPolygon =
                        clipUvTriangleToRect(triangleUv[triangleIndex], overlapMin, overlapMax);
                    if (clippedPolygon.size() < 3) {
                        continue;
                    }

                    glm::vec3 fanOrigin;
                    glm::vec3 fanOriginTint;
                    if (!uvToTrianglePosition(clippedPolygon[0], triangleUv[triangleIndex], trianglePos[triangleIndex],
                                              fanOrigin) ||
                        !uvToTriangleTint(clippedPolygon[0], triangleUv[triangleIndex], triangleTint[triangleIndex],
                                          fanOriginTint)) {
                        continue;
                    }

                    for (size_t polygonIndex = 1; polygonIndex + 1 < clippedPolygon.size(); ++polygonIndex) {
                        glm::vec3 p1;
                        glm::vec3 p2;
                        glm::vec3 p1Tint;
                        glm::vec3 p2Tint;
                        if (!uvToTrianglePosition(clippedPolygon[polygonIndex], triangleUv[triangleIndex],
                                                  trianglePos[triangleIndex], p1) ||
                            !uvToTriangleTint(clippedPolygon[polygonIndex], triangleUv[triangleIndex],
                                              triangleTint[triangleIndex], p1Tint) ||
                            !uvToTrianglePosition(clippedPolygon[polygonIndex + 1], triangleUv[triangleIndex],
                                                  trianglePos[triangleIndex], p2) ||
                            !uvToTriangleTint(clippedPolygon[polygonIndex + 1], triangleUv[triangleIndex],
                                              triangleTint[triangleIndex], p2Tint)) {
                            continue;
                        }

                        float triangleLightArea = triangleArea(fanOrigin, p1, p2);
                        if (triangleLightArea <= 1e-6f) {
                            continue;
                        }

                        glm::vec3 lightNormal = glm::cross(p1 - fanOrigin, p2 - fanOrigin);
                        float lightNormalLength = glm::length(lightNormal);
                        lightNormal = lightNormalLength > 1e-6f ? lightNormal / lightNormalLength : normal;
                        glm::vec3 avgTint = glm::max((fanOriginTint + p1Tint + p2Tint) / 3.0f, glm::vec3(0.0f));

                        LightInfo info{
                            .p0 = fanOrigin,
                            .p1 = p1,
                            .p2 = p2,
                            .p3 = p2,
                            .normal = lightNormal,
                            .radiance = cell.avgColor * avgTint * cell.avgEmission,
                            .area = triangleLightArea,
                            .textureID = v0.textureID,
                            .stableID = hashCombine64(baseStableID, trianglePieceIndex++),
                        };
                        lightInfos.push_back(info);
                    }
                }
            }
        }
    }
}

namespace {
// Chunk geometry goes to the GPU in a compact form of its own; entities keep the general layout. Each tagged
// buffer address has its lowest bit set, which is free because every region is at least 2-byte aligned, and the
// shaders' loaders in util/vertex.glsl read the compact layout wherever they see it.
//   indices    16-bit when the geometry has at most 65535 vertices (tag), otherwise 32-bit
//   positions  4 x half, relative to the section origin (tag, always). Block geometry lies on a 1/16 grid, which
//              half floats represent exactly over a section's range, so shared edges stay exactly shared.
//   materials  when every vertex of a geometry shares its texture, flags, emission, overlay and glint (terrain
//              always does): a 5-word header with those, then 5 words per vertex (normal, colour, u, v, light)
//              (tag). Otherwise the regular 40-byte PackedMaterialVertex.
constexpr VkDeviceAddress kCompactTag = 1;

size_t alignTo(size_t value, size_t alignment) {
    return (value + alignment - 1) / alignment * alignment;
}

bool hasUniformMaterial(const std::vector<vk::VertexFormat::PBRVertex> &vertices) {
    if (vertices.empty()) return false;
    const auto first = vk::Vertex::makeMaterialVertex(vertices[0]);
    for (const auto &vertex : vertices) {
        const auto m = vk::Vertex::makeMaterialVertex(vertex);
        if (m.textures != first.textures || m.overlay != first.overlay || m.glintUV != first.glintUV ||
            m.packedData != first.packedData ||
            std::memcmp(&m.albedoEmission, &first.albedoEmission, sizeof(float)) != 0) {
            return false;
        }
    }
    return true;
}

struct PackedChunkGeometry {
    std::vector<uint8_t> bytes;
    std::vector<size_t> indexOffsets;
    std::vector<size_t> positionOffsets;
    std::vector<size_t> materialOffsets;
    std::vector<uint8_t> index16;
    std::vector<uint8_t> compactMaterial;
};

PackedChunkGeometry packChunkGeometry(const std::vector<std::vector<vk::VertexFormat::PBRVertex>> &vertices,
                                      const std::vector<std::vector<uint32_t>> &indices) {
    PackedChunkGeometry packed;
    const size_t count = vertices.size();
    packed.indexOffsets.resize(count);
    packed.positionOffsets.resize(count);
    packed.materialOffsets.resize(count);
    packed.index16.resize(count);
    packed.compactMaterial.resize(count);

    size_t size = 0;
    for (size_t g = 0; g < count; g++) {
        packed.index16[g] = vertices[g].size() <= 65535 ? 1 : 0;
        packed.indexOffsets[g] = size;
        size = alignTo(size + indices[g].size() * (packed.index16[g] ? 2 : 4), 8);
    }
    for (size_t g = 0; g < count; g++) {
        packed.positionOffsets[g] = size;
        size = alignTo(size + vertices[g].size() * 8, 8);
    }
    for (size_t g = 0; g < count; g++) {
        packed.compactMaterial[g] = hasUniformMaterial(vertices[g]) ? 1 : 0;
        packed.materialOffsets[g] = size;
        size_t bytes = packed.compactMaterial[g] ? (5 + vertices[g].size() * 5) * sizeof(uint32_t) :
                                                   vertices[g].size() * sizeof(vk::VertexFormat::PackedMaterialVertex);
        size = alignTo(size + bytes, 8);
    }
    packed.bytes.assign(std::max<size_t>(size, 16), 0);
    uint8_t *base = packed.bytes.data();

    for (size_t g = 0; g < count; g++) {
        const auto &geometryIndices = indices[g];
        if (packed.index16[g]) {
            auto *out = reinterpret_cast<uint16_t *>(base + packed.indexOffsets[g]);
            for (size_t i = 0; i < geometryIndices.size(); i++) { out[i] = static_cast<uint16_t>(geometryIndices[i]); }
        } else {
            std::memcpy(base + packed.indexOffsets[g], geometryIndices.data(), geometryIndices.size() * 4);
        }

        auto *positions = reinterpret_cast<uint32_t *>(base + packed.positionOffsets[g]);
        for (size_t v = 0; v < vertices[g].size(); v++) {
            const glm::vec3 &pos = vertices[g][v].pos;
            positions[v * 2] = glm::packHalf2x16(glm::vec2(pos.x, pos.y));
            positions[v * 2 + 1] = glm::packHalf2x16(glm::vec2(pos.z, 0.0f));
        }

        if (packed.compactMaterial[g]) {
            auto *words = reinterpret_cast<uint32_t *>(base + packed.materialOffsets[g]);
            const auto first = vk::Vertex::makeMaterialVertex(vertices[g][0]);
            words[0] = first.textures;
            words[1] = first.overlay;
            words[2] = first.glintUV;
            std::memcpy(&words[3], &first.albedoEmission, sizeof(float));
            words[4] = first.packedData;
            for (size_t v = 0; v < vertices[g].size(); v++) {
                const auto m = vk::Vertex::makeMaterialVertex(vertices[g][v]);
                uint32_t *w = words + 5 + v * 5;
                w[0] = m.normal;
                w[1] = m.color;
                std::memcpy(&w[2], &m.u, sizeof(float));
                std::memcpy(&w[3], &m.v, sizeof(float));
                w[4] = m.light;
            }
        } else {
            auto *materials =
                reinterpret_cast<vk::VertexFormat::PackedMaterialVertex *>(base + packed.materialOffsets[g]);
            for (size_t v = 0; v < vertices[g].size(); v++) {
                materials[v] = vk::Vertex::makeMaterialVertex(vertices[g][v]);
            }
        }
    }
    return packed;
}
} // namespace

void ChunkBuildData::packGeometry(const std::shared_ptr<vk::VMA> &vma,
                                  const std::shared_ptr<vk::Device> &device,
                                  bool persistStaging,
                                  const std::shared_ptr<vk::BLASBuilder> &builder) {
    PackedChunkGeometry packed = packChunkGeometry(vertices, indices);

    // One buffer per chunk holds all of its geometry. Chunks used to share buffers with the rest of their build
    // batch, so rebuilding one chunk left the whole batch's memory alive until every chunk in it was rebuilt too,
    // and video memory crept up over a long session.
    auto buffer = vk::DeviceLocalBuffer::create(vma, device, persistStaging, packed.bytes.size(),
                                                VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT |
                                                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR |
                                                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
    buffer->uploadToStagingBuffer(packed.bytes.data(), packed.bytes.size(), 0);
    buffer->flushStagingBuffer();
    Chunks::devBytes[0] += packed.bytes.size();

    indexBuffer = buffer;
    positionBuffer = buffer;
    materialBuffer = buffer;

    indexBufferAddresses.clear();
    positionBufferAddresses.clear();
    materialBufferAddresses.clear();
    indexBufferAddresses.reserve(geometryCount);
    positionBufferAddresses.reserve(geometryCount);
    materialBufferAddresses.reserve(geometryCount);

    const VkDeviceAddress address = buffer->bufferAddress();
    auto geometryBuilder = builder->beginGeometries();
    for (uint32_t i = 0; i < geometryCount; i++) {
        const VkDeviceAddress indexAddress = address + packed.indexOffsets[i];
        const VkDeviceAddress positionAddress = address + packed.positionOffsets[i];
        const VkDeviceAddress materialAddress = address + packed.materialOffsets[i];
        indexBufferAddresses.push_back(indexAddress | (packed.index16[i] ? kCompactTag : 0));
        positionBufferAddresses.push_back(positionAddress | kCompactTag);
        materialBufferAddresses.push_back(materialAddress | (packed.compactMaterial[i] ? kCompactTag : 0));

        geometryBuilder->defineTriangleGeometryRaw(positionAddress, VK_FORMAT_R16G16B16A16_SFLOAT, 8,
                                                   static_cast<uint32_t>(vertices[i].size()), indexAddress,
                                                   packed.index16[i] ? VK_INDEX_TYPE_UINT16 : VK_INDEX_TYPE_UINT32,
                                                   static_cast<uint32_t>(indices[i].size()),
                                                   geometryTypes[i] == World::WORLD_SOLID);
    }
    geometryBuilder->endGeometries();
}

void ChunkBuildData::build(bool persistStaging) {
    auto framework = Renderer::instance().framework();
    auto vma = framework->vma();
    auto device = framework->device();
    auto physicalDevice = framework->physicalDevice();

    buildLightBuffer(vma, device, persistStaging);

    if (geometryCount == 0) {
        blas = nullptr;
        blasBuilder = nullptr;
        return;
    }

    blasBuilder = vk::BLASBuilder::create();
    packGeometry(vma, device, persistStaging, blasBuilder);
    blas = blasBuilder->defineBuildProperty(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR)
               ->querySizeInfo(device)
               ->allocateBuffers(physicalDevice, device, vma)
               ->build(device);
}

ChunkBuildDataBatch::ChunkBuildDataBatch(std::vector<std::shared_ptr<ChunkBuildData>> &&batchData)
    : batchData(std::move(batchData)) {}

ChunkBuildDataBatch::ChunkBuildDataBatch(uint32_t maxBatchSize,
                                         std::set<int64_t> &queuedIndexSet,
                                         std::vector<std::shared_ptr<Chunk1>> &chunks,
                                         std::vector<std::shared_ptr<ChunkBuildData>> &chunkBuildDatas,
                                         glm::vec3 cameraPos) {
    std::vector<int64_t> queuedIndices;
    std::copy(queuedIndexSet.begin(), queuedIndexSet.end(), std::back_inserter(queuedIndices));
    auto currentTime = std::chrono::steady_clock::now();
    auto queuedChunkPos = [&](int64_t id) -> glm::vec3 {
        const auto &chunkBuildData = chunkBuildDatas[id];
        if (chunkBuildData != nullptr) {
            return {
                static_cast<float>(chunkBuildData->x),
                static_cast<float>(chunkBuildData->y),
                static_cast<float>(chunkBuildData->z),
            };
        }

        return {
            static_cast<float>(chunks[id]->x),
            static_cast<float>(chunks[id]->y),
            static_cast<float>(chunks[id]->z),
        };
    };
    std::sort(queuedIndices.begin(), queuedIndices.end(), [&](int64_t a, int64_t b) -> bool {
        return chunks[a]->buildFactor(currentTime, cameraPos, queuedChunkPos(a)) >
               chunks[b]->buildFactor(currentTime, cameraPos, queuedChunkPos(b));
    });

    for (int i = 0; i < std::min((size_t)maxBatchSize, queuedIndices.size()); i++) {
        auto iter = queuedIndexSet.find(queuedIndices[i]);
        if (iter != queuedIndexSet.end()) { queuedIndexSet.erase(iter); }

        auto data = chunkBuildDatas[queuedIndices[i]];
        if (data == nullptr) {
            continue;
        }
        chunkBuildDatas[queuedIndices[i]] = nullptr;
        batchData.push_back(data);
    }
}

void ChunkBuildDataBatch::build() {
    auto framework = Renderer::instance().framework();
    auto vma = framework->vma();
    auto device = framework->device();
    auto physicalDevice = framework->physicalDevice();

    blasBatchBuilder = vk::BLASBatchBuilder::create();
    std::vector<size_t> builtIndices;
    for (size_t i = 0; i < batchData.size(); i++) {
        auto &data = batchData[i];
        if (data == nullptr) continue;
        data->buildLightBuffer(vma, device, true);
        if (data->geometryCount == 0) {
            data->indexBufferAddresses.clear();
            data->positionBufferAddresses.clear();
            data->materialBufferAddresses.clear();
            data->indexBuffer = nullptr;
            data->positionBuffer = nullptr;
            data->materialBuffer = nullptr;
            data->blas = nullptr;
            data->blasBuilder = nullptr;
            continue;
        }

        auto builder = blasBatchBuilder->defineBLASBuilder();
        data->packGeometry(vma, device, true, builder);
        data->blasBuilder = builder
                                ->defineBuildProperty(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR |
                                                      VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR)
                                ->querySizeInfo(device);
        builtIndices.push_back(i);
    }

    if (builtIndices.empty()) {
        blasBatchBuilder = nullptr;
        return;
    }

    auto blases = blasBatchBuilder->allocateBuffers(physicalDevice, device, vma)->build(device);
    Chunks::devBytes[3] += blasBatchBuilder->totalBlasBytes();
    for (size_t i = 0; i < builtIndices.size(); i++) {
        batchData[builtIndices[i]]->blas = blases[i];
    }

    builtBlases = blases;
    VkQueryPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
    poolInfo.queryType = VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR;
    poolInfo.queryCount = static_cast<uint32_t>(blases.size());
    if (vkCreateQueryPool(device->vkDevice(), &poolInfo, nullptr, &compactionQueryPool) != VK_SUCCESS) {
        compactionQueryPool = VK_NULL_HANDLE;
    }
}

ChunkBuildScheduler::ChunkBuildScheduler(std::set<int64_t> &queuedIndex,
                                         std::vector<std::shared_ptr<Chunk1>> &chunks,
                                         std::vector<std::shared_ptr<ChunkBuildData>> &chunkBuildDatas,
                                         std::recursive_mutex &mutex,
                                         std::vector<ChunkPackedData> &chunkPackedData,
                                         uint32_t chunkBuildingBatchSize,
                                         uint32_t chunkBuildingTotalBatches)
    : queuedIndex_(queuedIndex),
      chunks_(chunks),
      chunkBuildDatas_(chunkBuildDatas),
      mutex_(mutex),
      chunkPackedData_(chunkPackedData),
      chunkBuildingBatchSize_(chunkBuildingBatchSize),
      chunkBuildingTotalBatches_(chunkBuildingTotalBatches) {
    auto framework = Renderer::instance().framework();
    auto device = framework->device();
    auto physicalDevice = framework->physicalDevice();
    useSecondaryQueue_ = physicalDevice->mainQueueIndex() == physicalDevice->secondaryQueueIndex();
    auto commandPool = useSecondaryQueue_ ? framework->asyncCommandPool() : framework->mainCommandPool();

    uint32_t numFences = chunkBuildingTotalBatches_;
    for (int i = 0; i < numFences; i++) {
        freeFences_.push(vk::Fence::create(device));
        freeCommandBuffers_.push(vk::CommandBuffer::create(device, commandPool));
    }
}

void ChunkBuildScheduler::collectCompactions(const std::shared_ptr<ChunkBuildDataBatch> &batch) {
    if (batch->compactionQueryPool == VK_NULL_HANDLE) return;
    auto device = Renderer::instance().framework()->device();
    const uint32_t count = static_cast<uint32_t>(batch->builtBlases.size());
    std::vector<uint64_t> sizes(count, 0);
    VkResult result = vkGetQueryPoolResults(device->vkDevice(), batch->compactionQueryPool, 0, count,
                                            sizes.size() * sizeof(uint64_t), sizes.data(), sizeof(uint64_t),
                                            VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT);
    vkDestroyQueryPool(device->vkDevice(), batch->compactionQueryPool, nullptr);
    batch->compactionQueryPool = VK_NULL_HANDLE;
    if (result != VK_SUCCESS || compactionSink_ == nullptr) return;

    std::vector<ChunkCompaction> compactions;
    for (const auto &data : batch->batchData) {
        if (data == nullptr || data->blas == nullptr) continue;
        for (uint32_t i = 0; i < count; i++) {
            if (batch->builtBlases[i] == data->blas && sizes[i] > 0) {
                compactions.push_back({data->id, data->blas, sizes[i]});
                break;
            }
        }
    }
    compactionSink_(std::move(compactions));
}

void ChunkBuildScheduler::tryCheckBatchesFinish() {
    auto framework = Renderer::instance().framework();
    auto device = framework->device();

    std::unique_lock<std::recursive_mutex> lock(mutex_);
    auto iterFence = buildingFences_.begin();
    auto iterCommandBuffer = buildingCommandBuffers_.begin();
    auto iterBatch = buildingBatches_.begin();
    for (; iterFence != buildingFences_.end() && iterCommandBuffer != buildingCommandBuffers_.end() &&
           iterBatch != buildingBatches_.end();) {
        if (vkWaitForFences(device->vkDevice(), 1, &(*iterFence)->vkFence(), true, 0) == VK_SUCCESS) {
            vkResetFences(device->vkDevice(), 1, &(*iterFence)->vkFence());
            vkResetCommandBuffer((*iterCommandBuffer)->vkCommandBuffer(), 0);
            freeFences_.push(*iterFence);
            freeCommandBuffers_.push(*iterCommandBuffer);

            collectCompactions(*iterBatch);
            for (auto chunkBuildData : (*iterBatch)->batchData) {
                bool wasEnqueued = chunks_[chunkBuildData->id]->enqueue(chunkBuildData);
                releaseChunkBuildDataStaging(chunkBuildData);
                if (!wasEnqueued) {
                    continue;
                }

                storeChunkPackedData(
                    chunkPackedData_, chunkBuildData->id, chunkBuildData->x, chunkBuildData->y, chunkBuildData->z,
                    chunkBuildData->geometryCount, chunkBuildData->lightCount,
                    chunkBuildData->lightBuffer != nullptr ? chunkBuildData->lightBuffer->bufferAddress() : 0);
            }

            iterFence = buildingFences_.erase(iterFence);
            iterCommandBuffer = buildingCommandBuffers_.erase(iterCommandBuffer);
            iterBatch = buildingBatches_.erase(iterBatch);
        } else {
            ++iterFence;
            ++iterCommandBuffer;
            ++iterBatch;
        }
    }
}

void ChunkBuildScheduler::waitAllBatchesFinish() {
    auto framework = Renderer::instance().framework();
    auto device = framework->device();

    std::unique_lock<std::recursive_mutex> lock(mutex_);
    auto iterFence = buildingFences_.begin();
    auto iterCommandBuffer = buildingCommandBuffers_.begin();
    auto iterBatch = buildingBatches_.begin();
    for (; iterFence != buildingFences_.end() && iterCommandBuffer != buildingCommandBuffers_.end() &&
           iterBatch != buildingBatches_.end();) {
        if (vkWaitForFences(device->vkDevice(), 1, &(*iterFence)->vkFence(), true, UINT64_MAX) == VK_SUCCESS) {
            vkResetFences(device->vkDevice(), 1, &(*iterFence)->vkFence());
            vkResetCommandBuffer((*iterCommandBuffer)->vkCommandBuffer(), 0);
            freeFences_.push(*iterFence);
            freeCommandBuffers_.push(*iterCommandBuffer);

            collectCompactions(*iterBatch);
            for (auto chunkBuildData : (*iterBatch)->batchData) {
                bool wasEnqueued = chunks_[chunkBuildData->id]->enqueue(chunkBuildData);
                releaseChunkBuildDataStaging(chunkBuildData);
                if (!wasEnqueued) {
                    continue;
                }

                storeChunkPackedData(
                    chunkPackedData_, chunkBuildData->id, chunkBuildData->x, chunkBuildData->y, chunkBuildData->z,
                    chunkBuildData->geometryCount, chunkBuildData->lightCount,
                    chunkBuildData->lightBuffer != nullptr ? chunkBuildData->lightBuffer->bufferAddress() : 0);
            }

            iterFence = buildingFences_.erase(iterFence);
            iterCommandBuffer = buildingCommandBuffers_.erase(iterCommandBuffer);
            iterBatch = buildingBatches_.erase(iterBatch);
        }
    }

    for (const auto &chunkBuildData : pendingBatchData_) {
        if (chunkBuildData == nullptr) {
            continue;
        }
        queuedIndex_.insert(chunkBuildData->id);
        chunkBuildDatas_[chunkBuildData->id] = chunkBuildData;
    }
    pendingBatchData_.clear();
    pendingBatchFrames_ = 0;
}

void ChunkBuildScheduler::tryScheduleBatches(uint32_t maxBatchSize) {
    if (!Renderer::instance().framework()->isRunning()) return;
    // Building a batch (packing its geometry, sizing and recording its acceleration structures) happens right here
    // on the render thread, and its builds then run on the GPU alongside the frame. Scheduling every batch there was
    // room for at once stalled a single frame for tens of milliseconds - and the GPU for hundreds - whenever a burst
    // of chunks arrived, walking into a dense city or teleporting. A small time budget per frame spreads them out.
    constexpr double kScheduleBudgetMs = 3.0;
    auto budgetStart = std::chrono::steady_clock::now();
    bool scheduledOne = false;
    while (true) {
        if (scheduledOne &&
            std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - budgetStart).count() >
                kScheduleBudgetMs) {
            return;
        }
        std::shared_ptr<vk::Fence> fence;
        std::shared_ptr<vk::CommandBuffer> commandBuffer;
        std::shared_ptr<ChunkBuildDataBatch> chunkBuildDataBatch;

        {
            std::unique_lock<std::recursive_mutex> lock(mutex_);
            if (freeFences_.empty() || freeCommandBuffers_.empty()) { return; }

            if (pendingBatchData_.size() < maxBatchSize && !queuedIndex_.empty()) {
                const uint32_t remainingCapacity = maxBatchSize - static_cast<uint32_t>(pendingBatchData_.size());
                glm::vec3 cameraPos = Renderer::instance().world()->getCameraPos();
                auto supplementBatch =
                    ChunkBuildDataBatch::create(remainingCapacity, queuedIndex_, chunks_, chunkBuildDatas_, cameraPos);
                pendingBatchData_.insert(pendingBatchData_.end(), supplementBatch->batchData.begin(),
                                         supplementBatch->batchData.end());
            }

            if (pendingBatchData_.empty()) { return; }

            const bool isFullBatch = pendingBatchData_.size() >= maxBatchSize;
            if (!isFullBatch) {
                pendingBatchFrames_ = pendingBatchFrames_ == 0 ? 1 : pendingBatchFrames_ + 1;
                if (pendingBatchFrames_ < maxPendingBatchFrames_) { return; }
            } else {
                pendingBatchFrames_ = 0;
            }

            fence = freeFences_.front();
            freeFences_.pop();
            commandBuffer = freeCommandBuffers_.front();
            freeCommandBuffers_.pop();

            chunkBuildDataBatch = ChunkBuildDataBatch::create(std::move(pendingBatchData_));
            pendingBatchData_.clear();
            pendingBatchFrames_ = 0;
        }

        chunkBuildDataBatch->build();
        scheduledOne = true;

        bool hasLightUploads = false;
        for (const auto &chunkBuildData : chunkBuildDataBatch->batchData) {
            if (chunkBuildData->lightBuffer != nullptr) {
                hasLightUploads = true;
                break;
            }
        }

        if (chunkBuildDataBatch->blasBatchBuilder == nullptr && !hasLightUploads) {
            std::unique_lock<std::recursive_mutex> lock(mutex_);
            freeFences_.push(fence);
            freeCommandBuffers_.push(commandBuffer);

            for (auto &chunkBuildData : chunkBuildDataBatch->batchData) {
                if (!chunks_[chunkBuildData->id]->enqueue(chunkBuildData)) {
                    continue;
                }

                storeChunkPackedData(
                    chunkPackedData_, chunkBuildData->id, chunkBuildData->x, chunkBuildData->y, chunkBuildData->z,
                    chunkBuildData->geometryCount, chunkBuildData->lightCount,
                    chunkBuildData->lightBuffer != nullptr ? chunkBuildData->lightBuffer->bufferAddress() : 0);
            }
            continue;
        }

        auto framework = Renderer::instance().framework();
        auto device = framework->device();
        auto physicalDevice = framework->physicalDevice();
        const auto queueFamilyIndex =
            useSecondaryQueue_ ? physicalDevice->secondaryQueueIndex() : physicalDevice->mainQueueIndex();

        commandBuffer->begin();
        bool uploadedAnything = false;
        for (const auto &chunkBuildData : chunkBuildDataBatch->batchData) {
            if (chunkBuildData->indexBuffer != nullptr) {
                chunkBuildData->indexBuffer->uploadToBuffer(commandBuffer);
                uploadedAnything = true;
            }
            if (chunkBuildData->lightBuffer != nullptr) {
                chunkBuildData->lightBuffer->uploadToBuffer(commandBuffer);
                uploadedAnything = true;
            }
        }
        if (uploadedAnything) {
            commandBuffer->barriersMemory({vk::CommandBuffer::MemoryBarrier{
                .srcStageMask = VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                .srcAccessMask = VK_ACCESS_2_MEMORY_WRITE_BIT,
                .dstStageMask = VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR |
                                VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                .dstAccessMask = VK_ACCESS_2_MEMORY_READ_BIT,
            }});
        }

        if (chunkBuildDataBatch->blasBatchBuilder != nullptr) {
            chunkBuildDataBatch->blasBatchBuilder->submit(commandBuffer);
        }
        if (chunkBuildDataBatch->compactionQueryPool != VK_NULL_HANDLE) {
            const uint32_t count = static_cast<uint32_t>(chunkBuildDataBatch->builtBlases.size());
            std::vector<VkAccelerationStructureKHR> handles;
            handles.reserve(count);
            for (const auto &blas : chunkBuildDataBatch->builtBlases) { handles.push_back(blas->blas()); }
            commandBuffer->barriersMemory({vk::CommandBuffer::MemoryBarrier{
                .srcStageMask = VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                .srcAccessMask = VK_ACCESS_2_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                .dstStageMask = VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                .dstAccessMask = VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR,
            }});
            vkCmdResetQueryPool(commandBuffer->vkCommandBuffer(), chunkBuildDataBatch->compactionQueryPool, 0, count);
            vkCmdWriteAccelerationStructuresPropertiesKHR(
                commandBuffer->vkCommandBuffer(), count, handles.data(),
                VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR, chunkBuildDataBatch->compactionQueryPool, 0);
        }
        commandBuffer->end();

        VkSubmitInfo vkSubmitInfo = {};
        vkSubmitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        vkSubmitInfo.commandBufferCount = 1;
        vkSubmitInfo.pCommandBuffers = &commandBuffer->vkCommandBuffer();

        if (useSecondaryQueue_) {
            vkQueueSubmit(device->secondaryQueue(), 1, &vkSubmitInfo, fence->vkFence());
        } else {
            vkQueueSubmit(device->mainVkQueue(), 1, &vkSubmitInfo, fence->vkFence());
        }

        std::unique_lock<std::recursive_mutex> lock(mutex_);
        buildingFences_.push_back(fence);
        buildingCommandBuffers_.push_back(commandBuffer);
        buildingBatches_.push_back(chunkBuildDataBatch);
    }
}

uint32_t ChunkBuildScheduler::chunkBuildingBatchSize() {
    return chunkBuildingBatchSize_;
}

uint32_t ChunkBuildScheduler::chunkBuildingTotalBatches() {
    return chunkBuildingTotalBatches_;
}

float Chunk1::buildFactor(std::chrono::steady_clock::time_point currentTime, glm::vec3 cameraPos, glm::vec3 chunkPos) {
    double tDiff = std::chrono::duration<double, std::milli>(currentTime - lastUpdate).count();
    double dDiff = glm::distance(cameraPos, chunkPos);

    double tScore = 1 - exp(-tDiff / T_HALF);
    double nearScore = 1 / (1 + pow(dDiff / D_HALF, D_SENSITIVITY));
    double dScore = D_PRIORITY_FLOOR + (1.0 - D_PRIORITY_FLOOR) * nearScore;
    double score = pow(tScore, T_WEIGHT) * pow(dScore, D_WEIGHT);

    return score;
}

bool Chunk1::enqueue(std::shared_ptr<ChunkBuildData> chunkBuildData) {
    auto framework = Renderer::instance().framework();
    auto &frr = framework->frameResourceRetainer();

    lastUpdate = std::chrono::steady_clock::now();

    if (chunkBuildData->version > blasVersion) {
        blasVersion = chunkBuildData->version;
        x = chunkBuildData->x;
        y = chunkBuildData->y;
        z = chunkBuildData->z;

        frr.retain(blas);
        blas = chunkBuildData->blas;

        frr.retain(indexBufferAddresses);
        indexBufferAddresses =
            std::make_shared<std::vector<VkDeviceAddress>>(std::move(chunkBuildData->indexBufferAddresses));

        frr.retain(positionBufferAddresses);
        positionBufferAddresses =
            std::make_shared<std::vector<VkDeviceAddress>>(std::move(chunkBuildData->positionBufferAddresses));

        frr.retain(materialBufferAddresses);
        materialBufferAddresses =
            std::make_shared<std::vector<VkDeviceAddress>>(std::move(chunkBuildData->materialBufferAddresses));

        frr.retain(indexBuffer);
        indexBuffer = chunkBuildData->indexBuffer;

        frr.retain(positionBuffer);
        positionBuffer = chunkBuildData->positionBuffer;

        frr.retain(materialBuffer);
        materialBuffer = chunkBuildData->materialBuffer;

        frr.retain(lightInfos);
        lightInfos = std::make_shared<std::vector<LightInfo>>(std::move(chunkBuildData->lightInfos));

        frr.retain(lightBuffer);
        lightBuffer = chunkBuildData->lightBuffer;
        lightCount = chunkBuildData->lightCount;

        geometryCount = chunkBuildData->geometryCount;

        frr.retain(geometryGroupNames);
        geometryGroupNames = std::make_shared<std::vector<std::string>>(std::move(chunkBuildData->geometryGroupNames));
        if (occupiedFlag != nullptr) { *occupiedFlag = blas != nullptr ? 1 : 0; }
        Chunks::bumpContentVersion();
        return true;
    } else {
        frr.retain(chunkBuildData->blas);
        frr.retain(chunkBuildData->indexBuffer);
        frr.retain(chunkBuildData->positionBuffer);
        frr.retain(chunkBuildData->materialBuffer);
        frr.retain(chunkBuildData->lightBuffer);
        return false;
    }
}

void Chunk1::invalidate() {
    auto framework = Renderer::instance().framework();
    auto &frr = framework->frameResourceRetainer();

    lastUpdate = std::chrono::steady_clock::now();

    blasVersion = latestVersion++;

    frr.retain(blas);
    blas = nullptr;

    frr.retain(indexBufferAddresses);
    indexBufferAddresses = nullptr;

    frr.retain(positionBufferAddresses);
    positionBufferAddresses = nullptr;

    frr.retain(materialBufferAddresses);
    materialBufferAddresses = nullptr;

    frr.retain(indexBuffer);
    indexBuffer = nullptr;

    frr.retain(positionBuffer);
    positionBuffer = nullptr;

    frr.retain(materialBuffer);
    materialBuffer = nullptr;

    frr.retain(lightInfos);
    lightInfos = nullptr;

    frr.retain(lightBuffer);
    lightBuffer = nullptr;
    lightCount = 0;

    geometryCount = 0;

    frr.retain(geometryGroupNames);
    geometryGroupNames = nullptr;
    if (occupiedFlag != nullptr) { *occupiedFlag = 0; }
    Chunks::bumpContentVersion();
}

void Chunk1::retainResources(FrameResourceRetainer &frr) {
    frr.retain(blas);
    frr.retain(indexBufferAddresses);
    frr.retain(positionBufferAddresses);
    frr.retain(materialBufferAddresses);
    frr.retain(indexBuffer);
    frr.retain(positionBuffer);
    frr.retain(materialBuffer);
    frr.retain(lightInfos);
    frr.retain(lightBuffer);
}

void Chunk1::releaseEmissionResources(FrameResourceRetainer &frr) {
    frr.retain(lightInfos);
    lightInfos = nullptr;

    frr.retain(lightBuffer);
    lightBuffer = nullptr;

    lightCount = 0;
}

std::shared_ptr<ChunkRenderData> Chunk1::tryGetValid() {
    auto ret = ChunkRenderData::create();
    ret->x = x;
    ret->y = y;
    ret->z = z;
    ret->blas = blas;
    ret->indexBufferAddresses = indexBufferAddresses;
    ret->positionBufferAddresses = positionBufferAddresses;
    ret->materialBufferAddresses = materialBufferAddresses;
    ret->indexBuffer = indexBuffer;
    ret->positionBuffer = positionBuffer;
    ret->materialBuffer = materialBuffer;
    ret->lightInfos = lightInfos;
    ret->lightBuffer = lightBuffer;
    ret->lightCount = lightCount;
    ret->geometryCount = geometryCount;
    ret->geometryGroupNames = geometryGroupNames;

    return ret;
}

Chunks::Chunks(std::shared_ptr<Framework> framework) {
    importantBLASBuilders_ = std::make_shared<std::vector<std::shared_ptr<vk::BLASBuilder>>>();
}

void Chunks::allocateChunkPackedDataBuffers() {
    auto framework = Renderer::instance().framework();
    auto device = framework->device();
    auto vma = framework->vma();

    chunkPackedDataBuffers_.clear();
    chunkPackedDataBuffers_.resize(framework->swapchain()->imageCount());
    if (chunkPackedData_.empty()) {
        return;
    }

    const size_t chunkPackedDataBytes = chunkPackedData_.size() * sizeof(ChunkPackedData);
    for (auto &chunkPackedDataBuffer : chunkPackedDataBuffers_) {
        chunkPackedDataBuffer = vk::DeviceLocalBuffer::create(vma, device, false, chunkPackedDataBytes,
                                                              VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
    }
}

void Chunks::releaseEmissionResources() {
    auto framework = Renderer::instance().framework();
    auto &frr = framework->frameResourceRetainer();

    for (auto &chunk : chunks_) {
        if (chunk != nullptr) {
            chunk->releaseEmissionResources(frr);
        }
    }

    for (auto &chunkBuildData : chunkBuildDatas_) {
        if (chunkBuildData == nullptr) {
            continue;
        }
        chunkBuildData->lightInfos.clear();
        frr.retain(chunkBuildData->lightBuffer);
        chunkBuildData->lightBuffer = nullptr;
        chunkBuildData->lightCount = 0;
    }

    std::fill(chunkPackedData_.begin(), chunkPackedData_.end(), ChunkPackedData{});
    for (auto &chunkPackedDataBuffer : chunkPackedDataBuffers_) {
        frr.retain(chunkPackedDataBuffer);
        chunkPackedDataBuffer = nullptr;
    }
    chunkPackedDataBuffers_.clear();
}

void Chunks::reset(uint32_t numChunks,
                   uint32_t sizeX,
                   uint32_t sizeY,
                   uint32_t sizeZ,
                   int32_t bottomSectionCoord) {
    std::unique_lock<std::recursive_mutex> lock(mutex_);

    auto framework = Renderer::instance().framework();
    auto device = framework->device();

    // Rejoining a world replaces every section. Builds still running on the GPU and frames already handed to
    // Minecraft reference the old buffers and acceleration structures, so finish the builds and keep the old
    // sections alive until those frames are done.
    if (chunkBuildScheduler_ != nullptr) { chunkBuildScheduler_->waitAllBatchesFinish(); }
    vkQueueWaitIdle(device->mainVkQueue());
    vkQueueWaitIdle(device->secondaryQueue());
    auto &retainer = framework->frameResourceRetainer();
    // The old chunks point into occupied_, which is about to be replaced.
    for (auto &chunk : chunks_) {
        if (chunk != nullptr) { chunk->occupiedFlag = nullptr; }
    }
    retainer.retain(std::make_shared<std::vector<std::shared_ptr<Chunk1>>>(std::move(chunks_)));
    retainer.retain(std::make_shared<std::vector<std::shared_ptr<ChunkBuildData>>>(std::move(chunkBuildDatas_)));
    retainer.retain(
        std::make_shared<std::vector<std::shared_ptr<vk::DeviceLocalBuffer>>>(std::move(chunkPackedDataBuffers_)));
    retainer.retain(chunkBuildScheduler_);

    sizeX_ = static_cast<int32_t>(sizeX);
    sizeY_ = static_cast<int32_t>(sizeY);
    sizeZ_ = static_cast<int32_t>(sizeZ);
    bottomSectionCoord_ = bottomSectionCoord;
    chunkStorageSectionPos_ = glm::ivec3(0, bottomSectionCoord, 0);

    importantBLASBuilders_ = std::make_shared<std::vector<std::shared_ptr<vk::BLASBuilder>>>();

    chunks_.clear();
    chunks_.resize(numChunks);
    chunkPackedData_.assign(numChunks, ChunkPackedData{});
    chunkPackedDataBuffers_.clear();
    if (Renderer::options.collectChunkEmission) {
        allocateChunkPackedDataBuffers();
    }
    chunkBuildDatas_.clear();
    chunkBuildDatas_.resize(numChunks);
    queuedIndex_.clear();

    occupied_.assign(numChunks, 0);
    bumpContentVersion();
    for (int i = 0; i < numChunks; i++) {
        chunks_[i] = Chunk1::create();
        chunks_[i]->occupiedFlag = &occupied_[i];
        chunkBuildDatas_[i] = nullptr;
    }

    uint32_t chunkBuildingBatchSize = Renderer::instance().options.chunkBuildingBatchSize;
    uint32_t chunkBuildingTotalBatches = Renderer::instance().options.chunkBuildingTotalBatches;
    chunkBuildScheduler_ =
        ChunkBuildScheduler::create(queuedIndex_, chunks_, chunkBuildDatas_, mutex_, chunkPackedData_,
                                    chunkBuildingBatchSize, chunkBuildingTotalBatches);
    chunkBuildScheduler_->setCompactionSink(
        [this](std::vector<ChunkCompaction> &&compactions) { addPendingCompactions(std::move(compactions)); });
}

void Chunks::resetScheduler() {
    std::unique_lock<std::recursive_mutex> lock(mutex_);

    if (chunkBuildScheduler_ == nullptr) return;

    chunkBuildScheduler_->waitAllBatchesFinish();

    uint32_t chunkBuildingBatchSize = Renderer::instance().options.chunkBuildingBatchSize;
    uint32_t chunkBuildingTotalBatches = Renderer::instance().options.chunkBuildingTotalBatches;
    chunkBuildScheduler_ =
        ChunkBuildScheduler::create(queuedIndex_, chunks_, chunkBuildDatas_, mutex_, chunkPackedData_,
                                    chunkBuildingBatchSize, chunkBuildingTotalBatches);
    chunkBuildScheduler_->setCompactionSink(
        [this](std::vector<ChunkCompaction> &&compactions) { addPendingCompactions(std::move(compactions)); });
}

void Chunks::setCollectChunkEmission(bool collect) {
    std::unique_lock<std::recursive_mutex> lock(mutex_);

    if (chunkBuildScheduler_ != nullptr) {
        chunkBuildScheduler_->waitAllBatchesFinish();
    }

    auto textures = Renderer::instance().textures();
    auto emission = textures != nullptr ? textures->emission() : nullptr;
    for (auto &chunkBuildData : chunkBuildDatas_) {
        if (chunkBuildData == nullptr) {
            continue;
        }

        chunkBuildData->collectChunkEmission = collect;
        if (collect && emission != nullptr) {
            chunkBuildData->buildLightInfos(*emission);
        } else {
            chunkBuildData->lightInfos.clear();
            chunkBuildData->lightCount = 0;
            chunkBuildData->lightBuffer = nullptr;
        }
    }

    if (collect) {
        if (chunkPackedDataBuffers_.empty()) {
            allocateChunkPackedDataBuffers();
        }
        return;
    }

    releaseEmissionResources();
}

void Chunks::resetFrame() {
    auto context = Renderer::instance().framework()->safeAcquireCurrentContext();
    std::unique_lock<std::recursive_mutex> lock(mutex_);
    auto framework = Renderer::instance().framework();
    auto &frr = framework->frameResourceRetainer();

    frr.retain(importantBLASBuilders_);
    importantBLASBuilders_ = std::make_shared<std::vector<std::shared_ptr<vk::BLASBuilder>>>();

    if (Renderer::options.collectChunkEmission && !chunkPackedData_.empty() &&
        context->frameIndex < chunkPackedDataBuffers_.size()) {
        auto &chunkPackedDataBuffer = chunkPackedDataBuffers_[context->frameIndex];
        if (chunkPackedDataBuffer != nullptr) {
            const size_t chunkPackedDataBytes = chunkPackedData_.size() * sizeof(ChunkPackedData);
            chunkPackedDataBuffer->uploadToStagingBuffer(chunkPackedData_.data(), chunkPackedDataBytes, 0);
            Renderer::instance().buffers()->queueImportantWorldUpload(chunkPackedDataBuffer);
        }
    }
}

void Chunks::invalidateChunk(int id) {
    std::unique_lock<std::recursive_mutex> lock(mutex_);
    auto framework = Renderer::instance().framework();
    auto &frr = framework->frameResourceRetainer();

    queuedIndex_.erase(id);

    frr.retain(chunkBuildDatas_[id]);
    chunkBuildDatas_[id] = nullptr;

    chunks_[id]->invalidate();

    storeChunkPackedData(chunkPackedData_, id, chunks_[id]->x, chunks_[id]->y, chunks_[id]->z, 0, 0, 0);
}

void Chunks::relocateChunk(int id, int x, int y, int z) {
    std::unique_lock<std::recursive_mutex> lock(mutex_);
    auto framework = Renderer::instance().framework();
    auto &frr = framework->frameResourceRetainer();

    queuedIndex_.erase(id);

    frr.retain(chunkBuildDatas_[id]);
    chunkBuildDatas_[id] = nullptr;

    chunks_[id]->x = x;
    chunks_[id]->y = y;
    chunks_[id]->z = z;
    chunks_[id]->invalidate();

    storeChunkPackedData(chunkPackedData_, id, x, y, z, 0, 0, 0);
}

void Chunks::queueChunkBuild(ChunkBuildTask task) {
    uint32_t allVertexCount = 0, allIndexCount = 0;
    std::vector<World::GeometryTypes> geometryTypes;
    std::vector<std::string> geometryGroupNames;
    std::vector<std::vector<vk::VertexFormat::PBRVertex>> vertices;
    std::vector<std::vector<uint32_t>> indices;

    for (int i = 0; i < task.geometryCount; i++) {
        World::GeometryTypes geometryType = static_cast<World::GeometryTypes>(task.geometryTypes[i]);
        auto &geometryVertices = vertices.emplace_back();
        auto &geometryIndices = indices.emplace_back();

        geometryVertices.resize(task.vertexCounts[i]);
        std::memcpy(geometryVertices.data(), task.vertices[i],
                    task.vertexCounts[i] * sizeof(vk::VertexFormat::PBRVertex));

        // Whole quads only: a partial one indexes past the vertices and the build reads them anyway.
        for (int j = 0; j + 3 < task.vertexCounts[i]; j += 4) {
            geometryIndices.push_back(j + 0);
            geometryIndices.push_back(j + 1);
            geometryIndices.push_back(j + 2);
            geometryIndices.push_back(j + 2);
            geometryIndices.push_back(j + 3);
            geometryIndices.push_back(j + 0);
        }

        if (geometryVertices.empty() || geometryIndices.empty()) {
            vertices.pop_back();
            indices.pop_back();
            continue;
        }

        geometryTypes.push_back(geometryType);
        if (task.geometryGroupNames != nullptr && task.geometryGroupNames[i] != nullptr) {
            geometryGroupNames.emplace_back(task.geometryGroupNames[i]);
        } else {
            geometryGroupNames.emplace_back("default");
        }

        allVertexCount += geometryVertices.size();
        allIndexCount += geometryIndices.size();
    }

    std::unique_lock<std::recursive_mutex> lock(mutex_);

    const bool collectChunkEmission = Renderer::options.collectChunkEmission;
    std::shared_ptr<ChunkBuildData> chunkBuildData =
        ChunkBuildData::create(task.id, task.x, task.y, task.z, chunks_[task.id]->latestVersion++,
                               collectChunkEmission, allVertexCount, allIndexCount,
                               static_cast<uint32_t>(vertices.size()), std::move(geometryTypes),
                               std::move(geometryGroupNames), std::move(vertices), std::move(indices));

    if (collectChunkEmission && (Renderer::instance().textures() != nullptr)) {
        auto textures = Renderer::instance().textures();
        if (auto emission = textures->emission(); emission != nullptr) {
            chunkBuildData->buildLightInfos(*emission);
        }
    }

    if (task.isImportant) {
        auto &frr = Renderer::instance().framework()->frameResourceRetainer();
        queuedIndex_.erase(task.id);
        frr.retain(chunkBuildDatas_[task.id]);
        chunkBuildDatas_[task.id] = nullptr;

        chunkBuildData->build(false);
        if (chunkBuildData->indexBuffer != nullptr) {
            // Indices, positions and materials share one buffer; see ChunkBuildData::packGeometry.
            Renderer::instance().buffers()->queueImportantWorldUpload(chunkBuildData->indexBuffer);
        }
        if (chunkBuildData->lightBuffer != nullptr) {
            Renderer::instance().buffers()->queueImportantWorldUpload(chunkBuildData->lightBuffer);
        }
        if (chunkBuildData->blasBuilder != nullptr) { importantBLASBuilders_->push_back(chunkBuildData->blasBuilder); }

        if (!chunks_[task.id]->enqueue(chunkBuildData)) {
            return;
        }

        storeChunkPackedData(
            chunkPackedData_, chunkBuildData->id, chunkBuildData->x, chunkBuildData->y, chunkBuildData->z,
            chunkBuildData->geometryCount, chunkBuildData->lightCount,
            chunkBuildData->lightBuffer != nullptr ? chunkBuildData->lightBuffer->bufferAddress() : 0);
    } else {
        queuedIndex_.insert(task.id);
        chunkBuildDatas_[task.id] = chunkBuildData;
    }
}

bool Chunks::isChunkReady(int64_t id) {
    std::unique_lock<std::recursive_mutex> lock(mutex_);
    auto chunkRenderData = chunks_[id]->tryGetValid();
    return chunkRenderData->blas != nullptr;
}

void Chunks::close() {
    std::unique_lock<std::recursive_mutex> lock(mutex_);
    if (chunkBuildScheduler_ != nullptr) {
        chunkBuildScheduler_->waitAllBatchesFinish();
        chunkBuildScheduler_ = nullptr;
    }

    queuedIndex_.clear();
    chunkBuildDatas_.clear();
    importantBLASBuilders_ = nullptr;
    chunkPackedData_.clear();
    chunkPackedDataBuffers_.clear();
    chunks_.clear();
    sizeX_ = 0;
    sizeY_ = 0;
    sizeZ_ = 0;
    bottomSectionCoord_ = 0;
    chunkStorageSectionPos_ = glm::ivec3(0);
}

std::recursive_mutex &Chunks::mutex() {
    return mutex_;
}

namespace {
std::atomic<uint64_t> g_chunkContentVersion{0};
}
std::atomic<uint64_t> Chunks::devBytes[5] = {};

uint64_t Chunks::contentVersion() {
    return g_chunkContentVersion.load(std::memory_order_acquire);
}

void Chunks::bumpContentVersion() {
    g_chunkContentVersion.fetch_add(1, std::memory_order_acq_rel);
}

void Chunks::addPendingCompactions(std::vector<PendingCompaction> &&compactions) {
    std::unique_lock<std::recursive_mutex> lock(mutex_);
    pendingCompactions_.insert(pendingCompactions_.end(), std::make_move_iterator(compactions.begin()),
                               std::make_move_iterator(compactions.end()));
}

void Chunks::compactFinishedBlases(std::shared_ptr<vk::CommandBuffer> commandBuffer) {
    std::unique_lock<std::recursive_mutex> lock(mutex_);
    if (pendingCompactions_.empty()) return;

    auto framework = Renderer::instance().framework();
    auto device = framework->device();
    auto vma = framework->vma();
    auto &frr = framework->frameResourceRetainer();

    // Only chunks still showing the structure that was built; anything rebuilt or dropped since is skipped.
    std::vector<PendingCompaction> live;
    for (auto &pending : pendingCompactions_) {
        if (pending.chunkId >= 0 && pending.chunkId < static_cast<int64_t>(chunks_.size()) &&
            chunks_[pending.chunkId]->blas == pending.source) {
            live.push_back(std::move(pending));
        }
    }
    pendingCompactions_.clear();
    if (live.empty()) return;

    VkDeviceSize total = 0;
    for (size_t i = 0; i < live.size(); i++) {
        // Each compacted structure gets its own buffer, so replacing one chunk frees exactly its memory.
        auto buffer = vk::DeviceLocalBuffer::create(vma, device, false, live[i].compactedSize,
                                                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR |
                                                        VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                                                    0, VMA_MEMORY_USAGE_GPU_ONLY, 256);
        VkAccelerationStructureCreateInfoKHR createInfo{};
        createInfo.sType = VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR;
        createInfo.buffer = buffer->vkBuffer();
        createInfo.offset = 0;
        createInfo.size = live[i].compactedSize;
        createInfo.type = VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR;
        VkAccelerationStructureKHR handle = VK_NULL_HANDLE;
        if (vkCreateAccelerationStructureKHR(device->vkDevice(), &createInfo, nullptr, &handle) != VK_SUCCESS) {
            continue;
        }

        VkCopyAccelerationStructureInfoKHR copyInfo{};
        copyInfo.sType = VK_STRUCTURE_TYPE_COPY_ACCELERATION_STRUCTURE_INFO_KHR;
        copyInfo.src = live[i].source->blas();
        copyInfo.dst = handle;
        copyInfo.mode = VK_COPY_ACCELERATION_STRUCTURE_MODE_COMPACT_KHR;
        vkCmdCopyAccelerationStructureKHR(commandBuffer->vkCommandBuffer(), &copyInfo);

        auto &chunk = chunks_[live[i].chunkId];
        frr.retain(chunk->blas);
        chunk->blas = vk::BLAS::create(device, handle, buffer);
        total += live[i].compactedSize;
    }
    bumpContentVersion();
    devBytes[3] += total;
}

const std::vector<uint8_t> &Chunks::occupied() {
    return occupied_;
}

std::vector<std::shared_ptr<Chunk1>> &Chunks::chunks() {
    return chunks_;
}

std::shared_ptr<ChunkBuildScheduler> Chunks::chunkBuildScheduler() {
    std::unique_lock<std::recursive_mutex> lock(mutex_);
    return chunkBuildScheduler_;
}

std::vector<std::shared_ptr<vk::BLASBuilder>> &Chunks::importantBLASBuilders() {
    return *importantBLASBuilders_;
}

std::shared_ptr<vk::DeviceLocalBuffer> Chunks::chunkPackedData() {
    auto context = Renderer::instance().framework()->safeAcquireCurrentContext();
    std::unique_lock<std::recursive_mutex> lock(mutex_);
    if (context->frameIndex >= chunkPackedDataBuffers_.size()) {
        return nullptr;
    }
    return chunkPackedDataBuffers_[context->frameIndex];
}

glm::ivec4 Chunks::chunkGridInfo() {
    std::unique_lock<std::recursive_mutex> lock(mutex_);
    return glm::ivec4(sizeX_, sizeY_, sizeZ_, bottomSectionCoord_);
}

void Chunks::setChunkStorageSectionPos(glm::ivec3 sectionPos) {
    std::unique_lock<std::recursive_mutex> lock(mutex_);
    chunkStorageSectionPos_ = sectionPos;
}

glm::ivec4 Chunks::chunkStorageSectionPos() {
    std::unique_lock<std::recursive_mutex> lock(mutex_);
    return glm::ivec4(chunkStorageSectionPos_, 0);
}
