#!/bin/sh
# Builds the native libraries the Android app needs, from their official
# source releases, as static libraries for each ABI:
#
#   third_party/android/arm64-v8a/   releases and the phone
#   third_party/android/x86_64/      the emulator (debug builds only)
#
# SDL3, curl (HTTP and HTTPS only, with Mbed TLS), mpg123, faad2 (AAC), Ogg,
# Vorbis, Opus, opusfile, FLAC, zlib, FreeType, HarfBuzz and FriBidi: the
# same libraries and versions as the Linux app's tools/build_linux_deps.sh
# (in Enso-Radio-Linux), built with the pinned NDK for min SDK 33.
#
# 16 KB pages: the NDK (r28) and the -z max-page-size flags below align
# everything for 16 KB; the libraries are static, so what matters is the
# final libmain.so, which Gradle links with the same flags.
# Reproducible: build paths are mapped away (-ffile-prefix-map), no build
# IDs, archives written in deterministic mode, SOURCE_DATE_EPOCH fixed.
#
# Usage: tools/build_android_deps.sh [arm64-v8a|x86_64|all] [library...]
#        (default: all ABIs, all libraries)
# Needs: the NDK from tools/android_env.sh, cmake, ninja-build, meson,
# pkg-config, make, curl, sha256sum.
# Downloads are checked against the SHA-256 sums below.
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
. "$ROOT/tools/android_env.sh"
TP="$ROOT/third_party"
SRC="$TP/src"
JOBS=$(nproc 2>/dev/null || echo 4)
API=$DXG_MIN_SDK
NDK_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"

SDL_VER=3.4.18
CURL_VER=8.23.0
MPG123_VER=1.33.7
FREETYPE_VER=2.15.0
HARFBUZZ_VER=15.0.0
FRIBIDI_VER=1.0.18
MBEDTLS_VER=3.6.7
FAAD2_VER=2.11.4
OGG_VER=1.3.6
VORBIS_VER=1.3.7
OPUS_VER=1.6.1
OPUSFILE_VER=0.12
FLAC_VER=1.5.0
ZLIB_VER=1.3.1

# name|url|sha256
SOURCES="
SDL3-$SDL_VER.tar.gz|https://github.com/libsdl-org/SDL/releases/download/release-$SDL_VER/SDL3-$SDL_VER.tar.gz|9c75cf16330322c217dedd2e0609f1124f1b54b8633e763467b4684d0f4334a3
curl-$CURL_VER.tar.xz|https://curl.se/download/curl-$CURL_VER.tar.xz|8dadd7d2536a314f982ad05a651b713f426bf7d02cc2c76cffb9796f2218e6c5
mpg123-$MPG123_VER.tar.bz2|https://www.mpg123.de/download/mpg123-$MPG123_VER.tar.bz2|31d0e35a4ca567ec9b5ebda6c3062bb4435d6d3eacd6ef0d95cadd7854dc03ee
freetype-$FREETYPE_VER.tar.xz|https://download.savannah.gnu.org/releases/freetype/freetype-$FREETYPE_VER.tar.xz|93cd47eb719c325107f2d3cf087d06299e944c3b48a40b55537302304dea1dd5
harfbuzz-$HARFBUZZ_VER.tar.xz|https://github.com/harfbuzz/harfbuzz/releases/download/$HARFBUZZ_VER/harfbuzz-$HARFBUZZ_VER.tar.xz|968536e9abd782f07f7954044c0c4020199fcdd39b41d221ec42d2a3be4a44df
fribidi-$FRIBIDI_VER.tar.xz|https://github.com/fribidi/fribidi/releases/download/v$FRIBIDI_VER/fribidi-$FRIBIDI_VER.tar.xz|1de5e8073d035388b71d0b2d452b1da1f8caeea8a7702997b401fe1b6250727f
mbedtls-$MBEDTLS_VER.tar.bz2|https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-$MBEDTLS_VER/mbedtls-$MBEDTLS_VER.tar.bz2|a7e8bcbec0e6f761b4af24f25677626b35f762f68eef79c08677a363212d11f6
faad2-$FAAD2_VER.tar.gz|https://github.com/knik0/faad2/archive/refs/tags/$FAAD2_VER.tar.gz|ee479ccbae4a8387ab696e6f21a481bd83fe3881471cafa81b4ae59d7d3aed43
libogg-$OGG_VER.tar.xz|https://downloads.xiph.org/releases/ogg/libogg-$OGG_VER.tar.xz|5c8253428e181840cd20d41f3ca16557a9cc04bad4a3d04cce84808677fa1061
libvorbis-$VORBIS_VER.tar.xz|https://downloads.xiph.org/releases/vorbis/libvorbis-$VORBIS_VER.tar.xz|b33cc4934322bcbf6efcbacf49e3ca01aadbea4114ec9589d1b1e9d20f72954b
opus-$OPUS_VER.tar.gz|https://downloads.xiph.org/releases/opus/opus-$OPUS_VER.tar.gz|6ffcb593207be92584df15b32466ed64bbec99109f007c82205f0194572411a1
opusfile-$OPUSFILE_VER.tar.gz|https://downloads.xiph.org/releases/opus/opusfile-$OPUSFILE_VER.tar.gz|118d8601c12dd6a44f52423e68ca9083cc9f2bfe72da7a8c1acb22a80ae3550b
zlib-$ZLIB_VER.tar.gz|https://zlib.net/fossils/zlib-$ZLIB_VER.tar.gz|9a93b2b7dfdac77ceba5a558a580e74667dd6fede4585b91eefb60f03b72df23
flac-$FLAC_VER.tar.xz|https://downloads.xiph.org/releases/flac/flac-$FLAC_VER.tar.xz|f2c1c76592a82ffff8413ba3c4a1299b6c7ab06c734dee03fd88630485c2b920
"

fetch_all() {
    mkdir -p "$SRC"
    echo "$SOURCES" | while IFS='|' read -r name url sum; do
        [ -n "$name" ] || continue
        if [ ! -f "$SRC/$name" ]; then
            echo "== downloading $name"
            curl -fL --retry 3 -o "$SRC/$name.part" "$url"
            mv "$SRC/$name.part" "$SRC/$name"
        fi
        got=$(sha256sum "$SRC/$name" | cut -d' ' -f1)
        if [ "$got" != "$sum" ]; then
            echo "SHA-256 mismatch for $name: got $got, expected $sum" >&2
            exit 1
        fi
        dir="$SRC/${name%.tar.*}"
        [ -d "$dir" ] || tar -xf "$SRC/$name" -C "$SRC"
    done
}

# Flags for every library: optimised, no build paths in the output.
common_flags() {
    echo "-O2 -fPIC -ffile-prefix-map=$ROOT=. -ffile-prefix-map=$ANDROID_NDK_HOME=ndk"
}
LINK_FLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,--build-id=none"

cmake_build() { # name srcdir args...
    name=$1 src=$2
    shift 2
    bdir="$TP/build/android-$ABI/$name"
    echo "== $name ($ABI)"
    rm -rf "$bdir"
    cmake -S "$src" -B "$bdir" -G Ninja -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_TOOLCHAIN_FILE="$DXG_NDK_TOOLCHAIN" -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM="android-$API" -DANDROID_STL=c++_static \
        -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
        -DCMAKE_INSTALL_PREFIX="$PREFIX" -DCMAKE_PREFIX_PATH="$PREFIX" \
        -DCMAKE_FIND_ROOT_PATH="$PREFIX" \
        -DCMAKE_C_FLAGS="$(common_flags)" -DCMAKE_CXX_FLAGS="$(common_flags)" \
        -DCMAKE_ASM_FLAGS="$(common_flags)" \
        -DCMAKE_SHARED_LINKER_FLAGS="$LINK_FLAGS" \
        -DCMAKE_EXE_LINKER_FLAGS="$LINK_FLAGS" \
        -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON "$@" \
        >"$TP/build/$name-$ABI-configure.log" 2>&1 ||
        { echo "configure failed: $TP/build/$name-$ABI-configure.log" >&2; exit 1; }
    cmake --build "$bdir" -j "$JOBS" >"$TP/build/$name-$ABI-build.log" 2>&1 ||
        { echo "build failed: $TP/build/$name-$ABI-build.log" >&2; exit 1; }
    cmake --install "$bdir" >/dev/null
}

# Meson needs a cross file per ABI.
meson_cross_file() {
    f="$TP/build/android-$ABI/meson-cross.ini"
    mkdir -p "$(dirname "$f")"
    flags=$(common_flags | sed "s/ /', '/g")
    cat >"$f" <<CROSS
[binaries]
c = '$NDK_BIN/$TRIPLE$API-clang'
cpp = '$NDK_BIN/$TRIPLE$API-clang++'
ar = '$NDK_BIN/llvm-ar'
strip = '$NDK_BIN/llvm-strip'
pkg-config = 'pkg-config'

[properties]
pkg_config_libdir = '$PREFIX/lib/pkgconfig'

[built-in options]
c_args = ['$flags']
cpp_args = ['$flags']
c_link_args = ['$(echo "$LINK_FLAGS" | sed "s/ /', '/g")']
cpp_link_args = ['$(echo "$LINK_FLAGS" | sed "s/ /', '/g")', '-static-libstdc++']

[host_machine]
system = 'android'
cpu_family = '$CPU'
cpu = '$CPU'
endian = 'little'
CROSS
    echo "$f"
}

meson_build() { # name srcdir args...
    name=$1 src=$2
    shift 2
    bdir="$TP/build/android-$ABI/$name"
    echo "== $name ($ABI)"
    rm -rf "$bdir"
    PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig" PKG_CONFIG_PATH= \
        meson setup "$bdir" "$src" --cross-file "$(meson_cross_file)" \
        --prefix "$PREFIX" --libdir lib --buildtype release \
        -Ddefault_library=static -Db_staticpic=true "$@" \
        >"$TP/build/$name-$ABI-configure.log" 2>&1 ||
        { echo "configure failed: $TP/build/$name-$ABI-configure.log" >&2; exit 1; }
    meson compile -C "$bdir" -j "$JOBS" >"$TP/build/$name-$ABI-build.log" 2>&1 ||
        { echo "build failed: $TP/build/$name-$ABI-build.log" >&2; exit 1; }
    meson install -C "$bdir" >/dev/null
}

autotools_build() { # name srcdir configure-args...
    name=$1 src=$2
    shift 2
    bdir="$TP/build/android-$ABI/$name"
    echo "== $name ($ABI)"
    rm -rf "$bdir"
    mkdir -p "$bdir"
    (cd "$bdir" &&
        PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig" PKG_CONFIG_PATH= \
        CC="$NDK_BIN/$TRIPLE$API-clang" AR="$NDK_BIN/llvm-ar" \
        RANLIB="$NDK_BIN/llvm-ranlib" STRIP="$NDK_BIN/llvm-strip" \
        CFLAGS="$(common_flags)" LDFLAGS="$LINK_FLAGS" \
        "$src/configure" --host="$TRIPLE" --prefix="$PREFIX" --libdir="$PREFIX/lib" \
        --enable-static --disable-shared "$@" \
        >"$TP/build/$name-$ABI-configure.log" 2>&1 &&
        make -j "$JOBS" >"$TP/build/$name-$ABI-build.log" 2>&1 &&
        make install >/dev/null) ||
        { echo "$name failed: see $TP/build/$name-$ABI-*.log" >&2; exit 1; }
}

b_zlib() {
    cmake_build zlib "$SRC/zlib-$ZLIB_VER" -DZLIB_BUILD_EXAMPLES=OFF
    # zlib's CMake always builds the shared library too; keep the static one.
    rm -f "$PREFIX"/lib/libz.so*
}
b_sdl() {
    cmake_build SDL3 "$SRC/SDL3-$SDL_VER" \
        -DSDL_SHARED=OFF -DSDL_STATIC=ON -DSDL_TEST_LIBRARY=OFF -DSDL_EXAMPLES=OFF \
        -DSDL_TESTS=OFF -DSDL_VULKAN=OFF -DSDL_RENDER_VULKAN=OFF -DSDL_GPU=OFF
}
b_mbedtls() {
    cmake_build mbedtls "$SRC/mbedtls-$MBEDTLS_VER" \
        -DENABLE_PROGRAMS=OFF -DENABLE_TESTING=OFF -DUSE_STATIC_MBEDTLS_LIBRARY=ON \
        -DUSE_SHARED_MBEDTLS_LIBRARY=OFF
}
b_curl() {
    # No CA bundle path built in: the app ships curl's Mozilla CA bundle in
    # the APK and sets CURLOPT_CAINFO to it.
    cmake_build curl "$SRC/curl-$CURL_VER" \
        -DCURL_USE_MBEDTLS=ON -DCURL_USE_OPENSSL=OFF -DCURL_USE_GNUTLS=OFF \
        -DCURL_USE_LIBPSL=OFF -DUSE_LIBIDN2=OFF -DUSE_NGHTTP2=OFF -DCURL_ZLIB=OFF \
        -DCURL_BROTLI=OFF -DCURL_ZSTD=OFF -DCURL_USE_LIBSSH2=OFF -DCURL_USE_GSSAPI=OFF \
        -DCURL_DISABLE_LDAP=ON -DCURL_CA_BUNDLE=none -DCURL_CA_PATH=none \
        -DCURL_DISABLE_DICT=ON -DCURL_DISABLE_FILE=ON -DCURL_DISABLE_FTP=ON \
        -DCURL_DISABLE_GOPHER=ON -DCURL_DISABLE_IMAP=ON -DCURL_DISABLE_IPFS=ON \
        -DCURL_DISABLE_MQTT=ON -DCURL_DISABLE_POP3=ON -DCURL_DISABLE_RTSP=ON \
        -DCURL_DISABLE_SMTP=ON -DCURL_DISABLE_TELNET=ON -DCURL_DISABLE_TFTP=ON \
        -DCURL_DISABLE_WEBSOCKETS=ON \
        -DBUILD_CURL_EXE=OFF -DBUILD_TESTING=OFF -DBUILD_EXAMPLES=OFF \
        -DBUILD_LIBCURL_DOCS=OFF -DBUILD_MISC_DOCS=OFF -DENABLE_CURL_MANUAL=OFF
}
b_mpg123() {
    cmake_build mpg123 "$SRC/mpg123-$MPG123_VER/ports/cmake" \
        -DBUILD_PROGRAMS=OFF -DBUILD_LIBOUT123=OFF
}
b_freetype() {
    cmake_build freetype "$SRC/freetype-$FREETYPE_VER" \
        -DFT_DISABLE_ZLIB=ON -DFT_DISABLE_BZIP2=ON -DFT_DISABLE_PNG=ON \
        -DFT_DISABLE_HARFBUZZ=ON -DFT_DISABLE_BROTLI=ON
}
b_harfbuzz() {
    meson_build harfbuzz "$SRC/harfbuzz-$HARFBUZZ_VER" \
        -Dfreetype=enabled -Dglib=disabled -Dgobject=disabled -Dcairo=disabled \
        -Dicu=disabled -Dchafa=disabled -Dtests=disabled -Ddocs=disabled \
        -Dutilities=disabled -Dintrospection=disabled
}
b_fribidi() {
    meson_build fribidi "$SRC/fribidi-$FRIBIDI_VER" -Ddocs=false -Dbin=false -Dtests=false
}
b_faad2() {
    # faad2 builds four variants; only libfaad (floating point, no DRM) is used.
    cmake_build faad2 "$SRC/faad2-$FAAD2_VER" -DFAAD_BUILD_CLI=OFF
}
b_ogg() {
    cmake_build ogg "$SRC/libogg-$OGG_VER" -DINSTALL_DOCS=OFF -DBUILD_TESTING=OFF
}
b_vorbis() {
    cmake_build vorbis "$SRC/libvorbis-$VORBIS_VER" -DBUILD_TESTING=OFF
}
b_opus() {
    cmake_build opus "$SRC/opus-$OPUS_VER" -DOPUS_BUILD_PROGRAMS=OFF \
        -DOPUS_BUILD_TESTING=OFF -DOPUS_INSTALL_PKG_CONFIG_MODULE=ON
}
b_opusfile() {
    # opusfile 0.12 has no CMake build. Its own HTTP code would need
    # OpenSSL; curl fetches the stream.
    autotools_build opusfile "$SRC/opusfile-$OPUSFILE_VER" \
        --disable-http --disable-examples --disable-doc
}
b_flac() {
    cmake_build flac "$SRC/flac-$FLAC_VER" -DWITH_OGG=ON -DBUILD_CXXLIBS=OFF \
        -DBUILD_PROGRAMS=OFF -DBUILD_EXAMPLES=OFF -DBUILD_TESTING=OFF -DBUILD_DOCS=OFF \
        -DINSTALL_MANPAGES=OFF -DINSTALL_PKGCONFIG_MODULES=ON
}

# In dependency order.
ALL_LIBS="zlib sdl mbedtls curl mpg123 freetype harfbuzz fribidi faad2 ogg vorbis opus opusfile flac"

set_abi() {
    ABI=$1
    case $ABI in
    arm64-v8a) TRIPLE=aarch64-linux-android CPU=aarch64 ;;
    x86_64)    TRIPLE=x86_64-linux-android CPU=x86_64 ;;
    *) echo "unknown ABI: $ABI" >&2; exit 2 ;;
    esac
    PREFIX="$TP/android/$ABI"
}

abis=${1:-all}
[ $# -gt 0 ] && shift
libs=${*:-$ALL_LIBS}
[ "$abis" = all ] && abis="arm64-v8a x86_64"

mkdir -p "$TP/build"
fetch_all
for a in $abis; do
    set_abi "$a"
    for l in $libs; do "b_$l"; done
    echo "$SDL_VER" >"$PREFIX/SDL_VERSION"
done
echo "== done"
