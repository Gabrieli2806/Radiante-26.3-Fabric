/*
 * Copyright (c) 2024-2025, NVIDIA CORPORATION.  All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-FileCopyrightText: Copyright (c) 2024-2025 NVIDIA CORPORATION
 * SPDX-License-Identifier: Apache-2.0
 */

/*
 * MODIFICATIONS and INTEGRATION:
 *
 * Copyright (c) 2026 Radiance
 *
 * This file has been modified from its original version to be integrated into
 * Radiance Mod.
 *
 * Modifications include:
 * - Integration with Radiance Mod's vulkan rendering system.
 * - Refactoring of APIs, introducing RAII and supporting shared_ptr.
 *
 * These modifications are licensed under the GNU General Public License
 * as published by the Free Software Foundation; either version 3 of the License,
 * or (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

#pragma once

#include "common/shared.hpp"
#include "common/singleton.hpp"
#include "core/all_extern.hpp"

#include <NRD.h>

#include "core/render/render_framework.hpp"

#include <cstddef>
#include <cstdint>

class NrdWrapper : public SharedObject<NrdWrapper> {
  public:
    /* Create the Vulkan NRD Wrapper
     * NrdWrapper does not automatically resize its resources and will have to get recreated if the size changes.
     * However, it does support rendering to only a part of the images, as described in the NRD documentation
     * regarding nrd::CommonSettings::resourceSize and nrd::CommonSettings::rectSize.
     *
     * The userTexturePool is a pool of textures that NRD uses as input and output data.
     * Which textures are needed depends on the actual denoiser in use. Refer to NRDDescs.h
     * to find out which textures are needed for which denoiser.
     * To make it an easier interface, we use an array where each slot corresponds to one 'nrd::ResourceType'
     * texture resource. Depending on the denoiser in use, this array will be sparsely populated.
     * 'userTexturePool' (but NOT the textures it contains) will be copied into an internal copy
     * and thus can be discarded after the call.
     *
     * NRD uses two internal pools of textures ("resources"): permanent and transient ones.
     * Permanent textures must not be altered outside of NRD, while transient textures could be
     * reused as (or aliased with) other application specific textures. Albeit, this wrapper
     * does not expose the transient pool to the application and thus makes no use of reusing
     * transient textures for other purposes.
     */
    NrdWrapper() = default;
    ~NrdWrapper();

    void init(std::shared_ptr<Framework> framework,
              uint16_t width,
              uint16_t height);

    void setUserPoolTexture(
        std::shared_ptr<std::array<std::shared_ptr<vk::DeviceLocalImage>, size_t(nrd::ResourceType::MAX_NUM)>>
            userTexturePool);

    /* Set common NRD settings, typically called once per frame */
    void setCommonSettings(nrd::CommonSettings &settings);

    /* Denoiser specifc settings */
    void setREBLURSettings(const nrd::ReblurSettings &ssettings);
    void setRELAXSettings(const nrd::RelaxSettings &settings);

    /* Perform the actual denoising. NRD will read from a number of 'IN_*' images in the user texture pool
     * and write to the 'OUT_' images specified by the denoiser.
     * Refer to NRDDescs.h for the per-denoiser input and output textures.
     */
    void denoise(const nrd::Identifier *denoisers, uint32_t denoisersNum, VkCommandBuffer &commandBuffer);

    /* When the NRD library is compiled, it is hardcoded to a specific Normal/Roughness encoding.
     * It requires to use a specific image format to store the encoded values.
     */
    static VkFormat getNormalRoughnessFormat();

  private:
    struct NRDPipeline {
        VkPipeline pipeline = VK_NULL_HANDLE;
        VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
        VkDescriptorSetLayout resourceDescriptorLayout = VK_NULL_HANDLE;
        uint32_t numBindings = 0;
    };

    NrdWrapper(const NrdWrapper &) = delete;
    NrdWrapper &operator=(const NrdWrapper) = delete;

    std::shared_ptr<vk::DeviceLocalImage> createTexture(const nrd::TextureDesc &tDesc, uint16_t width, uint16_t height);
    void createPipelines();
    void setDenoiserSettings(nrd::Identifier identifier, const void *settings);
    void dispatch(VkCommandBuffer commandBuffer, const nrd::DispatchDesc &dispatchDesc);

    std::weak_ptr<Framework> framework_;
    nrd::Instance *m_instance = nullptr;

    std::vector<std::shared_ptr<vk::DeviceLocalImage>> m_permanentTextures;
    std::vector<std::shared_ptr<vk::DeviceLocalImage>> m_transientTextures;
    std::shared_ptr<std::array<std::shared_ptr<vk::DeviceLocalImage>, size_t(nrd::ResourceType::MAX_NUM)>>
        m_userTexturePool;
    std::vector<VkSampler> m_vkSamplers;
    std::vector<std::shared_ptr<vk::Sampler>> m_samplers;
    std::shared_ptr<vk::HostVisibleBuffer> m_constantBuffer;

    std::vector<NRDPipeline> m_pipelines;

    // Vulkan doesn't let us create a pipeline layout with two sets that use
    // push descriptors. So if NRD places immutable samplers in a different
    // set, then we store its set (which does not require updates) here.
    // We use a single one that will be shared among all pipelines.
    VkDescriptorSetLayout m_samplerConstBufferDescriptorLayout;
    VkDescriptorPool m_samplerConstBufferDescriptorPool;
    VkDescriptorSet m_samplerConstBufferDescriptorSet;
};