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

set(DXG_CORE_ROOT "${DXG_ROOT}")
include("${DXG_ROOT}/cmake/core_sources.cmake")

add_library(main SHARED
    ${CORE_SOURCES}
    src/bridge.c
    src/decode.c
    src/earth.c
    src/globe_view.c
    src/log.c
    src/main.c
    src/player.c
    src/ui_jni.c
)
set_target_properties(main PROPERTIES C_EXTENSIONS ON)
target_compile_definitions(main PRIVATE
    DXG_VERSION="${DXG_VERSION}" DXG_BUILD="${DXG_BUILD}" DXG_PLATFORM_NAME="Android"
    "STATION_FILE_MAX=(32u * 1024 * 1024)"
    CURL_STATICLIB FRIBIDI_LIB_STATIC FLAC__NO_DLL DXG_STATIC_DEPS)
target_include_directories(main PRIVATE "${DXG_ROOT}/core" src "${DXG_TP}/include"
    "${DXG_TP}/include/opus")
target_compile_options(main PRIVATE -Wall -Wextra -O2
    # Reproducible: no build paths in the library.
    "-ffile-prefix-map=${DXG_ROOT}=."
    "-ffile-prefix-map=${ANDROID_NDK}=ndk")
target_link_options(main PRIVATE
    # 16 KB pages; no build ID (it would differ between machines).
    -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
    -Wl,--build-id=none)

# Static libraries, users before what they use.
foreach(lib curl mbedtls mbedx509 mbedcrypto everest p256m
            mpg123 faad opusfile opus vorbisfile vorbis FLAC ogg z)
    target_link_libraries(main PRIVATE "${DXG_TP}/lib/lib${lib}.a")
endforeach()
target_link_libraries(main PRIVATE SDL3::SDL3-static GLESv3 EGL android log m)
