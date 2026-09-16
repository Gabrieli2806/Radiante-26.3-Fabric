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

#if defined(_WIN)
    #include "core/win/framework_win.h" // Framework
#elif defined(__linux__)
    #include "core/linux/framework_linux.h"
#else
    #error No Main defined for Platform!
#endif

// If using a custom sample, pull in its header
#if defined(SampleInclude)

    #define XSTRING(x)   #x
    #define AS_STRING(x) XSTRING(x)

    #define FOLDER(x) x##/
    #define INCLUDEFILE(x)   x##sample.h

    #define PATH(x) FOLDER(x)##INCLUDEFILE(x)

    // Sample render module
    #include AS_STRING(PATH(SampleInclude))

// Otherwise use the default
#else
    #include "sample/sample.h"  // Sample defines for instantiation and naming
    typedef Sample FrameworkType;

#endif // defined(SampleHeader)

using namespace cauldron;

#if !defined(SampleName)
    #pragma message("Please define your sample's name in your CMakeLists.txt file")
    #error Please define your sample's name in your CMakeLists.txt file
#endif // !defined(SampleName)

#ifdef _WIN
static FrameworkInitParamsInternal s_WindowsParams;
//////////////////////////////////////////////////////////////////////////
// WinMain
int WINAPI wWinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance, LPWSTR lpCmdLine, int nCmdShow)
{
    // Create the sample and kick it off to the framework to run
    FrameworkInitParams initParams  = { };
    initParams.Name                 = const_cast<wchar_t*>(SampleName);
    initParams.CmdLine              = lpCmdLine;
    initParams.AdditionalParams     = &s_WindowsParams;

    // Setup the windows info
    s_WindowsParams.InstanceHandle       = hInstance;
    s_WindowsParams.CmdShow              = nCmdShow;

    FrameworkType frameworkInstance(&initParams);
    return RunFramework(&frameworkInstance);
}
#elif defined(__linux__)
static FrameworkInitParamsInternal s_LinuxParams;
static std::wstring s_CmdLineStorage;
int main(int argc, char** argv)
{
    FrameworkInitParams initParams = {};
    initParams.Name = SampleName;

    s_LinuxParams.Argc = argc;
    s_LinuxParams.Argv = argv;

    std::string cmdLine;
    for (int i = 1; i < argc; ++i)
    {
        if (i > 1)
            cmdLine += " ";
        cmdLine += argv[i];
    }
    s_CmdLineStorage = StringToWString(cmdLine);
    initParams.CmdLine = const_cast<wchar_t*>(s_CmdLineStorage.c_str());
    initParams.AdditionalParams = &s_LinuxParams;

    FrameworkType frameworkInstance(&initParams);
    return RunFramework(&frameworkInstance);
}
#endif // _WIN
