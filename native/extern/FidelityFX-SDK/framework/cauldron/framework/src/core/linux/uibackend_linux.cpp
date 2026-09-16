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

#if defined(__linux__)

#include "core/linux/uibackend_linux.h"

#include "core/framework.h"
#include "core/loaders/textureloader.h"
#include "core/taskmanager.h"
#include "core/uimanager.h"
#include "imgui/imgui.h"

#include "render/device.h"
#include "render/dynamicresourcepool.h"
#include "render/rendermodules/ui/uirendermodule.h"

namespace cauldron
{
    UIBackend* UIBackend::CreateUIBackend()
    {
        return new UIBackendInternal();
    }

    UIBackendInternal::UIBackendInternal() :
        UIBackend()
    {
        m_pImGuiContext = ImGui::CreateContext();
        CauldronAssert(ASSERT_ERROR, m_pImGuiContext, L"Could not create ImGui context, UI will be disabled.");

        ImGui::StyleColorsDark();

        ImGuiIO& io = ImGui::GetIO();
        io.BackendRendererName = "imgui_impl_cauldron";
        io.BackendFlags |= ImGuiBackendFlags_RendererHasVtxOffset;

        if (GetConfig()->DeveloperMode)
            io.IniFilename = nullptr;

        std::function<void(void*)> loadFont = [this](void*) { this->LoadUIFont(); };
        std::function<void(void*)> loadCompleteCallback = [this](void*) { this->UIFontLoadComplete(); };
        TaskCompletionCallback* pCompletionCallback = new TaskCompletionCallback(Task(loadCompleteCallback));
        Task fontLoadTask(loadFont, nullptr, pCompletionCallback);
        GetTaskManager()->AddTask(fontLoadTask);
    }

    UIBackendInternal::~UIBackendInternal()
    {
        ImGui::DestroyContext(m_pImGuiContext);
    }

    void UIBackendInternal::PlatformUpdate(double deltaTime)
    {
        ImGuiIO& io = ImGui::GetIO();
        io.DeltaTime = deltaTime > 0.0 ? static_cast<float>(deltaTime) : 1.0f / 60.0f;

        const ResolutionInfo resInfo = GetFramework()->GetResolutionInfo();
        io.DisplaySize = ImVec2(resInfo.fDisplayWidth(), resInfo.fDisplayHeight());
    }

    bool UIBackendInternal::MessageHandler(const void* pMessage)
    {
        return false;
    }

    void UIBackendInternal::BeginUIUpdates()
    {
        ImGui::NewFrame();
    }

    void UIBackendInternal::EndUIUpdates()
    {
        ImGui::Render();
    }

    void UIBackendInternal::BuildTabbedDialog(Vec2 uiscale)
    {
    }

    void UIBackendInternal::BuildPerfDialog(Vec2 uiscale)
    {
    }

    void UIBackendInternal::BuildOutputDialog(Vec2 uiscale)
    {
    }

    void UIBackendInternal::BuildGeneralTab()
    {
    }

    void UIBackendInternal::BuildSceneTab()
    {
    }

    void UIBackendInternal::LoadUIFont()
    {
        ImGuiIO& io = ImGui::GetIO();

        ImFontConfig font_cfg;
        font_cfg.SizePixels = GetConfig()->FontSize;
        io.Fonts->AddFontDefault(&font_cfg);

        unsigned char* pixels;
        int width, height;
        io.Fonts->GetTexDataAsRGBA32(&pixels, &width, &height);
        MemTextureDataBlock* pDataBlock = new MemTextureDataBlock(reinterpret_cast<char*>(pixels));

        TextureDesc fontDesc = TextureDesc::Tex2D(L"UIFontTexture", ResourceFormat::RGBA8_UNORM, width, height, 1, 1);
        m_pFontTexture = GetDynamicResourcePool()->CreateTexture(&fontDesc, ResourceState::CopyDest);
        CauldronAssert(ASSERT_ERROR, m_pFontTexture, L"Could not create the font texture for UI");

        if (m_pFontTexture)
        {
            const_cast<Texture*>(m_pFontTexture)->CopyData(pDataBlock);

            Barrier textureTransition = Barrier::Transition(m_pFontTexture->GetResource(),
                                                            ResourceState::CopyDest,
                                                            ResourceState::PixelShaderResource | ResourceState::NonPixelShaderResource);
            GetDevice()->ExecuteResourceTransitionImmediate(1, &textureTransition);
        }

        delete pDataBlock;
    }

    void UIBackendInternal::UIFontLoadComplete()
    {
        while (!GetFramework()->IsRunning()) {}

        RenderModule* pRenderModule = GetFramework()->GetRenderModule("UIRenderModule");
        CauldronAssert(ASSERT_CRITICAL, pRenderModule, L"Could not find UI render module to load font into");
        static_cast<UIRenderModule*>(pRenderModule)->SetFontResourceTexture(m_pFontTexture);

        m_BackendReady.store(true);
    }

    void UIText::BuildUI()
    {
        ImGui::Text("%s", GetDesc());
    }

    void UIButton::BuildUI()
    {
        if (ImGui::Button(GetDesc()))
        {
            m_Callback();
        }
    }

    void UICheckBox::BuildUI()
    {
        bool cur = GetData();

        if (ImGui::Checkbox(GetDesc(), &cur))
        {
            SetData(cur);
        }
    }

    void UIRadioButton::BuildUI()
    {
        CauldronError(L"Not yet implemented! Add support to ImGuiBackendCommon::BuildGeneralTab()");
    }

    void UICombo::BuildUI()
    {
        int32_t cur = GetData();

        if (ImGui::Combo(GetDesc(), &cur, m_Option.data(), static_cast<int32_t>(m_Option.size())))
        {
            SetData(cur);
        }
    }

    template <>
    void UISlider<int32_t>::BuildUI()
    {
        int32_t cur = GetData();

        if (ImGui::SliderInt(GetDesc(), &cur, m_MinValue, m_MaxValue, m_Format))
        {
            SetData(cur);
        }
    }

    template <>
    void UISlider<float>::BuildUI()
    {
        float cur = GetData();

        if (ImGui::SliderFloat(GetDesc(), &cur, m_MinValue, m_MaxValue, m_Format))
        {
            SetData(cur);
        }
    }

    void UISeparator::BuildUI()
    {
        ImGui::Separator();
    }

} // namespace cauldron

#endif // defined(__linux__)
