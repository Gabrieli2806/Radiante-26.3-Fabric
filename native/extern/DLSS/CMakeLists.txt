set(DLSS_REPO_URL "https://github.com/NVIDIA/DLSS.git")
set(DLSS_TARGET_DIR "${CMAKE_CURRENT_SOURCE_DIR}/lib")
set(DLSS_BRANCH "main")

if(NOT EXISTS "${DLSS_TARGET_DIR}")
    message(STATUS "DLSS lib not found. Downloading from ${DLSS_REPO_URL}...")

    find_package(Git REQUIRED)

    set(DOWNLOAD_WORKSPACE "${CMAKE_CURRENT_BINARY_DIR}/dlss_download_temp")
    file(MAKE_DIRECTORY ${DOWNLOAD_WORKSPACE})

    execute_process(COMMAND ${GIT_EXECUTABLE} init
                    WORKING_DIRECTORY ${DOWNLOAD_WORKSPACE}
                    RESULT_VARIABLE GIT_INIT_RESULT)
    
    execute_process(COMMAND ${GIT_EXECUTABLE} remote add origin ${DLSS_REPO_URL}
                    WORKING_DIRECTORY ${DOWNLOAD_WORKSPACE})

    execute_process(COMMAND ${GIT_EXECUTABLE} config core.sparseCheckout true
                    WORKING_DIRECTORY ${DOWNLOAD_WORKSPACE})

    file(WRITE "${DOWNLOAD_WORKSPACE}/.git/info/sparse-checkout" "lib/\n")

    message(STATUS "Fetching DLSS libraries (this may take a moment)...")
    execute_process(COMMAND ${GIT_EXECUTABLE} pull --depth 1 origin ${DLSS_BRANCH}
                    WORKING_DIRECTORY ${DOWNLOAD_WORKSPACE}
                    RESULT_VARIABLE GIT_PULL_RESULT)

    if(NOT GIT_PULL_RESULT EQUAL 0)
        message(FATAL_ERROR "Failed to download DLSS libraries.")
    endif()

    file(RENAME "${DOWNLOAD_WORKSPACE}/lib" "${DLSS_TARGET_DIR}")

    file(REMOVE_RECURSE ${DOWNLOAD_WORKSPACE})

    message(STATUS "DLSS libraries downloaded successfully to ${DLSS_TARGET_DIR}")
else()
    message(STATUS "DLSS libraries found at ${DLSS_TARGET_DIR}. Skipping download.")
endif()

add_library(ngx IMPORTED STATIC GLOBAL)

set_property(TARGET ngx APPEND PROPERTY IMPORTED_CONFIGURATIONS DEBUG)
set_property(TARGET ngx APPEND PROPERTY IMPORTED_CONFIGURATIONS RELEASE)

set_target_properties(ngx PROPERTIES
    MAP_IMPORTED_CONFIG_MINSIZEREL Release
    MAP_IMPORTED_CONFIG_RELWITHDEBINFO Release
)

if(WIN32)
    set(NGX_USE_STATIC_MSVCRT OFF CACHE BOOL "[Deprecated?]Use NGX libs with static VC runtime (/MT), otherwise dynamic (/MD)")

    if(NGX_USE_STATIC_MSVCRT)
        set_target_properties(ngx PROPERTIES IMPORTED_IMPLIB_DEBUG ${CMAKE_CURRENT_SOURCE_DIR}/lib/Windows_x86_64/x64/nvsdk_ngx_s_dbg.lib)
        set_target_properties(ngx PROPERTIES IMPORTED_IMPLIB_RELEASE ${CMAKE_CURRENT_SOURCE_DIR}/lib/Windows_x86_64/x64/nvsdk_ngx_s.lib)
        set_target_properties(ngx PROPERTIES IMPORTED_LOCATION_DEBUG ${CMAKE_CURRENT_SOURCE_DIR}/lib/Windows_x86_64/x64/nvsdk_ngx_s_dbg.lib)
        set_target_properties(ngx PROPERTIES IMPORTED_LOCATION_RELEASE ${CMAKE_CURRENT_SOURCE_DIR}/lib/Windows_x86_64/x64/nvsdk_ngx_s.lib)

    else()
        set_target_properties(ngx PROPERTIES IMPORTED_IMPLIB_DEBUG ${CMAKE_CURRENT_SOURCE_DIR}/lib/Windows_x86_64/x64/nvsdk_ngx_d_dbg.lib)
        set_target_properties(ngx PROPERTIES IMPORTED_IMPLIB_RELEASE ${CMAKE_CURRENT_SOURCE_DIR}/lib/Windows_x86_64/x64/nvsdk_ngx_d.lib)
        set_target_properties(ngx PROPERTIES IMPORTED_LOCATION_DEBUG ${CMAKE_CURRENT_SOURCE_DIR}/lib/Windows_x86_64/x64/nvsdk_ngx_d_dbg.lib)
        set_target_properties(ngx PROPERTIES IMPORTED_LOCATION_RELEASE ${CMAKE_CURRENT_SOURCE_DIR}/lib/Windows_x86_64/x64/nvsdk_ngx_d.lib)
    endif()

    # set the list of DLLs that need copying to target folder of application
    file(GLOB __NGX_DLLS_LIST_DEBUG "${CMAKE_CURRENT_SOURCE_DIR}/lib/Windows_x86_64/dev/nvngx_*.dll")
    file(GLOB __NGX_DLLS_LIST_RELEASE "${CMAKE_CURRENT_SOURCE_DIR}/lib/Windows_x86_64/rel/nvngx_*.dll")
else()
    set_target_properties(ngx PROPERTIES IMPORTED_LOCATION_DEBUG ${CMAKE_CURRENT_SOURCE_DIR}/lib/Linux_x86_64/libnvsdk_ngx.a)
    set_target_properties(ngx PROPERTIES IMPORTED_LOCATION_RELEASE ${CMAKE_CURRENT_SOURCE_DIR}/lib/Linux_x86_64/libnvsdk_ngx.a)

    file(GLOB __NGX_DLLS_LIST_DEBUG "${CMAKE_CURRENT_SOURCE_DIR}/lib/Linux_x86_64/dev/libnvidia-ngx-*.so.*")
    file(GLOB __NGX_DLLS_LIST_RELEASE "${CMAKE_CURRENT_SOURCE_DIR}/lib/Linux_x86_64/rel/libnvidia-ngx-*.so.*")
endif()

if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    set(__NGX_DLLS_LIST ${__NGX_DLLS_LIST_DEBUG})
else()
    set(__NGX_DLLS_LIST ${__NGX_DLLS_LIST_RELEASE})
endif()

message(STATUS "NGX snippets: ${__NGX_DLLS_LIST}")

set_property(TARGET ngx APPEND PROPERTY EXTRA_DLLS "${__NGX_DLLS_LIST}")

target_include_directories(ngx INTERFACE "include")

install(
    FILES ${__NGX_DLLS_LIST}
    DESTINATION ${MCVR_INSTALL_BIN_DIR}
)