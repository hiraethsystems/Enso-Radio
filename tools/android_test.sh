#!/bin/sh
# Cross-builds the core/ unit tests (ensoradio_test) with the NDK, pushes
# them and testdata/ to a device, and runs them there.
#   tools/android_test.sh [serial]
# serial: an adb serial (default: the headless emulator, emulator-$DXG_EMU_PORT).
# The ABI is read from the device. zlib comes from tools/build_android_deps.sh.
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
. "$ROOT/tools/android_env.sh"
SERIAL=${1:-emulator-$DXG_EMU_PORT}
DEV_DIR=/data/local/tmp/dxg

ABI=$(adb -s "$SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')
case $ABI in
arm64-v8a|x86_64) ;;
*) echo "unsupported device ABI: '$ABI'" >&2; exit 1 ;;
esac
TPA="$ROOT/third_party/android/$ABI"
[ -f "$TPA/lib/libz.a" ] || { echo "run tools/build_android_deps.sh $ABI first" >&2; exit 1; }

B="$ROOT/build-android-test-$ABI"
cmake -S "$ROOT" -B "$B" -G Ninja -DCMAKE_BUILD_TYPE=Debug -DDXG_APP=OFF \
    -DCMAKE_TOOLCHAIN_FILE="$DXG_NDK_TOOLCHAIN" -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$DXG_MIN_SDK" \
    -DZLIB_INCLUDE_DIR="$TPA/include" -DZLIB_LIBRARY="$TPA/lib/libz.a" \
    -DDXG_TESTDATA_DIR="$DEV_DIR/testdata" >/dev/null
cmake --build "$B" --target ensoradio_test

adb -s "$SERIAL" shell "rm -rf $DEV_DIR && mkdir -p $DEV_DIR/tmp"
adb -s "$SERIAL" push -q "$B/ensoradio_test" "$DEV_DIR/"
adb -s "$SERIAL" push -q "$ROOT/testdata" "$DEV_DIR/"
echo "== ensoradio_test on $SERIAL ($ABI, Android $(adb -s "$SERIAL" shell getprop ro.build.version.release | tr -d '\r'))"
# adb shell passes the exit status through since Android 7.
adb -s "$SERIAL" shell "cd $DEV_DIR && TMPDIR=$DEV_DIR/tmp ./ensoradio_test"
