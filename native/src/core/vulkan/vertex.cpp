#include "core/vulkan/vertex.hpp"

#include "common/shared.hpp"

#include <cmath>
#include <glm/gtc/packing.hpp>

uint32_t vk::Vertex::packMaterialFlags(const VertexFormat::PBRVertex &vertex) {
    uint32_t packed = 0;
    packed |= vertex.useColorLayer > 0 ? useColorLayerBit : 0u;
    packed |= vertex.useTexture > 0 ? useTextureBit : 0u;
    packed |= vertex.useOverlay > 0 ? useOverlayBit : 0u;
    packed |= vertex.useGlint > 0 ? useGlintBit : 0u;
    packed |= vertex.useNorm > 0 ? useNormBit : 0u;
    packed |= vertex.useLight > 0 ? useLightBit : 0u;
    packed |= (vertex.alphaMode & 0xFu) << alphaModeShift;
    packed |= (vertex.coordinate & 0xFu) << coordinateShift;
    packed |= (static_cast<uint32_t>(vertex.alphaMode) & alphaModeWaterFlag) != 0u ? waterSurfaceBit : 0u;
    return packed;
}

vk::VertexFormat::PositionVertex vk::Vertex::makePositionVertex(const VertexFormat::PBRVertex &vertex) {
    return {
        .pos = vertex.pos,
        .pad0 = 0,
    };
}

namespace {
uint32_t packNormal(glm::vec3 n) {
    float length = std::abs(n.x) + std::abs(n.y) + std::abs(n.z);
    if (!(length > 1e-8f)) return 0x80008000u;
    glm::vec2 e = glm::vec2(n.x, n.y) / length;
    if (n.z < 0.0f) {
        glm::vec2 signs(e.x >= 0.0f ? 1.0f : -1.0f, e.y >= 0.0f ? 1.0f : -1.0f);
        e = (1.0f - glm::abs(glm::vec2(e.y, e.x))) * signs;
    }
    return glm::packSnorm2x16(glm::clamp(e, glm::vec2(-1.0f), glm::vec2(1.0f)));
}

uint32_t packInt16Pair(int32_t x, int32_t y) {
    return (static_cast<uint32_t>(static_cast<uint16_t>(static_cast<int16_t>(glm::clamp(x, -32768, 32767))))) |
           (static_cast<uint32_t>(static_cast<uint16_t>(static_cast<int16_t>(glm::clamp(y, -32768, 32767)))) << 16);
}

uint32_t packUint16Pair(int32_t x, int32_t y) {
    return static_cast<uint32_t>(glm::clamp(x, 0, 65535)) | (static_cast<uint32_t>(glm::clamp(y, 0, 65535)) << 16);
}
} // namespace

vk::VertexFormat::PackedMaterialVertex vk::Vertex::makeMaterialVertex(const VertexFormat::PBRVertex &vertex) {
    return {
        .normal = packNormal(vertex.norm),
        .textures = (vertex.textureID & 0xFFFFu) | ((vertex.glintTexture & 0xFFFFu) << 16),
        .color = glm::packUnorm4x8(glm::clamp(vertex.colorLayer, glm::vec4(0.0f), glm::vec4(1.0f))),
        .u = vertex.textureUV.x,
        .v = vertex.textureUV.y,
        .overlay = packInt16Pair(vertex.overlayUV.x, vertex.overlayUV.y),
        .glintUV = glm::packHalf2x16(vertex.glintUV),
        .albedoEmission = vertex.albedoEmission,
        .light = packUint16Pair(vertex.lightUV.x, vertex.lightUV.y),
        .packedData = packMaterialFlags(vertex),
    };
}

std::vector<vk::VertexFormat::PositionVertex>
vk::Vertex::buildPositionVertices(const std::vector<VertexFormat::PBRVertex> &vertices) {
    std::vector<VertexFormat::PositionVertex> packedVertices;
    packedVertices.reserve(vertices.size());
    for (const auto &vertex : vertices) { packedVertices.push_back(makePositionVertex(vertex)); }
    return packedVertices;
}

std::vector<vk::VertexFormat::PackedMaterialVertex>
vk::Vertex::buildMaterialVertices(const std::vector<VertexFormat::PBRVertex> &vertices) {
    std::vector<VertexFormat::PackedMaterialVertex> packedVertices;
    packedVertices.reserve(vertices.size());
    for (const auto &vertex : vertices) { packedVertices.push_back(makeMaterialVertex(vertex)); }
    return packedVertices;
}

template <>
vk::VertexLayoutInfo &vk::Vertex::vertexLayoutInfo<vk::VertexFormat::Triangle>() {
    static std::vector<VertexAttribute> attributes = {
        {VK_FORMAT_R32G32B32_SFLOAT, offsetof(vk::VertexFormat::Triangle, pos)},
        {VK_FORMAT_R32G32B32_SFLOAT, offsetof(vk::VertexFormat::Triangle, color)},
    };

    static vk::VertexLayoutInfo vertexLayoutInfo = initVertexLayout<vk::VertexFormat::Triangle>(attributes);
    return vertexLayoutInfo;
}

template <>
vk::VertexLayoutInfo &vk::Vertex::vertexLayoutInfo<vk::VertexFormat::TexturedTriangle>() {
    static std::vector<VertexAttribute> attributes = {
        {VK_FORMAT_R32G32B32_SFLOAT, offsetof(vk::VertexFormat::TexturedTriangle, pos)},
        {VK_FORMAT_R32G32_SFLOAT, offsetof(vk::VertexFormat::TexturedTriangle, uv)},
    };
    static vk::VertexLayoutInfo vertexLayoutInfo = initVertexLayout<vk::VertexFormat::TexturedTriangle>(attributes);
    return vertexLayoutInfo;
}

template <>
vk::VertexLayoutInfo &vk::Vertex::vertexLayoutInfo<vk::VertexFormat::ArrayTexturedTriangle>() {
    static std::vector<VertexAttribute> attributes = {
        {VK_FORMAT_R32G32B32_SFLOAT, offsetof(vk::VertexFormat::ArrayTexturedTriangle, pos)},
        {VK_FORMAT_R32G32_SFLOAT, offsetof(vk::VertexFormat::ArrayTexturedTriangle, uv)},
        {VK_FORMAT_R32_SFLOAT, offsetof(vk::VertexFormat::ArrayTexturedTriangle, textureLayer)},
    };
    static vk::VertexLayoutInfo vertexLayoutInfo = initVertexLayout<vk::VertexFormat::ArrayTexturedTriangle>(attributes);
    return vertexLayoutInfo;
}
