#ifndef SHARED_HPP
#define SHARED_HPP

#include "common/mapping.hpp"

#define INF_DISTANCE 65504.0
#define PI 3.14159265358979323
#define INV_PI 0.31830988618379067
#define TWO_PI 6.28318530717958648
#define INV_TWO_PI 0.15915494309189533
#define INV_4_PI 0.07957747154594766

#ifdef __cplusplus
namespace vk {
#endif
#ifdef __cplusplus
namespace VertexFormat {
#endif
    struct Triangle {
        T_VEC3 pos;
        T_VEC3 color;
    };

    struct TexturedTriangle {
        T_VEC3 pos;
        T_VEC2 uv;
    };

    struct ArrayTexturedTriangle {
        T_VEC3 pos;
        T_FLOAT metallic;
        T_VEC3 norm;
        T_FLOAT roughness;
        T_VEC2 uv;
        T_FLOAT textureLayer;
        T_FLOAT pad0;
        T_VEC3 color;
        T_FLOAT intensity;
    };

    struct PositionOnly {
        T_VEC3 position;
    };

    struct PositionTexColor {
        T_VEC3 position;
        T_VEC2 uv;
        T_UINT color;
    };

    struct PositionColor {
        T_VEC3 position;
        T_UINT color;
    };

    struct PositionColorNormal {
        T_VEC3 position;
        T_UINT color;
        T_UINT normal; // first 3 bytes
    };

    struct PositionTex {
        T_VEC3 position;
        T_VEC2 uv;
    };

    struct PositionColorTexLight {
        T_VEC3 position;
        T_UINT color;
        T_VEC2 uv0;
        T_UINT uv2;
    };

    struct PositionColorLight {
        T_VEC3 position;
        T_UINT color;
        T_UINT uv2;
    };

    struct PositionTexColorLight {
        T_VEC3 position;
        T_VEC2 uv0;
        T_UINT color;
        T_UINT uv2;
    };

    struct PositionTexColorNormal {
        T_VEC3 position;
        T_VEC2 uv0;
        T_UINT color;
        T_UINT normal; // first 3 bytes
    };

    struct PositionTexLightColor {
        T_VEC3 position;
        T_VEC2 uv0;
        T_UINT uv2;
        T_UINT color;
    };

    struct PositionColorTexLightNormal {
        T_VEC3 position;
        T_UINT color;
        T_VEC2 uv0;    // texture
        T_UINT uv2;    // lightmap
        T_UINT normal; // first 3 bytes
    };

    struct PositionColorTexOverlayLightNormal {
        T_VEC3 position;
        T_UINT color;
        T_VEC2 uv0;    // texture
        T_UINT uv1;    // overlay
        T_UINT uv2;    // lightmap
        T_UINT normal; // first 3 bytes
    };

    struct PBRVertex {
        T_VEC3 pos;
        T_UINT useNorm;

        T_VEC3 norm;
        T_UINT useColorLayer;

        T_VEC4 colorLayer;

        T_UINT useTexture;
        T_UINT useOverlay;
        T_VEC2 textureUV;

        T_IVEC2 overlayUV;
        T_UINT useGlint;
        T_UINT textureID;

        T_VEC2 glintUV;
        T_UINT glintTexture;
        T_UINT useLight;

        T_IVEC2 lightUV;
        T_UINT coordinate;
        T_FLOAT albedoEmission;

        T_VEC3 postBase;
        T_UINT alphaMode;
    };

    struct PositionVertex {
        T_VEC3 pos;
        T_UINT pad0;
    };

    struct MaterialVertex {
        T_VEC3 norm;
        T_UINT textureID;

        T_VEC4 colorLayer;

        T_VEC2 textureUV;
        T_IVEC2 overlayUV;

        T_VEC2 glintUV;
        T_UINT glintTexture;
        T_FLOAT albedoEmission;

        T_IVEC2 lightUV;
        T_UINT packedData;
        T_UINT pad0;
    };
    // What the material buffers actually hold: MaterialVertex packed into 40 bytes instead of 80. At far render
    // distances these buffers were most of the video memory in use (5.4 GB of 9 at 32 chunks). The shaders unpack
    // it back into a MaterialVertex in loadTriangleMaterial, so nothing past that point changes.
    //   normal         octahedral, 2 x snorm16 (0x80008000 = no normal)
    //   textures       textureID in the low 16 bits, glintTexture in the high 16
    //   color          colorLayer as 4 x unorm8
    //   u, v           textureUV, full floats (weather and some entity UVs run far past 1)
    //   overlay        overlayUV as 2 x int16
    //   glintUV        2 x half
    //   albedoEmission full float
    //   light          lightUV as 2 x uint16
    //   packedData     unchanged
    struct PackedMaterialVertex {
        T_UINT normal;
        T_UINT textures;
        T_UINT color;
        T_FLOAT u;
        T_FLOAT v;
        T_UINT overlay;
        T_UINT glintUV;
        T_FLOAT albedoEmission;
        T_UINT light;
        T_UINT packedData;
    };
#ifdef __cplusplus
}; // namespace VertexFormat

static_assert(sizeof(VertexFormat::PackedMaterialVertex) == 40);
static_assert(sizeof(VertexFormat::MaterialVertex) == 80);
static_assert(offsetof(VertexFormat::MaterialVertex, lightUV) == 64);
static_assert(offsetof(VertexFormat::MaterialVertex, packedData) == 72);
#endif

#ifdef __cplusplus
namespace Data {
#endif
    struct Camera {
        T_MAT4 viewMatrix;
        T_MAT4 projMatrix;
        T_MAT4 viewMatrixInv;
        T_MAT4 projMatrixInv;
        T_VEC2 jitter;
        T_VEC2 pad0;
    };

    struct DirectionalLight {
        T_VEC3 direction;  // 12 bytes
        T_FLOAT pad0;      // 4 bytes
        T_VEC3 color;      // 12 bytes
        T_FLOAT intensity; // 4 bytes
    };

    struct World {
        DirectionalLight directionalLight; // 32 bytes
        T_FLOAT time;                      // 4 bytes
        T_UINT seed;                       // 4 bytes
    };

    struct OverlayPostUBO {
        T_MAT4 projectionMat;
        T_VEC2 inSize;
        T_VEC2 outSize;
        T_VEC2 blurDir;
        T_FLOAT radius;
        T_FLOAT radiusMultiplier;
    };

    struct WorldUBO {
        T_MAT4 cameraViewMat;

        T_MAT4 cameraEffectedViewMat;

        T_MAT4 cameraProjMat;

        T_MAT4 cameraViewMatInv;

        T_MAT4 cameraEffectedViewMatInv;

        T_MAT4 cameraProjMatInv;

        T_VEC2 cameraJitter;
        T_FLOAT gameTime;
        T_UINT seed;

        T_MAT4 textureMat;

        T_UINT overlayTextureID;
        T_UINT isFirstPerson;
        T_FLOAT fogStart;
        T_FLOAT fogEnd;

        T_VEC4 fogColor;

        T_UINT fogType;
        T_UINT skyType;
        // Non-zero while some entity has the glowing effect, so the outline rays only run when they can find one.
        T_UINT hasGlowOutline;
        // Non-zero when block lights are sampled directly (the option, with Block Emission on to fill the list).
        T_UINT blockLightSampling;

        T_DVEC4 cameraPos; // w for padding
        T_IVEC4 chunkGridInfo; // x=sizeX, y=sizeY, z=sizeZ, w=bottomSectionCoord
        T_IVEC4 chunkStorageSectionPos; // xyz=BuiltChunkStorage.sectionPos

        T_UINT endSkyTextureID;
        T_UINT endPortalTextureID;
        T_UINT lightMapTextureID;
        // tan(hudFov/2) / tan(fov/2). Minecraft draws the first person hand with its own fixed field of view
        // (Camera.calculateHudFov is 70 degrees whatever the setting), so the hand keeps its size while the world
        // opens up or narrows. Primary rays that look for the hand are widened or narrowed by this to match.
        T_FLOAT handFovScale;

        // A light the player is holding (torch, lantern, glowstone...): xyz camera-relative position, w reach in
        // blocks. The colour's rgb is its radiance; w is non-zero while there is one.
        T_VEC4 heldLightPos;
        T_VEC4 heldLightColor;

        // Non-zero: where a carved (parallax) face reaches an outer edge of its block, the carved corner is
        // see-through. Zero shows the border's colour there.
        T_UINT parallaxTransparentEdges;
        // Player brightness settings, 1 = the shader pack's own values: sunlight and the daytime sky, moonlight and
        // the night sky, and the light of emissive blocks (their glow, the light they cast, a held light).
        T_FLOAT sunBrightness;
        T_FLOAT moonBrightness;
        T_FLOAT emissionBrightness;
        // Nonzero: surfaces are lit per texel, as if each pixel of the texture were a flat tile of its own (the
        // retro, blocky look of pixelated lighting). Zero lights them smoothly.
        T_UINT pixelLighting;
        // How far rays reach to find terrain, in blocks, when that is further than the usual reach: far terrain from
        // Distant Horizons lies well past it. Zero otherwise.
        T_FLOAT traceDistance;
        // How far rain has fallen since the previous frame, in blocks. The drops are a texture scrolling down static
        // sheets, so the geometry never moves; the motion vectors of rain take this instead, or the upscalers
        // accumulate the drops over several frames into long streaks.
        T_FLOAT rainFallPerFrame;
        T_UINT worldPad2;
    };

    struct SkyUBO {
        T_VEC3 baseColor;
        T_UINT skyType;

        T_VEC4 horizonColor;

        T_VEC3 sunDirection;
        T_UINT isSunRisingOrSetting;

        T_UINT isSkyDark;
        T_UINT hasBlindnessOrDarkness;
        T_UINT cameraSubmersionType;
        T_UINT moonPhase;
        T_FLOAT rainGradient;

        T_UINT sunTextureID;
        T_UINT moonTextureID;
        // How strong the night vision effect is, 0 to 1, fading out the way vanilla's does in its last ten
        // seconds. Minecraft applies it to the lightmap, which a path tracer never reads.
        T_FLOAT nightVision;

        // Where each body sits in the celestials atlas, as (u0, v0, u1, v1). Minecraft 26.3 stitches the sun and
        // the eight moon phases into one runtime atlas, so neither can be sampled as a whole texture and the moon
        // has no fixed grid to index - the phase is picked on the Java side and its rectangle handed over here.
        T_VEC4 sunUvRect;
        T_VEC4 moonUvRect;

        // Per-biome haze, blended around the camera on the Java side: rgb is the share of the extinction that
        // scatters (the haze's tint), a its extinction per block. Zero when the option is off, outside the overworld
        // sky, or with the camera out of the sky light.
        T_VEC4 biomeFog;

        // xyz: the axis the sun and moon turn around, which vanilla keeps as one edge of their square sprites.
        // w: 1 to orient the sprites that way (the "Vanilla Sun/Moon Orientation" option), 0 for a free basis.
        T_VEC4 celestialAxis;

        // rgb: how much each channel's extinction differs from biomeFog.a; a Bedrock RTX fog that absorbs more blue
        // than red turns the distance orange. (1, 1, 1) for Radiante's own grey haze.
        T_VEC4 biomeFogChroma;
        // x: height (world y) the haze is at full density below, y: height it has thinned out to nothing at. Radiante's
        // own haze does not thin out, and puts both far above the world.
        T_VEC4 biomeFogHeights;

        // Water as a medium, for the biomes at the camera: a converted Bedrock pack's where it has one, otherwise
        // worked out from the biome's vanilla water colour. rgb extinction per block (w 1 when set), and the share
        // of it that scatters.
        T_VEC4 waterExtinction;
        T_VEC4 waterAlbedo;
    };

    struct TextureMapEntry {
        T_INT specular;
        T_INT normal;
        T_INT flag;
    };

    struct TextureMapping {
        TextureMapEntry entries[4096];
    };

    struct ExposureData {
        T_INT width;
        T_INT height;
        T_INT stride;
        T_FLOAT exposure;

        T_FLOAT minL;
        T_FLOAT maxL;
        T_UINT total;
        T_UINT pad0;

        T_UINT bins[256];
    };

    struct LightMapUBO {
        T_FLOAT ambientLightFactor;
        T_FLOAT skyFactor;
        T_FLOAT blockFactor;
        T_INT useBrightLightmap;

        T_VEC3 skyLightColor;
        T_FLOAT nightVisionFactor;

        T_FLOAT darknessScale;
        T_FLOAT darkenWorldFactor;
        T_FLOAT brightnessFactor;
        T_FLOAT pad0;
    };
#ifdef __cplusplus
}; // namespace Data
#endif
#ifdef __cplusplus
}; // namespace vk
#endif

#endif
