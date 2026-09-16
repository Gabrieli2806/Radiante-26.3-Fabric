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

#include "core/inputmanager.h"

#include <GLFW/glfw3.h>
#include <mutex>

namespace cauldron
{
    class InputManagerInternal : public InputManager
    {
    public:
        InputManagerInternal();
        virtual ~InputManagerInternal();

        void PushWheelChange(double wheelChange);

    private:
        virtual void PollInputStates() override;

        bool IsKeyDown(KeyboardInputMappings key) const;

        double m_LastMouseX = 0.0;
        double m_LastMouseY = 0.0;
        bool m_HasMousePos = false;
        double m_WheelDelta = 0.0;
        std::mutex m_WheelMutex;
    };

} // namespace cauldron
