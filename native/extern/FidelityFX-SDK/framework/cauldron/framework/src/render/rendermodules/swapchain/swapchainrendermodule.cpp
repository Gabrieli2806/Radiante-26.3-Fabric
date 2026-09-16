// This file is part of the FidelityFX SDK.
//
// Copyright (C) 2024 Advanced Micro Devices, Inc.
// 
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files(the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and /or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions :
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
// THE SOFTWARE.

#include "core/framework.h"
#include "render/rendermodules/swapchain/swapchainrendermodule.h"
#include "render/parameterset.h"
#include "render/pipelineobject.h"
#include "render/profiler.h"
#include "render/rasterview.h"
#include "render/rootsignature.h"
#include "render/swapchain.h"
#include "render/texture.h"

#include <algorithm>
#include <cctype>
#include <cstdlib>
#include <cstring>
#include <string>

namespace cauldron
{
    namespace
    {
        bool IsEnabledValue(const char* value)
        {
            if (!value)
                return false;
            if (std::strcmp(value, "1") == 0)
                return true;
            std::string lower(value);
            std::transform(lower.begin(), lower.end(), lower.begin(), [](unsigned char c) {
                return static_cast<char>(std::tolower(c));
            });
            return (lower == "true" || lower == "yes" || lower == "on");
        }

        bool ContainsCaseInsensitive(const char* value, const char* token)
        {
            if (!value || !token)
                return false;
            std::string lower(value);
            std::string tokenLower(token);
            std::transform(lower.begin(), lower.end(), lower.begin(), [](unsigned char c) {
                return static_cast<char>(std::tolower(c));
            });
            std::transform(tokenLower.begin(), tokenLower.end(), tokenLower.begin(), [](unsigned char c) {
                return static_cast<char>(std::tolower(c));
            });
            return lower.find(tokenLower) != std::string::npos;
        }
    }

    void SwapChainRenderModule::Init(const json& initData)
    {
        // Setup the texture to be the swapchain render target input
        m_pSwapChainProxyTexture = GetFramework()->GetRenderTexture(L"SwapChainProxy");
        m_pHdrTexture = GetFramework()->GetRenderTexture(L"HDR11Color");
        m_pTexture = m_pSwapChainProxyTexture;
        if (m_pHdrTexture)
            m_pHdrRasterView = GetRasterViewAllocator()->RequestRasterView(m_pHdrTexture, ViewDimension::Texture2D);
        if (!m_pHdrTexture)
            CauldronWarning(L"SwapChainRenderModule: HDR11Color texture not found.");
        if (m_pHdrTexture && !m_pHdrRasterView)
            CauldronWarning(L"SwapChainRenderModule: HDR11Color raster view unavailable.");

        // root signature
        RootSignatureDesc signatureDesc;
        signatureDesc.AddConstantBufferView(0, ShaderBindStage::Pixel, 1);
        signatureDesc.AddTextureSRVSet(0, ShaderBindStage::Pixel, 1);

        m_pRootSignature = RootSignature::CreateRootSignature(L"SampleRenderPass_RootSignature", signatureDesc);

        m_pRenderTarget = GetFramework()->GetSwapChain()->GetBackBufferRT();
        CauldronAssert(ASSERT_CRITICAL, m_pRenderTarget != nullptr, L"Couldn't get the swapchain render target when initializing SwapChainRenderModule.");

        CauldronAssert(ASSERT_ERROR, m_pRenderTarget->GetDesc().Width == m_pTexture->GetDesc().Width && m_pRenderTarget->GetDesc().Height == m_pTexture->GetDesc().Height, L"Final Render Resource and SwapChain width does not match.");

        // Get a raster view
        m_pRasterView = GetRasterViewAllocator()->RequestRasterView(m_pRenderTarget, ViewDimension::Texture2D);

        // Setup the pipeline object
        PipelineDesc psoDesc;
        psoDesc.SetRootSignature(m_pRootSignature);

        // Setup the shaders to build on the pipeline object
        psoDesc.AddShaderDesc(ShaderBuildDesc::Vertex(L"fullscreen.hlsl", L"FullscreenVS", ShaderModel::SM6_0, nullptr));
        psoDesc.AddShaderDesc(ShaderBuildDesc::Pixel(L"copytexture.hlsl", L"CopyTextureToSwapChainPS", ShaderModel::SM6_0, nullptr));

        // Setup remaining information and build
        psoDesc.AddPrimitiveTopology(PrimitiveTopologyType::Triangle);
        psoDesc.AddRasterFormats(m_pRenderTarget->GetFormat());

        m_pPipelineObj = PipelineObject::CreatePipelineObject(L"SwapChainCopyPass_PipelineObj", psoDesc);

        m_pParameters = ParameterSet::CreateParameterSet(m_pRootSignature);
        m_pParameters->SetRootConstantBufferResource(GetDynamicBufferPool()->GetResource(), sizeof(SwapchainCBData), 0);

        // Set our texture to the right parameter slot
        m_pParameters->SetTextureSRV(m_pTexture, ViewDimension::Texture2D, 0);

        // We are now ready for use
        SetModuleReady(true);
    }

    SwapChainRenderModule::~SwapChainRenderModule()
    {
        delete m_pRootSignature;
        delete m_pPipelineObj;
        delete m_pParameters;
    }

    void SwapChainRenderModule::Execute(double deltaTime, CommandList* pCmdList)
    {
        GPUScopedProfileCapture SwapchainMarker(pCmdList, L"SwapChain");

        m_ConstantData.displayMode = GetFramework()->GetSwapChain()->GetSwapChainDisplayMode();

        // Cauldron resources need to be transitioned app-side to avoid confusion in states internally
        // Render modules expect resources coming in/going out to be in a shader read state
        Barrier barrier = Barrier::Transition(m_pRenderTarget->GetCurrentResource(), ResourceState::NonPixelShaderResource | ResourceState::PixelShaderResource, ResourceState::RenderTargetResource);
        ResourceBarrier(pCmdList, 1, &barrier);

        ResourceViewInfo backBufferRtv = GetFramework()->GetSwapChain()->GetBackBufferRTV();
        const char* debugClear = std::getenv("CAULDRON_DEBUG_SWAPCHAIN_CLEAR");
        const char* debugHdrClear = std::getenv("CAULDRON_DEBUG_HDR11_CLEAR");
        const char* debugSource = std::getenv("CAULDRON_DEBUG_SWAPCHAIN_SOURCE");
        if (!m_DebugEnvLogged)
        {
            std::wstring clearValue = StringToWString(debugClear ? debugClear : "(null)");
            std::wstring hdrValue = StringToWString(debugHdrClear ? debugHdrClear : "(null)");
            std::wstring sourceValue = StringToWString(debugSource ? debugSource : "(null)");
            CauldronWarning(L"SwapChainRenderModule: CAULDRON_DEBUG_SWAPCHAIN_CLEAR=%ls", clearValue.c_str());
            CauldronWarning(L"SwapChainRenderModule: CAULDRON_DEBUG_HDR11_CLEAR=%ls", hdrValue.c_str());
            CauldronWarning(L"SwapChainRenderModule: CAULDRON_DEBUG_SWAPCHAIN_SOURCE=%ls", sourceValue.c_str());
            m_DebugEnvLogged = true;
        }
        if (debugClear && std::strcmp(debugClear, "1") == 0)
        {
            const float debugColor[4] = {0.1f, 0.6f, 0.1f, 1.0f};
            ClearRenderTarget(pCmdList, &backBufferRtv, debugColor);
            barrier = Barrier::Transition(m_pRenderTarget->GetCurrentResource(),
                                          ResourceState::RenderTargetResource,
                                          ResourceState::NonPixelShaderResource | ResourceState::PixelShaderResource);
            ResourceBarrier(pCmdList, 1, &barrier);
            return;
        }

        if (debugHdrClear && std::strcmp(debugHdrClear, "1") == 0 && m_pHdrTexture && m_pHdrRasterView)
        {
            const GPUResource* hdrResource = m_pHdrTexture->GetResource();
            ResourceState hdrState = hdrResource->GetCurrentResourceState();
            Barrier hdrBarrier = Barrier::Transition(hdrResource, hdrState, ResourceState::RenderTargetResource);
            ResourceBarrier(pCmdList, 1, &hdrBarrier);
            const float hdrDebugColor[4] = {1.0f, 0.0f, 1.0f, 1.0f};
            ResourceViewInfo hdrRtv = m_pHdrRasterView->GetResourceView();
            ClearRenderTarget(pCmdList, &hdrRtv, hdrDebugColor);
            hdrBarrier = Barrier::Transition(hdrResource,
                                             ResourceState::RenderTargetResource,
                                             ResourceState::NonPixelShaderResource | ResourceState::PixelShaderResource);
            ResourceBarrier(pCmdList, 1, &hdrBarrier);
        }

        const Texture* sourceTexture = m_pSwapChainProxyTexture;
        if (debugSource && m_pHdrTexture)
        {
            if (ContainsCaseInsensitive(debugSource, "hdr") || IsEnabledValue(debugSource))
                sourceTexture = m_pHdrTexture;
        }
        if (sourceTexture != m_pTexture)
        {
            m_pTexture = sourceTexture;
            m_pParameters->SetTextureSRV(m_pTexture, ViewDimension::Texture2D, 0);
        }

        ClearRenderTarget(pCmdList, &backBufferRtv, m_pBackbufferClearColor);

        BeginRaster(pCmdList, 1, &m_pRasterView);

        // Allocate a dynamic constant buffers and set
        BufferAddressInfo bufferInfo  = GetDynamicBufferPool()->AllocConstantBuffer(sizeof(SwapchainCBData), &m_ConstantData);

        // Update constant buffers
        m_pParameters->UpdateRootConstantBuffer(&bufferInfo, 0);

        // Bind all the parameters
        m_pParameters->Bind(pCmdList, m_pPipelineObj);

        // Swap chain RM always done at display res
        const ResolutionInfo& resInfo = GetFramework()->GetResolutionInfo();
        SetViewportScissorRect(pCmdList, 0, 0, resInfo.DisplayWidth, resInfo.DisplayHeight, 0.f, 1.f);

        // Set pipeline and draw
        SetPrimitiveTopology(pCmdList, PrimitiveTopology::TriangleList);
        SetPipelineState(pCmdList, m_pPipelineObj);

        DrawInstanced(pCmdList, 3);

        EndRaster(pCmdList);

        // Render modules expect resources coming in/going out to be in a shader read state
        barrier = Barrier::Transition(m_pRenderTarget->GetCurrentResource(), ResourceState::RenderTargetResource, ResourceState::NonPixelShaderResource | ResourceState::PixelShaderResource);
        ResourceBarrier(pCmdList, 1, &barrier);
    }

} // namespace cauldron
