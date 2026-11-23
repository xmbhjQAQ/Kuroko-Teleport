cmake_minimum_required(VERSION 3.22)
project(kuroko_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Parse unified version contract from repo root version.properties
set(KUROKO_VERSION_FILE "${CMAKE_CURRENT_SOURCE_DIR}/../version.properties")
if(NOT EXISTS "${KUROKO_VERSION_FILE}")
    message(FATAL_ERROR "Missing unified version contract: ${KUROKO_VERSION_FILE}")
endif()
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${KUROKO_VERSION_FILE}")

file(STRINGS "${KUROKO_VERSION_FILE}" _vp_lines)
foreach(_line IN LISTS _vp_lines)
    string(STRIP "${_line}" _line)
    if(_line STREQUAL "" OR _line MATCHES "^#")
        continue()
    endif()
    if(_line MATCHES "^([A-Za-z0-9_]+)=(.*)$")
        set("_VP_${CMAKE_MATCH_1}" "${CMAKE_MATCH_2}")
    endif()
endforeach()

foreach(_req IN ITEMS RELEASE_VERSION_NAME RELEASE_VERSION_CODE IPC_PROTOCOL_VERSION SHM_SCHEMA_VERSION MIN_COMPAT_APP_CODE)
    if(NOT DEFINED "_VP_${_req}" OR "${_VP_${_req}}" STREQUAL "")
        message(FATAL_ERROR "Missing required key '${_req}' in ${KUROKO_VERSION_FILE}")
    endif()
endforeach()

add_compile_definitions(
    KUROKO_MODULE_VERSION_NAME="${_VP_RELEASE_VERSION_NAME}"
    KUROKO_MODULE_VERSION_CODE=${_VP_RELEASE_VERSION_CODE}
    KUROKO_IPC_PROTOCOL_VERSION=${_VP_IPC_PROTOCOL_VERSION}
    KUROKO_SHM_SCHEMA_VERSION=${_VP_SHM_SCHEMA_VERSION}
    KUROKO_MIN_COMPAT_APP_CODE=${_VP_MIN_COMPAT_APP_CODE}
)

# Android 17 / ARM64 BTI+PAC + 16KB page size alignment flags
add_compile_options(
    -O2
    -Wall
    -Wextra
    -Wno-unused-parameter
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -mbranch-protection=standard
)
add_link_options(
    -Wl,-z,max-page-size=16384
    -Wl,--gc-sections
)

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/../common/include
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

# 1. Standalone C++ simulation & IPC daemon (started by KSU2 post-fs-data.sh)
add_executable(kuroko_daemon
    daemon/main.cpp
    daemon/sim_engine.cpp
    daemon/nmea_gnss.cpp
)
target_link_libraries(kuroko_daemon log dl)

# 2. Zygisk Next arm64-v8a module (loaded ONLY into system_server; dlclosed in all apps)
add_library(kuroko_zygisk SHARED
    zygisk/module.cpp
    zygisk/sensor_hook.cpp
)
set_target_properties(kuroko_zygisk PROPERTIES OUTPUT_NAME "arm64-v8a" PREFIX "")
target_link_libraries(kuroko_zygisk log dl)
