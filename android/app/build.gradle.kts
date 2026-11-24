import java.io.File

plugins {
    id("com.android.application")
}

// android/VERSION (MAJOR.MINOR.PATCH) and android/BUILD (goes up by one for
// every build) are the only place the version is written down.
fun readLine1(name: String): String = File(rootDir, name).readText().trim().lines().first().trim()
val dxgVersion = readLine1("VERSION")
val dxgBuild = readLine1("BUILD")
require(Regex("^([0-9]|[1-9][0-9])\\.[0-9]\\.[0-9]$").matches(dxgVersion)) {
    "android/VERSION must be MAJOR.MINOR.PATCH with minor and patch 0-9: '$dxgVersion'"
}
require(Regex("^[1-9][0-9]*$").matches(dxgBuild)) { "android/BUILD must be a whole number: '$dxgBuild'" }

// APK name: dxgarden-<version>-b<build>-<buildtype>.apk
base {
    archivesName.set("dxgarden-$dxgVersion-b$dxgBuild")
}

android {
    namespace = "systems.hiraeth.dxgarden"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "systems.hiraeth.dxgarden"
        minSdk = 33
        targetSdk = 36
        versionCode = dxgBuild.toInt()
        versionName = dxgVersion

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DDXG_VERSION=$dxgVersion",
                    "-DDXG_BUILD=$dxgBuild",
                )
            }
        }
    }

    buildTypes {
        debug {
            // The emulator is x86_64; the phone and releases are arm64-v8a.
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            ndk { abiFilters += listOf("arm64-v8a") }
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../jni/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // Uncompressed and page-aligned in the APK, loaded in place.
        jniLibs { useLegacyPackaging = false }
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    jvmToolchain(21)
}
