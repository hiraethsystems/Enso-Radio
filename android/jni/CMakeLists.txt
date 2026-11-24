# The native app, libmain.so, built by Gradle (android/app/build.gradle.kts)
# for each ABI. Libraries come from tools/build_android_deps.sh.
cmake_minimum_required(VERSION 3.22)
project(dxgarden_native C CXX)

get_filename_component(DXG_ROOT "${CMAKE_CURRENT_LIST_DIR}/../.." ABSOLUTE)
set(DXG_TP "${DXG_ROOT}/third_party/android/${ANDROID_ABI}")
if(NOT EXISTS "${DXG_TP}/lib/libSDL3.a")
    message(FATAL_ERROR "No libraries for ${ANDROID_ABI}: run tools/build_android_deps.sh ${ANDROID_ABI}")
endif()
list(APPEND CMAKE_FIND_ROOT_PATH "${DXG_TP}")
list(APPEND CMAKE_PREFIX_PATH "${DXG_TP}")
find_package(SDL3 CONFIG REQUIRED)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)

add_library(main SHARED
    src/main.c
)
target_compile_definitions(main PRIVATE
    DXG_VERSION="${DXG_VERSION}" DXG_BUILD="${DXG_BUILD}")
target_include_directories(main PRIVATE "${DXG_ROOT}/core")
target_compile_options(main PRIVATE -Wall -Wextra
    # Reproducible: no build paths in the library.
    "-ffile-prefix-map=${DXG_ROOT}=."
    "-ffile-prefix-map=${ANDROID_NDK}=ndk")
target_link_options(main PRIVATE
    # 16 KB pages; no build ID (it would differ between machines).
    -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
    -Wl,--build-id=none)
target_link_libraries(main PRIVATE SDL3::SDL3-static android log)
