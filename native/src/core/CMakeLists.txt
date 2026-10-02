file(GLOB_RECURSE SOURCE_FILES CONFIGURE_DEPENDS "*.cpp")

add_library(core SHARED ${SOURCE_FILES})
if(NOT WIN32)
    set_source_files_properties(${CMAKE_CURRENT_SOURCE_DIR}/glibc_shims.cpp PROPERTIES COMPILE_DEFINITIONS RADIANTE_GLIBC_SHIM)
endif()
target_link_libraries(core PUBLIC ngx ${JNI_LIBRARIES} VulkanMemoryAllocator volk::volk minizip tinyexpr
                                   nlohmann_json::nlohmann_json)

if(DEFINED ENV{VULKAN_SDK} AND NOT "$ENV{VULKAN_SDK}" STREQUAL "")
    set(MCVR_VULKAN_SDK "$ENV{VULKAN_SDK}")
else()
    message(FATAL_ERROR "VULKAN_SDK environment variable is required")
endif()

find_path(SHADERC_INCLUDE_DIR shaderc/shaderc.hpp
    HINTS
    "${MCVR_VULKAN_SDK}/x86_64/include"
    "${MCVR_VULKAN_SDK}/include"
)
find_library(SHADERC_LIBRARY
    NAMES shaderc_combined shaderc_shared shaderc
    HINTS
    "${MCVR_VULKAN_SDK}/x86_64/lib"
    "${MCVR_VULKAN_SDK}/lib"
)

if(NOT SHADERC_INCLUDE_DIR OR NOT SHADERC_LIBRARY)
    message(FATAL_ERROR "shaderc was not found. Set VULKAN_SDK or install shaderc.")
endif()

target_include_directories(core PUBLIC ${GENTOOL_OUTPUT_DIR} ${VOLK_INCLUDE_DIR} ${VMA_INCLUDE_DIR} ${STB_INCLUDE_DIR} ${TINYEXPR_INCLUDE_DIR} ${JNI_INCLUDE_DIRS} ${GLM_INCLUDE_DIR} ${SHADERC_INCLUDE_DIR})
target_link_libraries(core PUBLIC ${SHADERC_LIBRARY})
target_compile_definitions(core PUBLIC VK_NO_PROTOTYPES CORE_LIB)
if(USE_AMD)
    target_compile_definitions(core PUBLIC USE_AMD)
endif()
if(MCVR_ENABLE_FFX_UPSCALER)
    # amd_fidelityfx_vk provides the main FFX API (ffxCreateContext, ffxQuery, etc.)
    target_link_libraries(core PUBLIC amd_fidelityfx_vk ffx_backend_vk_x64 ffx_fsr3_x64 ffx_fsr3upscaler_x64
                                      ffx_frameinterpolation_x64 ffx_opticalflow_x64)
    target_compile_definitions(core PUBLIC MCVR_ENABLE_FFX_UPSCALER)
endif()
if(MCVR_ENABLE_NRD)
    target_link_libraries(core PUBLIC NRD)
    target_include_directories(core PUBLIC ${NRD_INCLUDE_DIR})
    target_compile_definitions(core PUBLIC NRD_STATIC_LIBRARY=1)
    target_compile_definitions(core PUBLIC MCVR_ENABLE_NRD)
endif()
if(MCVR_ENABLE_XESS)
    find_library(XESS_RUNTIME_LIB
        NAMES xess libxess
        PATHS
            "${PROJECT_SOURCE_DIR}/extern/xess/lib"
            "${PROJECT_SOURCE_DIR}/extern/xess/bin"
    )

    if(XESS_RUNTIME_LIB)
        target_link_libraries(core PUBLIC "${XESS_RUNTIME_LIB}")
        target_include_directories(core PUBLIC ${XESS_INCLUDE_DIR})
        target_compile_definitions(core PUBLIC MCVR_ENABLE_XESS)

        if(WIN32)
            # libxess.dll is downloaded on demand (the mod does not ship it), so core.dll must load without it:
            # delay-loaded, and only called once XeSSWrapper::runtimePresent() sees it loaded.
            target_link_options(core PRIVATE "/DELAYLOAD:libxess.dll")
            target_link_libraries(core PRIVATE delayimp)
            # Only the upscaler. The SDK also ships libxess_fg.dll (frame generation) and libxess_dx11.dll, both for
            # DirectX; neither is used here, and together they added some 30 MB to every jar.
            file(GLOB XESS_RUNTIME_DLLS "${PROJECT_SOURCE_DIR}/extern/xess/bin/libxess.dll")
            if(XESS_RUNTIME_DLLS)
                install(FILES ${XESS_RUNTIME_DLLS} DESTINATION ${MCVR_INSTALL_LIB_DIR})
                install(FILES ${XESS_RUNTIME_DLLS} DESTINATION ${MCVR_INSTALL_BIN_DIR})
            else()
                message(WARNING "XeSS enabled and linked, but no runtime DLLs were found in extern/xess/bin.")
            endif()
        endif()
    else()
        message(WARNING "XeSS headers were found but no XeSS runtime library was found; building without XeSS backend.")
    endif()
endif()
# DLSS Super Resolution and Ray Reconstruction runtimes, redistributed under the NVIDIA RTX SDK licence (see
# common/src/main/resources/licenses/THIRD-PARTY-NOTICES.md). The mod extracts them into run/radiante/dlss, where
# NGX looks for them. Linux builds go to their own folder of the jar, next to the Windows ones.
if(WIN32)
    set(MCVR_PLATFORM_LIB_DIR ${MCVR_INSTALL_LIB_DIR})
    set(MCVR_PLATFORM_BIN_DIR ${MCVR_INSTALL_BIN_DIR})
    set(DLSS_RUNTIME_DIR "${PROJECT_SOURCE_DIR}/extern/DLSS/lib/Windows_x86_64/rel")
    file(GLOB DLSS_RUNTIME_DLLS "${DLSS_RUNTIME_DIR}/nvngx_dlss.dll" "${DLSS_RUNTIME_DIR}/nvngx_dlssd.dll"
                                "${DLSS_RUNTIME_DIR}/nvngx_dlssg.dll")
else()
    set(MCVR_PLATFORM_LIB_DIR ${MCVR_INSTALL_LIB_DIR}/linux-x64)
    set(MCVR_PLATFORM_BIN_DIR ${MCVR_INSTALL_BIN_DIR}/linux-x64)
    set(DLSS_RUNTIME_DIR "${PROJECT_SOURCE_DIR}/extern/DLSS/lib/Linux_x86_64/rel")
    file(GLOB DLSS_RUNTIME_DLLS "${DLSS_RUNTIME_DIR}/libnvidia-ngx-dlss.so.*"
                                "${DLSS_RUNTIME_DIR}/libnvidia-ngx-dlssd.so.*"
                                "${DLSS_RUNTIME_DIR}/libnvidia-ngx-dlssg.so.*")
endif()
if(DLSS_RUNTIME_DLLS)
    install(FILES ${DLSS_RUNTIME_DLLS} DESTINATION ${MCVR_PLATFORM_LIB_DIR}/dlss)
    install(FILES ${DLSS_RUNTIME_DLLS} DESTINATION ${MCVR_PLATFORM_BIN_DIR}/dlss)
endif()

if(NOT WIN32)
    # The C++ runtime goes inside libcore.so: distributions older than the build machine ship an older libstdc++.
    # The bundled copy must stay private: the game process already has the system libstdc++ loaded (SDL, GTK,
    # the GPU driver), and if libcore exports its own copy the two mix locale/facet state and crash on startup.
    target_link_options(core PRIVATE -static-libstdc++ -static-libgcc -Wl,--exclude-libs,ALL
                       "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map")
    set_property(TARGET core APPEND PROPERTY LINK_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/exports.map)
endif()

if(MSVC)
    target_compile_definitions(core PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX)
    target_compile_options(core PRIVATE /utf-8)
endif()

set_target_properties(core PROPERTIES CXX_VISIBILITY_PRESET default VISIBILITY_INLINES_HIDDEN OFF)

install(TARGETS core DESTINATION ${MCVR_PLATFORM_LIB_DIR})
install(TARGETS core DESTINATION ${MCVR_PLATFORM_BIN_DIR})
