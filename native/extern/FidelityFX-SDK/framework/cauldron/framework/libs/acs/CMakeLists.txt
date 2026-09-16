set(PLATFORM_DIR ${CMAKE_GENERATOR_PLATFORM})
if(NOT PLATFORM_DIR)
    if(CMAKE_SIZEOF_VOID_P EQUAL 8)
        set(PLATFORM_DIR x64)
    else()
        set(PLATFORM_DIR x86)
    endif()
endif()

add_library(amd_acs SHARED IMPORTED GLOBAL)
set_property(TARGET amd_acs PROPERTY IMPORTED_IMPLIB ${CMAKE_CURRENT_SOURCE_DIR}/amd_acs_${PLATFORM_DIR}.lib)

copyTargetCommand(${CMAKE_CURRENT_SOURCE_DIR}/amd_acs_${PLATFORM_DIR}.dll ${BIN_OUTPUT} copied_amd_acs_bin)
add_dependencies(amd_acs copied_amd_acs_bin)
