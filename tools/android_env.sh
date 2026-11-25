# Environment for every Android build and test step. Source it:
#   . tools/android_env.sh
# Pinned versions live here; NOTES.md (A0) says why each was chosen.

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

ENSO_NDK_VERSION=28.2.13676358      # SDL 3.4.18's android-project pin (r28c)
ENSO_CMAKE_VERSION=3.31.6
ENSO_BUILD_TOOLS=36.1.0
ENSO_COMPILE_SDK=36
ENSO_MIN_SDK=33
ENSO_AVD=enso_api36
ENSO_AVD_IMAGE="system-images;android-36;google_apis_ps16k;x86_64"
ENSO_EMU_PORT=5580                  # emulator serial emulator-5580
export ENSO_NDK_VERSION ENSO_CMAKE_VERSION ENSO_BUILD_TOOLS ENSO_COMPILE_SDK \
       ENSO_MIN_SDK ENSO_AVD ENSO_AVD_IMAGE ENSO_EMU_PORT

export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$ENSO_NDK_VERSION"
export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
export ENSO_NDK_TOOLCHAIN="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"

# Our tools first, so the right adb, cmake and emulator are used.
PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/cmake/$ENSO_CMAKE_VERSION/bin:$PATH"
export PATH

# Gradle and the JDK: Debian's OpenJDK 21.
if [ -z "${JAVA_HOME:-}" ] && [ -d /usr/lib/jvm/java-21-openjdk-amd64 ]; then
    export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
fi

# Reproducible native builds: fixed timestamps for anything that embeds one.
export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-1767225600}"   # 2026-01-01

if [ ! -d "$ANDROID_NDK_HOME" ]; then
    echo "android_env.sh: NDK $ENSO_NDK_VERSION missing; run:" >&2
    echo "  sdkmanager 'ndk;$ENSO_NDK_VERSION'" >&2
fi
