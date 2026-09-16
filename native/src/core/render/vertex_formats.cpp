#include "common/shared.hpp"

#include "common/singleton.hpp"
#include "core/all_extern.hpp"
#include "core/vulkan/all_core_vulkan.hpp"

template <>
vk::VertexLayoutInfo &vk::Vertex::vertexLayoutInfo<vk::VertexFormat::PositionTexColor>() {
    static std::vector<VertexAttribute> attributes = {
        {VK_FORMAT_R32G32B32_SFLOAT, offsetof(VertexFormat::PositionTexColor, position)},
        {VK_FORMAT_R32G32_SFLOAT, offsetof(VertexFormat::PositionTexColor, uv)},
        {VK_FORMAT_R8G8B8A8_UNORM, offsetof(VertexFormat::PositionTexColor, color)},
    };
    static vk::VertexLayoutInfo vertexLayoutInfo = initVertexLayout<vk::VertexFormat::PositionTexColor>(attributes);
    return vertexLayoutInfo;
}

template <>
vk::VertexLayoutInfo &vk::Vertex::vertexLayoutInfo<vk::VertexFormat::PositionColor>() {
    static std::vector<VertexAttribute> attributes = {
        {VK_FORMAT_R32G32B32_SFLOAT, offsetof(VertexFormat::PositionColor, position)},
        {VK_FORMAT_R8G8B8A8_UNORM, offsetof(VertexFormat::PositionColor, color)},
    };
    static vk::VertexLayoutInfo vertexLayoutInfo = initVertexLayout<vk::VertexFormat::PositionColor>(attributes);
    return vertexLayoutInfo;
}

template <>
vk::VertexLayoutInfo &vk::Vertex::vertexLayoutInfo<vk::VertexFormat::PositionTex>() {
    static std::vector<VertexAttribute> attributes = {
        {VK_FORMAT_R32G32B32_SFLOAT, offsetof(VertexFormat::PositionTex, position)},
        {VK_FORMAT_R32G32_SFLOAT, offsetof(VertexFormat::PositionTex, uv)},
    };
    static vk::VertexLayoutInfo vertexLayoutInfo = initVertexLayout<vk::VertexFormat::PositionTex>(attributes);
    return vertexLayoutInfo;
}

template <>
vk::VertexLayoutInfo &vk::Vertex::vertexLayoutInfo<vk::VertexFormat::PositionColorTexLight>() {
    static std::vector<VertexAttribute> attributes = {
        {VK_FORMAT_R32G32B32_SFLOAT, offsetof(VertexFormat::PositionColorTexLight, position)},
        {VK_FORMAT_R8G8B8A8_UNORM, offsetof(VertexFormat::PositionColorTexLight, color)},
        {VK_FORMAT_R32G32_SFLOAT, offsetof(VertexFormat::PositionColorTexLight, uv0)},
        {VK_FORMAT_R16G16_SINT, offsetof(VertexFormat::PositionColorTexLight, uv2)},
    };
    static vk::VertexLayoutInfo vertexLayoutInfo =
        initVertexLayout<vk::VertexFormat::PositionColorTexLight>(attributes);
    return vertexLayoutInfo;
}

template <>
vk::VertexLayoutInfo &vk::Vertex::vertexLayoutInfo<vk::VertexFormat::PositionColorTexOverlayLightNormal>() {
    static std::vector<VertexAttribute> attributes = {
        {VK_FORMAT_R32G32B32_SFLOAT, offsetof(VertexFormat::PositionColorTexOverlayLightNormal, position)},
        {VK_FORMAT_R8G8B8A8_UNORM, offsetof(VertexFormat::PositionColorTexOverlayLightNormal, color)},
        {VK_FORMAT_R32G32_SFLOAT, offsetof(VertexFormat::PositionColorTexOverlayLightNormal, uv0)},
        {VK_FORMAT_R16G16_SINT, offsetof(VertexFormat::PositionColorTexOverlayLightNormal, uv1)},
        {VK_FORMAT_R16G16_SINT, offsetof(VertexFormat::PositionColorTexOverlayLightNormal, uv2)},
        {VK_FORMAT_R8G8B8A8_SNORM, offsetof(VertexFormat::PositionColorTexOverlayLightNormal, normal)},
    };
    static vk::VertexLayoutInfo vertexLayoutInfo =
        initVertexLayout<vk::VertexFormat::PositionColorTexOverlayLightNormal>(attributes);
    return vertexLayoutInfo;
}

template <>
vk::VertexLayoutInfo &vk::Vertex::vertexLayoutInfo<vk::VertexFormat::PositionColorTexLightNormal>() {
    static std::vector<VertexAttribute> attributes = {
        {VK_FORMAT_R32G32B32_SFLOAT, offsetof(VertexFormat::PositionColorTexLightNormal, position)},
        {VK_FORMAT_R8G8B8A8_UNORM, offsetof(VertexFormat::PositionColorTexLightNormal, color)},
        {VK_FORMAT_R32G32_SFLOAT, offsetof(VertexFormat::PositionColorTexLightNormal, uv0)},
        {VK_FORMAT_R16G16_SINT, offsetof(VertexFormat::PositionColorTexLightNormal, uv2)},
        {VK_FORMAT_R8G8B8A8_SNORM, offsetof(VertexFormat::PositionColorTexLightNormal, normal)},
    };
    static vk::VertexLayoutInfo vertexLayoutInfo =
        initVertexLayout<vk::VertexFormat::PositionColorTexLightNormal>(attributes);
    return vertexLayoutInfo;
}

template <>
vk::VertexLayoutInfo &vk::Vertex::vertexLayoutInfo<vk::VertexFormat::PositionTexColorLight>() {
    static std::vector<VertexAttribute> attributes = {
        {VK_FORMAT_R32G32B32_SFLOAT, offsetof(VertexFormat::PositionTexColorLight, position)},
        {VK_FORMAT_R32G32_SFLOAT, offsetof(VertexFormat::PositionTexColorLight, uv0)},
        {VK_FORMAT_R8G8B8A8_UNORM, offsetof(VertexFormat::PositionTexColorLight, color)},
        {VK_FORMAT_R16G16_SINT, offsetof(VertexFormat::PositionTexColorLight, uv2)},
    };
    static vk::VertexLayoutInfo vertexLayoutInfo =
        initVertexLayout<vk::VertexFormat::PositionTexColorLight>(attributes);
    return vertexLayoutInfo;
}

template <>
vk::VertexLayoutInfo &vk::Vertex::vertexLayoutInfo<vk::VertexFormat::PositionColorNormal>() {
    static std::vector<VertexAttribute> attributes = {
        {VK_FORMAT_R32G32B32_SFLOAT, offsetof(VertexFormat::PositionColorNormal, position)},
        {VK_FORMAT_R8G8B8A8_UNORM, offsetof(VertexFormat::PositionColorNormal, color)},
        {VK_FORMAT_R8G8B8A8_SNORM, offsetof(VertexFormat::PositionColorNormal, normal)},
    };
    static vk::VertexLayoutInfo vertexLayoutInfo =
        initVertexLayout<vk::VertexFormat::PositionColorNormal>(attributes);
    return vertexLayoutInfo;
}

template <>
vk::VertexLayoutInfo &vk::Vertex::vertexLayoutInfo<vk::VertexFormat::PositionColorLight>() {
    static std::vector<VertexAttribute> attributes = {
        {VK_FORMAT_R32G32B32_SFLOAT, offsetof(VertexFormat::PositionColorLight, position)},
        {VK_FORMAT_R8G8B8A8_UNORM, offsetof(VertexFormat::PositionColorLight, color)},
        {VK_FORMAT_R16G16_SINT, offsetof(VertexFormat::PositionColorLight, uv2)},
    };
    static vk::VertexLayoutInfo vertexLayoutInfo =
        initVertexLayout<vk::VertexFormat::PositionColorLight>(attributes);
    return vertexLayoutInfo;
}

template <>
vk::VertexLayoutInfo &vk::Vertex::vertexLayoutInfo<vk::VertexFormat::PositionTexLightColor>() {
    static std::vector<VertexAttribute> attributes = {
        {VK_FORMAT_R32G32B32_SFLOAT, offsetof(VertexFormat::PositionTexLightColor, position)},
        {VK_FORMAT_R32G32_SFLOAT, offsetof(VertexFormat::PositionTexLightColor, uv0)},
        {VK_FORMAT_R16G16_SINT, offsetof(VertexFormat::PositionTexLightColor, uv2)},
        {VK_FORMAT_R8G8B8A8_UNORM, offsetof(VertexFormat::PositionTexLightColor, color)},
    };
    static vk::VertexLayoutInfo vertexLayoutInfo =
        initVertexLayout<vk::VertexFormat::PositionTexLightColor>(attributes);
    return vertexLayoutInfo;
}

template <>
vk::VertexLayoutInfo &vk::Vertex::vertexLayoutInfo<vk::VertexFormat::PositionTexColorNormal>() {
    static std::vector<VertexAttribute> attributes = {
        {VK_FORMAT_R32G32B32_SFLOAT, offsetof(VertexFormat::PositionTexColorNormal, position)},
        {VK_FORMAT_R32G32_SFLOAT, offsetof(VertexFormat::PositionTexColorNormal, uv0)},
        {VK_FORMAT_R8G8B8A8_UNORM, offsetof(VertexFormat::PositionTexColorNormal, color)},
        {VK_FORMAT_R8G8B8A8_SNORM, offsetof(VertexFormat::PositionTexColorNormal, normal)},
    };
    static vk::VertexLayoutInfo vertexLayoutInfo =
        initVertexLayout<vk::VertexFormat::PositionTexColorNormal>(attributes);
    return vertexLayoutInfo;
}

template <>
vk::VertexLayoutInfo &vk::Vertex::vertexLayoutInfo<vk::VertexFormat::PositionOnly>() {
    static std::vector<VertexAttribute> attributes = {
        {VK_FORMAT_R32G32B32_SFLOAT, offsetof(VertexFormat::PositionOnly, position)},
    };
    static vk::VertexLayoutInfo vertexLayoutInfo = initVertexLayout<vk::VertexFormat::PositionOnly>(attributes);
    return vertexLayoutInfo;
}

template <>
vk::VertexLayoutInfo &vk::Vertex::vertexLayoutInfo<vk::VertexFormat::PBRVertex>() {
    static std::vector<VertexAttribute> attributes = {
        {VK_FORMAT_R32G32B32_SFLOAT, offsetof(VertexFormat::PBRVertex, pos)},
        {VK_FORMAT_R32_UINT, offsetof(VertexFormat::PBRVertex, useNorm)},
        {VK_FORMAT_R32G32B32_SFLOAT, offsetof(VertexFormat::PBRVertex, norm)},
        {VK_FORMAT_R32_UINT, offsetof(VertexFormat::PBRVertex, useColorLayer)},
        {VK_FORMAT_R32G32B32A32_SFLOAT, offsetof(VertexFormat::PBRVertex, colorLayer)},
        {VK_FORMAT_R32_UINT, offsetof(VertexFormat::PBRVertex, useTexture)},
        {VK_FORMAT_R32_UINT, offsetof(VertexFormat::PBRVertex, useOverlay)},
        {VK_FORMAT_R32G32_SFLOAT, offsetof(VertexFormat::PBRVertex, textureUV)},
        {VK_FORMAT_R32G32_SINT, offsetof(VertexFormat::PBRVertex, overlayUV)},
        {VK_FORMAT_R32_UINT, offsetof(VertexFormat::PBRVertex, useGlint)},
        {VK_FORMAT_R32_UINT, offsetof(VertexFormat::PBRVertex, textureID)},
        {VK_FORMAT_R32G32_SFLOAT, offsetof(VertexFormat::PBRVertex, glintUV)},
        {VK_FORMAT_R32_UINT, offsetof(VertexFormat::PBRVertex, glintTexture)},
        {VK_FORMAT_R32_UINT, offsetof(VertexFormat::PBRVertex, useLight)},
        {VK_FORMAT_R32G32_SINT, offsetof(VertexFormat::PBRVertex, lightUV)},
        {VK_FORMAT_R32_UINT, offsetof(VertexFormat::PBRVertex, coordinate)},
        {VK_FORMAT_R32G32B32A32_SFLOAT, offsetof(VertexFormat::PBRVertex, postBase)},
    };
    static vk::VertexLayoutInfo vertexLayoutInfo = initVertexLayout<vk::VertexFormat::PBRVertex>(attributes);
    return vertexLayoutInfo;
}
