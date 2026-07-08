cmake_minimum_required(VERSION 3.22)
project(desktop_filament_bridge LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(FILAMENT_DIR "${CMAKE_CURRENT_LIST_DIR}/../../filament/filament" CACHE PATH "Filament desktop release")

if(NOT EXISTS "${FILAMENT_DIR}/include/filament/Engine.h")
    message(FATAL_ERROR "Filament desktop headers not found at ${FILAMENT_DIR}. Run tools/fetch_filament_desktop.sh 1.72.0 from the repo root.")
endif()

find_package(JNI REQUIRED)

add_library(desktop_filament_bridge SHARED
    desktop_filament_bridge.cpp
    filament_chess_core.cpp
)

target_include_directories(desktop_filament_bridge PRIVATE
    ${JNI_INCLUDE_DIRS}
    "${FILAMENT_DIR}/include"
)

set(FILAMENT_LIBRARY_DIRS
    "${FILAMENT_DIR}/lib"
    "${FILAMENT_DIR}/lib/${CMAKE_SYSTEM_PROCESSOR}"
    "${FILAMENT_DIR}/lib/${CMAKE_OSX_ARCHITECTURES}"
    "${FILAMENT_DIR}/lib/x86_64"
    "${FILAMENT_DIR}/lib/arm64"
    "${FILAMENT_DIR}/lib/aarch64"
)

foreach(candidate IN LISTS FILAMENT_LIBRARY_DIRS)
    if(EXISTS "${candidate}/libfilament.a" OR EXISTS "${candidate}/filament.lib" OR EXISTS "${candidate}/libfilament.dylib" OR EXISTS "${candidate}/libfilament.so")
        set(FILAMENT_LIBRARY_DIR "${candidate}")
        break()
    endif()
endforeach()

if(NOT FILAMENT_LIBRARY_DIR)
    message(FATAL_ERROR "Filament desktop libraries not found under ${FILAMENT_DIR}/lib. Run tools/fetch_filament_desktop.sh 1.72.0 from the repo root.")
endif()

target_link_directories(desktop_filament_bridge PRIVATE "${FILAMENT_LIBRARY_DIR}")
set_target_properties(desktop_filament_bridge PROPERTIES
    BUILD_RPATH "${FILAMENT_LIBRARY_DIR}"
)

target_link_libraries(desktop_filament_bridge PRIVATE
    $<$<AND:$<PLATFORM_ID:Linux>,$<OR:$<CXX_COMPILER_ID:GNU>,$<CXX_COMPILER_ID:Clang>>>:-Wl,--start-group>
    filament backend bluegl bluevk filabridge filaflat geometry utils smol-v
    ibl image ktxreader
    gltfio_core uberarchive uberzlib dracodec basis_transcoder meshoptimizer mikktspace stb
    zstd abseil
    $<$<AND:$<PLATFORM_ID:Linux>,$<OR:$<CXX_COMPILER_ID:GNU>,$<CXX_COMPILER_ID:Clang>>>:-Wl,--end-group>
)

if(APPLE)
    target_link_libraries(desktop_filament_bridge PRIVATE
        "-framework Metal"
        "-framework MetalKit"
        "-framework CoreVideo"
        "-framework QuartzCore"
        "-framework Foundation"
        "-framework CoreGraphics"
        "-framework Cocoa"
        "-framework IOKit"
    )
elseif(UNIX)
    target_link_libraries(desktop_filament_bridge PRIVATE dl pthread)
endif()
