@echo off
setlocal
rem Builds the native renderer and installs core.dll + shaders into src\main\resources\radiante-native.
rem Requires Visual Studio 2026 (C++ workload) and the Vulkan SDK.
set CM="C:\Program Files\Microsoft Visual Studio\18\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe"
if "%RADIANTE_BUILD_DIR%"=="" set RADIANTE_BUILD_DIR=C:\rtb\radiante
set SRC=%~dp0
%CM% -S "%SRC%." -B "%RADIANTE_BUILD_DIR%" -G "Visual Studio 18 2026" -A x64 -DMCVR_ENABLE_NRD=ON -DUSE_AMD=ON -DCMAKE_POLICY_VERSION_MINIMUM=3.5 || exit /b 1
%CM% --build "%RADIANTE_BUILD_DIR%" --config Release -j 16 || exit /b 1
%CM% --install "%RADIANTE_BUILD_DIR%" --config Release || exit /b 1
