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

#pragma once

#include "core/framework.h"

#if defined(_VK) && !defined(GLFW_INCLUDE_VULKAN)
#define GLFW_INCLUDE_VULKAN
#endif
#include <GLFW/glfw3.h>

namespace cauldron
{
    struct FrameworkInitParamsInternal
    {
        int    Argc = 0;
        char** Argv = nullptr;
    };

    enum PresentationMode
    {
        PRESENTATIONMODE_WINDOWED,
        PRESENTATIONMODE_BORDERLESS_FULLSCREEN
    };

    class FrameworkInternal : public FrameworkImpl
    {
    public:
        FrameworkInternal(Framework* pFramework, const FrameworkInitParams* pInitParams);
        virtual ~FrameworkInternal();

        virtual void Init() override;
        virtual int32_t Run() override;

        virtual void PreRun() override {}
        virtual void PostRun() override {}
        virtual void Shutdown() override {}

        GLFWwindow* GetGLFWwindow() const { return m_Window; }
        const PresentationMode GetPresentationMode() const { return m_PresentationMode; }

    private:
        FrameworkInternal() = delete;

        static void FramebufferResizeCallback(GLFWwindow* window, int width, int height);
        static void WindowFocusCallback(GLFWwindow* window, int focused);
        static void WindowCloseCallback(GLFWwindow* window);

        void OnResize(uint32_t width, uint32_t height);
        void OnFocusLost();
        void OnFocusGained();

        GLFWwindow*       m_Window = nullptr;
        PresentationMode  m_PresentationMode = PRESENTATIONMODE_WINDOWED;
        bool              m_PendingResize = false;
        bool              m_Minimized = false;
        bool              m_Quitting = false;
        int               m_PendingWidth = 0;
        int               m_PendingHeight = 0;

        const FrameworkInitParamsInternal* m_InitParams = nullptr;
    };

} // namespace cauldron
