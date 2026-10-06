#pragma once

#include "common/shared.hpp"
#include "common/singleton.hpp"
#include "core/all_extern.hpp"
#include "core/vulkan/all_core_vulkan.hpp"
#include <chrono>
#include <cstdint>

#include "core/render/modules/world/world_module.hpp"

class Framework;
class FrameworkContext;
class WorldPipeline;
struct WorldModuleContext;

struct ToneMappingModuleContext;

struct ToneMappingModuleExposureData {
    float exposure;
    float avgLogLum;
    float padding0;
    float padding1;
};

enum ToneMappingMethod : int32_t {
    TONE_MAPPING_METHOD_PBR_NEUTRAL = 0,
    TONE_MAPPING_METHOD_REINHARD = 1,
    TONE_MAPPING_METHOD_REINHARD_WHITE_POINT = 2,
    TONE_MAPPING_METHOD_ACES_FITTED = 3,
    TONE_MAPPING_METHOD_ACES_FITTED_WHITE_POINT = 4,
    TONE_MAPPING_METHOD_UNCHARTED2 = 5,
    // The Bedrock-style mapper: its curve is a placeholder until measured (see tone_mapping.frag bedrockToneCurve).
    TONE_MAPPING_METHOD_BEDROCK_PROVISIONAL = 6,
};

enum ToneMappingExposureMeteringMode : int32_t {
    TONE_MAPPING_EXPOSURE_METERING_MODE_GLOBAL = 0,
    TONE_MAPPING_EXPOSURE_METERING_MODE_CENTER = 1,
};

struct ToneMappingModulePushConstant {
    float log2Min;
    float log2Max;
    float epsilon;
    float lowPercent;
    float highPercent;
    float middleGrey;
    float dt;
    float speedUp;
    float speedDown;
    float minExposure;
    float maxExposure;
    float manualExposure;
    float exposureBias;
    float whitePoint;
    float saturation;
    int toneMappingMethod;
    int autoExposure;
    int clampOutput;
    int exposureMeteringMode;
    float centerMeteringPercent;
    float adaptation;
    // Bedrock-style grade and bloom; neutral values change nothing. Must match tone_mapping.frag.
    float bloomIntensity;
    float contrast;
    float shadowContrast;
    float shadowContrastEnd;
    float gamma;
    float balanceR;
    float balanceG;
    float balanceB;
};
static_assert(sizeof(ToneMappingModulePushConstant) <= 128, "push constants must fit the guaranteed 128 bytes");

// Push constants of one bloom pass (bloom_down.frag / bloom_up.frag).
struct ToneMappingBloomPushConstant {
    float srcTexelX;
    float srcTexelY;
    int32_t firstPass;
    int32_t autoExposure;
    float manualExposure;
    float exposureBias;
    float threshold;
    float scatter;
    float weight;
    float padding0;
};

class ToneMappingModule : public WorldModule, public SharedObject<ToneMappingModule> {
    friend ToneMappingModuleContext;

  public:
    constexpr static std::string_view NAME = "render_pipeline.module.tone_mapping.name";
    constexpr static uint32_t inputImageNum = 1;
    constexpr static uint32_t outputImageNum = 1;

    ToneMappingModule();

    void init(std::shared_ptr<Framework> framework, std::shared_ptr<WorldPipeline> worldPipeline);

    bool setOrCreateInputImages(std::vector<std::shared_ptr<vk::DeviceLocalImage>> &images,
                                std::vector<VkFormat> &formats,
                                uint32_t frameIndex) override;
    bool setOrCreateOutputImages(std::vector<std::shared_ptr<vk::DeviceLocalImage>> &images,
                                 std::vector<VkFormat> &formats,
                                 uint32_t frameIndex) override;

    void setAttributes(int attributeCount, std::vector<std::string> &attributeKVs) override;

    void build() override;

    std::vector<std::shared_ptr<WorldModuleContext>> &contexts() override;

    void
    bindTexture(std::shared_ptr<vk::Sampler> sampler, std::shared_ptr<vk::DeviceLocalImage> image, int index) override;

    void preClose() override;

    // What the HDR display output (HdrOutput) needs to tone map the same radiance the same way, brighter.
    std::shared_ptr<vk::DeviceLocalBuffer> exposureBuffer() const {
        return exposureData_;
    }
    float exposureBias() const {
        return exposureBias_;
    }
    float saturation() const {
        return saturation_;
    }
    float manualExposure() const {
        return manualExposure_;
    }
    bool isAutoExposureEnabled() const {
        return isAutoExposureEnabled_;
    }

  private:
    static constexpr uint32_t histSize = 256;

    void initDescriptorTables();
    void initImages();
    void initBuffers();
    void initRenderPass();
    void initFrameBuffers();
    void initPipeline();
    void initBloom();
    void recordBloom(std::shared_ptr<vk::CommandBuffer> commandBuffer, uint32_t frameIndex, uint32_t queueFamily);

  public:
    // Bedrock's bloom runs one uniform downscale, four Gaussian downscales and four upscales (the pass names and
    // their repeat counts, see docs/bedrock-rtx-compat/BLOOM_AND_TONEMAPPING.md). Levels below are the downscaled
    // images (half, 1/4, 1/8, 1/16, 1/32 of the HDR image) and the combined upscaled ones (1/16 .. 1/2).
    static constexpr uint32_t BLOOM_DOWN_LEVELS = 5;
    static constexpr uint32_t BLOOM_UP_LEVELS = 4;
    static constexpr uint32_t BLOOM_PASSES = BLOOM_DOWN_LEVELS + BLOOM_UP_LEVELS;

  private:
    // input
    std::vector<std::shared_ptr<vk::DeviceLocalImage>> hdrImages_;

    // tone mapping
    std::vector<std::shared_ptr<vk::DescriptorTable>> descriptorTables_;

    std::vector<std::shared_ptr<vk::DeviceLocalBuffer>> histBuffers_;
    std::shared_ptr<vk::DeviceLocalBuffer> exposureData_;

    std::shared_ptr<vk::Shader> histShader_;
    std::shared_ptr<vk::ComputePipeline> histPipeline_;

    std::shared_ptr<vk::Shader> exposureShader_;
    std::shared_ptr<vk::ComputePipeline> exposurePipeline_;

    std::shared_ptr<vk::Shader> vertShader_;
    std::shared_ptr<vk::Shader> fragShader_;
    std::shared_ptr<vk::RenderPass> renderPass_;
    std::vector<std::shared_ptr<vk::Framebuffer>> framebuffers_;
    std::shared_ptr<vk::GraphicsPipeline> pipeline_;
    std::vector<std::shared_ptr<vk::Sampler>> samplers_;

    float middleGrey_ = 0.18f;
    float speedUp_ = 3.0f;
    float speedDown_ = 3.0f;

    float log2Min_ = -12.0f;
    float log2Max_ = 4.0f;
    float epsilon_ = 1e-6f;
    float lowPercent_ = 0.005f;
    float highPercent_ = 0.99f;
    float minExposure_ = 1e-4f;
    float maxExposure_ = 1.2f;

    float manualExposure_ = 1.0f;
    float exposureBias_ = 0.0f;
    float whitePoint_ = 11.2f;
    float saturation_ = 1.0f;

    // Bloom (off by default: the Bedrock RTX profile switches it on). Values are placeholders, see the docs.
    bool bloomEnabled_ = false;
    float bloomIntensity_ = 0.04f;
    float bloomScatter_ = 0.5f;
    float bloomThreshold_ = 0.0f;
    float bloomWeights_[BLOOM_UP_LEVELS] = {1.0f, 1.0f, 1.0f, 1.0f};

    // Bedrock-style grade, neutral by default.
    float contrast_ = 1.0f;
    float shadowContrast_ = 0.0f;
    float shadowContrastEnd_ = 0.1f;
    float gamma_ = 2.2f;
    float colorBalance_[3] = {1.0f, 1.0f, 1.0f};

    // Bloom resources, per frame in flight. downImages_[frame][level], upImages_[frame][level].
    std::shared_ptr<vk::RenderPass> bloomRenderPass_;
    std::shared_ptr<vk::Shader> bloomDownShader_;
    std::shared_ptr<vk::Shader> bloomUpShader_;
    std::shared_ptr<vk::Sampler> bloomSampler_;
    std::vector<std::vector<std::shared_ptr<vk::DeviceLocalImage>>> bloomDownImages_;
    std::vector<std::vector<std::shared_ptr<vk::DeviceLocalImage>>> bloomUpImages_;
    // [frame][pass], pass order: down 0..4, then up 3..0.
    std::vector<std::vector<std::shared_ptr<vk::DescriptorTable>>> bloomTables_;
    std::vector<std::vector<std::shared_ptr<vk::Framebuffer>>> bloomFramebuffers_;
    // [pass]: one pipeline per output size, since a pipeline bakes its viewport.
    std::vector<std::shared_ptr<vk::GraphicsPipeline>> bloomPipelines_;

    int toneMappingMethod_ = TONE_MAPPING_METHOD_ACES_FITTED;
    bool isAutoExposureEnabled_ = true;
    bool shouldClampOutput_ = true;
    int exposureMeteringMode_ = TONE_MAPPING_EXPOSURE_METERING_MODE_GLOBAL;
    float centerMeteringPercent_ = 20.0f;
    // How far the exposure follows the scene: 1 all the way, 0 not at all (held at manualExposure_).
    float adaptation_ = 1.0f;

    // output
    std::vector<std::shared_ptr<vk::DeviceLocalImage>> ldrImages_;

    std::vector<std::shared_ptr<WorldModuleContext>> contexts_;

    std::chrono::time_point<std::chrono::high_resolution_clock> lastTimePoint_;

    uint32_t width_ = 0, height_ = 0;
};

struct ToneMappingModuleContext : public WorldModuleContext, SharedObject<ToneMappingModuleContext> {
    std::weak_ptr<ToneMappingModule> toneMappingModule;

    // input
    std::shared_ptr<vk::DeviceLocalImage> hdrImage;

    // tone mapping
    std::shared_ptr<vk::DescriptorTable> descriptorTable;
    std::shared_ptr<vk::Framebuffer> framebuffer;
    std::shared_ptr<vk::DeviceLocalBuffer> histBuffer;

    // output
    std::shared_ptr<vk::DeviceLocalImage> ldrImage;

    ToneMappingModuleContext(std::shared_ptr<FrameworkContext> frameworkContext,
                             std::shared_ptr<WorldPipelineContext> worldPipelineContext,
                             std::shared_ptr<ToneMappingModule> toneMappingModule);

    void render() override;
};
