#include "core/render/modules/world/tone_mapping/tone_mapping_module.hpp"

#include "core/render/pipeline.hpp"
#include "core/render/render_framework.hpp"
#include "core/render/renderer.hpp"

#include <algorithm>
#include <cctype>
#include <cmath>

namespace {

bool tryParseFloat(const std::string &text, float &outValue) {
    try {
        outValue = std::stof(text);
        return true;
    } catch (...) { return false; }
}

bool tryParseInt(const std::string &text, int &outValue) {
    try {
        outValue = std::stoi(text);
        return true;
    } catch (...) { return false; }
}

bool parseBoolValue(const std::string &value, bool fallback) {
    if (value == "render_pipeline.true")
        return true;
    else if (value == "render_pipeline.false")
        return false;
    else
        return fallback;
}

int parseToneMappingMethodValue(const std::string &value, int fallback) {
    if (value == "render_pipeline.module.tone_mapping.attribute.method.pbr_neutral") return TONE_MAPPING_METHOD_PBR_NEUTRAL;
    if (value == "render_pipeline.module.tone_mapping.attribute.method.reinhard") return TONE_MAPPING_METHOD_REINHARD;
    if (value == "render_pipeline.module.tone_mapping.attribute.method.reinhard_white_point") return TONE_MAPPING_METHOD_REINHARD_WHITE_POINT;
    if (value == "render_pipeline.module.tone_mapping.attribute.method.aces") return TONE_MAPPING_METHOD_ACES_FITTED;
    if (value == "render_pipeline.module.tone_mapping.attribute.method.aces_white_point")
        return TONE_MAPPING_METHOD_ACES_FITTED_WHITE_POINT;
    if (value == "render_pipeline.module.tone_mapping.attribute.method.uncharted2") return TONE_MAPPING_METHOD_UNCHARTED2;
    if (value == "render_pipeline.module.tone_mapping.attribute.method.bedrock_provisional")
        return TONE_MAPPING_METHOD_BEDROCK_PROVISIONAL;
    return fallback;
}

int parseExposureMeteringModeValue(const std::string &value, int fallback) {
    if (value == "render_pipeline.module.tone_mapping.attribute.exposure_metering_mode.global")
        return TONE_MAPPING_EXPOSURE_METERING_MODE_GLOBAL;
    if (value == "render_pipeline.module.tone_mapping.attribute.exposure_metering_mode.center")
        return TONE_MAPPING_EXPOSURE_METERING_MODE_CENTER;
    if (value == "render_pipeline.module.tone_mapping.attribute.exposure_metering_mode.light_meter")
        return TONE_MAPPING_EXPOSURE_METERING_MODE_LIGHT_METER;
    return fallback;
}

} // namespace

ToneMappingModule::ToneMappingModule() {}

void ToneMappingModule::init(std::shared_ptr<Framework> framework, std::shared_ptr<WorldPipeline> worldPipeline) {
    WorldModule::init(framework, worldPipeline);

    uint32_t size = framework->swapchain()->imageCount();

    hdrImages_.resize(size);
    ldrImages_.resize(size);
}

bool ToneMappingModule::setOrCreateInputImages(std::vector<std::shared_ptr<vk::DeviceLocalImage>> &images,
                                               std::vector<VkFormat> &formats,
                                               uint32_t frameIndex) {
    if (images.size() == 0) return false;

    auto framework = framework_.lock();
    if (images[0] == nullptr) {
        hdrImages_[frameIndex] = images[0] = vk::DeviceLocalImage::create(
            framework->device(), framework->vma(), false, width_, height_, 1, formats[0],
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
    } else {
        if (images[0]->width() != width_ || images[0]->height() != height_) return false;
        hdrImages_[frameIndex] = images[0];
    }

    return true;
}

bool ToneMappingModule::setOrCreateOutputImages(std::vector<std::shared_ptr<vk::DeviceLocalImage>> &images,
                                                std::vector<VkFormat> &formats,
                                                uint32_t frameIndex) {
    if (images.size() == 0 || images[0] == nullptr) return false;

    width_ = images[0]->width();
    height_ = images[0]->height();

    ldrImages_[frameIndex] = images[0];

    return true;
}

void ToneMappingModule::setAttributes(int attributeCount, std::vector<std::string> &attributeKVs) {
    for (int i = 0; i < attributeCount; i++) {
        const std::string &key = attributeKVs[2 * i];
        const std::string &value = attributeKVs[2 * i + 1];

        float floatValue = 0.0f;
        if (key == "render_pipeline.module.tone_mapping.attribute.middle_grey") {
            if (tryParseFloat(value, floatValue)) middleGrey_ = std::max(floatValue, 1e-4f);
        } else if (key == "render_pipeline.module.tone_mapping.attribute.exposure_up_speed") {
            if (tryParseFloat(value, floatValue)) speedUp_ = std::max(floatValue, 0.0f);
        } else if (key == "render_pipeline.module.tone_mapping.attribute.exposure_down_speed") {
            if (tryParseFloat(value, floatValue)) speedDown_ = std::max(floatValue, 0.0f);
        } else if (key == "render_pipeline.module.tone_mapping.attribute.log2_luminance_min") {
            if (tryParseFloat(value, floatValue)) log2Min_ = floatValue;
        } else if (key == "render_pipeline.module.tone_mapping.attribute.log2_luminance_max") {
            if (tryParseFloat(value, floatValue)) log2Max_ = floatValue;
        } else if (key == "render_pipeline.module.tone_mapping.attribute.histogram_epsilon") {
            if (tryParseFloat(value, floatValue)) epsilon_ = std::max(floatValue, 1e-8f);
        } else if (key == "render_pipeline.module.tone_mapping.attribute.low_percent") {
            if (tryParseFloat(value, floatValue)) lowPercent_ = floatValue;
        } else if (key == "render_pipeline.module.tone_mapping.attribute.high_percent") {
            if (tryParseFloat(value, floatValue)) highPercent_ = floatValue;
        } else if (key == "render_pipeline.module.tone_mapping.attribute.min_exposure") {
            if (tryParseFloat(value, floatValue)) minExposure_ = std::max(floatValue, 1e-6f);
        } else if (key == "render_pipeline.module.tone_mapping.attribute.max_exposure") {
            if (tryParseFloat(value, floatValue)) maxExposure_ = std::max(floatValue, 1e-6f);
        } else if (key == "render_pipeline.module.tone_mapping.attribute.enable_auto_exposure") {
            isAutoExposureEnabled_ = parseBoolValue(value, isAutoExposureEnabled_);
        } else if (key == "render_pipeline.module.tone_mapping.attribute.manual_exposure") {
            if (tryParseFloat(value, floatValue)) manualExposure_ = std::max(floatValue, 1e-6f);
        } else if (key == "render_pipeline.module.tone_mapping.attribute.exposure_bias") {
            if (tryParseFloat(value, floatValue)) exposureBias_ = floatValue;
        } else if (key == "render_pipeline.module.tone_mapping.attribute.white_point") {
            if (tryParseFloat(value, floatValue)) whitePoint_ = std::max(floatValue, 1e-3f);
        } else if (key == "render_pipeline.module.tone_mapping.attribute.saturation") {
            if (tryParseFloat(value, floatValue)) saturation_ = std::max(floatValue, 0.0f);
        } else if (key == "render_pipeline.module.tone_mapping.attribute.clamp_output") {
            shouldClampOutput_ = parseBoolValue(value, shouldClampOutput_);
        } else if (key == "render_pipeline.module.tone_mapping.attribute.method") {
            toneMappingMethod_ = parseToneMappingMethodValue(value, toneMappingMethod_);
        } else if (key == "render_pipeline.module.tone_mapping.attribute.exposure_metering_mode") {
            exposureMeteringMode_ = parseExposureMeteringModeValue(value, exposureMeteringMode_);
        } else if (key == "render_pipeline.module.tone_mapping.attribute.center_metering_percent") {
            if (tryParseFloat(value, floatValue)) centerMeteringPercent_ = std::clamp(floatValue, 1.0f, 100.0f);
        } else if (key == "render_pipeline.module.tone_mapping.attribute.exposure_adaptation") {
            if (tryParseFloat(value, floatValue)) adaptation_ = std::clamp(floatValue, 0.0f, 1.0f);
        } else if (key == "render_pipeline.module.tone_mapping.attribute.bloom_enable") {
            bloomEnabled_ = parseBoolValue(value, bloomEnabled_);
        } else if (key == "render_pipeline.module.tone_mapping.attribute.bloom_intensity") {
            if (tryParseFloat(value, floatValue)) bloomIntensity_ = std::clamp(floatValue, 0.0f, 4.0f);
        } else if (key == "render_pipeline.module.tone_mapping.attribute.bloom_scatter") {
            if (tryParseFloat(value, floatValue)) bloomScatter_ = std::clamp(floatValue, 0.0f, 1.0f);
        } else if (key == "render_pipeline.module.tone_mapping.attribute.bloom_threshold") {
            if (tryParseFloat(value, floatValue)) bloomThreshold_ = std::clamp(floatValue, 0.0f, 64.0f);
        } else if (key == "render_pipeline.module.tone_mapping.attribute.bloom_weight_1") {
            if (tryParseFloat(value, floatValue)) bloomWeights_[0] = std::clamp(floatValue, 0.0f, 4.0f);
        } else if (key == "render_pipeline.module.tone_mapping.attribute.bloom_weight_2") {
            if (tryParseFloat(value, floatValue)) bloomWeights_[1] = std::clamp(floatValue, 0.0f, 4.0f);
        } else if (key == "render_pipeline.module.tone_mapping.attribute.bloom_weight_3") {
            if (tryParseFloat(value, floatValue)) bloomWeights_[2] = std::clamp(floatValue, 0.0f, 4.0f);
        } else if (key == "render_pipeline.module.tone_mapping.attribute.bloom_weight_4") {
            if (tryParseFloat(value, floatValue)) bloomWeights_[3] = std::clamp(floatValue, 0.0f, 4.0f);
        } else if (key == "render_pipeline.module.tone_mapping.attribute.contrast") {
            if (tryParseFloat(value, floatValue)) contrast_ = std::clamp(floatValue, 0.25f, 4.0f);
        } else if (key == "render_pipeline.module.tone_mapping.attribute.shadow_contrast") {
            if (tryParseFloat(value, floatValue)) shadowContrast_ = std::clamp(floatValue, -2.0f, 2.0f);
        } else if (key == "render_pipeline.module.tone_mapping.attribute.shadow_contrast_end") {
            if (tryParseFloat(value, floatValue)) shadowContrastEnd_ = std::clamp(floatValue, 1e-3f, 4.0f);
        } else if (key == "render_pipeline.module.tone_mapping.attribute.gamma") {
            if (tryParseFloat(value, floatValue)) gamma_ = std::clamp(floatValue, 0.5f, 3.0f);
        } else if (key == "render_pipeline.module.tone_mapping.attribute.curve_dynamic_range") {
            if (tryParseFloat(value, floatValue)) curveDynamicRange_ = std::clamp(floatValue, 2.6f, 20.0f);
        } else if (key == "render_pipeline.module.tone_mapping.attribute.curve_shift") {
            if (tryParseFloat(value, floatValue)) curveShift_ = std::clamp(floatValue, -4.0f, 4.0f);
        } else if (key == "render_pipeline.module.tone_mapping.attribute.curve_max_exposure_increase") {
            if (tryParseFloat(value, floatValue)) curveMaxExposureIncrease_ = std::clamp(floatValue, 0.0f, 16.0f);
        } else if (key == "render_pipeline.module.tone_mapping.attribute.curve_shadow_min_slope") {
            if (tryParseFloat(value, floatValue)) curveShadowMinSlope_ = std::clamp(floatValue, 0.0f, 1.0f);
        } else if (key == "render_pipeline.module.tone_mapping.attribute.filmic_saturation") {
            if (tryParseFloat(value, floatValue)) filmicSaturation_ = std::clamp(floatValue, 0.0f, 2.0f);
        } else if (key == "render_pipeline.module.tone_mapping.attribute.color_balance_r") {
            if (tryParseFloat(value, floatValue)) colorBalance_[0] = std::clamp(floatValue, 0.0f, 4.0f);
        } else if (key == "render_pipeline.module.tone_mapping.attribute.color_balance_g") {
            if (tryParseFloat(value, floatValue)) colorBalance_[1] = std::clamp(floatValue, 0.0f, 4.0f);
        } else if (key == "render_pipeline.module.tone_mapping.attribute.color_balance_b") {
            if (tryParseFloat(value, floatValue)) colorBalance_[2] = std::clamp(floatValue, 0.0f, 4.0f);
        }
    }
}

void ToneMappingModule::build() {
    auto framework = framework_.lock();
    auto worldPipeline = worldPipeline_.lock();
    uint32_t size = framework->swapchain()->imageCount();

    initDescriptorTables();
    initImages();
    initBuffers();
    initRenderPass();
    initFrameBuffers();
    initPipeline();
    initBloom();

    contexts_.resize(size);

    for (int i = 0; i < size; i++) {
        contexts_[i] = ToneMappingModuleContext::create(framework->contexts()[i], worldPipeline->contexts()[i],
                                                        shared_from_this());
    }

    lastTimePoint_ = std::chrono::high_resolution_clock::now();
}

std::vector<std::shared_ptr<WorldModuleContext>> &ToneMappingModule::contexts() {
    return contexts_;
}

void ToneMappingModule::bindTexture(std::shared_ptr<vk::Sampler> sampler,
                                    std::shared_ptr<vk::DeviceLocalImage> image,
                                    int index) {}

void ToneMappingModule::preClose() {}

void ToneMappingModule::initDescriptorTables() {
    auto framework = framework_.lock();
    uint32_t size = framework->swapchain()->imageCount();

    descriptorTables_.resize(size);
    samplers_.resize(size);

    for (int i = 0; i < size; i++) {
        descriptorTables_[i] = vk::DescriptorTableBuilder{}
                                   .beginDescriptorLayoutSet() // set 0
                                   .beginDescriptorLayoutSetBinding()
                                   .defineDescriptorLayoutSetBinding({
                                       .binding = 0,
                                       .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                                       .descriptorCount = 1,
                                       .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT | VK_SHADER_STAGE_COMPUTE_BIT,
                                   })
                                   .defineDescriptorLayoutSetBinding({
                                       .binding = 1,
                                       .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                                       .descriptorCount = 1,
                                       .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT | VK_SHADER_STAGE_COMPUTE_BIT,
                                   })
                                   .defineDescriptorLayoutSetBinding({
                                       .binding = 2,
                                       .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                                       .descriptorCount = 1,
                                       .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT | VK_SHADER_STAGE_COMPUTE_BIT,
                                   })
                                   .defineDescriptorLayoutSetBinding({
                                       .binding = 3, // the bloom pyramid's result
                                       .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                                       .descriptorCount = 1,
                                       .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT,
                                   })
                                   .defineDescriptorLayoutSetBinding({
                                       .binding = 4, // the Bedrock-style histogram and tone curve
                                       .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                                       .descriptorCount = 1,
                                       .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT | VK_SHADER_STAGE_COMPUTE_BIT,
                                   })
                                   .endDescriptorLayoutSetBinding()
                                   .endDescriptorLayoutSet()
                                   .definePushConstant(VkPushConstantRange{
                                       .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                                       .offset = 0,
                                       .size = sizeof(ToneMappingModulePushConstant),
                                   })
                                   .build(framework->device());

        samplers_[i] = vk::Sampler::create(framework->device(), VK_FILTER_LINEAR, VK_SAMPLER_MIPMAP_MODE_LINEAR,
                                           VK_SAMPLER_ADDRESS_MODE_REPEAT);
    }
}

void ToneMappingModule::initImages() {
    auto framework = framework_.lock();
    uint32_t size = framework->swapchain()->imageCount();

    for (int i = 0; i < size; i++) {
        descriptorTables_[i]->bindSamplerImageForShader(samplers_[i], hdrImages_[i], 0, 0);
    }
}

void ToneMappingModule::initBuffers() {
    auto framework = framework_.lock();
    auto vma = framework->vma();
    auto device = framework->device();
    uint32_t size = framework->swapchain()->imageCount();

    histBuffers_.resize(size);

    exposureData_ =
        vk::DeviceLocalBuffer::create(vma, device, sizeof(ToneMappingModuleExposureData),
                                      VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);

    bedrockCurveData_ = vk::DeviceLocalBuffer::create(vma, device, 2 * histSize * sizeof(uint32_t),
                                                      VK_BUFFER_USAGE_TRANSFER_DST_BIT |
                                                          VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
    bedrockCurveNeedsReset_ = true;

    for (int i = 0; i < size; i++) {
        descriptorTables_[i]->bindBuffer(bedrockCurveData_, 0, 4);
        histBuffers_[i] =
            vk::DeviceLocalBuffer::create(vma, device, histSize * sizeof(uint32_t),
                                          VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        descriptorTables_[i]->bindBuffer(histBuffers_[i], 0, 1);

        descriptorTables_[i]->bindBuffer(exposureData_, 0, 2);
    }
}

void ToneMappingModule::initRenderPass() {
    renderPass_ = vk::RenderPassBuilder{}
                      .beginAttachmentDescription()
                      .defineAttachmentDescription({
                          // color
                          .format = ldrImages_[0]->vkFormat(),
                          .samples = VK_SAMPLE_COUNT_1_BIT,
                          .loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR,
                          .storeOp = VK_ATTACHMENT_STORE_OP_STORE,
                          .stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE,
                          .stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE,
#ifdef USE_AMD
                          .initialLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                          .finalLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
#else
                          .initialLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                          .finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
#endif
                      })
                      .endAttachmentDescription()
                      .beginAttachmentReference()
                      .defineAttachmentReference({
                          .attachment = 0,
                          .layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                      })
                      .endAttachmentReference()
                      .beginSubpassDescription()
                      .defineSubpassDescription({
                          .pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS,
                          .colorAttachmentIndices = {0},
                      })
                      .endSubpassDescription()
                      .build(framework_.lock()->device());
}

void ToneMappingModule::initFrameBuffers() {
    auto framework = framework_.lock();
    uint32_t size = framework->swapchain()->imageCount();

    framebuffers_.resize(size);

    for (int i = 0; i < size; i++) {
        framebuffers_[i] = vk::FramebufferBuilder{}
                               .beginAttachment()
                               .defineAttachment(ldrImages_[i])
                               .endAttachment()
                               .build(framework->device(), renderPass_);
    }
}

void ToneMappingModule::initPipeline() {
    auto framework = framework_.lock();
    auto device = framework->device();
    std::filesystem::path shaderPath = Renderer::folderPath / "shaders";

    histShader_ = vk::Shader::create(framework->device(), (shaderPath / "world/tone_mapping/hist_comp.spv").string());
    histPipeline_ =
        vk::ComputePipelineBuilder{}.defineShader(histShader_).definePipelineLayout(descriptorTables_[0]).build(device);

    exposureShader_ =
        vk::Shader::create(framework->device(), (shaderPath / "world/tone_mapping/exposure_comp.spv").string());
    exposurePipeline_ = vk::ComputePipelineBuilder{}
                            .defineShader(exposureShader_)
                            .definePipelineLayout(descriptorTables_[0])
                            .build(device);

    bedrockHistShader_ =
        vk::Shader::create(framework->device(), (shaderPath / "world/tone_mapping/bedrock_hist_comp.spv").string());
    bedrockHistPipeline_ = vk::ComputePipelineBuilder{}
                               .defineShader(bedrockHistShader_)
                               .definePipelineLayout(descriptorTables_[0])
                               .build(device);
    bedrockCurveShader_ =
        vk::Shader::create(framework->device(), (shaderPath / "world/tone_mapping/bedrock_curve_comp.spv").string());
    bedrockCurvePipeline_ = vk::ComputePipelineBuilder{}
                                .defineShader(bedrockCurveShader_)
                                .definePipelineLayout(descriptorTables_[0])
                                .build(device);

    vertShader_ =
        vk::Shader::create(framework->device(), (shaderPath / "world/tone_mapping/tone_mapping_vert.spv").string());
    fragShader_ =
        vk::Shader::create(framework->device(), (shaderPath / "world/tone_mapping/tone_mapping_frag.spv").string());

    pipeline_ = vk::GraphicsPipelineBuilder{}
                    .defineRenderPass(renderPass_, 0)
                    .beginShaderStage()
                    .defineShaderStage(vertShader_, VK_SHADER_STAGE_VERTEX_BIT)
                    .defineShaderStage(fragShader_, VK_SHADER_STAGE_FRAGMENT_BIT)
                    .endShaderStage()
                    .defineVertexInputState<void>()
                    .defineViewportScissorState({
                        .viewport =
                            {
                                .x = 0,
                                .y = 0,
                                .width = static_cast<float>(framework->swapchain()->vkExtent().width),
                                .height = static_cast<float>(framework->swapchain()->vkExtent().height),
                                .minDepth = 0.0,
                                .maxDepth = 1.0,
                            },
                        .scissor =
                            {
                                .offset = {.x = 0, .y = 0},
                                .extent = framework->swapchain()->vkExtent(),
                            },
                    })
                    .defineDepthStencilState({
                        .depthTestEnable = VK_FALSE,
                        .depthWriteEnable = VK_FALSE,
                        .depthCompareOp = VK_COMPARE_OP_ALWAYS,
                        .depthBoundsTestEnable = VK_FALSE,
                        .stencilTestEnable = VK_FALSE,
                    })
                    .beginColorBlendAttachmentState()
                    .defineDefaultColorBlendAttachmentState() // color
                    .endColorBlendAttachmentState()
                    .definePipelineLayout(descriptorTables_[0])
                    .build(device);
}

// Bloom: Bedrock's pass layout; every filter shape and weight is a placeholder.
void ToneMappingModule::initBloom() {
    auto framework = framework_.lock();
    auto device = framework->device();
    auto vma = framework->vma();
    uint32_t frames = framework->swapchain()->imageCount();

    if (!bloomEnabled_) {
        // Nothing is rendered; the slot still has to point at a valid image, and the intensity pushed is 0. The
        // tone mapping sampler does for that, so bloom off creates no sampler of its own.
        for (uint32_t i = 0; i < frames; i++) {
            descriptorTables_[i]->bindSamplerImageForShader(samplers_[i], hdrImages_[i], 0, 3);
        }
        return;
    }

    bloomSampler_ = vk::Sampler::create(device, VK_FILTER_LINEAR, VK_SAMPLER_MIPMAP_MODE_NEAREST,
                                        VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);

    constexpr VkFormat bloomFormat = VK_FORMAT_R16G16B16A16_SFLOAT;
    auto levelSize = [&](uint32_t level) {
        return VkExtent2D{std::max(1u, width_ >> (level + 1)), std::max(1u, height_ >> (level + 1))};
    };

    bloomRenderPass_ = vk::RenderPassBuilder{}
                           .beginAttachmentDescription()
                           .defineAttachmentDescription({
                               .format = bloomFormat,
                               .samples = VK_SAMPLE_COUNT_1_BIT,
                               .loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE,
                               .storeOp = VK_ATTACHMENT_STORE_OP_STORE,
                               .stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE,
                               .stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE,
                               .initialLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                               .finalLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                           })
                           .endAttachmentDescription()
                           .beginAttachmentReference()
                           .defineAttachmentReference({
                               .attachment = 0,
                               .layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                           })
                           .endAttachmentReference()
                           .beginSubpassDescription()
                           .defineSubpassDescription({
                               .pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS,
                               .colorAttachmentIndices = {0},
                           })
                           .endSubpassDescription()
                           .build(device);

    std::filesystem::path shaderPath = Renderer::folderPath / "shaders";
    bloomDownShader_ = vk::Shader::create(device, (shaderPath / "world/tone_mapping/bloom_down_frag.spv").string());
    bloomUpShader_ = vk::Shader::create(device, (shaderPath / "world/tone_mapping/bloom_up_frag.spv").string());

    bloomDownImages_.assign(frames, std::vector<std::shared_ptr<vk::DeviceLocalImage>>(BLOOM_DOWN_LEVELS));
    bloomUpImages_.assign(frames, std::vector<std::shared_ptr<vk::DeviceLocalImage>>(BLOOM_UP_LEVELS));
    bloomTables_.assign(frames, std::vector<std::shared_ptr<vk::DescriptorTable>>(BLOOM_PASSES));
    bloomFramebuffers_.assign(frames, std::vector<std::shared_ptr<vk::Framebuffer>>(BLOOM_PASSES));
    bloomPipelines_.assign(BLOOM_PASSES, nullptr);

    constexpr VkImageUsageFlags usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    for (uint32_t frame = 0; frame < frames; frame++) {
        for (uint32_t level = 0; level < BLOOM_DOWN_LEVELS; level++) {
            VkExtent2D e = levelSize(level);
            bloomDownImages_[frame][level] =
                vk::DeviceLocalImage::create(device, vma, false, e.width, e.height, 1, bloomFormat, usage);
        }
        for (uint32_t level = 0; level < BLOOM_UP_LEVELS; level++) {
            VkExtent2D e = levelSize(level);
            bloomUpImages_[frame][level] =
                vk::DeviceLocalImage::create(device, vma, false, e.width, e.height, 1, bloomFormat, usage);
        }

        for (uint32_t pass = 0; pass < BLOOM_PASSES; pass++) {
            std::shared_ptr<vk::DeviceLocalImage> srcA, srcB, target;
            if (pass == 0) {
                srcA = srcB = hdrImages_[frame];
                target = bloomDownImages_[frame][0];
            } else if (pass < BLOOM_DOWN_LEVELS) {
                srcA = srcB = bloomDownImages_[frame][pass - 1];
                target = bloomDownImages_[frame][pass];
            } else {
                uint32_t up = pass - BLOOM_DOWN_LEVELS; // 0..3, producing level 3..0
                uint32_t level = BLOOM_UP_LEVELS - 1 - up;
                srcA = up == 0 ? bloomDownImages_[frame][BLOOM_DOWN_LEVELS - 1] : bloomUpImages_[frame][level + 1];
                srcB = bloomDownImages_[frame][level];
                target = bloomUpImages_[frame][level];
            }

            auto table = vk::DescriptorTableBuilder{}
                             .beginDescriptorLayoutSet()
                             .beginDescriptorLayoutSetBinding()
                             .defineDescriptorLayoutSetBinding({
                                 .binding = 0,
                                 .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                                 .descriptorCount = 1,
                                 .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT,
                             })
                             .defineDescriptorLayoutSetBinding({
                                 .binding = 1,
                                 .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                                 .descriptorCount = 1,
                                 .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT,
                             })
                             .defineDescriptorLayoutSetBinding({
                                 .binding = 2,
                                 .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                                 .descriptorCount = 1,
                                 .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT,
                             })
                             .endDescriptorLayoutSetBinding()
                             .endDescriptorLayoutSet()
                             .definePushConstant(VkPushConstantRange{
                                 .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT,
                                 .offset = 0,
                                 .size = sizeof(ToneMappingBloomPushConstant),
                             })
                             .build(device);
            table->bindSamplerImageForShader(bloomSampler_, srcA, 0, 0);
            table->bindSamplerImageForShader(bloomSampler_, srcB, 0, 1);
            table->bindBuffer(exposureData_, 0, 2);
            bloomTables_[frame][pass] = table;

            bloomFramebuffers_[frame][pass] =
                vk::FramebufferBuilder{}.beginAttachment().defineAttachment(target).endAttachment().build(
                    device, bloomRenderPass_);

            if (frame == 0) {
                VkExtent2D e = {target->width(), target->height()};
                bloomPipelines_[pass] =
                    vk::GraphicsPipelineBuilder{}
                        .defineRenderPass(bloomRenderPass_, 0)
                        .beginShaderStage()
                        .defineShaderStage(vertShader_, VK_SHADER_STAGE_VERTEX_BIT)
                        .defineShaderStage(pass < BLOOM_DOWN_LEVELS ? bloomDownShader_ : bloomUpShader_,
                                           VK_SHADER_STAGE_FRAGMENT_BIT)
                        .endShaderStage()
                        .defineVertexInputState<void>()
                        .defineViewportScissorState({
                            .viewport = {.x = 0,
                                         .y = 0,
                                         .width = static_cast<float>(e.width),
                                         .height = static_cast<float>(e.height),
                                         .minDepth = 0.0,
                                         .maxDepth = 1.0},
                            .scissor = {.offset = {.x = 0, .y = 0}, .extent = e},
                        })
                        .defineDepthStencilState({
                            .depthTestEnable = VK_FALSE,
                            .depthWriteEnable = VK_FALSE,
                            .depthCompareOp = VK_COMPARE_OP_ALWAYS,
                            .depthBoundsTestEnable = VK_FALSE,
                            .stencilTestEnable = VK_FALSE,
                        })
                        .beginColorBlendAttachmentState()
                        .defineDefaultColorBlendAttachmentState()
                        .endColorBlendAttachmentState()
                        .definePipelineLayout(table)
                        .build(device);
            }
        }

        // The main tone mapping table reads the finest combined level.
        descriptorTables_[frame]->bindSamplerImageForShader(bloomSampler_, bloomUpImages_[frame][0], 0, 3);
    }
}

void ToneMappingModule::recordBloom(std::shared_ptr<vk::CommandBuffer> commandBuffer,
                                    uint32_t frameIndex,
                                    uint32_t queueFamily) {
    auto transition = [&](const std::shared_ptr<vk::DeviceLocalImage> &image, VkImageLayout newLayout) {
        VkImageLayout oldLayout = image->imageLayout();
        bool fresh = oldLayout == VK_IMAGE_LAYOUT_UNDEFINED;
        commandBuffer->barriersBufferImage(
            {}, {{
                    .srcStageMask = fresh ? VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT : VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                    .srcAccessMask = fresh ? 0 : (VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT),
                    .dstStageMask = VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                    .dstAccessMask = VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT,
                    .oldLayout = oldLayout,
                    .newLayout = newLayout,
                    .srcQueueFamilyIndex = queueFamily,
                    .dstQueueFamilyIndex = queueFamily,
                    .image = image,
                    .subresourceRange = vk::wholeColorSubresourceRange,
                }});
        image->imageLayout() = newLayout;
    };

    for (uint32_t pass = 0; pass < BLOOM_PASSES; pass++) {
        bool down = pass < BLOOM_DOWN_LEVELS;
        std::shared_ptr<vk::DeviceLocalImage> source, target;
        float weight = 1.0f;
        if (pass == 0) {
            source = hdrImages_[frameIndex];
            target = bloomDownImages_[frameIndex][0];
        } else if (down) {
            source = bloomDownImages_[frameIndex][pass - 1];
            target = bloomDownImages_[frameIndex][pass];
        } else {
            uint32_t up = pass - BLOOM_DOWN_LEVELS;
            uint32_t level = BLOOM_UP_LEVELS - 1 - up;
            source = up == 0 ? bloomDownImages_[frameIndex][BLOOM_DOWN_LEVELS - 1] : bloomUpImages_[frameIndex][level + 1];
            target = bloomUpImages_[frameIndex][level];
            weight = bloomWeights_[level];
        }

        transition(target, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

        ToneMappingBloomPushConstant pc{};
        pc.srcTexelX = 1.0f / static_cast<float>(source->width());
        pc.srcTexelY = 1.0f / static_cast<float>(source->height());
        pc.firstPass = pass == 0 ? 1 : 0;
        pc.autoExposure = isAutoExposureEnabled_ ? 1 : 0;
        pc.manualExposure = std::max(manualExposure_, 1e-6f);
        pc.exposureBias = exposureBias_;
        pc.threshold = bloomThreshold_;
        pc.scatter = bloomScatter_;
        pc.weight = weight;

        auto &table = bloomTables_[frameIndex][pass];
        commandBuffer->beginRenderPass({
            .renderPass = bloomRenderPass_,
            .framebuffer = bloomFramebuffers_[frameIndex][pass],
            .renderAreaExtent = {target->width(), target->height()},
            .clearValues = {{.color = {0.0f, 0.0f, 0.0f, 1.0f}}},
        });
        vkCmdPushConstants(commandBuffer->vkCommandBuffer(), table->vkPipelineLayout(), VK_SHADER_STAGE_FRAGMENT_BIT, 0,
                           sizeof(ToneMappingBloomPushConstant), &pc);
        commandBuffer->bindGraphicsPipeline(bloomPipelines_[pass])
            ->bindDescriptorTable(table, VK_PIPELINE_BIND_POINT_GRAPHICS)
            ->draw(3, 1)
            ->endRenderPass();

        transition(target, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }
}

ToneMappingModuleContext::ToneMappingModuleContext(std::shared_ptr<FrameworkContext> frameworkContext,
                                                   std::shared_ptr<WorldPipelineContext> worldPipelineContext,
                                                   std::shared_ptr<ToneMappingModule> toneMappingModule)
    : WorldModuleContext(frameworkContext, worldPipelineContext),
      toneMappingModule(toneMappingModule),
      hdrImage(toneMappingModule->hdrImages_[frameworkContext->frameIndex]),
      descriptorTable(toneMappingModule->descriptorTables_[frameworkContext->frameIndex]),
      framebuffer(toneMappingModule->framebuffers_[frameworkContext->frameIndex]),
      histBuffer(toneMappingModule->histBuffers_[frameworkContext->frameIndex]),
      ldrImage(toneMappingModule->ldrImages_[frameworkContext->frameIndex]) {}

void ToneMappingModuleContext::render() {
    auto context = frameworkContext.lock();
    auto framework = context->framework.lock();
    auto worldCommandBuffer = context->worldCommandBuffer;
    auto mainQueueIndex = framework->physicalDevice()->mainQueueIndex();

    auto module = toneMappingModule.lock();

    auto chooseSrc = [](VkImageLayout oldLayout, VkPipelineStageFlags2 fallbackStage, VkAccessFlags2 fallbackAccess,
                        VkPipelineStageFlags2 &outStage, VkAccessFlags2 &outAccess) {
        if (oldLayout == VK_IMAGE_LAYOUT_UNDEFINED) {
            outStage = VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT;
            outAccess = 0;
        } else {
            outStage = fallbackStage;
            outAccess = fallbackAccess;
        }
    };

    VkPipelineStageFlags2 hdrSrcStage = 0;
    VkAccessFlags2 hdrSrcAccess = 0;
    chooseSrc(hdrImage->imageLayout(),
              VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT |
                  VK_PIPELINE_STAGE_2_TRANSFER_BIT,
              VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT, hdrSrcStage, hdrSrcAccess);

    VkPipelineStageFlags2 ldrSrcStage = 0;
    VkAccessFlags2 ldrSrcAccess = 0;
    chooseSrc(ldrImage->imageLayout(),
              VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR | VK_PIPELINE_STAGE_2_TRANSFER_BIT,
              VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT, ldrSrcStage, ldrSrcAccess);

    worldCommandBuffer->barriersBufferImage(
        {{
            .srcStageMask = VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_2_TRANSFER_BIT,
            .srcAccessMask = VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT,
            .dstStageMask = VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT |
                            VK_PIPELINE_STAGE_2_TRANSFER_BIT,
            .srcQueueFamilyIndex = mainQueueIndex,
            .dstQueueFamilyIndex = mainQueueIndex,
            .buffer = histBuffer,
        }},
        {{
             .srcStageMask = hdrSrcStage,
             .srcAccessMask = hdrSrcAccess,
             .dstStageMask = VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT |
                             VK_PIPELINE_STAGE_2_TRANSFER_BIT,
             .dstAccessMask = VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT,
             .oldLayout = hdrImage->imageLayout(),
             .newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
             .srcQueueFamilyIndex = mainQueueIndex,
             .dstQueueFamilyIndex = mainQueueIndex,
             .image = hdrImage,
             .subresourceRange = vk::wholeColorSubresourceRange,
         },
         {
             .srcStageMask = ldrSrcStage,
             .srcAccessMask = ldrSrcAccess,
             .dstStageMask = VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_2_TRANSFER_BIT,
             .dstAccessMask = VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT |
                              VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT,
             .oldLayout = ldrImage->imageLayout(),
#ifdef USE_AMD
             .newLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
#else
             .newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
#endif
             .srcQueueFamilyIndex = mainQueueIndex,
             .dstQueueFamilyIndex = mainQueueIndex,
             .image = ldrImage,
             .subresourceRange = vk::wholeColorSubresourceRange,
         }});
    hdrImage->imageLayout() = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
#ifdef USE_AMD
    ldrImage->imageLayout() = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
#else
    ldrImage->imageLayout() = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
#endif

    vkCmdFillBuffer(worldCommandBuffer->vkCommandBuffer(), histBuffer->vkBuffer(), 0, VK_WHOLE_SIZE, 0);

    worldCommandBuffer->barriersBufferImage(
        {{
            .srcStageMask = VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_2_TRANSFER_BIT,
            .srcAccessMask = VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT,
            .dstStageMask = VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT |
                            VK_PIPELINE_STAGE_2_TRANSFER_BIT,
            .dstAccessMask = VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT,
            .srcQueueFamilyIndex = mainQueueIndex,
            .dstQueueFamilyIndex = mainQueueIndex,
            .buffer = histBuffer,
        }},
        {});

    std::chrono::time_point<std::chrono::high_resolution_clock> currentTimePoint =
        std::chrono::high_resolution_clock::now();
    std::chrono::duration<double> elapsedTime = currentTimePoint - module->lastTimePoint_;
    module->lastTimePoint_ = currentTimePoint;

    float dtSeconds = static_cast<float>(elapsedTime.count());
    if (!std::isfinite(dtSeconds)) dtSeconds = 1.0f / 60.0f;
    dtSeconds = std::clamp(dtSeconds, 0.0f, 1.0f);

    float sanitizedLog2Min = std::min(module->log2Min_, module->log2Max_ - 1e-3f);
    float sanitizedLog2Max = std::max(module->log2Max_, sanitizedLog2Min + 1e-3f);
    float sanitizedLowPercent = std::clamp(module->lowPercent_, 0.0f, 0.9999f);
    float sanitizedHighPercent = std::clamp(module->highPercent_, sanitizedLowPercent + 1e-4f, 1.0f);
    float sanitizedCenterMeteringPercent = std::clamp(module->centerMeteringPercent_, 1.0f, 100.0f) / 100.0f;

    ToneMappingModulePushConstant pc{};
    pc.log2Min = sanitizedLog2Min;
    pc.log2Max = sanitizedLog2Max;
    pc.epsilon = std::max(module->epsilon_, 1e-8f);
    pc.lowPercent = sanitizedLowPercent;
    pc.highPercent = sanitizedHighPercent;
    pc.middleGrey = std::max(module->middleGrey_, 1e-4f);
    pc.dt = dtSeconds;
    pc.speedUp = std::max(module->speedUp_, 0.0f);
    pc.speedDown = std::max(module->speedDown_, 0.0f);
    pc.minExposure = std::max(module->minExposure_, 1e-6f);
    pc.maxExposure = std::max(module->maxExposure_, pc.minExposure);
    pc.manualExposure = std::max(module->manualExposure_, 1e-6f);
    pc.exposureBias = module->exposureBias_;
    pc.whitePoint = std::max(module->whitePoint_, 1e-3f);
    pc.saturation = std::max(module->saturation_, 0.0f);
    pc.toneMappingMethod = std::clamp(module->toneMappingMethod_, static_cast<int>(TONE_MAPPING_METHOD_PBR_NEUTRAL),
                                      static_cast<int>(TONE_MAPPING_METHOD_BEDROCK_PROVISIONAL));
    pc.autoExposure = module->isAutoExposureEnabled_ ? 1 : 0;
    pc.clampOutput = module->shouldClampOutput_ ? 1 : 0;
    pc.exposureMeteringMode =
        std::clamp(module->exposureMeteringMode_, static_cast<int>(TONE_MAPPING_EXPOSURE_METERING_MODE_GLOBAL),
                   static_cast<int>(TONE_MAPPING_EXPOSURE_METERING_MODE_LIGHT_METER));
    pc.centerMeteringPercent = sanitizedCenterMeteringPercent;
    pc.adaptation = std::clamp(module->adaptation_, 0.0f, 1.0f);
    pc.bloomIntensity = module->bloomEnabled_ ? module->bloomIntensity_ : 0.0f;
    pc.contrast = module->contrast_;
    pc.shadowContrast = module->shadowContrast_;
    pc.shadowContrastEnd = module->shadowContrastEnd_;
    pc.gamma = module->gamma_;
    pc.balanceR = module->colorBalance_[0];
    pc.balanceG = module->colorBalance_[1];
    pc.balanceB = module->colorBalance_[2];
    pc.filmicSaturation = module->filmicSaturation_;

    vkCmdPushConstants(worldCommandBuffer->vkCommandBuffer(), descriptorTable->vkPipelineLayout(),
                       VK_SHADER_STAGE_COMPUTE_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0,
                       sizeof(ToneMappingModulePushConstant), &pc);

    worldCommandBuffer->bindDescriptorTable(descriptorTable, VK_PIPELINE_BIND_POINT_COMPUTE)
        ->bindComputePipeline(module->histPipeline_);

    uint32_t groupX = (module->width_ + 16 - 1) / 16;
    uint32_t groupY = (module->height_ + 16 - 1) / 16;
    vkCmdDispatch(worldCommandBuffer->vkCommandBuffer(), groupX, groupY, 1);

    worldCommandBuffer->barriersBufferImage(
        {{
            .srcStageMask = VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_2_TRANSFER_BIT,
            .srcAccessMask = VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT,
            .dstStageMask = VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT |
                            VK_PIPELINE_STAGE_2_TRANSFER_BIT,
            .dstAccessMask = VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT,
            .srcQueueFamilyIndex = mainQueueIndex,
            .dstQueueFamilyIndex = mainQueueIndex,
            .buffer = histBuffer,
        }},
        {});

    worldCommandBuffer->bindComputePipeline(module->exposurePipeline_);
    vkCmdDispatch(worldCommandBuffer->vkCommandBuffer(), 1, 1, 1);

    worldCommandBuffer->barriersBufferImage(
        {{
            .srcStageMask = VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_2_TRANSFER_BIT,
            .srcAccessMask = VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT,
            .dstStageMask = VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT |
                            VK_PIPELINE_STAGE_2_TRANSFER_BIT,
            .dstAccessMask = VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT,
            .srcQueueFamilyIndex = mainQueueIndex,
            .dstQueueFamilyIndex = mainQueueIndex,
            .buffer = module->exposureData_,
        }},
        {});

    if (pc.toneMappingMethod == TONE_MAPPING_METHOD_BEDROCK_PROVISIONAL) {
        auto curveBarrier = [&]() {
            worldCommandBuffer->barriersBufferImage(
                {{
                    .srcStageMask = VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                    .srcAccessMask = VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT,
                    .dstStageMask = VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT |
                                    VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                    .dstAccessMask = VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT,
                    .srcQueueFamilyIndex = mainQueueIndex,
                    .dstQueueFamilyIndex = mainQueueIndex,
                    .buffer = module->bedrockCurveData_,
                }},
                {});
        };
        if (module->bedrockCurveNeedsReset_) {
            vkCmdFillBuffer(worldCommandBuffer->vkCommandBuffer(), module->bedrockCurveData_->vkBuffer(), 0,
                            VK_WHOLE_SIZE, 0);
            curveBarrier();
        }

        ToneMappingBedrockCurvePushConstant curvePc{};
        curvePc.dynamicRange = module->curveDynamicRange_;
        curvePc.curveShift = module->curveShift_;
        curvePc.maxExposureIncrease = module->curveMaxExposureIncrease_;
        curvePc.shadowMinSlope = module->curveShadowMinSlope_;
        curvePc.shadowContrast = module->shadowContrast_;
        curvePc.shadowContrastEnd = module->shadowContrastEnd_;
        curvePc.needsReset = module->bedrockCurveNeedsReset_ ? 1 : 0;
        curvePc.autoExposure = pc.autoExposure;
        curvePc.manualExposure = pc.manualExposure;
        curvePc.exposureBias = pc.exposureBias;
        module->bedrockCurveNeedsReset_ = false;
        vkCmdPushConstants(worldCommandBuffer->vkCommandBuffer(), descriptorTable->vkPipelineLayout(),
                           VK_SHADER_STAGE_COMPUTE_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(curvePc), &curvePc);

        worldCommandBuffer->bindComputePipeline(module->bedrockHistPipeline_);
        vkCmdDispatch(worldCommandBuffer->vkCommandBuffer(), groupX, groupY, 1);
        curveBarrier();
        worldCommandBuffer->bindComputePipeline(module->bedrockCurvePipeline_);
        vkCmdDispatch(worldCommandBuffer->vkCommandBuffer(), 1, 1, 1);
        curveBarrier();

        vkCmdPushConstants(worldCommandBuffer->vkCommandBuffer(), descriptorTable->vkPipelineLayout(),
                           VK_SHADER_STAGE_COMPUTE_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0,
                           sizeof(ToneMappingModulePushConstant), &pc);
    }

    if (module->bloomEnabled_) {
        module->recordBloom(worldCommandBuffer, context->frameIndex, mainQueueIndex);
        // The bloom passes bound a pipeline layout of their own; push the tone mapping constants again so the draw
        // below does not depend on what that did to them.
        vkCmdPushConstants(worldCommandBuffer->vkCommandBuffer(), descriptorTable->vkPipelineLayout(),
                           VK_SHADER_STAGE_COMPUTE_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0,
                           sizeof(ToneMappingModulePushConstant), &pc);
    }

    worldCommandBuffer->beginRenderPass({
        .renderPass = module->renderPass_,
        .framebuffer = framebuffer,
        .renderAreaExtent = {ldrImage->width(), ldrImage->height()},
        .clearValues = {{.color = {0.1f, 0.1f, 0.1f, 1.0f}}},
    });
    ldrImage->imageLayout() = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

    worldCommandBuffer->bindGraphicsPipeline(module->pipeline_)
        ->bindDescriptorTable(descriptorTable, VK_PIPELINE_BIND_POINT_GRAPHICS)
        ->draw(3, 1)
        ->endRenderPass();
#ifdef USE_AMD
    ldrImage->imageLayout() = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
#else
    ldrImage->imageLayout() = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
#endif
}
