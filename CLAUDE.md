# Project brief: DX Garden for Android

Name: **DX Garden**. Repository: `hiraethsystems/DX-Garden-Android`. Application ID: `systems.hiraeth.dxgarden`. Internal/folder name: `dxgarden`.

## What this is

A Radio Garden style radio explorer for Android phones and tablets: spin a globe, hold the crosshair over a place, and hear live radio from there. It is a port of DX Garden for Linux (`hiraethsystems/DX-Garden-Linux`), which itself grew out of the PS Vita app (`hiraethsystems/DX-Garden`, frozen).

It should feel like Radio Garden to use, but with its own visual identity. Do not copy Radio Garden's artwork, logo, colours, icons or text. There is no Windows 9x look on Android: the interface is modern Android (Material 3), described in `NOTES.md` under "Interface".

## Where it came from (my decision, 2026-11-24)

This repository was made from `hiraethsystems/DX-Garden-Linux` with its history, keeping `core/`, `tests/`, `testdata/`, `tools/` (minus the AppImage scripts), `assets/fonts/` and the docs, and leaving out the Linux app (`linux/`, `cmake/linux.cmake`, `tools/make_appimage.sh`, `tools/build_linux_deps.sh`). `core/` here is the Android app's own copy and may change freely. Keep it portable C99, with no SDL, OpenGL or JNI, and keep its unit tests passing on the PC.

I will test builds on my phone (Samsung Galaxy S25 Ultra) and report back. Work through the milestones in `NOTES.md` in order, in one continuous run. At the end of each milestone, check everything you can headless, write the milestone's section in `NOTES.md`, commit, and carry straight on to the next one. Stop and wait for me only:

- at the checkpoints marked **[me]** in the milestone list, where something needs a person (listening, how touch feels, lock screen and Bluetooth, the look of the interface);
- before any `sudo` command, before pushing to GitHub, and before adding a library not already agreed;
- when a headless check fails and you can't fix it, or a decision isn't covered by this brief or `NOTES.md`.

When you stop at a checkpoint, give me a short test checklist for the phone, saying which items you've already checked headless.

## Development machine

All work happens on my main PC, **Saoirse** (Debian). Never assume Android Studio: everything must build, install and test from the command line (Android SDK command-line tools, Gradle wrapper, NDK, adb, the emulator). Do as much testing as possible headless (see "Testing" in `NOTES.md`) and only ask me for what needs a person: listening, how gestures feel, lock screen and Bluetooth controls.

## Platform

- Target SDK 36 (Android 16), compile SDK 36, **min SDK 33 (Android 13)**. Older devices are not supported.
- ABIs: `arm64-v8a` for releases; `x86_64` only in debug builds, for the emulator.
- Native libraries must work on devices with 16 KB memory pages.
- Edge-to-edge is mandatory at target 36: the globe draws behind the status and navigation bars, and the interface respects the insets.
- Back navigation uses the predictive back APIs (`OnBackInvokedCallback`); `onBackPressed` and `KEYCODE_BACK` are not delivered at target 36.
- Portrait, landscape and large screens (tablets, foldables) must all work; at target 36 orientation can't be locked on large screens.

## Station data (important)

As on Linux:

- The app ships a bundled station list (`assets/stations/android/stations.txt`), one dated snapshot of Radio Browser made with `tools/rb_to_stations.py --bundle <version>`, every format the app plays, HLS included, stations without coordinates placed as in `NOTES.md` (F6). On first run it becomes the user's `stations.txt`; the app never overwrites an existing one.
- The user's files live in the app's external files folder, `Android/data/systems.hiraeth.dxgarden/files/`, which a computer can reach over USB. Most users will only use the bundled list; anyone who wants to edit `stations.txt` or drop M3U/PLS files into `import/` does it from a computer over USB. No file picker or import/export screens are needed.
- Add station by URL, and "Open with DX Garden" for M3U, M3U8 and PLS files and links.

**One directory is allowed: Radio Browser (radio-browser.info).** Download Station List works as on Linux (`core/rb_build.c`, the same lines as `tools/rb_to_stations.py`, checked by `tests/compare_listtool.sh`): only when the user asks, never in the background.

**Country labels follow where a station actually is, whatever Radio Browser says (my decision):** Ukraine's territory, including Crimea and the occupied east and south, is labelled Ukraine; Palestinian territory is labelled Gaza or Occupied West Bank; UK stations are labelled by nation (England, Scotland, Wales, Northern Ireland), and the Crown Dependencies by their own names (Isle of Man, Jersey, Guernsey). Stations placed only by country go in that country's Country-wide place.

Bundle no other list, and do not use Radio Garden's own API or data in any form (including entries that point back at Radio Garden). No other directory or live lookup without asking me first. Keep all station loading behind the `station_source` interface in `core/`.

Mobile data needs no warning or setting: users know streaming works over mobile data.

## Graphics

The globe must be as detailed and good-looking as we can make it. Do not cut imagery resolution or quality to save APK size; modern phones have plenty of storage. Tell me the resulting sizes, and the licence of any new imagery, before adding it.

## Architecture

- `core/`: portable C99 as above. Unit tests in `tests/` (`dxgarden_test`), built on the PC without SDL, and also cross-built with the NDK and run on the emulator and phone over adb.
- `android/jni/`: the native app on SDL3: OpenGL ES 3 globe, decoders (mpg123, faad2, Vorbis, Opus, FLAC), libcurl with Mbed TLS, HarfBuzz/FreeType/FriBidi where native text is still needed, and the JNI bridge.
- `android/app/`: Kotlin. `MainActivity` (SDL's activity), the interface in Jetpack Compose (Material 3) drawn over the SDL surface, and the playback service (foreground service of type `mediaPlayback`, MediaSession, notification, audio focus).
- `tools/`: `build_android_deps.sh` (the libraries for each ABI, pinned versions and SHA-256 sums), `make_earth.py`, `rb_to_stations.py`, font and Unicode table generators.

Prefer libraries already used by the Linux app. Tell me what you propose before adding a new library (Kotlin/AndroidX libraries included). Everything must be free software buildable from source, so the app can go into F-Droid: no Google Play Services, Firebase, analytics or proprietary SDKs.

## Releases

- Distributed on GitHub (APK in Releases) and F-Droid. No Google Play.
- Signed with my own key, never committed. Aim for reproducible builds from the start so F-Droid can publish my signed APK (pinned NDK and Gradle, no build paths or build IDs in the native libraries).

## How to work with me

- Explain decisions in plain terms and keep a running `NOTES.md` of design decisions and known issues.
- Before each milestone, give a short plan; after it, give me a test checklist for the phone, saying which items you have already checked headless.
- Bump `android/BUILD` for every build so I can tell builds apart; the app's About screen, the log and the APK name show it. `android/VERSION` (MAJOR.MINOR.PATCH, minor and patch 0-9) changes only when we decide the next release. versionCode comes from BUILD. Releases are tagged `android-vX.Y.Z`.
- If something is uncertain (performance on other phones, a manufacturer's battery management, a device I haven't got), say so instead of guessing, and suggest how to test it.
- Keep the README honest: a player and explorer for streams, shipping a dated Radio Browser snapshot as a default list that is the user's to maintain. The app downloads a directory only when the user asks.
