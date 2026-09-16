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

#include "core/linux/inputmanager_linux.h"
#include "core/linux/framework_linux.h"

namespace cauldron
{
    static void ScrollCallback(GLFWwindow* window, double xoffset, double yoffset)
    {
        FrameworkInternal* impl = reinterpret_cast<FrameworkInternal*>(glfwGetWindowUserPointer(window));
        if (!impl)
            return;
        InputManagerInternal* input = static_cast<InputManagerInternal*>(GetInputManager());
        if (!input)
            return;
        input->PushWheelChange(yoffset);
    }

    InputManager* InputManager::CreateInputManager()
    {
        return new InputManagerInternal();
    }

    InputManagerInternal::InputManagerInternal() :
        InputManager()
    {
        GLFWwindow* window = GetFramework()->GetImpl()->GetGLFWwindow();
        if (window)
            glfwSetScrollCallback(window, ScrollCallback);
    }

    InputManagerInternal::~InputManagerInternal()
    {
    }

    void InputManagerInternal::PushWheelChange(double wheelChange)
    {
        std::unique_lock<std::mutex> lock(m_WheelMutex);
        m_WheelDelta += wheelChange;
    }

    bool InputManagerInternal::IsKeyDown(KeyboardInputMappings key) const
    {
        GLFWwindow* window = GetFramework()->GetImpl()->GetGLFWwindow();
        if (!window)
            return false;

        switch (key)
        {
        case Key_Shift:
            return glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS ||
                   glfwGetKey(window, GLFW_KEY_RIGHT_SHIFT) == GLFW_PRESS;
        case Key_Ctrl:
            return glfwGetKey(window, GLFW_KEY_LEFT_CONTROL) == GLFW_PRESS ||
                   glfwGetKey(window, GLFW_KEY_RIGHT_CONTROL) == GLFW_PRESS;
        case Key_Alt:
            return glfwGetKey(window, GLFW_KEY_LEFT_ALT) == GLFW_PRESS ||
                   glfwGetKey(window, GLFW_KEY_RIGHT_ALT) == GLFW_PRESS;
        default:
            break;
        }

        static const int s_KeyMap[Key_Count] = {
            GLFW_KEY_0,
            GLFW_KEY_1,
            GLFW_KEY_2,
            GLFW_KEY_3,
            GLFW_KEY_4,
            GLFW_KEY_5,
            GLFW_KEY_6,
            GLFW_KEY_7,
            GLFW_KEY_8,
            GLFW_KEY_9,
            GLFW_KEY_A,
            GLFW_KEY_B,
            GLFW_KEY_C,
            GLFW_KEY_D,
            GLFW_KEY_E,
            GLFW_KEY_F,
            GLFW_KEY_G,
            GLFW_KEY_H,
            GLFW_KEY_I,
            GLFW_KEY_J,
            GLFW_KEY_K,
            GLFW_KEY_L,
            GLFW_KEY_M,
            GLFW_KEY_N,
            GLFW_KEY_O,
            GLFW_KEY_P,
            GLFW_KEY_Q,
            GLFW_KEY_R,
            GLFW_KEY_S,
            GLFW_KEY_T,
            GLFW_KEY_U,
            GLFW_KEY_V,
            GLFW_KEY_W,
            GLFW_KEY_X,
            GLFW_KEY_Y,
            GLFW_KEY_Z,
            GLFW_KEY_BACKSPACE,
            GLFW_KEY_TAB,
            GLFW_KEY_ENTER,
            GLFW_KEY_LEFT_SHIFT,
            GLFW_KEY_LEFT_CONTROL,
            GLFW_KEY_LEFT_ALT,
            GLFW_KEY_PAUSE,
            GLFW_KEY_CAPS_LOCK,
            GLFW_KEY_SPACE,
            GLFW_KEY_PRINT_SCREEN,
            GLFW_KEY_LEFT,
            GLFW_KEY_UP,
            GLFW_KEY_RIGHT,
            GLFW_KEY_DOWN,
            GLFW_KEY_F1,
            GLFW_KEY_F2,
            GLFW_KEY_F3,
            GLFW_KEY_F4,
            GLFW_KEY_F5,
            GLFW_KEY_F6,
            GLFW_KEY_F7,
            GLFW_KEY_F8,
            GLFW_KEY_F9,
            GLFW_KEY_F10,
            GLFW_KEY_F11,
            GLFW_KEY_F12
        };

        if (key < 0 || key >= Key_Count)
            return false;

        int glfwKey = s_KeyMap[key];
        return glfwGetKey(window, glfwKey) == GLFW_PRESS;
    }

    void InputManagerInternal::PollInputStates()
    {
        GLFWwindow* window = GetFramework()->GetImpl()->GetGLFWwindow();
        if (!window)
            return;

        uint32_t prevFrameID = (m_CurrentStateID == 0) ? s_InputStateCacheSize - 1 : m_CurrentStateID - 1;

        m_InputStateRep[m_CurrentStateID].KeyboardState = 0;
        m_InputStateRep[m_CurrentStateID].KeyboardUpState = 0;

        if (!m_IgnoreFrameInputs)
        {
            for (uint32_t keyID = 0; keyID < static_cast<uint32_t>(Key_Count); ++keyID)
            {
                if (IsKeyDown(static_cast<KeyboardInputMappings>(keyID)))
                    m_InputStateRep[m_CurrentStateID].KeyboardState |= (1ull << static_cast<uint64_t>(keyID));
                else if (m_InputStateRep[prevFrameID].GetKeyState(static_cast<KeyboardInputMappings>(keyID)))
                    m_InputStateRep[m_CurrentStateID].KeyboardUpState |= (1ull << static_cast<uint64_t>(keyID));
            }
        }

        m_InputStateRep[m_CurrentStateID].Mouse = MouseState{};

        double wheelDelta = 0.0;
        {
            std::unique_lock<std::mutex> lock(m_WheelMutex);
            wheelDelta = m_WheelDelta;
            m_WheelDelta = 0.0;
        }
        int64_t wheelDeltaInt = static_cast<int64_t>(wheelDelta);

        if (!m_IgnoreFrameInputs)
        {
            bool lButtonDown = glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_LEFT) == GLFW_PRESS;
            bool rButtonDown = glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_RIGHT) == GLFW_PRESS;
            bool mButtonDown = glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_MIDDLE) == GLFW_PRESS;

            if (lButtonDown)
                m_InputStateRep[m_CurrentStateID].Mouse.ButtonState |= 1 << static_cast<uint32_t>(Mouse_LButton);
            if (rButtonDown)
                m_InputStateRep[m_CurrentStateID].Mouse.ButtonState |= 1 << static_cast<uint32_t>(Mouse_RButton);
            if (mButtonDown)
                m_InputStateRep[m_CurrentStateID].Mouse.ButtonState |= 1 << static_cast<uint32_t>(Mouse_MButton);

            for (uint32_t buttonID = 0; buttonID < static_cast<uint32_t>(Mouse_ButtonCount); ++buttonID)
            {
                bool wasDown = (m_InputStateRep[prevFrameID].Mouse.ButtonState & 1 << buttonID) != 0;
                bool isDown = (m_InputStateRep[m_CurrentStateID].Mouse.ButtonState & 1 << buttonID) != 0;
                m_InputStateRep[m_CurrentStateID].Mouse.ButtonUpState |= (wasDown && !isDown) ? 1 << buttonID : 0;
            }

            m_InputStateRep[m_CurrentStateID].Mouse.AxisState[Mouse_Wheel] = static_cast<uint64_t>(wheelDeltaInt);
            m_InputStateRep[m_CurrentStateID].Mouse.AxisDelta[Mouse_Wheel] = wheelDeltaInt;
        }

        double xPos = 0.0;
        double yPos = 0.0;
        glfwGetCursorPos(window, &xPos, &yPos);
        if (!m_HasMousePos)
        {
            m_LastMouseX = xPos;
            m_LastMouseY = yPos;
            m_HasMousePos = true;
        }

        m_InputStateRep[m_CurrentStateID].Mouse.AxisState[Mouse_XAxis] = static_cast<uint64_t>(xPos);
        m_InputStateRep[m_CurrentStateID].Mouse.AxisState[Mouse_YAxis] = static_cast<uint64_t>(yPos);
        m_InputStateRep[m_CurrentStateID].Mouse.AxisDelta[Mouse_XAxis] = static_cast<int64_t>(xPos - m_LastMouseX);
        m_InputStateRep[m_CurrentStateID].Mouse.AxisDelta[Mouse_YAxis] = static_cast<int64_t>(yPos - m_LastMouseY);

        m_LastMouseX = xPos;
        m_LastMouseY = yPos;

        m_IgnoreFrameInputs = false;
    }

} // namespace cauldron

#endif // defined(__linux__)
