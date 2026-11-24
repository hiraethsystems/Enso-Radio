// Versions are pinned here; NOTES.md (A0) says why.
plugins {
    id("com.android.application") version "9.5.2" apply false
    // AGP 9 compiles Kotlin itself ("built-in Kotlin"); listing the Kotlin
    // plugin here pins the Kotlin compiler version it uses.
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
}
