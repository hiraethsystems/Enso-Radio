cmake_minimum_required(VERSION 3.16)

# Ensō Radio for Android, the PC side:
#   cmake -S . -B build-test -DDXG_APP=OFF        core/ unit tests and the list tool
# The app itself is built with Gradle from android/ (./gradlew assembleDebug).
project(ensoradio C)

# Versions, in android/ (also read by Gradle for versionName and versionCode):
#   VERSION  the release version, MAJOR.MINOR.PATCH, minor and patch 0-9.
#   BUILD    goes up by one for every build handed over for testing.
set(DXG_APP_DIR "${CMAKE_SOURCE_DIR}/android")
file(STRINGS "${DXG_APP_DIR}/VERSION" DXG_VERSION LIMIT_COUNT 1)
file(STRINGS "${DXG_APP_DIR}/BUILD" DXG_BUILD LIMIT_COUNT 1)
# Re-run CMake when either changes, so a bumped BUILD reaches the binary.
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
             "${DXG_APP_DIR}/VERSION" "${DXG_APP_DIR}/BUILD")
if(NOT DXG_VERSION MATCHES "^([0-9]|[1-9][0-9])\\.([0-9])\\.([0-9])$")
    message(FATAL_ERROR "VERSION must be MAJOR.MINOR.PATCH with minor and patch 0-9, "
                        "e.g. 1.0.0: '${DXG_VERSION}'")
endif()
if(NOT DXG_BUILD MATCHES "^[1-9][0-9]*$")
    message(FATAL_ERROR "BUILD must be a whole number: '${DXG_BUILD}'")
endif()

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)

set(DXG_CORE_ROOT "${CMAKE_SOURCE_DIR}")
include(cmake/core_sources.cmake)

# The app is not built here. DXG_APP is kept so the Linux-era command line
# (-DDXG_APP=OFF) still works; ON is refused rather than silently ignored.
option(DXG_APP "Build the app (the Android app is built by Gradle instead)" OFF)
if(DXG_APP)
    message(FATAL_ERROR "The Android app is built with Gradle from android/, "
                        "not from this CMakeLists.txt. Use -DDXG_APP=OFF.")
endif()

find_package(ZLIB REQUIRED)
add_executable(ensoradio_test
    ${CORE_SOURCES}
    ${RB_SOURCES}
    tests/test_rb.c
    tests/test_main.c
    tests/test_library.c
    tests/test_globe.c
    tests/test_controls.c
    tests/test_stationfiles.c
    tests/test_bidi.c
    tests/test_stream.c
    tests/test_hls.c
    tests/test_sun.c
)
target_include_directories(ensoradio_test PRIVATE core)
# Where the tests read testdata/ from. tools/android_test.sh builds with
# the folder it pushes testdata/ to on the device.
set(DXG_TESTDATA_DIR "${CMAKE_SOURCE_DIR}/testdata" CACHE STRING "testdata/ at run time")
target_compile_definitions(ensoradio_test PRIVATE
                           DXG_TESTDATA_DIR="${DXG_TESTDATA_DIR}")
target_compile_options(ensoradio_test PRIVATE -Wall -Wextra -Wpedantic -g)
# AddressSanitizer and UBSan on the PC. Off for the NDK build: an ASan
# executable on Android needs its runtime pushed alongside it.
if(ANDROID)
    set(DXG_SANITIZE_DEFAULT OFF)
else()
    set(DXG_SANITIZE_DEFAULT ON)
endif()
option(DXG_SANITIZE "Build the tests with ASan and UBSan" ${DXG_SANITIZE_DEFAULT})
if(DXG_SANITIZE)
    target_compile_options(ensoradio_test PRIVATE -fsanitize=address,undefined)
    target_link_options(ensoradio_test PRIVATE -fsanitize=address,undefined)
endif()
target_link_libraries(ensoradio_test ZLIB::ZLIB m)

# The station-list builder from the command line, to compare it with
# tools/rb_to_stations.py (tests/compare_listtool.sh).
add_executable(ensoradio_listtool ${CORE_SOURCES} ${RB_SOURCES} tests/listtool.c)
target_include_directories(ensoradio_listtool PRIVATE core)
target_compile_options(ensoradio_listtool PRIVATE -Wall -Wextra -O2 -g)
target_link_libraries(ensoradio_listtool ZLIB::ZLIB m)

enable_testing()
add_test(NAME core COMMAND ensoradio_test)
