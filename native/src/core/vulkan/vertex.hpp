#pragma once

#include "common/shared.hpp"
#include "core/all_extern.hpp"

#include <vector>

namespace vk {
struct VertexLayoutInfo {
    VkVertexInputBindingDescription bindingDescription;
    std::vector<VkVertexInputAttributeDescription> attributeDescriptions;
};

struct VertexAttribute {
    VkFormat format;
    uint32_t offset;
};

struct Vertex {
    static constexpr uint32_t useColorLayerBit = 1u << 0u;
    static constexpr uint32_t useTextureBit = 1u << 1u;
    static constexpr uint32_t useOverlayBit = 1u << 2u;
    static constexpr uint32_t useGlintBit = 1u << 3u;
    static constexpr uint32_t useNormBit = 1u << 4u;
    static constexpr uint32_t useLightBit = 1u << 5u;
    static constexpr uint32_t alphaModeShift = 8u;
    static constexpr uint32_t coordinateShift = 12u;
    // Above the four bits of the alpha mode, the Java side marks water surfaces.
    static constexpr uint32_t alphaModeWaterFlag = 0x10u;
    static constexpr uint32_t waterSurfaceBit = 1u << 17u;
    // Likewise for rain sheets, whose texture falls while the sheets stand still.
    static constexpr uint32_t alphaModeRainFlag = 0x20u;
    static constexpr uint32_t rainSurfaceBit = 1u << 18u;
    // And for what the player holds in first person, whose glow follows its own brightness setting.
    static constexpr uint32_t alphaModeHeldFlag = 0x40u;
    static constexpr uint32_t heldSurfaceBit = 1u << 19u;

    template <typename T>
    static VertexLayoutInfo initVertexLayout(std::vector<VertexAttribute> &attributes);

    template <typename T>
    static void buildVertexLayoutInfo(VertexLayoutInfo &vertexLayoutInfo, std::vector<VertexAttribute> &attributes);

    template <typename T>
    static VertexLayoutInfo &vertexLayoutInfo();

    static uint32_t packMaterialFlags(const VertexFormat::PBRVertex &vertex);
    static VertexFormat::PositionVertex makePositionVertex(const VertexFormat::PBRVertex &vertex);
    static VertexFormat::PackedMaterialVertex makeMaterialVertex(const VertexFormat::PBRVertex &vertex);
    static std::vector<VertexFormat::PositionVertex>
    buildPositionVertices(const std::vector<VertexFormat::PBRVertex> &vertices);
    static std::vector<VertexFormat::PackedMaterialVertex>
    buildMaterialVertices(const std::vector<VertexFormat::PBRVertex> &vertices);
};

template <typename T>
vk::VertexLayoutInfo vk::Vertex::initVertexLayout(std::vector<VertexAttribute> &attributes) {
    vk::VertexLayoutInfo vertexLayoutInfo{};
    buildVertexLayoutInfo<T>(vertexLayoutInfo, attributes);
    return vertexLayoutInfo;
}

template <typename T>
void Vertex::buildVertexLayoutInfo(VertexLayoutInfo &vertexLayoutInfo, std::vector<VertexAttribute> &attributes) {
    vertexLayoutInfo.bindingDescription.binding = 0;
    vertexLayoutInfo.bindingDescription.stride = sizeof(T);
    vertexLayoutInfo.bindingDescription.inputRate = VK_VERTEX_INPUT_RATE_VERTEX;

    for (uint32_t i = 0; i < attributes.size(); i++) {
        vertexLayoutInfo.attributeDescriptions.push_back({
            .location = i,
            .binding = 0,
            .format = attributes[i].format,
            .offset = attributes[i].offset,
        });
    }
}
}; // namespace vk
