cmake_minimum_required(VERSION 3.15)

project(minecraft-vulkan-renderer LANGUAGES C CXX)

if(NOT DEFINED JAVA_PROJECT_ROOT_DIR OR "${JAVA_PROJECT_ROOT_DIR}" STREQUAL "")
    get_filename_component(JAVA_PROJECT_ROOT_DIR "${PROJECT_SOURCE_DIR}/.." ABSOLUTE)
endif()
file(TO_CMAKE_PATH "${JAVA_PROJECT_ROOT_DIR}" JAVA_PROJECT_ROOT_DIR)
message(STATUS "JAVA_PROJECT_ROOT_DIR: ${JAVA_PROJECT_ROOT_DIR}")

set(CMAKE_C_STANDARD 99)
set(CMAKE_CXX_STANDARD 23)
if(MSVC)
    add_compile_definitions($<$<CONFIG:Debug>:DEBUG>)
else()
    if(CMAKE_BUILD_TYPE STREQUAL "Debug")
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fPIC -g -O0")
        add_compile_definitions(DEBUG)
    else()
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fPIC -O2")
    endif()
endif()

if(UNIX AND NOT APPLE)
    # Keep libcore.so loadable on older glibc than the build machine's (see glibc_compat.h).
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -include ${PROJECT_SOURCE_DIR}/glibc_compat.h")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -include ${PROJECT_SOURCE_DIR}/glibc_compat.h")
endif()

set(MCVR_INCLUDE_DIR ${PROJECT_SOURCE_DIR}/src)
message(STATUS "Include dir: ${MCVR_INCLUDE_DIR}")

set(MCVR_INSTALL_BIN_DIR ${PROJECT_SOURCE_DIR}/bin)
message(STATUS "Install bin dir: ${MCVR_INSTALL_BIN_DIR}")

set(MCVR_INSTALL_LIB_DIR ${JAVA_PROJECT_ROOT_DIR}/common/src/main/resources/radiante-native/)
message(STATUS "Install lib dir: ${MCVR_INSTALL_LIB_DIR}")

include_directories(${MCVR_INCLUDE_DIR})

# Volk
set(VOLK_BUILD_TESTS OFF CACHE BOOL "Disable tests")
set(VOLK_BUILD_EXAMPLES OFF CACHE BOOL "Disable examples")
add_subdirectory(extern/volk EXCLUDE_FROM_ALL)
set(VOLK_INCLUDE_DIR ${PROJECT_SOURCE_DIR}/extern/volk)

# VMA
add_subdirectory(extern/vma EXCLUDE_FROM_ALL)
set(VMA_INCLUDE_DIR ${PROJECT_SOURCE_DIR}/extern/vma/include)

# GLM
set(GLM_INCLUDE_DIR ${PROJECT_SOURCE_DIR}/extern/glm)

# VK.XML
set(VK_XML_PATH ${PROJECT_SOURCE_DIR}/extern/vulkan_headers/registry/vk.xml)

# STB
set(STB_INCLUDE_DIR ${PROJECT_SOURCE_DIR}/extern/stb)

# tinyexpr
add_library(tinyexpr STATIC extern/tinyexpr/tinyexpr.c)
set_target_properties(tinyexpr PROPERTIES POSITION_INDEPENDENT_CODE ON)
set(TINYEXPR_INCLUDE_DIR ${PROJECT_SOURCE_DIR}/extern/tinyexpr)

# DLSS
add_subdirectory(extern/DLSS EXCLUDE_FROM_ALL)

# JNI
if(WIN32)
    find_package(JNI REQUIRED)
else()
    # Only the JNI headers and libjvm: headless JDKs, common on Linux build machines, have no AWT.
    find_package(JNI REQUIRED COMPONENTS JVM)
endif()
message(STATUS "${JNI_INCLUDE_DIRS}")
message(STATUS "${JNI_LIBRARIES}")

option(USE_AMD "Enable AMD specific optimizations/features" ON)
if(USE_AMD)
    message(STATUS "AMD Support Enabled")
else()
    message(STATUS "AMD Support Disabled")
endif()

set(MCVR_BUILT_IN_SHADER_PACKS vanilla-pt)
message(STATUS "Built-in shader packs: ${MCVR_BUILT_IN_SHADER_PACKS}")

# FidelityFX Upscaler (FSR3 Upscaler only)
option(MCVR_ENABLE_FFX_UPSCALER "Enable FidelityFX Upscaler support" ON)
if(MCVR_ENABLE_FFX_UPSCALER)
    message(STATUS "FidelityFX Upscaler Enabled")

    set(FFX_API_BACKEND VK_X64 CACHE STRING "FidelityFX API backend" FORCE)

    # Disable everything except FSR3 Upscaler
    set(FFX_DENOISER OFF CACHE BOOL "Build FFX Denoiser" FORCE)
    set(FFX_ALL OFF CACHE BOOL "Build all FFX components" FORCE)
    set(FFX_SSSR OFF CACHE BOOL "Build FFX SSSR" FORCE)
    # FSR3 frame generation (frame interpolation + optical flow) as well, for frame generation on any GPU.
    set(FFX_FSR3 ON CACHE BOOL "Build FFX FSR3" FORCE)
    set(FFX_FSR3UPSCALER ON CACHE BOOL "Build FFX FSR3 Upscaler" FORCE)
    set(FFX_FI ON CACHE BOOL "Build FFX Frame Interpolation" FORCE)
    set(FFX_OF ON CACHE BOOL "Build FFX Optical Flow" FORCE)

    set(FFX_AUTO_COMPILE_SHADERS ON CACHE BOOL "Compile shaders automatically as a prebuild step." FORCE)

    # Use ffx-api which includes sdk internally, providing the necessary API symbols (ffxQuery)
    set(FFX_BIN_PATH ${CMAKE_BINARY_DIR}/ffx_sdk CACHE STRING "FFX binary output path" FORCE)
    add_subdirectory(extern/FidelityFX-SDK/ffx-api EXCLUDE_FROM_ALL)

    if(TARGET ffx_backend_vk_x64)
        target_include_directories(ffx_backend_vk_x64 PRIVATE ${VOLK_INCLUDE_DIR})
    endif()

    # Note: ffx_denoiser_x64 target will NOT be created, so don't link against it.
else()
    message(STATUS "FidelityFX Upscaler Disabled")
endif()

# NRD Denoiser
option(MCVR_ENABLE_NRD "Enable NRD denoiser support" ON)
if(MCVR_ENABLE_NRD)
    message(STATUS "NRD Denoiser Enabled")

    set(NRD_STATIC_LIBRARY ON CACHE BOOL "Build NRD as a static library" FORCE)
    set(NRD_DISABLE_SHADER_COMPILATION ON CACHE BOOL "Disable NRD shader compilation" FORCE)
    set(NRD_SHADERS_PATH "${PROJECT_SOURCE_DIR}/extern/nrd/_Shaders" CACHE STRING "NRD shader headers path" FORCE)
    set(NRD_NORMAL_ENCODING "2" CACHE STRING "Normal encoding variant (0-4, matches nrd::NormalEncoding)" FORCE)
    set(NRD_ROUGHNESS_ENCODING "1" CACHE STRING "Roughness encoding variant (0-2, matches nrd::RoughnessEncoding)" FORCE)

    add_subdirectory(extern/nrd EXCLUDE_FROM_ALL)

    set(NRD_INCLUDE_DIR ${PROJECT_SOURCE_DIR}/extern/nrd/Include)

    if(TARGET NRD)
        target_include_directories(NRD PRIVATE "${NRD_SHADERS_PATH}")
    endif()
else()
    message(STATUS "NRD Denoiser Disabled")
endif()

# Intel XeSS
option(MCVR_ENABLE_XESS "Enable Intel XeSS support" ON)
if(MCVR_ENABLE_XESS)
    set(XESS_INCLUDE_DIR ${PROJECT_SOURCE_DIR}/extern/xess/inc)
    if(EXISTS "${XESS_INCLUDE_DIR}/xess/xess.h")
        message(STATUS "Intel XeSS Enabled")
    else()
        message(WARNING "MCVR_ENABLE_XESS is ON but extern/xess is missing; building without XeSS backend.")
        set(MCVR_ENABLE_XESS OFF)
    endif()
else()
    message(STATUS "Intel XeSS Disabled")
endif()

# minizip
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build static libraries" FORCE)
set(MZ_BUILD_TESTS OFF CACHE BOOL "Disable minizip tests" FORCE)
set(MZ_BUILD_UNIT_TESTS OFF CACHE BOOL "Disable minizip unit tests" FORCE)
# minizip-ng only fetches zlib on Windows by default; on Linux, without zlib headers it silently drops deflate, the
# built-in shader pack zip cannot be extracted and the world renders black. Always build zlib-ng in, statically.
set(MZ_ZLIB ON CACHE BOOL "Enable deflate in minizip" FORCE)
set(MZ_FETCH_LIBS ON CACHE BOOL "Fetch third-party libraries" FORCE)
set(MZ_FORCE_FETCH_LIBS ON CACHE BOOL "Always use the fetched zlib so libcore has no system zlib dependency" FORCE)
set(MZ_ZSTD OFF CACHE BOOL "Disable zstd in minizip-ng due to upstream target mismatch" FORCE)
# Shader packs only use deflate/store; the rest would add shared-library dependencies on Linux.
set(MZ_BZIP2 OFF CACHE BOOL "Disable bzip2" FORCE)
set(MZ_LZMA OFF CACHE BOOL "Disable lzma" FORCE)
set(MZ_OPENSSL OFF CACHE BOOL "Disable OpenSSL" FORCE)
set(MZ_LIBBSD OFF CACHE BOOL "Disable libbsd" FORCE)
set(MZ_ICONV OFF CACHE BOOL "Disable iconv" FORCE)
# arc4random only exists in glibc 2.36+; minizip falls back to getrandom (glibc 2.25) without it.
set(HAVE_ARC4RANDOM_BUF OFF CACHE INTERNAL "")
set(HAVE_ARC4RANDOM OFF CACHE INTERNAL "")
set(ZLIB_COMPAT ON CACHE BOOL "Build zlib-ng with the zlib API" FORCE)
if(NOT WIN32)
    # Everything static ends up inside libcore.so.
    set(CMAKE_POSITION_INDEPENDENT_CODE ON)
endif()
add_subdirectory(extern/minizip-ng EXCLUDE_FROM_ALL)
set_target_properties(minizip PROPERTIES POSITION_INDEPENDENT_CODE ON)
get_target_property(MCVR_MINIZIP_SOURCES minizip SOURCES)
if(NOT MCVR_MINIZIP_SOURCES MATCHES "mz_strm_zlib")
    message(FATAL_ERROR "minizip-ng was configured without zlib; shader pack zips could not be extracted")
endif()

# nlohmann json
add_subdirectory(extern/json EXCLUDE_FROM_ALL)

add_subdirectory(src)
