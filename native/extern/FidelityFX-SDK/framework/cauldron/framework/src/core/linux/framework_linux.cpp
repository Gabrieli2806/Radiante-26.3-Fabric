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

#include "core/linux/framework_linux.h"

#include "core/uimanager.h"
#include "misc/helpers.h"
#include "render/device.h"
#include "render/swapchain.h"

#include <cstring>
#include <thread>

namespace cauldron
{
    FrameworkInternal::FrameworkInternal(Framework* pFramework, const FrameworkInitParams* pInitParams) :
        FrameworkImpl(pFramework),
        m_InitParams(reinterpret_cast<const FrameworkInitParamsInternal*>(pInitParams->AdditionalParams))
    {
    }

    FrameworkInternal::~FrameworkInternal()
    {
        if (m_Window)
        {
            glfwDestroyWindow(m_Window);
            m_Window = nullptr;
        }
        glfwTerminate();
    }

    void FrameworkInternal::Init()
    {
        if (!glfwInit())
        {
            const char* description = nullptr;
            int error = glfwGetError(&description);
            if (description)
                CauldronCritical(L"Failed to initialize GLFW: %ls", StringToWString(std::string(description)).c_str());
            else
                CauldronCritical(L"Failed to initialize GLFW (error %d).", error);
            return;
        }

        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        uint32_t width = m_pFramework->m_Config.Width;
        uint32_t height = m_pFramework->m_Config.Height;

        GLFWmonitor* monitor = nullptr;
        if (m_pFramework->m_Config.Fullscreen)
        {
            monitor = glfwGetPrimaryMonitor();
            const GLFWvidmode* mode = glfwGetVideoMode(monitor);
            if (mode)
            {
                width = static_cast<uint32_t>(mode->width);
                height = static_cast<uint32_t>(mode->height);
                m_PresentationMode = PRESENTATIONMODE_BORDERLESS_FULLSCREEN;
                m_pFramework->m_Config.Width = width;
                m_pFramework->m_Config.Height = height;
            }
        }

        std::string windowTitle = WStringToString(m_pFramework->m_Name);
        m_Window = glfwCreateWindow(static_cast<int>(width),
                                    static_cast<int>(height),
                                    windowTitle.c_str(),
                                    monitor,
                                    nullptr);
        if (!m_Window)
        {
            const char* description = nullptr;
            int error = glfwGetError(&description);
            if (description)
                CauldronCritical(L"Failed to create GLFW window: %ls", StringToWString(std::string(description)).c_str());
            else
                CauldronCritical(L"Failed to create GLFW window (error %d).", error);
            return;
        }

        glfwSetWindowUserPointer(m_Window, this);
        glfwSetFramebufferSizeCallback(m_Window, FrameworkInternal::FramebufferResizeCallback);
        glfwSetWindowFocusCallback(m_Window, FrameworkInternal::WindowFocusCallback);
        glfwSetWindowCloseCallback(m_Window, FrameworkInternal::WindowCloseCallback);

        // Set app name for benchmark reporting.
        if (m_InitParams && m_InitParams->Argc > 0 && m_InitParams->Argv && m_InitParams->Argv[0])
        {
            m_pFramework->m_Config.AppName = StringToWString(std::string(m_InitParams->Argv[0]));
        }
        else
        {
            m_pFramework->m_Config.AppName = m_pFramework->m_Name;
        }
    }

    void FrameworkInternal::FramebufferResizeCallback(GLFWwindow* window, int width, int height)
    {
        FrameworkInternal* impl = reinterpret_cast<FrameworkInternal*>(glfwGetWindowUserPointer(window));
        if (!impl)
            return;

        impl->m_PendingWidth = width;
        impl->m_PendingHeight = height;
        impl->m_PendingResize = true;
        impl->m_Minimized = (width == 0 || height == 0);
    }

    void FrameworkInternal::WindowFocusCallback(GLFWwindow* window, int focused)
    {
        FrameworkInternal* impl = reinterpret_cast<FrameworkInternal*>(glfwGetWindowUserPointer(window));
        if (!impl)
            return;
        if (focused)
            impl->OnFocusGained();
        else
            impl->OnFocusLost();
    }

    void FrameworkInternal::WindowCloseCallback(GLFWwindow* window)
    {
        FrameworkInternal* impl = reinterpret_cast<FrameworkInternal*>(glfwGetWindowUserPointer(window));
        if (!impl)
            return;
        impl->m_Quitting = true;
    }

    void FrameworkInternal::OnResize(uint32_t width, uint32_t height)
    {
        CauldronAssert(ASSERT_CRITICAL,
                       std::this_thread::get_id() == m_pFramework->MainThreadID(),
                       L"Cauldron: OnResize: Expecting OnResize to be called on MainThread. Not thread safe!");

        GetDevice()->FlushAllCommandQueues();
        GetSwapChain()->OnResize(width, height);
        m_pFramework->ResizeEvent();
    }

    void FrameworkInternal::OnFocusLost()
    {
        m_pFramework->FocusLostEvent();
    }

    void FrameworkInternal::OnFocusGained()
    {
        m_pFramework->FocusGainedEvent();
    }

    int32_t FrameworkInternal::Run()
    {
        // Everything is now initialized and we are entering the "running" state
        m_pFramework->m_Running.store(true);

        while (!m_Quitting && m_Window && !glfwWindowShouldClose(m_Window))
        {
            GetDevice()->UpdateAntiLag2();

            glfwPollEvents();

            if (!m_Minimized && !m_Quitting)
            {
                if (m_PendingResize)
                {
                    const ResolutionInfo& resInfo = m_pFramework->GetResolutionInfo();
                    uint32_t width = static_cast<uint32_t>(m_PendingWidth);
                    uint32_t height = static_cast<uint32_t>(m_PendingHeight);
                    if (width != resInfo.DisplayWidth || height != resInfo.DisplayHeight)
                        OnResize(width, height);
                    m_PendingResize = false;
                }
                m_pFramework->MainLoop();
            }
        }

        return 0;
    }

} // namespace cauldron

#endif // defined(__linux__)
