#include "core/render/textures.hpp"

#include <atomic>
#include <tuple>

#include "core/render/emission.hpp"
#include "core/render/pipeline.hpp"
#include "core/render/render_framework.hpp"
#include "core/render/renderer.hpp"
#include "core/util/logging.hpp"

std::ostream &texturesCout() {
    return radiante::out() << "[Textures] ";
}

std::ostream &texturesCerr() {
    return radiante::err() << "[Textures] ";
}


namespace {
// Samplers are a limited device resource and every texture only needs one of a handful of
// configurations, so they are shared between textures.
std::shared_ptr<vk::Sampler> acquireSharedSampler(const std::shared_ptr<vk::Device> &device,
                                                  VkFilter samplingMode,
                                                  VkSamplerMipmapMode mipmapMode,
                                                  VkSamplerAddressMode addressMode) {
    static std::mutex mutex;
    static std::map<std::tuple<int, int, int>, std::shared_ptr<vk::Sampler>> cache;

    auto key = std::make_tuple(static_cast<int>(samplingMode), static_cast<int>(mipmapMode),
                               static_cast<int>(addressMode));
    std::scoped_lock lock(mutex);
    auto iter = cache.find(key);
    if (iter != cache.end()) return iter->second;

    auto sampler = vk::Sampler::create(device, samplingMode, mipmapMode, addressMode);
    cache.emplace(key, sampler);
    return sampler;
}
} // namespace

Textures::Textures(std::shared_ptr<Framework> framework) {}

void Textures::reset() {
    textures_.clear();
    if (emission_ != nullptr) { emission_->reset(); }
    nextID = 0;
}

void Textures::resetFrame() {
    auto framework = Renderer::instance().framework();

    collectCompletedUploadsImpl();

    framework->frameResourceRetainer().retain(uploadQueue_);
    uploadQueue_ = std::make_shared<std::map<uint32_t, std::vector<VkBufferImageCopy>>>();
    queuedUploadBytes_ = 0;

    for (auto &entry : caches_) {
        auto &cache = entry.second;
        cache->reset();
    }
}

uint32_t Textures::allocateTexture() {
    std::scoped_lock lck(mtx_, Renderer::instance().framework()->recreateMtx());

    textures_.emplace(std::make_pair(nextID, nullptr));
    samplers.emplace(std::make_pair(nextID, nullptr));
    return nextID++;
}

void Textures::initializeTexture(uint32_t id, uint32_t maxLevel, uint32_t width, uint32_t height, VkFormat format) {
    auto framework = Renderer::instance().framework();
    auto device = framework->device();
    auto vma = framework->vma();

    std::scoped_lock lck(mtx_, Renderer::instance().framework()->recreateMtx());

    auto textureIter = textures_.find(id);
    if (textureIter == textures_.end()) {
        texturesCerr() << "The given texture id: " << id << " is not allocated for texture" << std::endl;
        exit(EXIT_FAILURE);
    }

    framework->frameResourceRetainer().retain(textures_[id]);
#ifdef DEBUG
    if (textures_[id] != nullptr) { radiante::out() << "Textrue reinitialized: " << id << std::endl; }
#endif
    textures_[id] = vk::DeviceLocalImage::create(device, vma, false, maxLevel, width, height, 1, format,
                                                 VK_IMAGE_USAGE_SAMPLED_BIT, 0, VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE, 0
#ifdef DEBUG
                                                 ,
                                                 "Texture " + std::to_string(id)
#endif
    );

    auto samplerIter = samplers.find(id);
    if (samplerIter == samplers.end()) {
        texturesCerr() << "The given texture id: " << id << " is not allocated for sampler" << std::endl;
        exit(EXIT_FAILURE);
    }
    samplers[id] = acquireSharedSampler(device, VK_FILTER_NEAREST, VK_SAMPLER_MIPMAP_MODE_NEAREST,
                                        VK_SAMPLER_ADDRESS_MODE_REPEAT);

    // A new image starts in VK_IMAGE_LAYOUT_UNDEFINED and only becomes readable when its first upload
    // transitions it. It is bound into the descriptor table right here though, so between now and that upload the
    // ray tracing shaders sample an image in the wrong layout - undefined behaviour that the driver is free to
    // fault on. It is a wide window on a resource reload, where every atlas is destroyed and recreated frames
    // before its contents are stitched back. The next flush clears the image and puts it in the layout the
    // shaders expect.
    pendingInitializations_.push_back(id);

    Renderer::instance().framework()->pipeline()->bindTexture(samplers[id], textures_[id], id);
}

void Textures::setSamplingMode(uint32_t id, VkFilter samplingMode, VkSamplerMipmapMode mipmapMode) {
    auto device = Renderer::instance().framework()->device();

    std::scoped_lock lck(mtx_, Renderer::instance().framework()->recreateMtx());

    auto samplerIter = samplers.find(id);
    if (samplerIter == samplers.end()) {
        texturesCerr() << "The given texture id: " << id << " is not allocated for sampler" << std::endl;
        exit(EXIT_FAILURE);
    }
    if (samplers[id]->vkSamplingMode() != samplingMode) {
        VkSamplerAddressMode addressMode = samplers[id]->vkAddressMode();

        samplers[id] = acquireSharedSampler(device, samplingMode, mipmapMode, addressMode);
    }

    Renderer::instance().framework()->pipeline()->bindTexture(samplers[id], textures_[id], id);
}

void Textures::setAddressMode(uint32_t id, VkSamplerAddressMode addressMode) {
    auto device = Renderer::instance().framework()->device();

    std::scoped_lock lck(mtx_, Renderer::instance().framework()->recreateMtx());

    auto samplerIter = samplers.find(id);
    if (samplerIter == samplers.end()) {
        texturesCerr() << "The given texture id: " << id << " is not allocated for sampler" << std::endl;
        exit(EXIT_FAILURE);
    }
    if (samplers[id]->vkAddressMode() != addressMode) {
        VkFilter samplingMode = samplers[id]->vkSamplingMode();
        VkSamplerMipmapMode mipmapMode = samplers[id]->vkMipmapMode();

        samplers[id] = acquireSharedSampler(device, samplingMode, mipmapMode, addressMode);
    }

    Renderer::instance().framework()->pipeline()->bindTexture(samplers[id], textures_[id], id);
}

void Textures::queueUpload(uint8_t *srcPointer,
                           uint32_t srcSizeInBytes,
                           uint32_t srcRowPixels,
                           uint32_t dstId,
                           int srcOffsetX,
                           int srcOffsetY,
                           int dstOffsetX,
                           int dstOffsetY,
                           uint32_t width,
                           uint32_t height,
                           uint32_t level) {
    std::scoped_lock lck(mtx_, Renderer::instance().framework()->recreateMtx());

    auto framework = Renderer::instance().framework();

    auto device = Renderer::instance().framework()->device();
    auto vma = Renderer::instance().framework()->vma();
    auto dstTextureIter = textures_.find(dstId);
    if (dstTextureIter == textures_.end()) {
        texturesCerr() << "The dstID " << dstId << " is not registered yet!" << std::endl;
        exit(EXIT_FAILURE);
    }
    auto dstTexture = (*dstTextureIter).second;

    auto cacheIter = caches_.find(dstId);
    if (cacheIter == caches_.end()) {
        cacheIter = caches_
                        .emplace(std::make_pair(
                            dstId, ImageBufferCache::create(vma, device, framework->swapchain()->imageCount())))
                        .first;
    }

    auto cache = cacheIter->second;

    auto format = dstTexture->vkFormat();
    uint32_t bytePerPixel = vk::formatToByte(format);

    // Only the rows the copy reads are staged. Taking the whole source meant a glyph of eight by eight texels
    // dragged its entire sheet along, sixty four kilobytes at a time, once per letter.
    size_t rowStride = static_cast<size_t>(srcRowPixels) * bytePerPixel;
    size_t skipRows = static_cast<size_t>(srcOffsetY) * rowStride;
    size_t neededBytes = skipRows + static_cast<size_t>(height) * rowStride;
    if (neededBytes > srcSizeInBytes) { neededBytes = srcSizeInBytes; }
    size_t stagedBytes = neededBytes - skipRows;
    size_t offset = cache->append(srcPointer + skipRows, stagedBytes);

    VkBufferImageCopy region = {};
    region.bufferRowLength = srcRowPixels;
    region.bufferOffset = offset + srcOffsetX * bytePerPixel;
    region.imageSubresource = {
        .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
        .mipLevel = 0,
        .baseArrayLayer = 0,
        .layerCount = 1, // avoid VK_REMAINING_ARRAY_LAYERS to get rid of maintaince5
    };
    region.imageSubresource.mipLevel = level;
    region.imageExtent = {width, height, 1};
    region.imageOffset = {dstOffsetX, dstOffsetY, 0};

    auto dstTextureUploadQueueIter = uploadQueue_->find(dstId);
    if (dstTextureUploadQueueIter == uploadQueue_->end()) {
        dstTextureUploadQueueIter = uploadQueue_->emplace(dstId, std::vector<VkBufferImageCopy>{}).first;
    }
    dstTextureUploadQueueIter->second.emplace_back(region);

    queuedUploadBytes_ += srcSizeInBytes;
    if (queuedUploadBytes_ >= UPLOAD_FLUSH_THRESHOLD) { flushQueuedUploadImpl(); }
}

void Textures::performQueuedUpload() {
    std::scoped_lock lck(mtx_, Renderer::instance().framework()->recreateMtx());
    collectCompletedUploadsImpl();
    flushQueuedUploadImpl();
}

std::shared_ptr<vk::HostVisibleBuffer> Textures::acquireUploadStagingBuffer(size_t minSize) {
    auto vma = Renderer::instance().framework()->vma();
    auto device = Renderer::instance().framework()->device();

    for (auto iter = freeUploadStagingBuffers_.begin(); iter != freeUploadStagingBuffers_.end(); ++iter) {
        if ((*iter)->size() >= minSize) {
            auto buffer = *iter;
            freeUploadStagingBuffers_.erase(iter);
            return buffer;
        }
    }

    return vk::HostVisibleBuffer::create(vma, device, minSize, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
}

std::shared_ptr<vk::Fence> Textures::acquireUploadFence() {
    auto device = Renderer::instance().framework()->device();

    if (!freeUploadFences_.empty()) {
        auto fence = freeUploadFences_.back();
        freeUploadFences_.pop_back();
        vkResetFences(device->vkDevice(), 1, &fence->vkFence());
        return fence;
    }

    return vk::Fence::create(device);
}

void Textures::collectCompletedUploadsImpl() {
    auto device = Renderer::instance().framework()->device();
    auto &batches = submittedUploadBatches_;
    for (auto iter = batches.begin(); iter != batches.end();) {
        if (vkGetFenceStatus(device->vkDevice(), iter->fence->vkFence()) == VK_SUCCESS) {
            freeUploadCommandBuffers_.emplace_back(iter->commandBuffer);
            freeUploadFences_.emplace_back(iter->fence);
            for (auto &stagingBuffer : iter->stagingBuffers) {
                freeUploadStagingBuffers_.emplace_back(stagingBuffer);
            }
            iter = batches.erase(iter);
        } else {
            ++iter;
        }
    }
}

void Textures::flushQueuedUploadImpl() {
    bool hasUploads = uploadQueue_ != nullptr && !uploadQueue_->empty();
    if (!hasUploads && pendingInitializations_.empty()) {
        queuedUploadBytes_ = 0;
        return;
    }

    auto framework = Renderer::instance().framework();
    auto device = framework->device();
    auto physicalDevice = framework->physicalDevice();
    collectCompletedUploadsImpl();
    std::shared_ptr<vk::CommandBuffer> cmdBuffer;
    if (!freeUploadCommandBuffers_.empty()) {
        cmdBuffer = freeUploadCommandBuffers_.back();
        freeUploadCommandBuffers_.pop_back();
        cmdBuffer->reset();
    } else {
        cmdBuffer = vk::CommandBuffer::create(device, framework->mainCommandPool());
    }
    auto fence = acquireUploadFence();
    cmdBuffer->begin();

    auto mainQueueIndex = physicalDevice->mainQueueIndex();

    std::vector<vk::CommandBuffer::ImageMemoryBarrier> uploadPreImageBarriers, uploadPostImageBarriers;

    for (auto &entry : *uploadQueue_) {
        auto &textureId = entry.first;
        auto textureIter = textures_.find(textureId);
        if (textureIter == textures_.end()) {
            texturesCerr() << "The textureId " << textureId << " is not registered yet!" << std::endl;
            exit(EXIT_FAILURE);
        }
        auto texture = textureIter->second;
        uploadPreImageBarriers.push_back({
            .srcStageMask = VK_PIPELINE_STAGE_2_TRANSFER_BIT,
            .srcAccessMask = VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT,
            .dstStageMask = VK_PIPELINE_STAGE_2_TRANSFER_BIT,
            .dstAccessMask = VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT,
            .oldLayout = texture->imageLayout(),
            .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            .srcQueueFamilyIndex = mainQueueIndex,
            .dstQueueFamilyIndex = mainQueueIndex,
            .image = texture,
            .subresourceRange = vk::wholeColorSubresourceRange,
        });
        texture->imageLayout() = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;

        uploadPostImageBarriers.push_back({
            .srcStageMask = VK_PIPELINE_STAGE_2_TRANSFER_BIT,
            .srcAccessMask = VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT,
            .dstStageMask = VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT |
                            VK_PIPELINE_STAGE_2_TRANSFER_BIT,
            .dstAccessMask = VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT,
            .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            .newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            .srcQueueFamilyIndex = mainQueueIndex,
            .dstQueueFamilyIndex = mainQueueIndex,
            .image = texture,
            .subresourceRange = vk::wholeColorSubresourceRange,
        });
        texture->imageLayout() = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    }

    // Freshly created images: clear them to transparent black and move them into the layout the shaders read, so
    // nothing ever samples an image still in VK_IMAGE_LAYOUT_UNDEFINED.
    for (uint32_t initId : pendingInitializations_) {
        auto initIter = textures_.find(initId);
        if (initIter == textures_.end() || initIter->second == nullptr) { continue; }
        auto image = initIter->second;
        if (image->imageLayout() != VK_IMAGE_LAYOUT_UNDEFINED) { continue; }
        // One with an upload waiting is about to be filled and transitioned anyway.
        if (hasUploads && uploadQueue_->find(initId) != uploadQueue_->end()) { continue; }

        cmdBuffer->barriersBufferImage({}, {{
                                               .srcStageMask = VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                                               .srcAccessMask = VK_ACCESS_2_MEMORY_READ_BIT,
                                               .dstStageMask = VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                                               .dstAccessMask = VK_ACCESS_2_MEMORY_WRITE_BIT,
                                               .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED,
                                               .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                                               .srcQueueFamilyIndex = mainQueueIndex,
                                               .dstQueueFamilyIndex = mainQueueIndex,
                                               .image = image,
                                               .subresourceRange = vk::wholeColorSubresourceRange,
                                           }});
        VkClearColorValue clearColor = {};
        vkCmdClearColorImage(cmdBuffer->vkCommandBuffer(), image->vkImage(),
                             VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &clearColor, 1,
                             &vk::wholeColorSubresourceRange);
        cmdBuffer->barriersBufferImage({}, {{
                                               .srcStageMask = VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                                               .srcAccessMask = VK_ACCESS_2_MEMORY_WRITE_BIT,
                                               .dstStageMask = VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT |
                                                               VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT |
                                                               VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR |
                                                               VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                                               .dstAccessMask = VK_ACCESS_2_MEMORY_READ_BIT,
                                               .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                                               .newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                                               .srcQueueFamilyIndex = mainQueueIndex,
                                               .dstQueueFamilyIndex = mainQueueIndex,
                                               .image = image,
                                               .subresourceRange = vk::wholeColorSubresourceRange,
                                           }});
        image->imageLayout() = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    }
    pendingInitializations_.clear();

    cmdBuffer->barriersBufferImage({}, uploadPreImageBarriers);

    for (auto &entry : *uploadQueue_) {
        auto &textureId = entry.first;
        auto &regions = entry.second;

        auto textureIter = textures_.find(textureId);
        if (textureIter == textures_.end()) {
            texturesCerr() << "The textureId " << textureId << " is not registered yet!" << std::endl;
            exit(EXIT_FAILURE);
        }
        auto texture = textureIter->second;

        auto cacheIter = caches_.find(textureId);
        if (cacheIter == caches_.end()) { continue; }
        auto cache = cacheIter->second;

        vkCmdCopyBufferToImage(cmdBuffer->vkCommandBuffer(), cache->vkBuffer(), texture->vkImage(),
                               VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, regions.size(), regions.data());

        cmdBuffer->barriersBufferImage(
            {}, {{
                    .srcStageMask = VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                    .srcAccessMask = VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT,
                    .dstStageMask = VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT |
                                    VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                    .dstAccessMask = VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT,
                    .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    .srcQueueFamilyIndex = mainQueueIndex,
                    .dstQueueFamilyIndex = mainQueueIndex,
                    .image = texture,
                    .subresourceRange = vk::wholeColorSubresourceRange,
                }});
    }

    cmdBuffer->barriersBufferImage({}, uploadPostImageBarriers);

    std::vector<std::shared_ptr<vk::HostVisibleBuffer>> stagingBuffers;
    stagingBuffers.reserve(uploadQueue_->size());
    for (auto &entry : *uploadQueue_) {
        auto cacheIter = caches_.find(entry.first);
        if (cacheIter == caches_.end()) { continue; }
        auto cache = cacheIter->second;
        cache->flush();
        auto detachedBuffer = cache->detachCurrentBuffer();
        stagingBuffers.emplace_back(detachedBuffer);
        cache->replaceCurrentBuffer(acquireUploadStagingBuffer(detachedBuffer->size()));
    }

    cmdBuffer->end();
    cmdBuffer->submitMainQueueIndividual(device, fence);

    submittedUploadBatches_.push_back({
        .fence = fence,
        .commandBuffer = cmdBuffer,
        .stagingBuffers = std::move(stagingBuffers),
    });

    framework->frameResourceRetainer().retain(uploadQueue_);
    uploadQueue_ = std::make_shared<std::map<uint32_t, std::vector<VkBufferImageCopy>>>();
    queuedUploadBytes_ = 0;
}

std::shared_ptr<vk::DeviceLocalImage> Textures::texture(uint32_t id) {
    std::scoped_lock lck(mtx_, Renderer::instance().framework()->recreateMtx());
    auto iter = textures_.find(id);
    return iter != textures_.end() ? iter->second : nullptr;
}

std::shared_ptr<vk::Sampler> Textures::sampler(uint32_t id) {
    std::scoped_lock lck(mtx_, Renderer::instance().framework()->recreateMtx());
    auto iter = samplers.find(id);
    return iter != samplers.end() ? iter->second : nullptr;
}

std::shared_ptr<Emission> Textures::emission() {
    if (!Renderer::options.collectChunkEmission) {
        return nullptr;
    }

    std::scoped_lock lck(mtx_);
    if (emission_ == nullptr) {
        emission_ = Emission::create(std::weak_ptr<Textures>(shared_from_this()));
    }
    return emission_;
}

void Textures::releaseEmission() {
    std::scoped_lock lck(mtx_);
    if (emission_ != nullptr) {
        emission_->reset();
        emission_ = nullptr;
    }
}

void Textures::bindAllTextures() {
    auto device = Renderer::instance().framework()->device();

    std::scoped_lock lck(mtx_);

    for (const auto &[id, texture] : textures_) {
        if (texture == nullptr) {
            continue; // only allocated, but not initialized yet
        }

        Renderer::instance().framework()->pipeline()->bindTexture(samplers[id], texture, id);
    }
}

ImageBufferCache::ImageBufferCache(std::shared_ptr<vk::VMA> vma, std::shared_ptr<vk::Device> device, uint32_t frameNum)
    : vma_(vma), device_(device) {
    capacities_.resize(frameNum);
    bases_.resize(frameNum);
    caches_.resize(frameNum);

    for (int i = 0; i < frameNum; i++) {
        caches_[i] = vk::HostVisibleBuffer::create(vma_, device_, BASE_SIZE, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        capacities_[i] = BASE_SIZE;
        bases_[i] = 0;
    }
}

ImageBufferCache::~ImageBufferCache() {
#ifdef DEBUG
// radiante::out() << "ImageBufferCache deconstructed" << std::endl;
#endif
}

size_t ImageBufferCache::append(void *src, size_t size) {
    if (bases_[current_] + size >= capacities_[current_]) {
        size_t newCapacity = capacities_[current_] * 2;

        while (newCapacity < bases_[current_] + size) { newCapacity *= 2; }

        auto newCache = vk::HostVisibleBuffer::create(vma_, device_, newCapacity, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);

        std::memcpy(newCache->mappedPtr(), caches_[current_]->mappedPtr(), bases_[current_]);

        auto framework = Renderer::instance().framework();
        framework->frameResourceRetainer().retain(caches_[current_]);
        caches_[current_] = newCache;
        capacities_[current_] = newCapacity;
    }

    size_t ret = bases_[current_];
    std::memcpy(static_cast<uint8_t *>(caches_[current_]->mappedPtr()) + bases_[current_], src, size);
    bases_[current_] = (bases_[current_] + size + ALIGNMENT - 1) & ~(ALIGNMENT - 1);
    return ret;
}

void ImageBufferCache::flush() {
    caches_[current_]->flush();
}

VkBuffer &ImageBufferCache::vkBuffer() {
    return caches_[current_]->vkBuffer();
}

void ImageBufferCache::reset() {
    current_ = (current_ + 1) % caches_.size();
    bases_[current_] = 0;
}

std::shared_ptr<vk::HostVisibleBuffer> ImageBufferCache::detachCurrentBuffer() {
    auto detached = caches_[current_];
    caches_[current_] = nullptr;
    capacities_[current_] = 0;
    bases_[current_] = 0;
    return detached;
}

void ImageBufferCache::replaceCurrentBuffer(std::shared_ptr<vk::HostVisibleBuffer> buffer) {
    caches_[current_] = std::move(buffer);
    capacities_[current_] = caches_[current_]->size();
    bases_[current_] = 0;
}
