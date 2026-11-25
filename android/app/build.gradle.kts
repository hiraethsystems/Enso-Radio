import java.security.MessageDigest
import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// android/VERSION (MAJOR.MINOR.PATCH) and android/BUILD (goes up by one for
// every build) are the only place the version is written down.
fun readLine1(name: String): String = File(rootDir, name).readText().trim().lines().first().trim()
val ensoVersion = readLine1("VERSION")
val ensoBuild = readLine1("BUILD")
require(Regex("^([0-9]|[1-9][0-9])\\.[0-9]\\.[0-9]$").matches(ensoVersion)) {
    "android/VERSION must be MAJOR.MINOR.PATCH with minor and patch 0-9: '$ensoVersion'"
}
require(Regex("^[1-9][0-9]*$").matches(ensoBuild)) { "android/BUILD must be a whole number: '$ensoBuild'" }

// The globe imagery is built by tools/make_earth.py into build-earth/ (not in
// git: 105 MB, plus the 254 MB level-6 detail packs in build-earth-detail/,
// which aren't in the APK). The APK isn't worth having without it, so stop with the
// commands to make it.
val earthDir = File(rootDir, "../build-earth")
for (pack in listOf("earth-day.pack", "earth-night.pack")) {
    if (!File(earthDir, pack).isFile) {
        throw GradleException(
            "build-earth/$pack is missing. Make the globe imagery first, from the repository root:\n" +
            "  python3 -I tools/make_earth.py --layer day --out build-earth --detail-from 6\n" +
            "  python3 -I tools/make_earth.py --layer night --out build-earth --detail-from 6"
        )
    }
}

// Full detail (GlobeDetail.kt): the level-6 packs aren't in the APK; the app
// downloads them when asked and checks each against the size and SHA-256 in
// GlobeDetail.kt. When build-earth-detail/ is here, make sure they match, so
// a changed pack can't go out with the old numbers.
val detailDir = File(rootDir, "../build-earth-detail")
if (detailDir.isDirectory) {
    val kt = File(projectDir, "src/main/kotlin/systems/hiraeth/ensoradio/GlobeDetail.kt").readText()
    val want = Regex("""Pack\("([^"]+)", ([0-9_]+)L, "([0-9a-f]{64})"\)""").findAll(kt)
        .associate { it.groupValues[1] to (it.groupValues[2].replace("_", "").toLong() to it.groupValues[3]) }
    for ((name, sw) in want) {
        val f = File(detailDir, name)
        if (!f.isFile) continue
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        val sha = md.digest().joinToString("") { "%02x".format(it) }
        if (f.length() != sw.first || sha != sw.second)
            throw GradleException("build-earth-detail/$name is ${f.length()} bytes, SHA-256 $sha; GlobeDetail.kt says ${sw.first}, ${sw.second}")
    }
}

// Release signing: Aisling's own key, kept outside the repository and never
// committed (tools/make_release_key.sh makes it). Without it, the release
// APK is built unsigned.
val signingFile = File(
    System.getenv("ENSO_SIGNING_PROPERTIES")
        ?: (System.getProperty("user.home") + "/.config/ensoradio/signing.properties")
)
val signingProps: Properties? = if (signingFile.isFile) Properties().apply { signingFile.inputStream().use { load(it) } } else null

// APK name: ensoradio-<version>-b<build>-<buildtype>.apk
base {
    archivesName.set("ensoradio-$ensoVersion-b$ensoBuild")
}

android {
    namespace = "systems.hiraeth.ensoradio"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "systems.hiraeth.ensoradio"
        minSdk = 33
        targetSdk = 36
        versionCode = ensoBuild.toInt()
        versionName = ensoVersion

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DENSO_VERSION=$ensoVersion",
                    "-DENSO_BUILD=$ensoBuild",
                )
            }
        }
    }

    signingConfigs {
        if (signingProps != null) {
            create("release") {
                storeFile = File(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
                storeType = "PKCS12"
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
            if (signingProps != null) signingConfig = signingConfigs.getByName("release")
            // R8: about 21 MB of dex down to a few. proguard-rules.pro keeps
            // what libmain calls by name.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // The release build as R8 makes it, but for the emulator too and
        // signed with the debug key: to test shrinking headless.
        create("r8test") {
            initWith(getByName("release"))
            ndk { abiFilters += listOf("x86_64") }
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
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

    sourceSets {
        getByName("main") {
            // cacert.pem; the bundled station list; the globe imagery.
            assets.srcDirs("src/main/assets", "../../assets/stations/android", earthDir.path)
        }
    }

    androidResources {
        // The packs are read in place with seeks, so they must be stored,
        // not deflated. (JPEG tiles wouldn't shrink anyway.)
        noCompress += "pack"
    }

    packaging {
        // Uncompressed and page-aligned in the APK, loaded in place.
        jniLibs { useLegacyPackaging = false }
    }

    lint {
        abortOnError = false
    }

    buildFeatures {
        compose = true
    }
}

// The interface (A4). Agreed with Aisling 2026-11-24; all Apache 2.0,
// built from source by AOSP. NOTES.md (A4) lists them. The BOM is the newest
// whose libraries compile against SDK 36 (Compose 1.12 and later need 37).
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
}

kotlin {
    jvmToolchain(21)
}
