# DX Garden for Android: design notes and known issues

> This repository was split from `hiraethsystems/DX-Garden-Linux` on
> 2026-11-24. The notes from the Linux app (and the Vita app before it) are
> kept below the Android section, because the shared `core/` and the station
> list rules come from them.

A running log of decisions, in plain terms. Newest milestone at the bottom of
the Android section.

## Decisions before A0 (2026-11-24)

| Question | Decision |
|---|---|
| Repository | New repo `hiraethsystems/DX-Garden-Android`, split from DX-Garden-Linux with its history |
| Test hardware | Samsung Galaxy S25 Ultra, wireless adb; as much as possible headless on Saoirse |
| Oldest Android | Android 13 (min SDK 33); target and compile SDK 36 |
| Distribution | GitHub APK and F-Droid only |
| Application ID | `systems.hiraeth.dxgarden` (reverse of hiraeth.systems; if that domain isn't ours, use `io.github.hiraethsystems.dxgarden` instead, as F-Droid rejects IDs that match someone else's domain) |
| Look | Modern Android, Material 3, no 9x look (see "Interface") |
| User files | `Android/data/systems.hiraeth.dxgarden/files/`, edited from a computer over USB |
| Globe imagery | Full detail, no cuts for size (see "Globe imagery") |
| ABIs | `arm64-v8a` for releases, `x86_64` for the emulator in debug builds |
| Mobile data | No warning or setting |

Why min SDK 33: everything the app needs is there (AAudio, OpenGL ES 3.2,
Material You colours, themed icons, the notification permission, predictable
storage rules), it avoids old-device workarounds, and it still covers most
phones in use. Android 12 and older are left out.

Known consequence of the user folder: Android deletes
`Android/data/<app id>/` when the app is uninstalled, favourites and edits
included. The README must say so and suggest copying `stations.txt` and
`favourites.txt` off over USB first. On the phone itself, only the built-in
Files app can see that folder; third-party file managers can't.

## How the Linux code maps to Android

| Linux | Android |
|---|---|
| `core/` | Unchanged; built by the NDK as part of the native library |
| Decoders, curl + Mbed TLS, text libraries | Same libraries, cross-built per ABI by `tools/build_android_deps.sh` |
| OpenGL 3.3 core shaders | OpenGL ES 3.0 (`#version 300 es`, precision qualifiers) |
| `earth-day.pack` read with `fopen` | Stored uncompressed in the APK, opened as a file descriptor plus offset |
| XDG data folder | App external files folder (see above) |
| System CA certificates (`SSL_CERT_FILE`) | curl's Mozilla CA bundle shipped in the APK |
| KDE/GNOME theme reading | System light/dark and Material You colours, from Kotlin |
| 9x toolkit, menus, child windows | Replaced by the Compose interface |
| Mouse and keyboard on the globe | Touch gestures (see "Interface") |
| Plays while the window is open | Foreground playback service (A3) |
| `--script`, `--screenshot` | Kept, passed as intent extras, for headless testing |

SDL notes:
- SDL's Android template still targets SDK 35 and overrides `onBackPressed`;
  we target 36 and handle back with `OnBackInvokedCallback`.
- SDL's activity puts its surface in a `RelativeLayout` (`mLayout`), which is
  where the Compose interface is added on top.
- The GL context can be lost when the app goes to the background and comes
  back. Globe tiles, atlases and shaders need a "rebuild everything" path.
- To keep audio playing in the background: `SDL_HINT_ANDROID_BLOCK_ON_PAUSE`
  off and `SDL_HINT_ANDROID_BLOCK_ON_PAUSE_PAUSEAUDIO` off, plus the
  foreground service so Android doesn't stop the process.

## Interface

The globe fills the screen, edge to edge. Everything else is Material 3
(Jetpack Compose) drawn over it, in DX Garden's own colours, with an option
to follow the phone's Material You colours instead.

Why Compose over the globe rather than our own C toolkit: it gets TalkBack
(screen reader) support, the system keyboard, font scaling, proper scrolling
for lists of 50,000 stations, predictive back and Material You for free.
Drawing all that ourselves in OpenGL would be a lot of code and still worse
for accessibility. The price is a JNI bridge between the Kotlin interface and
the C core, and two languages in one app.

### Portrait (phones)

- **Globe:** full screen, with a crosshair ring in the middle. Under it, a
  small label with the place being tuned ("Swansea, Wales").
- **Top:** a floating search bar (stations, places, genres), with a menu for
  Settings, Download Station List and About.
- **Bottom sheet**, dragged between three heights:
  - *Peek:* the tuned place, its country and how many stations it has.
  - *Half:* that place's stations; tap one to play it.
  - *Full:* tabs for Browse (country, then city, or by genre), Favourites and
    Recent.
- **Mini player**, docked above the sheet: station name, artist and title
  when the station sends them, play/stop and favourite. Tapping it opens Now
  Playing (station details, website link, sleep timer).

### Landscape, tablets and foldables

The sheet becomes a side panel on the left and the globe takes the rest.
Same content, no separate screens.

### The globe by touch

- One-finger drag spins it, with momentum when flung.
- Pinch zooms. Double-tap zooms in, two-finger tap zooms out.
- A light haptic tick when the crosshair tunes to a new place.
- Place dots as on Linux: orange for towns, light blue for Country-wide places.

### Outside the app

- Notification and lock screen controls through MediaSession, and headset
  and Bluetooth buttons.
- Pauses for phone calls (audio focus) and stops when headphones are
  unplugged.
- A themed (monochrome) app icon as well as the normal one.

## Globe imagery

The Linux pack goes to level 5 (32768 px around the equator, about 1.2 km a
pixel). NASA's Blue Marble pieces are 21600 px each, 86400 px around the
equator (about 500 m a pixel), so a level 6 (65536 px, about 600 m a pixel)
uses nearly all of the source's detail. Plan for A2:

- Build level 6. Size estimate (a guess until it's built): the extra level
  has four times the tiles of level 5, so roughly 250 to 300 MB for the pack,
  against about 70 MB now.
- Look at a night side with city lights (NASA's Black Marble) and the
  terminator moving with the real time, keeping the current atmosphere glow.
  Check its licence before using it.
- Check whether a free, higher-resolution global source exists that would be
  worth more levels.

**Open question for release (A7): F-Droid and a large APK.** F-Droid builds
the app from source, so the pack would be built on F-Droid's servers from the
NASA downloads, and a 300 MB APK may be more than it accepts. An F-Droid post
from 2017 talks of a 100 MB APK limit and offers OBB expansion files instead;
that needs checking against current practice. Options: the pack inside the
APK if F-Droid accepts it, an OBB file, or a separate download on first run
that the user agrees to. GitHub releases have no such problem, so GitHub
comes first.

## Testing

Everything runs from Saoirse's command line.

- **Unit tests:** `core/` tests on the PC as now, and the same tests
  cross-built with the NDK, pushed with adb and run on the emulator and the
  phone.
- **Emulator:** a headless `x86_64` Android 16 emulator on Saoirse (needs
  KVM, run with `-no-window`, software OpenGL ES). Used for installs,
  screenshots, scripted input, lifecycle tests and logs.
- **Scripted runs:** the Linux `--script` and `--screenshot` options, passed
  as intent extras (`adb shell am start ... -e script "..."`), plus
  `adb shell input` for real touch events and `adb exec-out screencap` for
  screenshots.
- **Checks without ears:** the log records connect, buffer, decode and
  samples written to the audio device, so playback can be confirmed without
  listening. `dumpsys media_session` checks the MediaSession;
  `am start` / home / `input keyevent` test going to the background and back.
- **The phone:** paired for wireless adb, so builds install and run without a
  cable. Left for a person: what it sounds like, how gestures feel, lock
  screen and Bluetooth controls, the notification, and battery drain over a
  long listen.

## Milestones

Run in one go. `BUILD` goes up for every build. **[me]** marks a checkpoint
where work stops until Aisling has tested on the phone; everything else
carries straight on once its headless checks pass.

- **A0. Repository and toolchain.** Split the repo from DX-Garden-Linux.
  Install the Android SDK command-line tools, NDK and an emulator image on
  Saoirse. `tools/build_android_deps.sh`. Gradle project from SDL's template,
  moved to SDK 36. `core/` tests run on the emulator.
- **A1. One stream.** The app starts, shows a plain screen and plays one
  built-in stream through the existing player and decoders. Logs to a file
  and to logcat. Proves curl, TLS, certificates, decoders and audio.
  **[me]** Does it actually sound right on the phone, every format?
- **A2. The globe.** OpenGL ES shaders, the level 6 imagery from the APK,
  touch gestures, the crosshair tuning to places, the bundled station list
  and place dots, edge-to-edge, and surviving the home screen and rotation.
  **[me]** How the globe looks and feels under a finger.
- **A3. Background playback.** Playback service, notification, MediaSession,
  audio focus, headphones unplugged, Wi-Fi lock, and stopping cleanly.
  **[me]** Lock screen, notification, Bluetooth, a call coming in, a long
  listen with the screen off.
- **A4. The interface.** Compose over the globe: search, bottom sheet, mini
  player, Now Playing, Browse, Favourites, Recent, Settings, the landscape
  and tablet layout, predictive back, TalkBack labels. An About page
  (asked for 2026-11-24): app name, version, build, a short description,
  and the author's name and email. Confirm the exact name and email with
  Aisling before the A4 build. The About page also carries this
  dedication, word for word, in its three paragraphs (asked for
  2026-11-24):

  > This small garden of voices is dedicated to Emma, Max and Phoebe, my
  > family, and to Imi and John, my dearest friends.
  >
  > Like stations drifting across the dial at night, so much in this world
  > arrives and fades away. You are the ones that stay.
  >
  > Wherever I wander on this turning earth, through every distant city
  > and quiet sea of static, my heart returns to you, the way a river
  > returns to the ocean, gently and always.
  **[me]** The whole interface, before it's polished further.
- **A5. Your stations.** First-run copy of the bundled list, `import/`,
  Add Station by URL, Open with DX Garden, Download Station List.
- **A6. GitHub release.** Icons, signing, README, licences screen, release
  APK tagged `android-v0.9.0`. **[me]** Final check before the release is
  published.
- **A7. F-Droid.** Reproducible build checks, F-Droid metadata, the imagery
  size question above, submission.

## A0: repository and toolchain (done, 2026-11-24)

**Repository split (2026-11-24).** Made like DX-Garden-Linux was made from
DX-Garden: a fresh clone of DX-Garden-Linux, then `git filter-repo
--invert-paths` on `linux/`, `cmake/linux.cmake`, `tools/make_appimage.sh`,
`tools/build_linux_deps.sh` and `assets/stations/linux/`, so those are gone
from the history too. The Linux screenshot is removed in the split commit,
as the Vita screenshots were in the Linux split. All 40 commits are kept.
Working copy: `~/Dev/DX-Garden-Android` on Saoirse.

- `CMakeLists.txt` now builds only `dxgarden_test` and `dxgarden_listtool`,
  reading `android/VERSION` and `android/BUILD`. `-DDXG_APP=OFF` still
  works; `ON` stops with a message pointing at Gradle.
- `cmake/revision.cmake` is kept (the short git commit for the About
  screen); nothing uses it until the native build does.
- Core tests on Saoirse after the split: `dxgarden_test` 4755 checks,
  0 failures, no warnings; `test_rb_to_stations.py` 27 tests OK.
- The GitHub repo had one commit made by GitHub (a LICENSE); Aisling agreed
  the split history is force-pushed over it.

**Toolchain on Saoirse.** `~/Android/Sdk` already existed (cmdline-tools
23.0, platform 37, NDK 30.0.16248370, CMake 4.1.2, an API 37 AVD); kept as
it is, with these added by `sdkmanager`:

| Package | Version | Why |
|---|---|---|
| `platforms;android-36` | r02 | compile SDK 36 |
| `build-tools;36.1.0` | 36.1.0 | |
| `ndk;28.2.13676358` | r28c | the version SDL 3.4.18's `android-project` pins (its README only asks for r15c or later) |
| `cmake;3.31.6` | 3.31.6 | same as Saoirse's own CMake |
| `emulator` | 37.3.10 | |
| `platform-tools` | 37.0.3 | |
| `system-images;android-36;google_apis_ps16k;x86_64` | r07 | Android 16 with **16 KB pages**, so the emulator checks our alignment; Google's 16 KB images only come with Google APIs (emulator only, nothing in the app) |

No new licence had to be accepted. Java is Debian's OpenJDK 21.0.13. KVM:
`/dev/kvm` is readable and writable through a user ACL.

AVD `dxg_api36` (Pixel 8 profile): boots with `-no-window -gpu
swiftshader_indirect` in about 20 s; reports Android 16, SDK 36,
`PAGE_SIZE` 16384, GLES "OpenGL ES 3.0 SwiftShader".

**Scripts** (all from the repository root):

- `. tools/android_env.sh`: SDK, pinned NDK and CMake on the `PATH`,
  `JAVA_HOME`, `SOURCE_DATE_EPOCH`, the AVD name and port.
- `tools/emulator.sh start|stop|status|create`: headless emulator
  `emulator-5580` (`-no-window -gpu swiftshader_indirect -no-audio`),
  waits for boot (about 10 s warm) and wakes the screen.
- `tools/build_android_deps.sh [arm64-v8a|x86_64|all] [lib...]`: the native
  libraries, about 2 minutes per ABI on Saoirse, 80 MB per ABI in
  `third_party/android/<abi>/` (git-ignored).
- `tools/android_test.sh [serial]`: cross-builds `dxgarden_test` for the
  device's ABI, pushes it and `testdata/` to `/data/local/tmp/dxg/`, runs it.

**Native libraries**: the same versions and SHA-256 sums as the Linux
script: SDL 3.4.18, curl 8.23.0, Mbed TLS 3.6.7, mpg123 1.33.7, FreeType
2.15.0, HarfBuzz 15.0.0, FriBidi 1.0.18, faad2 2.11.4, libogg 1.3.6,
libvorbis 1.3.7, Opus 1.6.1, opusfile 0.12, FLAC 1.5.0, zlib 1.3.1. All
static, min SDK 33, `c++_static`.

- CMake projects use the NDK's toolchain file; HarfBuzz and FriBidi use
  meson with a cross file written per ABI; opusfile (no CMake build in
  0.12) uses its shipped `configure --host`, so no autotools are needed.
- Every library found our own builds, not the NDK sysroot's (checked in
  the configure logs: curl with Mbed TLS 3.6.7, Vorbis and FLAC with our
  Ogg, HarfBuzz with our FreeType, opusfile with our Ogg and Opus).
- SDL is linked statically into `libmain.so`, so `MainActivity` loads only
  `main` (`getLibraries()`).
- Reproducible: `-ffile-prefix-map` for C, C++ **and assembly** (mpg123's
  `.S` decoders leaked build paths until `CMAKE_ASM_FLAGS` got the maps
  too), `--build-id=none`, deterministic archives. Rebuilding curl,
  opusfile and HarfBuzz gave byte-identical `.a` files; no archive contains
  a `/home` or SDK path.

**Gradle project** (`android/`), from SDL 3.4.18's `android-project`:

| | |
|---|---|
| Gradle wrapper | 9.9.2, `distributionSha256Sum` pinned |
| Android Gradle Plugin | 9.5.2 |
| Kotlin | 2.4.21 (AGP 9's built-in Kotlin; the plugin is listed only to pin the version) |
| JDK | 21 (Debian), Java/Kotlin target 17 bytecode |
| SDK | compile 36, target 36, min 33 |
| NDK / CMake | 28.2.13676358 / 3.31.6 |
| ABIs | debug: arm64-v8a and x86_64; release: arm64-v8a |

- Build files are Kotlin script (`*.gradle.kts`), not the template's Groovy.
- versionName from `android/VERSION`, versionCode from `android/BUILD`;
  APK `dxgarden-<version>-b<build>-<type>.apk`
  (`app/build/outputs/apk/debug/dxgarden-0.9.0-b1-debug.apk`).
- SDL's Java (`org.libsdl.app`) is copied unchanged from 3.4.18 and must be
  replaced whenever SDL is updated. Its `onBackPressed` gives the one
  deprecation note in the build; back handling comes in A2/A4.
- `MainActivity` (Kotlin) extends `SDLActivity`; the manifest turns on
  `enableOnBackInvokedCallback` and asks for OpenGL ES 3.0, `INTERNET` and
  `VIBRATE`. The launcher icons are SDL's placeholders until A6.
- `android/jni/` holds the native app: `CMakeLists.txt` and `src/main.c`.
  The A0 `main.c` logs its version and draws a plain blue screen.
- AGP 9 refuses `proguard-android.txt` (it has `-dontoptimize`); the
  release type uses `proguard-android-optimize.txt` (minify is off for now).

**Headless checks, all passed:**

- `dxgarden_test` on the emulator (x86_64, Android 16, 16 KB pages): 4755
  checks, 0 failures, exit 0, no compiler warnings. The arm64-v8a test
  binary cross-builds; it runs on the phone once paired.
- `./gradlew assembleDebug` from the command line. In the APK:
  versionCode 1, versionName 0.9.0, minSdk 33, targetSdk 36; both
  `libmain.so` files stored 16 KB-aligned (`zipalign -c -P 16`) with LOAD
  segments aligned to 0x4000; no build ID and no build paths in them.
- A clean rebuild gives byte-identical `libmain.so` (both ABIs) and dex
  files. (Same folder; a build from a different folder is for A7.)
- Installed and launched with `adb shell am start -W`: starts in about
  0.6 s; logcat `SDL/APP: DX Garden 0.9.0 build 1 starting (SDL 3.4.18)`,
  renderer `opengles2`. The screenshot shows the blue filling the screen
  behind the status and gesture bars.

**Surprises:**

- SDL checks whether `SDL_MAIN_USE_CALLBACKS` is *defined*, so
  `#define SDL_MAIN_USE_CALLBACKS 0` turns callbacks on.
- `SDL_WINDOW_FULLSCREEN` on Android means immersive mode (system bars
  hidden, with a "Viewing full screen" prompt). We want edge-to-edge with
  the bars showing, so the window is created without it.
- `tests/test_library.c` made its temporary folder under `/tmp`, which
  Android doesn't have; it now uses `TMPDIR` first. The tests' testdata
  path is a CMake cache variable (`DXG_TESTDATA_DIR`), and the sanitizers
  are an option (`DXG_SANITIZE`, on for the PC, off for the NDK build).

**Known issues:** none blocking. The phone isn't paired yet (instructions
come at the A1 checkpoint).

## A1: one stream (build 2, 2026-11-24)

**What it does.** The app starts on a plain screen and plays SomaFM Groove
Salad (MP3 128 kbps, HTTPS) through the Linux app's player and decoders.
The screen's colour shows the state: night blue while connecting or
buffering, green playing, amber reconnecting, red on an error. There's no
other interface yet.

**From the Linux app** (`android/jni/src/`, copied from DX-Garden-Linux's
`linux/` at the split): `player.c`/`.h` (curl network thread, ICY, HLS,
playlists, reconnects, SDL audio stream), `decode.c`/`.h` (mpg123, faad2,
Vorbis, Opus, FLAC), `log.c`/`.h`. Changes:

- **Certificates:** the Linux search of `/etc/ssl` is gone. curl's Mozilla
  CA bundle (`cacert-2026-11-05.pem`, 118 certificates, MPL 2.0) is in the
  APK as `assets/cacert.pem`, read once with `SDL_LoadFile` and given to
  curl from memory (`CURLOPT_CAINFO_BLOB`, which curl's Mbed TLS backend
  supports). `tools/update_cacert.sh` fetches a given dated bundle and
  checks its SHA-256; the file is committed so builds need no network.
- **Log:** every line goes to logcat (tag `DXGarden`) as well as to
  `log-<version>-b<build>.txt` in the external files folder
  (`Android/data/systems.hiraeth.dxgarden/files/`), with the previous run
  kept as `-previous.txt`, as on Linux.
- **Status:** `player_status.frames_out`, the stereo frames handed to the
  audio device. The decoder waits when SDL's queue is full, so this rises
  at the stream's sample rate only while the device is really taking
  audio. That's the "samples written" check without ears.
- User agent `DXGarden/0.9.0 (Android)`.
- `cmake/core_sources.cmake` now holds the `core/` file list, used by both
  the PC build and `android/jni/CMakeLists.txt`.

**Test mode.** In debug builds, `MainActivity` turns intent extras into the
Linux options: `-e play URL -e seconds N` plays for N seconds, logs a
status line each second and closes the app with `result: played` in the
log. Release builds ignore extras, so other apps can't drive them.
`tools/android_play_test.sh [-s serial] [-t secs] [URL...]` runs a list of
streams this way and prints the decoder, last status and result for each.

**Headless checks (emulator, x86_64, Android 16, 16 KB pages), all
passed**, 15 s each, no underruns, decode load under 1%:

| Format | Stream | Result |
|---|---|---|
| MP3 128 | SomaFM Groove Salad | played, ICY title and station site |
| AAC LC 320 | Radio Paradise | played, title |
| HE-AAC 32 | SomaFM Groove Salad | played |
| Opus | Deutschlandfunk | played (48 kHz) |
| Opus, chained | Nightwave Plaza | played, title |
| Ogg Vorbis | Energy 90s (Bulgaria) | played, title from Vorbis comments |
| FLAC in Ogg | Radio Paradise Rock FLAC | played, 955 kbps |
| FLAC | Rondo Classic Klasu | played |
| HLS (HE-AAC in TS) | BBC Radio 4 | played (48 kHz) |
| PLS playlist | SomaFM Groove Salad | followed to the MP3 stream, played |

Every run logged `frames out` rising at the stream's rate (about 44,100 or
48,000 a second) through SDL's AAudio driver. TLS: Mbed TLS 3.6.7 with the
bundled certificates, about 0.7 s to the handshake. `dxgarden_test` still
passes on the emulator after the CMake changes.

**Surprises:**

- Nightwave Plaza's `/ogg` stream is Opus, not Vorbis; a Vorbis station
  was found by reading the first Ogg page of Radio Browser's most-clicked
  Ogg stations.
- When `main` returns (end of a test run), the activity closes but Android
  keeps the process. On the next launch SDL sees its native code has
  already run and ends the process, and Android starts a fresh one, so
  the C code always starts clean. (Seen in logcat: "has died ...
  restarted".) Nothing to do, but worth knowing.
- The emulator with `-no-audio` still has a working AAudio output (to
  nowhere), so playback can be tested headless without sound on Saoirse.

**Known issues (for later milestones):**

- Going to the home screen pauses the audio and the main loop (SDL's
  default); it resumes on return. Background playback is A3.
- Nothing is shown on screen but a colour; the interface is A2 and A4.

**Checkpoint [me]:** does it sound right on the phone, in every format?

**On the phone (2026-11-24, build 2):** Galaxy S25 Ultra (SM-S938B),
Android 17 (API 37), arm64-v8a, 4 KB pages, over USB adb. The arm64 core
tests passed (4755 checks, 0 failures). `tools/android_play_test.sh -t 20`
played all 10 streams through AAudio, no underruns, decode under 1% of a
core. Aisling listened: **all formats sound right.** A1 done.

The phone uses 4 KB pages, so 16 KB page support is covered only by the
16 KB emulator image; a 16 KB device would be needed to test it for real.

## A2: the globe (builds 3 to 6, 2026-11-24)

**Imagery (Aisling agreed to both layers, 2026-11-24).** Both are level 6:
65536 x 32768 px for the whole Earth, about 600 m a pixel, which uses
nearly all of NASA's 500 m sources.

| Pack | Source | Tiles | Size |
|---|---|---|---|
| `earth-day.pack` | Blue Marble: Next Generation, July 2004, topography and bathymetry | 10,922 | 241.1 MB |
| `earth-night.pack` | Black Marble 2016, colour, 500 m tiles | 10,922 | 118.4 MB |

- Licence: NASA imagery, not under copyright in the US. NASA's media
  guidelines ask for NASA to be credited and for no implied endorsement.
  Credit for Black Marble 2016: NASA Earth Observatory (Joshua Stevens),
  Suomi NPP VIIRS data from Miguel Román, NASA Goddard Space Flight
  Center. Both are credited in the README. The night layer is bigger than
  guessed because the 2016 colour version shows faint land and sea, not
  plain black.
- `tools/make_earth.py --layer day|night --max-level N` (default 6). Level
  1 to N are now cut one 90-degree source piece at a time, and only level 0
  joins the pieces. That keeps memory to about one source piece (2.5 GB at
  level 6). `--max-level 5` gives the Linux pack (70.8 MB, same tiles).
  Black Marble's eight files are pinned by SHA-256 like Blue Marble's.
  Each pack builds in about 90 s on Saoirse.
- The packs go in `build-earth/`, which isn't in git. Gradle stops with
  the two commands to run if they are missing. The source images are
  cached in `third_party/earth/`.
- APK: the packs are stored uncompressed (`noCompress "pack"`) and read in
  place through SDL's asset streams with seeks. The debug APK is 378 MB
  (two ABIs). A release (arm64 only) should be about 373 MB. Installing
  over USB took 17 s.

**Station list.** `assets/stations/android/stations.txt` comes from the same
22 November Radio Browser snapshot and command as the Linux list
(`--bundle "for Android 0.9.0"`). It has the same 51,378 stations line for
line; only the header differs. The first-run copy to `stations.txt` (an A5
item) is done now because the globe needs a list: if `stations.txt` isn't
in the files folder, the bundled one is written there. An existing file is
never touched. The phone loads it in 195 ms: 5,285 places in 226
countries.

**Renderer** (`android/jni/src/earth.c`, from Linux `linux/earth.c`):
- OpenGL ES 3.0 shaders (`#version 300 es`, highp). Anisotropic filtering
  when the driver has it; the phone's Adreno 830 does.
- Two layers. Each tile is drawn from the day and night tiles (or their
  nearest loaded ancestors) and blended by the Sun's angle. Daylight fades
  over civil twilight (about 6 degrees either side of the terminator), with
  a gentle darkening towards it on the day side. The haze and atmosphere
  glow dim on the night side. A tile wholly in day or night (with an 8
  degree margin) loads only the layer it shows.
- The Sun: `core/sun.c` (new, portable C99) gives the subsolar point from
  the time, using the Astronomical Almanac's low-precision formulas. Its
  tests in `tests/test_sun.c` check the equinox, both solstices and two
  equation-of-time dates: 4,762 checks in all, passing on the PC, the
  emulator and the phone. The terminator follows the real time.
- The field of view (40 degrees) spans the screen's shorter side, so the
  whole Earth fits a portrait phone. Linux used the height.
- The crosshair is drawn in GL: a white ring that fills with an amber arc
  as the dwell timer runs. Place dots are 0.75 dp a unit (Linux: 1 px a
  unit at 100%), so Europe stays readable on a phone.
- Tile cache: 320 tiles for both layers, up to about 490 MB of GPU memory
  when full (measured on the phone after a tour of six places). Going to
  the background frees all but levels 0-2, which took the app from 720 MB
  to 167 MB, ready for background playback in A3.
- A lost GL context (SDL's `SDL_EVENT_RENDER_DEVICE_RESET`) rebuilds
  programs and buffers, and the tiles reload as they are drawn. A script
  step forces this path: after it, the screenshot matches the one before
  pixel for pixel.

**Touch** (`android/jni/src/globe_view.c`): one finger drags the point under
it, with a fling on release. A finger held still before lifting doesn't
fling. Two fingers pinch to zoom about their midpoint and drag together.
Lifting one carries on with the other. Double-tap flies to the point and
zooms in (altitude x 0.4); two-finger tap zooms out. Tapping a dot plays
that place. SDL's pretend mouse events are off. Tuning: the place under the
crosshair plays after 0.8 s (as on Linux), but not while a finger is on
the globe, and not for the place the app opens over. A light haptic tick
(`CLOCK_TICK`) marks tuning.

**Place label:** a plain Android `TextView` added by `MainActivity` over
SDL's layout ("London, England" over "293 stations" or the station
playing). Native code sets it, and triggers the haptic tick, through a
small JNI bridge (`bridge.c`). It uses no library and is a stopgap: the
Compose interface replaces it in A4.

**Lifecycle:** the camera is saved to `config.ini` in the files folder on
going to the background and at exit. SDL 3 doesn't queue the
background/foreground events: they reach only event watchers, called right
then, so `main.c` uses `SDL_AddEventWatch`. Android sends a low-memory
event each time the app goes to the background (`onTrimMemory`); it's
only logged.

**Checked headless:**
- Emulator (SwiftShader): the globe at three heights; drag and fling
  (`input swipe`); double-tap; tuning over Dublin (8Radio played, MP3
  320k, no underruns); rotation to landscape and back; home and back (hot
  start, no crash); the GL rebuild.
- Phone over USB: the same screens; 120 frames a second; the label under
  the crosshair; memory before and after home; the core tests.

**Test options (debug builds):** `-e goto "LAT LON ALT"`, `-e time UNIX` (fix
the Sun), `-e screenshot FILE -e frames N`, and `-e script` with `wait N`,
`sleep MS`, `goto`, `camera`, `shot FILE`, `glreset` and `quit`. A1's
`-e play URL -e seconds N` still works.

**Known issues:**
- Below about 100 km the night lights are blocky. That's in NASA's source
  (500 m cells, sharp-edged), not the filtering. They could be softened
  with a blur when the pack is built, which wouldn't lose detail but would
  change the look. Left as it is for Aisling to judge.
- Not testable headless (`adb shell input` has one finger): pinch,
  two-finger tap, and the haptic tick.
- The crosshair and label are at the screen's centre, not the centre of
  the area left by the status and navigation bars. They'll move when the
  A4 sheet takes part of the screen.
- Audio still pauses in the background (A3).
- A dwell plays a place's first station (alphabetical), as on Linux.
- GPU memory: 320 tiles is fine on the S25 Ultra. Phones with less memory
  are untested; the cache size could follow the device's memory class.

**Checkpoint [me]:** how the globe looks and feels under a finger.

### A2 phone feedback and fixes (builds 7 to 9, 2026-11-24)

Aisling on build 6: audio fine, label readable; but (1) the first couple of
zoom-outs zoomed out, moved the map, then zoomed in sharply somewhere else;
(2) tiles popped in and out around the North Pole; (3) touch a bit too
snappy, and inaccurate near the poles.

To reproduce gestures headless, the test script gained synthetic fingers
(`fdown|fmove|fup ID X Y`), `trace N` (the camera each frame), `probe X Y`
(the point under a pixel) and `stats` (the tile cache).

1. **The zoom jump.** At the end of a pinch the fingers are almost
   touching. The screen can report one lifting and a new one landing right
   next to the other, and a new pinch then started from a gap of about
   90 px, so a few pixels of spread zoomed right in and the midpoint
   dragged the map. Replayed on the emulator: 16,486 km went to 6,466 km
   and moved 5 degrees. Fix: pinch distances have a floor of 60 dp. The
   same replay now stays at 14,877 km.
2. **Pop-in at the poles.** Two causes:
   - **Lost tiles.** Decoded tiles waiting for upload (8 a frame) were
     thrown away once 96 were waiting. Their slots stayed "loading" for
     good and were never asked for again, so the coarser parent showed
     instead. This came from the Linux code (`linux/earth.c` has the same
     bug). Now the loaders wait for room, and anything still loading after
     5 s is asked for again. 21 to 64 stuck tiles went to 0.
   - **Too many tiles.** Near a pole a whole ring of thin tiles is in
     view, more than the cache holds. The detail threshold now rises until
     the view needs at most 120 tiles per layer. At the pole it now draws
     80 to 90.
3. **Dragging near the poles.** Adding to latitude and longitude goes wrong
   at high latitudes. The drag now turns the camera by the 3D rotation that
   carries the point under the finger onto the grabbed one. Then Newton
   steps on the screen position take up the twist from keeping north up.
   Every step is kept only if it brings the point closer, so nothing can
   jump. Measured: a circular drag at 60 N and at the equator keeps the
   grabbed point under the finger to within 0.01 degrees.
   
   North stays up, as on Linux. A north-up camera can't pass over a pole
   without the view spinning round, so the camera now stops at 87 degrees
   (it was 85), and sideways drags still turn the globe about the pole. A
   fling uses the same rotation and stops at the limit instead of flipping.
4. **Less snappy.** Zoom easing 10 to 7 per second; flying to a place 6 to
   4.5; the fling's speed now comes from the last 80 ms of the drag (it
   was a running average of each event) and slows a little sooner (decay 3
   to 2.5 per second, from a calmer start). Aisling to judge whether
   that's the right direction.

**Known issues:**
- A faint seam along the 180-degree meridian close to the North Pole,
  where tiles are thin and meet at the edge of NASA's source pieces.
- The phone was locked during the last test run, so build 9's tile counts
  were checked only on the emulator.

## A3: background playback (builds 10 to 13, 2026-11-24)

**What it does now.** The station keeps playing when you leave the app or
turn the screen off. While something is playing there is a media
notification (station, the title playing, the place) with play/pause,
previous, next and stop. The same controls are on the lock screen, the
quick settings media player, headset buttons and Bluetooth (AVRCP). The
build number ("0.9.0 · build 13") is in the bottom-left corner of the
globe, clear of the navigation bar, until the A4 interface has an About
page.

**How, in plain terms:**
- Only Android's own media APIs (`android.media.session.MediaSession`,
  `Notification.MediaStyle`), so no new library. AndroidX Media3 would
  have meant several new libraries for nothing we need yet.
- `PlaybackService` is a foreground service of type `mediaPlayback`. It
  runs only while a station is connecting, playing, paused or showing an
  error, and goes when you press stop. Media notifications tied to a
  session don't need the notification permission (Android 13+), so the app
  doesn't ask for it.
- `Playback.kt` holds the session, audio focus, the "headphones
  unplugged" receiver and the locks. Native code reports what's playing
  (`bridge_playback`, via `MainActivity.playbackUpdate`, only when
  something changes); the session's buttons go back to native code through
  `Playback.nativeCommand`, into a small locked queue the main loop
  empties every pass. (Build 10 used SDL user events for this, and in deep
  doze one command in eight was lost; the queue fixed it in build 12.)
- SDL is told not to block when the activity pauses
  (`SDL_HINT_ANDROID_BLOCK_ON_PAUSE` "0"), so the loop and audio go on.
  In the background nothing is drawn (the GL context isn't ours then): the
  loop wakes every 100 ms to run commands and the player's reconnects.
  Drawing comes back on return, with the tiles reloaded.
- **Pause stops the stream**, and play reconnects to the same station: a
  live stream can't wait. Next and previous step through the stations of
  the place playing, round in a circle.
- **Audio focus:** a call or another app's short sound pauses, and play
  resumes when it's over. Another app starting to play pauses and stays
  paused. Navigation prompts and similar lower our volume (Android does
  that itself). Pressing play during a call asks for focus again, so it
  doesn't play over the call. Unplugging headphones pauses.
- **Locks:** a partial wake lock and a Wi-Fi lock only while connecting or
  playing. Wi-Fi locks do nothing from Android 14 on but help on 13.
- **Swiping the app away from Recents stops playback** and ends the
  service: the player lives in the activity's native thread. Radio Garden
  and most radio apps keep playing instead. Making it keep going would
  mean moving the player out of SDL's thread. Aisling can ask for it.

**Checked headless:**
- Emulator (API 36):
  - Plays on in the background, with the screen off, and in forced deep
    doze (frames out still rising).
  - Pause, play, next, previous and stop through `cmd media_session
    dispatch`: all eight commands arrived in doze with the screen off.
  - The session (`dumpsys media_session`: active, PLAYING, metadata) and
    the foreground service (`isForeground=true`, type mediaPlayback).
  - A ringing call (`adb emu gsm call`) paused, and cancelling it
    resumed.
  - Returning to the app draws again.
  - Removing the task stops playback, ends the service and the process.
- Phone (build 13):
  - Plays in the background with the screen off.
  - Pause and play from the session; stop ends the service.
  - 119 MB PSS while playing in the background.

**Not checked (needs a person or a device):** how the notification and
lock screen look; Bluetooth and wired headset buttons; unplugging
headphones; a real call; a long listen with the screen off (whether
Samsung's battery management leaves it alone); Android Auto (not
supported yet: it needs a MediaBrowserService, a later question).

**Known issues:**
- The notification has no artwork: station logos aren't shown anywhere
  yet.
- While paused, the globe still marks the paused station's place as
  playing.

---

(The Linux and Vita notes follow, unchanged from DX-Garden-Linux.)

# DX Garden: design notes and known issues

> **This repository is DX Garden for Linux (split out 2026-11-24).** It was
> made from `hiraethsystems/DX-Garden` with its history, leaving out the
> Vita app (`vita/`, `sce_sys/`, LiveArea art, the flat map and the Vita's
> station list). The notes below are kept whole, Vita milestones included,
> because the Linux app grew out of them; the Vita app itself is frozen in
> the DX-Garden repository.

A running log of decisions, in plain terms. Newest milestone at the bottom.

## Building

```sh
# Vita VPK (needs VITASDK set):
cmake -S . -B build -DCMAKE_TOOLCHAIN_FILE=$VITASDK/share/vita.toolchain.cmake -DCMAKE_BUILD_TYPE=Release
cmake --build build            # -> build/dxgarden-<version>.vpk

# Linux unit tests for core/:
cmake -S . -B build-test && cmake --build build-test && ./build-test/dxgarden_test
```

The version lives in `VERSION` (format `##.##`, the Vita's `APP_VER`). It is
bumped for every build handed over for testing, and the VPK file name carries
it so builds can be told apart.

## Dev loop

- Builds are installed by hand: copy `build/dxgarden-XX.YY.vpk` to the Vita
  over FTP and install it with VitaShell. Reinstalling over an existing
  install keeps `ux0:data/dxgarden/`.
- **Do not suggest vitacompanion.** It stopped the Vita from booting and has
  been removed. There is no push-and-relaunch step.
- Logging goes to `ux0:data/dxgarden/log-<version>.txt`, one file per
  build (from 00.04; earlier builds wrote `log.txt`). A file is overwritten
  only when the same build is launched again. Each line is flushed so the
  log survives a crash. Copies brought back to the PC go in `logs/`
  (git-ignored) under the same name. No UDP logging for now (not wanted).

## Hardware and system requirements

- PCH-1000, HENkaku/ensō, taiHEN, SD2Vita (the target setup).
- **From M3 on: `ur0:data/libshacccg.suprx`** is required, because vitaGL
  compiles shaders at runtime. It is present on the test Vita. The app must
  check for it at startup and show a clear message instead of crashing when
  it is missing. From 00.05 the app checks `ur0:data/libshacccg.suprx` and
  `ur0:data/external/libshacccg.suprx`; if neither exists it shows the
  message with libvita2d (precompiled shaders, system font) and quits.

## Libraries (VitaSDK 2026.08, vdpm)

Agreed before M1:

| Need | Choice | Notes |
|---|---|---|
| HTTP/HTTPS | libcurl 8.22 built against mbedTLS | Not the OpenSSL 1.0.2 curl. Needs pthread linked. |
| CA certificates | Mozilla bundle from curl.se, shipped as `app0:cacert.pem` | MPL 2.0, redistributable. Dated 2026-09-25, SHA-256 `a41b5d35…0505`. Refresh occasionally. |
| MP3 | mpg123 | |
| Audio out | native SceAudio (BGM port) | speexdsp only if resampling turns out to be needed. |
| AAC/HE-AAC (M4) | faad2 first | Benchmark against the hardware decoder (SceAudiodec); ffmpeg as last resort. |
| Vorbis / Opus (M4) | libvorbis+libogg, opus+opusfile | libtremor in reserve. Opus headers are under `include/opus/`. |
| Globe (M3) | vitaGL | libvita2d as fallback. Needs vitashark, SceShaccCgExt, mathneon, taihen_stub, SceShaccCg_stub, stdc++. |
| Text | freetype with an OFL font | From 00.03: Noto Sans Regular and Bold (OFL 1.1, about 1.2 MB). Covers Latin, Greek and Cyrillic, but not Chinese, Japanese or Korean. Falls back to the system font if the files are missing. Needs bz2 linked. |

`core/` uses no external libraries.

## Map texture (for M3)

**Chosen: our own dark texture drawn from Natural Earth vector data**
(public domain, naturalearthdata.com). A script in `tools/` will download
the shapes once on the build machine and draw land, sea and borders in the
app's colours. This is map geography only; no station data is involved.
NASA Blue Marble was the alternative, turned down as too bright and busy
behind the dots and text.

## M1: plays one stream (build 00.01)

**What's there**

- `core/ring_buffer`: plain byte ring buffer.
- `core/stream_buffer`: adds the prebuffer rule. No reads until `prebuffer`
  bytes have arrived. If it runs dry while the stream is still open, it goes
  back to buffering, waits for the full prebuffer again and counts an
  underrun. After end-of-stream the remainder drains. Unit tested.
- `vita/player`: two threads.
  - **Network thread** (curl): writes into the buffer and blocks when it's full.
  - **Decode thread** (mpg123): reads 4 KB at a time and outputs 1024-sample
    grains to a BGM audio port. It runs at a higher priority than the UI.
  - **Stop never blocks the UI.** The UI only asks for a stop, then reaps the
    finished threads on a later frame. A new stream picked while the old one
    is stopping is queued.
- `vita/ui`: status screen with libvita2d and the system font.
- Three hardcoded test streams in `vita/test_urls.h`.

**Decisions**

- **Buffer size:** 512 KB ring (about 32 s at 128 kbps).
- **Prebuffer:** default 64 KB (about 4 s), adjustable with L/R from 16 to
  256 KB.
- **How the prebuffer works:** a live stream arrives at about real-time speed,
  so the buffer holds roughly the prebuffer plus whatever burst the server
  sends on connect. That is the cushion against Wi-Fi hiccups: a 64 KB
  prebuffer rides out about 4 s with no data. Prebuffer is in bytes because
  the bitrate isn't known until audio is decoded; the screen shows the
  seconds equivalent at 128 kbps.
- **Sample rates:** the decoder always outputs 16-bit stereo; mono is
  duplicated to both channels. The BGM port accepts all MPEG sample rates
  (8 to 48 kHz), so no resampling is needed. When the rate changes, the port
  is reopened.
- **Dead streams:** a stream that sends no data for 20 s counts as dead. The
  connect timeout is 15 s.
- **HTTP version:** forced to HTTP/1.1 so stream servers behave predictably.
- **Redirects:** followed already (up to 5). M4 still owns proper redirect
  and playlist handling.
- **No ICY metadata yet:** the app does not send `Icy-MetaData: 1`, so the
  stream is pure MP3. `icy-name` from the response headers is shown as the
  station name.
- **No auto-suspend:** while a stream is open, the app stops the Vita from
  auto-suspending. Screen-off playback is an M5 question.
- **Clocks:** the CPU clock is left at the system default. The log records
  the clocks, and the screen shows decode CPU time as a percentage of real
  time.

**Build 00.02:** 00.01 would not install (VitaShell error 0x8010113D).
`icon0.png` had been written as a 2-bit palette PNG, because Pillow shrinks
the bit depth for images with few colours. LiveArea PNGs must be 8-bit
palette images. `tools/make_livearea.py` now pads the palette to 256 entries
and checks the bit depth after saving. No code changes.

**Hardware results (build 00.02, log from 2026-10-15)**

- Installs and plays all three test streams.
- Clocks at launch: ARM 500 MHz, bus 222, GPU 222. 500 MHz comes from an
  overclocking plugin on the test Vita (confirmed). Don't take its speed as
  typical: a stock Vita runs slower.
- **MP3 decode costs about 1% CPU** relative to real time. mpg123 is not a
  concern. Even at 333 MHz it would be around 2%.
- **Time from pressing Cross to hearing audio:**

  | Stream | Connect | First data → playing | Total |
  |---|---|---|---|
  | Radio Paradise HTTP | 0.13 s | 0.10 s (burst on connect) | 0.23 s |
  | Radio Paradise HTTPS | 1.6 s | 3.7 s (no burst) | 5.3 s |
  | KEXP HTTPS | 6.1 s | 2.9 s | 9.0 s |

  The KEXP connect time was not repeated in 00.04 (see below): the same
  stream connected in 1.9 s, spread evenly over DNS, TCP, TLS and first
  byte. Most likely network or server variance on the day.
- Steady-state fill:
  - Radio Paradise HTTP: steady at about 150 KB.
  - KEXP: arrives in bursts (50 to 250 kbps over one-second samples) and
    swings between 56 and 204 KB.
- **Radio Paradise HTTPS run:** one second of zero input, then the fill fell
  from 99 KB to 0 over 40 s. That gave one underrun, which recovered in 3.1 s
  as designed. Still to confirm with the tester whether this was the
  airplane-mode test.
- Stops are clean: curl returns 23 or 42 (aborted by us), and no error is
  shown.
- Quitting with Start and closing with the PS button both work (confirmed
  by the tester; this log ended with the PS button).
- The screen did not dim during about 2 minutes of playback. That is
  inconclusive, because the system dim timer may be longer. Screen-off
  playback stays an M5 question.
- **M1 done.**

**Known issues and open questions**

- **Checked on hardware (00.02, 00.04):** MP3 decode at 1-2% CPU, TLS
  handshakes of 0.3-0.5 s, and `app0:cacert.pem` read correctly by mbedTLS.
- **DNS can't be interrupted:** a slow DNS lookup can't be cancelled (curl's
  resolver here is synchronous). Stop still returns at once, but the old
  thread lingers until DNS times out. On quit the app waits up to 3 s.
- **"ICY 200" responses:** Shoutcast v1 servers that answer with `ICY 200`
  instead of `HTTP/1.x` are untested. The M1 test streams are Icecast or
  plain HTTP.
- **48 kHz untested:** all three test streams are 44.1 kHz, so 48 kHz
  playback hasn't been tried.
- **No reconnect yet:** when the connection drops, the buffered audio plays
  out, then the screen shows the error. Auto-reconnect is M4.

## M2: station library (build 00.03, both halves in one build)

**Files** (created on first run if missing, never overwritten):

- `stations.txt`: written with an explanation of the format and two
  commented-out examples. No working stations ship with the app.
- `favourites.txt`, `recent.txt`: one URL per line, with a comment header.
- `config.ini`: `[player] prebuffer_kb`. The app rewrites it when the
  prebuffer is changed in Player details. Comments the user adds are lost
  then, but unknown settings are kept.
- `import/`: M3U, M3U8 and PLS files. Stations added by URL go into
  `import/added-by-url.m3u`.

**stations.txt format:**
`Name | Stream URL | Country | City | Latitude | Longitude | Genre | Notes`

- **Required:** name, URL, latitude and longitude.
- **Country and city:** may be empty.
- **Comma decimals:** "48,85" is accepted, because some locales write
  decimals that way.
- **Bad lines:** skipped with a reason, which shows in the load report and
  the log.
- **Size limit:** files over 8 MB are refused.
- **Text encoding:** files that aren't valid UTF-8 are read as Latin-1, with
  a note in the report.

**Station loading** goes only through `core/station_source.h`. There are two
sources: `stations.txt` and the `import/` folder. stations.txt loads first,
so on a duplicate URL its entry wins. That is how an imported station
disappears from Unplaced once it has been placed.

**Places** (`core/library.c`):

- **Grouping:** stations are grouped by country and city, ignoring case,
  extra spaces and common Latin accents ("Zürich" = "Zurich").
- **Same name, far apart:** stations with the same name more than 100 km
  apart are separate places (two Portlands).
- **No city:** a station with no city joins any place within 25 km;
  otherwise it gets its own place, named by its coordinates.
- **A place's position** is its first station's coordinates.

**Sorting:** sorting uses the same accent-free form, so "Évora" sorts with
E. Scripts outside Latin sort after Latin, by byte order.

**Placing an imported station:**

- **Steps:** choose an existing place (Countries or Cities tab), or type
  city, country, latitude and longitude on the on-screen keyboard.
- **Saving:** the station is appended to `stations.txt` as a full line,
  with "imported from <file>" as its note. The M3U/PLS file is left
  untouched.
- **Duplicates:** after placing, the import entry is hidden because its URL
  is now in stations.txt.

**Favourites and recently played** are stored by URL, so they survive edits
to `stations.txt`. Recent keeps 50 entries.

**Controls (M2):**

| Button | Action |
|---|---|
| Up/Down | Move |
| Left/Right | Page |
| L/R | Change tab |
| Cross | Open, play or choose |
| Circle | Back |
| Triangle | Favourite |
| Square | Stop |
| START | Menu |
| SELECT | Player details (prebuffer is adjusted there with L/R) |

Button remapping comes after M3.

**Logging added:**

- Per connection: DNS, TCP, TLS and first-byte times, redirect count, and
  the final URL after redirects.
- A 10-second average download rate.
- Wi-Fi connect and disconnect.
- "switch requested" when a new stream replaces the playing one.
- On load: station, place and genre counts, and every load-report entry.

**Tests:** `core/` has unit tests for every parser and for grouping, plus
4,000 rounds of random and mangled input through every parser. They run
under AddressSanitizer and UBSan. `testdata/` has files for testing on the
Vita, with the expected results.

**Not verified on hardware yet:**

- `sceIoRename` used to replace files safely. If it fails, the app falls
  back to writing the file directly and logs it.
- Font rendering speed with Noto Sans.
- Library load time with large files.

## Build 00.04: fixes from testing 00.03

- **Keyboard couldn't be closed without confirming.** The dialog now opens
  with `SCE_IME_DIALOG_DIALOG_MODE_WITH_CANCEL`. Circle also aborts it from
  the app side. The log records which way it closed.
- **Small text looked thin, with marks above some letters.**
  - *Cause:* libvita2d caches each glyph once per font object, keyed by
    character only, and scales it for other sizes. The cache has no gaps
    between glyphs, so scaled glyphs pick up their neighbours' edges.
  - *Fix:* each text size gets its own font object, and sizes snap to
    14/17/19/21/26/34 px. Glyphs are now drawn 1:1.
  - *Cost:* about 12 font objects of 256 KB (512x512) each, roughly 3 MB.
  - *Size change:* the smallest UI text moved from 15-16 px to 17 px.
- **Logs** are now named per build.

**Tested on hardware (00.04):**

- **Keyboard:** Circle closes it, as does the dialog's own close button.
  Both arrive as `SCE_IME_DIALOG_BUTTON_CLOSE` (logged as `button 1`): the
  dialog handles Circle itself, so the app-side abort never fires. It stays
  as a harmless backup.
- **Text:** small text no longer looks thin and the marks are gone.
- **HTTPS start times** (Cross to audio), at stock 333 MHz:

  | Stream | DNS | TCP | TLS | First byte | Total to audio |
  |---|---|---|---|---|---|
  | Radio Paradise HTTP | 40 ms | 69 ms | n/a | 131 ms | 0.22 s |
  | Radio Paradise HTTPS | 0 ms (cached) | 30 ms | 336 ms | 566 ms | 0.7 s |
  | KEXP HTTPS | 528 ms | 635 ms | 1164 ms | 1903 ms | 2.6 s |

  Times are cumulative from the request, as curl reports them. Every stream
  sent an initial burst, so the 64 KB prebuffer filled in under 0.3 s. The
  slow fill seen in 00.02 did not recur, so no separate start threshold is
  needed for now.
- **Steady play:** fill 143-163 KB, no underruns, decode 2% CPU.
- **Station switching:** old session torn down in about 0.15 s.
- **M2 done.**

## M3: the globe (plan)

Split into two builds so the renderer change can be tested on its own:

1. **00.05: same app on vitaGL.** vitaGL and libvita2d can't both drive the
   GPU, so every screen moves to vitaGL first. There should be no visible
   change.
2. **00.07: the globe** (00.06 became the fix for 00.05's start-up crash). Textured sphere, place dots, fixed crosshair,
   rotate with left stick, d-pad or touch drag, zoom with L/R or pinch,
   about 0.8 s settle before a place starts playing, bottom panel.

**Globe controls (agreed):** left stick, d-pad or touch drag rotate; L/R or
pinch zoom; right stick left/right or the panel arrows step through
stations in a place; SELECT switches between globe and list; Cross plays
or reopens the place; Triangle favourite; Square stop; START menu. Player
details move to the START menu only.

## Build 00.05: renderer moved to vitaGL

- **Text** is drawn from our own glyph atlas instead of libvita2d's: one
  1024x1024 RGBA texture (4 MB) shared by every size and weight, filled on
  first use, with a 1 px gap between glyphs. Each (weight, size) pair has
  its own FreeType size object, so glyphs are still rendered at exactly the
  size drawn and copied 1:1. Same Noto Sans, same six sizes, same hinting
  as 00.04, so text should look the same.
- **One draw call per frame:** rectangles, circles and icon strokes sample
  a white block in the same texture as the glyphs, and everything is
  batched.
- **If the atlas fills up** (only likely with many different scripts on
  screen), it is cleared at the start of the next frame and refilled. Logged
  as `gfx: glyph atlas full`.
- **Memory:** vitaGL is started with 16 MB of RAM left outside its pools.
  The log prints the pool sizes at start.
- **Frame log:** every 10 s, `frame:` lines give frame count, average and
  worst frame time, CPU time spent drawing, and atlas fill. At 60 fps the
  average should be about 16.7 ms.
- libvita2d stays linked only for the "shader compiler missing" message.
- **Uncertain on hardware:** whether vitaGL's `glTexSubImage2D` into a
  texture the GPU is already drawing from in the same frame shows new
  glyphs correctly. Only new parts of the atlas are written mid-frame, so
  it should be safe, but if new text appears blank or garbled for one frame
  this is the cause. Also untested: first-frame stalls while vitaGL compiles
  its shaders (shows in the `worst` figure of the first `frame:` line).

## Build 00.06: fix for 00.05 crashing at start-up (C2-12828-1)

- **What the log and crash dump showed:** the log ended at
  `gfx: vglInitExtended failed`. In the dump, the main thread hit a data
  abort inside SceGxm, called from `vita2d_pgf_draw_text` in
  `gfx_fallback_message`. vitaGL's own threads (garbage collector, display
  queue, splash screen) were running.
- **Cause 1:** in this vitaGL build, the `vglInit*` return value is not a
  success flag. Disassembling `vglInitWithCustomSizes` in `libvitaGL.a`
  shows it returns GL_FALSE when the requested resolution fits the display
  (the normal case) and GL_TRUE when vitaGL had to shrink it. The header
  doesn't say so. 00.05 treated a good start as a failure. 00.06 ignores
  the value except to log a resize.
- **Cause 2:** the failure path then started libvita2d on a GPU vitaGL was
  already using, which crashes. 00.06 only uses the vita2d message screen
  for problems found *before* vitaGL starts (missing shader compiler,
  missing fonts). A failure after vitaGL starts is logged and the app quits.
- **How the dump was read:** the core is a gzipped ELF. A small script in
  `build/coredebug/` (git-ignored) pulls the thread registers, module
  list and stack out of its notes. The eboot was loaded 0x9000 above its
  link address, so stack words are shifted back by that before
  `arm-vita-eabi-addr2line`.
- **Other findings:**
  - vitaGL start-up took about 3.6 s.
  - vitaGL now shows its own splash screen at start-up. There is no
    documented switch to turn it off. It isn't Radio Garden artwork, so it
    is left as is for now.

## Build 00.06 on hardware

- Starts and looks the same as 00.04. No glitches, according to the tester.
- vitaGL start-up: 3.4 s. Its pools have 58 MB of RAM and 83 MB of video
  memory free.
- `frame:` lines: a steady 60 fps (16.7 ms average), with 0.8-1.5 ms of CPU
  per frame for drawing. The glyph atlas was 3-5% full.
- **The first frame took 1.5 s**: vitaGL compiles its shaders then.
- **Worst frames of 85-96 ms** appear in 10-second windows that contain a
  station switch, the keyboard or a new screen. Station switching does not
  block the main loop, so the likely cause is file writes on the main
  thread (`recent.txt` on every play) and rendering new glyphs. From 00.07,
  `recent.txt` is written 5 s after the last change rather than on every
  play.

## Build 00.07: the globe

- **Map:** `assets/map/world.png`, 4096x2048, drawn by `tools/make_map.py`
  from Natural Earth 1:50m land, lakes and country borders (public domain,
  release v5.1.2). The script uses its own scanline fill, because Pillow's
  polygon fill drew stray horizontal lines across the sea. The PNG is 400 KB
  and is loaded as an RGB565 texture: 16 MB of video memory, about 21 MB
  with mipmaps. The log gives the load time.
- **Drawing:** orthographic globe from a 64x128 mesh, projected on the CPU
  each frame and drawn through the same batch as the 2D UI. Only cells fully
  on the near side are drawn; a dark disc under the globe covers the thin
  rim this leaves at the edge. Shading darkens towards the edge. A soft
  ring outlines the globe.
- **Maths in `core/globe.c`** (unit-tested on Linux): projection, dragging,
  zoom limits, coasting, easing the short way across the date line,
  picking the nearest place within 26 px of the crosshair, and the dwell
  timer.
- **Behaviour:**
  - A place starts after 0.8 s under the crosshair. An amber arc around the
    crosshair shows the countdown.
  - Once the globe stops, it eases the nearest dot onto the crosshair.
  - Passing over places on the way doesn't start them.
  - The app opens on the globe, turned to the last place played, without
    playing anything. Cross plays it.
- **Choosing a station:** in a place, a favourite plays first, otherwise
  the first station by name. The right stick or the panel arrows (touch)
  step through the place's stations.
- **Controls on the globe:**
  - Rotate: left stick, d-pad, or a one-finger drag.
  - Zoom: L/R, or a two-finger pinch.
  - Station: right-stick flick left/right.
  - Cross: plays the place under the crosshair, or opens its stations in
    the list.
  - Triangle: favourites the playing station.
  - Square: stop.
  - START: menu.
  - SELECT: list.
  - In the list, SELECT returns to the globe. Player details are in the
    START menu.
  - Only the front touch panel is enabled.
- **Zoom range:** from the whole globe (radius 185 px) to about 14 degrees
  across the screen (radius 4000 px). At full zoom the map is magnified
  about 6x, so coastlines are soft.
- **Uncertain on hardware:**
  - Frame rate with the globe. The mesh is about 24,000 vertices a frame
    when zoomed out.
  - Whether vitaGL builds mipmaps for a 565 texture. If it can't, the log
    says so and the map may shimmer when zoomed out.
  - How the stick speed and touch-drag feel.
  - How long the map takes to load.

## Build 00.07 on hardware

- **Tester:**
  - No gaps or seams, and no problem at the date line.
  - Too sensitive when fully zoomed out.
  - Would like a middling zoom at start-up.
  - Coastlines blurry at full zoom.
- **Log:**
  - The map loaded in 2.4 s (mipmapped), on top of 3.4 s for vitaGL.
  - Steady 60 fps, but drawing took 9-15 ms of CPU per frame (1 ms in the
    list), because the mesh was projected on the CPU every frame.
  - Spikes of 90-130 ms around station changes.
  - Brussels (one station) restarted six times in ten seconds: stepping
    "to the next station" wrapped round to the same one.

## Build 00.08: globe on the GPU, sharp coastlines, fixes

- **Globe on the GPU:**
  - The sphere is a static vertex/index buffer. Each frame only a 4x4
    matrix changes (orthographic, unit sphere to screen pixels). Its
    output matches `core/globe.c`'s projection to 1e-11 px, checked
    numerically.
  - The far side is hidden with the depth test, not by skipping cells, so
    the edge is exact.
  - The edge darkening is now a 2D radial overlay with the same curve as
    before.
- **Coastlines and borders as lines:**
  - `tools/make_map.py` also writes `assets/map/lines.bin`: Natural Earth
    1:50m coastlines, lake shores and land borders, about 98,000
    segments, 790 KB.
  - Stored in 10-degree tiles. Only tiles near the view are drawn, with
    neighbouring tiles merged into one draw call.
  - Drawn as GL lines, lifted 0.08% above the surface. They fade in
    between radius 650 and 1250, so they stay sharp at full zoom where the
    texture is soft.
- **Start-up:**
  - The map PNG and lines are decoded on a separate thread, started
    before vitaGL, so the 2.4 s overlaps vitaGL's 3.4 s.
  - The log shows decode time, how long the main thread waited, and the
    upload time.
  - The app opens at radius 550 (about a continent across).
- **Stick speed** scales with zoom (35% at full zoom-out). Finger drag
  stays 1:1.
- **Logging** goes through a 32 KB buffer and a writer thread, so the main
  loop no longer waits on the memory card. If the buffer ever fills,
  dropped lines are counted in the log.
- **Bug fix:** stepping through stations in a one-station place shows
  "has only one station" instead of restarting it.
- **Uncertain on hardware:**
  - vitaGL's depth convention. If the far side of the globe shows through
    mirrored, the depth sign is the wrong way round.
  - Line throughput. With about 60,000 lines in view just past the fade-in
    zoom, check the `frame:` lines there.

## Build 00.09: no drift, auto-select

- **Tester on 00.08:** the globe kept moving after the left stick was
  released, which made it hard to stop on a dot. They asked for no drift,
  plus auto-selection of the nearest dot when the crosshair is close to
  one.
- **No glide** after the stick or d-pad is released. Only a finger fling
  on the touchscreen still coasts.
- **Auto-select:** 0.05 s after input stops, the nearest dot within 48 px
  of the crosshair is pulled onto it. This settles in about 0.25 s. The
  dwell timer still counts from when the dot is within 26 px, so it plays
  about 0.8 s after landing.

## Build 00.09 on hardware: M3 done

- **Tester:** the globe stops dead when the stick is released,
  auto-select feels good, and the globe looks right (the depth test hides
  the far side as intended).
- **Log:**
  - The globe was ready 5.0 s after launch (5.9 s in 00.07). The map
    decode overlapped vitaGL completely ("waited 0 ms"). Uploading the
    map, its mipmaps and the line data takes 1.3 s.
  - Drawing CPU 4.4-4.6 ms per frame (9-15 ms in 00.07), steady 60 fps.
  - 12 tunes across 9 places, no repeated restarts, no underruns.
- **Open:**
  - **Occasional 68-77 ms frames.** These are still there with the log
    writer off the main loop, and the worst windows contain HTTPS
    connects. The guess is that mbedTLS handshakes in the network thread
    share a core with the main loop. The next step is to pin the network
    and decoder threads to the other user cores and compare `frame:`
    lines.
  - Map upload time (1.3 s) could be trimmed later.
- **M3 done.**

## Build 00.10: Settings (button remapping) and core pinning

- **Remapping**, from START, then Settings:
  - Remappable buttons: Triangle, Square, L, R, START and SELECT.
  - Fixed: Cross (confirm), Circle (back), and the d-pad and sticks
    (movement and cursor).
  - Actions:
    - Both views: Favourite, Stop, Menu, Globe/list.
    - Globe: Zoom in/out, and Next/Previous station (unassigned by
      default; the right stick does these).
    - List: Next/Previous tab.
  - A button can mean different things in the globe and the list, but only
    one thing within a view.
  - Assigning a taken button swaps the two actions. If a swap would clash
    in the other view, it is refused with a message naming the actions
    using that button.
  - Menu can't be left without a button, so Settings stays reachable.
  - Cross while choosing a button sets None.
  - The logic is in `core/controls.c`, unit-tested on Linux.
- **Globe tuning** on the same screen: stick speed 50-200% and auto-select
  reach 0-96 px. Both apply straight away.
- **Saved** to `config.ini` (`[buttons]`, `[globe]`) when Settings closes.
  If the file has two actions on one button in a view, or Menu set to
  none, all buttons go back to the defaults and a message says so.
- **On-screen hints** (panels, tab bar, details) show the current buttons.
- **Player details:** the prebuffer now changes with d-pad Left/Right
  instead of L/R, so it doesn't depend on the mapping.
- **Core pinning:** the main loop is on core 0, the network thread on
  core 1 and the decoder on core 2. This tests whether the 70 ms frames
  came from TLS handshakes sharing the main loop's core.

## Build 00.11: About

- Settings has a new last row, **About DX Garden**. It shows:
  - App name, version and build revision.
  - A one-line description (own streams, no station directory).
  - Author: Aisling de Grás, aisling@hiraeth.club, Threads @hiraeth.clwb.
  - Credits for the map data, font and CA certificates.
- **Build revision:** `cmake/revision.cmake` runs on every build and
  writes `build/generated/revision.h` with the short git commit. It adds
  `-modified` when tracked files have uncommitted changes, so a build made
  from a clean commit shows just the hash.

## Decision: Radio Browser as a desktop snapshot (after 00.11)

- **Decision by the tester:** one directory is allowed, Radio Browser
  (radio-browser.info), used only as a snapshot. Specifically:
  - A desktop script, `tools/rb_to_stations.py`, writes a normal
    `stations.txt`.
  - The Vita app never contacts Radio Browser.
  - Nothing from it ships in the VPK.
  - Radio Garden's API and data stay off-limits.
  - Any other directory, or any live lookup from the Vita, needs asking
    first.
  - CLAUDE.md's "Station data" and "Helper tools" sections now say this.
- **Radio Browser's data licence (checked 2026-10-18):** no official
  source names a licence for the data.
  - The API documentation served by the servers (e.g.
    de1.api.radio-browser.info) says the service "can be used freely but
    without guarantee to work".
  - api.radio-browser.info calls the API "completely free and open
    source" and says you may "install it on your own server and mirror
    all its data".
  - The radiobrowser-api-rust README says "Open source" and "Freely
    licensed" for the code, without naming a data licence.
  - The website (a JavaScript app) contains no licence text.
  - "Public domain" appears only in secondary sources, such as the DOKK
    wiki entry, with no citation.
  - Conclusion: free to use and mirror per the project's own words. Public
    domain is likely but unconfirmed. Low risk here, because the snapshot
    stays on the user's PC and Vita. If it ever matters, ask the
    maintainers (GitLab: radiobrowser).
- **GeoNames licence:** CC BY 4.0, per the official
  download.geonames.org/export/dump/readme.txt ("This work is licensed
  under a Creative Commons Attribution 4.0 License"). Attribution is
  required. It is credited in the generated file header and the README.
  The cities file is downloaded by the user and never shipped.
- **How the script works:**
  - **Server discovery:** DNS lookup of all.api.radio-browser.info, a
    reverse lookup per address, a random order, and the next server on
    failure, as the API docs describe.
  - **User-Agent:** `dxgarden-tools/<VERSION>`.
  - **Search:** `/json/stations/search` with has_geo_info=true,
    hidebroken=true, ordered by click count, in pages of 500 with a 1 s
    pause.
  - **Skipped:** HLS, lastcheckok false, missing or (0,0) coordinates,
    unselected codecs, bitrate under the minimum (unknown bitrate counts
    as under it), unusable or duplicate URLs, and URLs that mention Radio
    Garden (5 of about 9,000 in a test run carried a
    `listening-from-radio-garden` parameter).
  - **Stream URL:** `url_resolved`.
  - **Cities:** from GeoNames cities15000.
    - District entries (PPLX) and historical or abandoned places are
      ignored.
    - Within 20 km the most populous city wins, so suburbs map to their
      city. Beyond that, the nearest city within `--city-km` (default
      30) wins. Otherwise the city is left blank.
    - The station's own country is preferred.
  - **Fields:**
    - Genre is the first one or two tags.
    - Notes are `CODEC BITRATEk rb:<uuid>`, with `?k` when the bitrate
      is unknown.
    - Every field is stripped of `|`, line breaks and control
      characters.
    - Names are cut to 100 characters at a word boundary.
    - A leading `#` is dropped, because the line would otherwise read as
      a comment.
    - Long official country names are shortened ("The United States Of
      America" becomes "United States").
  - `--check N` probes N random URLs (8 at a time, 10 s timeout) and
    lists the dead ones.
- **Tests:**
  - `tools/test_rb_to_stations.py`: offline unit tests.
  - `testdata/rb-sample-stations.txt`: 36 real MP3 stations, parsed and
    grouped in `dxgarden_test` with zero malformed lines.
  - `dxgarden_test FILE...` checks any generated file and prints
    timings.
- **Codec labels seen in a run of about 9,000:** MP3 5,689, AAC+ 1,182,
  AAC 1,032, OGG 97, no OPUS. Radio Browser seems to list Ogg Opus
  streams under OGG, so M4 should handle both inside Ogg.
- **No click reporting.** Radio Browser asks apps to call `/json/url/`
  for each play, to rank stations. The Vita doesn't contact Radio Browser,
  so this isn't done. It is a possible later addition only if live
  lookups are ever allowed.

## How many stations the Vita can handle (estimate, untested)

- **Measured on this PC** (optimised build, real Radio Browser data; the
  globe column is one frame of place maths, pick plus project):

  | Stations | Places | File size | Parse | Group | Globe maths |
  |---|---|---|---|---|---|
  | 500 | 270 | 95 KB | 1 ms | 2 ms | 0.03 ms |
  | 2,000 | 867 | 373 KB | 3 ms | 16 ms | 0.11 ms |
  | 8,000 | 2,829 | 1.5 MB | 14 ms | 146 ms | 0.33 ms |

- **Measured on the Vita:** only up to 17 stations and 14 places, so
  everything below about larger files is a guess.
- **Guessed Vita speed:** 20-40 times slower than this PC for this kind of
  code (333 MHz Cortex-A9).
  - At 2,000 stations: loading in well under a second, and 2-4 ms per
    frame of place maths on the globe. Probably comfortable.
  - At 8,000 stations: grouping 3-6 s at start-up and on Reload, and
    7-13 ms per frame of place maths. On top of drawing about 1,400
    visible dots (two discs each, through the CPU batch), that probably
    drops the globe below 60 fps.
- **The script's default `--max` is 2,000** until a big file has been
  tried on hardware.
- **Grouping grows faster than linearly.** Stations without a city are
  each compared with every place, and Radio Browser data leaves about 5%
  of stations without a city.
- **The 8 MB file limit** allows roughly 40,000 stations.
- **If big files are too slow,** fixes include:
  - Precomputed unit vectors and float dot products for places on the
    globe.
  - Cheaper dots.
  - A grid index for grouping.

## 00.11 on hardware with 2,000 Radio Browser stations

- 2,000 stations and 990 places loaded in 0.6 s (estimated: under 1 s).
- The globe held 60 fps zoomed out with every dot, at about 10 ms draw CPU
  (4.5 ms with 14 places).
- Quiet frames had a worst of 17.7 ms, so the core pinning in 00.10 worked.
  Frames around station changes still reach 78-104 ms.
- 40 stations played. One answered HTTP 401.
- The tester: "incredible". Cyrillic names showed correctly. Arabic, Chinese
  and Japanese were missing from the font.

## Build 00.12: text in more scripts

- **Fonts** (all SIL OFL 1.1, from the Noto project):
  - Noto Sans stays first, in regular and bold.
  - Fallbacks, regular weight only: Noto Sans Arabic, Hebrew, Thai,
    Georgian and Ethiopic, plus a subset of Noto Sans CJK SC.
  - Where bold is wanted in a fallback script, the glyph is thickened in
    software (`FT_GlyphSlot_Embolden`).
- **The CJK subset** (`tools/make_fonts.py`), 6.2 MB:
  - GB 2312 and Big5 level 1 Chinese, JIS X 0208 kanji, all kana, KS X
    1001 Hangul syllables, CJK punctuation and full-width forms. That is
    13,302 characters.
  - Shared characters use the Simplified Chinese style, so a few Japanese
    kanji look Chinese-style.
  - The VPK is now 9.9 MB, up from 3.7 MB.
- **Coverage, checked offline with fontTools:**
  - All 375 characters of the script test file are covered.
  - In 8,000 Radio Browser stations, 10 characters are missing, all
    decorative mathematical Fraktur letters in one name.
- **Shaping:** HarfBuzz 14.5 (MIT) and FriBidi 1.0.17 (LGPL 2.1), from
  vdpm.
  - Text with no right-to-left or shaped characters keeps the old
    per-character path, with a cache from character to font and glyph.
  - Text containing Arabic, Hebrew or Thai (or bidi control characters)
    goes through FriBidi for levels and HarfBuzz for shaping. It is split
    into runs by level and font, and the runs are put in screen order by
    `core/bidi_order.c` (rule L2, unit-tested). The result is cached, 128
    strings.
- **Emoji and invisible format characters** are skipped (no emoji font).
  Characters missing from every font show the regular font's box.
- **Glyph texture** doubled to 2048x1024 (8 MB of video memory), with
  8,192 cache entries.
- **Ellipsis cut points** are cached per string, size and width, so long
  shaped names don't get re-measured every frame.
- **Frame log** now shows shaping counts and cache hits.
- **Testing:**
  - `testdata/stations-scripts-test.txt` has 14 stations named in every
    new script, including right-to-left text mixed with digits and Latin.
    `dxgarden_test` checks it parses.
  - The shaping itself can only be checked on hardware: this PC has no
    HarfBuzz or FriBidi.
- **Big-list test file:** `build/stations-00.12-test.txt` has 7,488 MP3
  stations from Radio Browser (apparently every MP3 station with
  coordinates) plus the 14 script tests. That is 7,502 stations in 2,781
  places, 1.4 MB, 0 malformed. Expected on the Vita, a guess: 3-6 s to
  load, and the globe may drop below 60 fps zoomed out.

## Place names for Radio Browser stations (after 00.12, script only)

- **Tester on the big list:** "a lot of stations that are just coordinates
  for location". These were stations with no city within 30 km in
  cities15000, so the app named their places by coordinates. 437 of 7,488
  stations were affected, mostly in the USA (114) and Canada (63).
- **Fixes in `tools/rb_to_stations.py`:**
  - Use GeoNames cities1000 (161,752 towns over 1,000 people). Blanks
    fall to 102. The "biggest city within 20 km" rule still stops small
    towns taking over from cities.
  - If there's still no town, fall back to Radio Browser's `state`
    (region or province). It is skipped when it just repeats the country
    or contains digits. That fixes 65 of the 102.
  - The summary now gives the count for each source.
- **Result:** 37 of 7,488 stations are still named by coordinates on the
  Vita, and some of those join a named place within 25 km.
- No app change: 00.12 stays as it is.

## 00.12 on hardware with 7,488 stations, and build 00.13

- **Tester:** stutters over dense areas (western Europe, North America).
- **Log** (the tester loaded the 7,488-station MP3 file without the script
  tests, so shaping wasn't exercised):
  - **Fonts:** 4.0 s on the main thread for 8 files (8 MB).
  - **Library:** 7,488 stations and 2,769 places in 4.4 s, within the
    3-6 s estimate.
  - **Globe:** 23-32 ms of draw CPU per frame over dense areas, about
    25 fps.
  - About 12.6 s from launch to the globe.
- **Causes:**
  - Every frame, every place was projected with double-precision
    trigonometry three times: dots, the crosshair pick and auto-select.
  - Each visible dot was two 16-segment discs: about 96 vertices, or
    about 140,000 per frame over ~1,500 dots.
  - `group_for` found genres and countries by linear search, about 28
    million string comparisons for 2,147 genres.
  - `read_file` grew its buffer by doubling, and all fonts were read
    before vitaGL started.
- **00.13:**
  - **Globe:**
    - Place unit vectors are computed once per library. Each frame
      projects every place once with float dot products, and the picks
      reuse that.
    - Dots are one textured quad each (a 64x64 smooth disc with
      mipmaps): 12 vertices for dot and rim.
    - A dot landing in a 3 px cell already used is skipped. The playing
      and crosshair dots are always drawn last, on top.
  - **Library:** genre and country groups are found through a hash map.
    On this PC, grouping 7,488 stations went from 62 ms to 23 ms.
  - **Files:** `read_file` sizes the buffer from the file and reads it
    in one go.
  - **Fonts:** only Noto Sans regular and bold load at start-up. The six
    other fonts are read on a background thread and switched on by the
    frame loop when ready, after which the character and shaping caches
    are cleared.
- **Expected, unconfirmed:** the globe back near 60 fps over dense areas,
  library loading around 1.5-2 s, and fonts no longer delaying start-up.

## Build 00.14: calmer auto-select in crowded areas

- **Tester:** the stutter looks like the crosshair trying to lock onto
  stations in crowded areas.
- **Likely causes:**
  - Auto-select started 0.05 s after input stopped, so the stick briefly
    passing through its dead zone mid-movement could trigger a pull.
  - It re-chose the nearest dot every frame, so with several dots close
    together the target could change during the pull.
  - The pull was fast (rate 12).
- **00.14:**
  - Auto-select waits 0.2 s of no input.
  - It chooses its target once per pause and keeps it until it arrives.
  - Any input cancels it.
  - It pulls more gently (rate 8, about 0.4 s).
  - Focusing a place from elsewhere (list, station step) doesn't also
    trigger auto-select.
  - Includes everything in 00.13.

## M4 (trimmed): titles, playlists, errors, reconnects (build 00.15)

- **Decision by the tester:** no other audio formats. Radio Browser's MP3
  stations (about 71% of them, 7,488 with coordinates) are plenty.
  - AAC, Ogg/Vorbis and Opus decoding are dropped from M4. They are a
    possible later addition (faad2 or the hardware decoder for AAC).
  - Streams in those formats now get a clear message instead of a decoder
    error.
- **Titles (ICY metadata):**
  - Requests send `Icy-MetaData: 1`. When the server replies with
    `icy-metaint`, `core/icy.c` strips the metadata blocks out of the
    audio and reports StreamTitle changes. Titles can contain
    apostrophes, and Latin-1 is converted to UTF-8.
  - The title shows in the top bar's second line and in Player details,
    and is logged as `title:`.
  - Tested on Linux with every chunk split from 1 to 17 bytes.
- **What a response is** (`core/stream_info.c`), judged from the
  Content-Type, the URL and the first bytes:
  - MP3, or unclear: played.
  - A playlist (M3U, PLS or ASX, including plain URL lists served as
    octet-stream): read, and its first http(s) URL fetched, up to 3
    playlists deep.
  - HLS, other audio (AAC/Ogg/Opus/FLAC by type or by sync bytes), or a
    web page: stopped with a message, no retries.
- **Errors:**
  - HTTP codes get plain messages with the code, such as "Not found:
    the stream may have moved (HTTP 404)" or "Refused, possibly blocked
    in your country (HTTP 403)".
  - curl failures get messages too: DNS, couldn't connect, timeouts,
    dropped connections, invalid certificate, missing CA bundle, too many
    redirects, invalid address.
- **Reconnect:**
  - Retried: dropped connections, the server ending the stream,
    timeouts, DNS and connect failures, and HTTP 408, 429 and 5xx.
  - Not retried: other HTTP 4xx, invalid certificates, unsupported
    formats.
  - Back-off of 1, 2, 4 and 8 s, then 15 s, for up to 10 tries (about 2
    minutes), then an error saying why.
  - A connection that delivered 256 KB or more resets the count.
  - The buffer is not ended while reconnecting, so buffered audio plays
    on and the decoder waits for the new connection. The state shows
    "Reconnecting in N s (k of 10)" with the reason.
  - Each attempt goes back to the station's own address, so a playlist
    gets a fresh target.
- **Build fix:**
  - vita-elf-create failed with "segment 1 overlaps", because the code
    had grown to end 1,416 bytes before the 64 KB boundary where the data
    segment starts, and the tool needs about 4.3 KB there.
  - `cmake/vita-gap.ld` (an INSERT linker script) always leaves 8 KB.
- **Test file:** `testdata/stations-m4-test.txt` has seven "Test" stations
  along the equator in the Atlantic: titles, a playlist link, AAC, 404,
  a web page, HLS (France Inter) and a non-existent server.
  `build/stations-00.15-test.txt` is the big list plus the script and M4
  test files.
- **Uncertain on hardware:**
  - Whether mpg123 resyncs cleanly when a reconnect resumes mid-frame.
  - How titles from Shoutcast v1 ("ICY 200") servers come through.

## Build 00.16: fixes from the 00.15 test

The 00.15 log showed the test stations working: titles, the playlist link,
the AAC message, 404, HLS, and DNS retries with back-off. Fixes:

- **Web page at a .pls address:** this was reported as "The playlist has no
  stream address in it", because the `.pls` in the URL was trusted over
  `text/html`. Now an HTML reply (by type, or starting with `<!doctype
  html`/`<html`) is a web page unless its body really is a playlist.
- **Titles:**
  - HTML character references are decoded (`&#1040;` → А, `&#x..;`,
    `&amp;`, `&quot;`, `&apos;`, `&lt;`, `&gt;`, `&nbsp;`). Beu FM sent
    its Kazakh titles this way.
  - A UTF-8 byte order mark in a title is dropped (KGRR sent one).
  - Titles with no letters or digits ("-") are treated as no title.
- **Frame stats:** the first "worst" frame time (3.2 s) was the startup
  load counted as one frame. Timing now starts when the main loop does.
- **Seen, not changed:**
  - "Cahier de Vacance  la communication…" has two spaces where a
    character is missing. The server sent either two spaces or a control
    character; the log can't tell which.
  - TLS error -0x7200 ("invalid SSL record") on aircast.breaking-bass.ru:8443
    is most likely a server that doesn't speak TLS on that port. It is
    retried; the message is mbedTLS's own wording.
  - Some Radio Browser entries have wrong coordinates or countries (a
    Chinese station in the Atlantic, a Turkish one in Burkina Faso). That
    is the source data, not the app.
- **Still untested:** reconnecting after a real drop mid-stream (no stream
  dropped during the test), and mpg123's resync when that happens.

## M5 part 1: search, random place, sleep timer (build 00.17)

- **Plan for M5:** 00.17 is search, random place and the sleep timer;
  00.18 is screen off and standby/resume. HLS stays out unless asked for.
- **Search** (`core/search.c`, START → Search stations and places):
  - The query is folded like sort keys (case and common Latin accents
    ignored), split into words, and a name matches if it contains every
    word. Places match on "name country" ("portland united" narrows it),
    stations on their name only.
  - Ranked: name starts with the query, then a word in it starts with the
    first query word, then anywhere; alphabetical within each. At most 300
    places and 300 stations.
  - Results are one list below the current tab's root: places first, then
    stations (`ROW_MIXED`; places stored as -1 - index). Cross on a place
    turns the globe to it and plays it; Cross on a station plays it.
    Circle goes back to the tab. The last query is offered again.
  - Each search folds every place name again (about 2,900 small
    allocations). The log line `search:` gives the time; if it's slow on
    the Vita, the folded place text can be kept in the library instead.
  - Non-Latin scripts match exactly (no case folding beyond ASCII and
    Latin accents).
- **Random place:** START → Jump to a random place, or the new remappable
  "Random place" action on the globe (no button by default, because every
  remappable button already has a globe action). Never picks the place
  already playing. The globe turns there and it plays straight away.
- **Sleep timer:** START → Sleep timer. Cross steps Off → 15 → 30 → 60 →
  90 min → Off, with the menu kept open. The top bar shows "Sleep in N
  min" under the Wi-Fi state. The last 10 s fade out (volume squared), then
  playback stops. Once the player has stopped, the volume goes back to
  full, and starting a station also restores it. After the stop, the Vita's
  normal auto-standby applies again.
  - Volume is set per audio port with `sceAudioOutSetVolume`, applied by
    the decode thread before each 1024-sample grain (about 23 ms).
- **Layout:** the menu rows are 42 px (was 48) to fit up to 10 entries;
  Settings rows are 29 px (was 30) for the extra button row.

## M5 part 2: screen off, standby and resume (build 00.18)

- **What's possible on the Vita (investigated):**
  - `scePowerRequestDisplayOff()` turns the display off while the app keeps
    running, so audio continues. That is "Turn the screen off" in the
    START menu. Drawing stops while it's off; the loop polls buttons every
    30 ms instead. Any button or front-touch wakes it, and the waking press
    is swallowed.
  - Real standby (power button) and the PS button both suspend the app, so
    audio stops. Playing in the background like the Music app would need
    system privileges that ordinary homebrew doesn't have. Not attempted.
  - The Vita's own idle screen-off still happens while playing (we only
    block auto-standby with `SCE_KERNEL_POWER_TICK_DISABLE_AUTO_SUSPEND`).
- **Resume:** found two ways, whichever comes first, at most once per 5 s:
  - a power callback (`SCE_POWER_CB_SYSTEM_RESUME` or `APP_RESUME`; both
    flags are marked "to confirm" in the SDK headers), and
  - the wall clock (RTC) running more than 3 s ahead of process time, which
    stops during standby.
  - Then, if a station was on (not stopped, not in error), it is started
    again from its address, because its connection is dead. Wi-Fi may take
    a few seconds to come back; the reconnect back-off covers that.
  - The sleep timer runs on process time, so it pauses during standby.
- **Logging:**
  - `power: event 0x…` for every callback, `power: resumed (found by …,
    clock gap N s)`, `screen: off/on` with the battery level, and the
    battery level and screen state on each 10 s `stats:` line, so battery
    use with the screen off can be compared.
  - `slow frame: N ms (input M ms) in <screen>` for any frame over 250 ms.
  - `ime: open` and `ime: confirmed`.
  - The previous run's log is kept as `log-<build>-previous.txt` (00.17's
    search was tested in an earlier run, and its log line was overwritten).
- **Uncertain on hardware:**
  - Whether buttons are read with the display off (if not, only the power
    button or PS button would bring it back).
  - Whether the system turns the display back on by itself (we'd then
    draw nothing until a button is pressed).
  - Whether Wi-Fi stays connected with the display off.
  - Which power callback flags actually arrive on resume.

## Build 00.19: screen-off fix

- **00.18 bug:** "Turn the screen off" turned straight back on, 30 ms
  later. With the display off the system takes the controls and sets
  `SCE_CTRL_INTERCEPTED` (0x10000) in the button word; the app counted that
  bit as a button press. Input now only looks at the real buttons, so
  status bits (intercepted, headphones, volume, power) can't act as
  presses anywhere.
- Since the system may hold the controls while the display is off, the app
  also wakes when the controls come back (intercepted bit clears), not
  only on a press it sees itself.
- **Seen in the 00.18 log:**
  - Display off sends power event 0x00400080 (APP_SUSPEND plus 0x80);
    display on sends 0x00800080 (APP_RESUMING plus 0x80). 0x80 is set in
    every event, including one at start-up. Neither is treated as a
    resume, which is right: the stream was fine.
  - Open question: does APP_SUSPEND on display off mean the system will
    actually suspend the app after a while? The 10 s `stats:` lines with
    "screen off" will show whether audio data keeps arriving.
  - The first drawn frame takes about 1.2 s (`slow frame: 1228 ms (input
    6 ms) in globe`), in drawing, not input. It is start-up only; most
    likely vitaGL setting up shaders or textures on first use.

## Build 00.20: dark screen instead of display off

- **00.19 on hardware:** with the display off (`scePowerRequestDisplayOff`)
  the audio faded to silence within moments, ordinary buttons did nothing,
  and only PS or power brought it back (with the audio). The system treats
  display-off as suspending the app (power event APP_SUSPEND), fades its
  audio and keeps the controls. Ordinary homebrew can't opt out. The 00.19
  logs didn't reach the PC.
- **So "Dark screen" replaces "Turn the screen off":**
  - Three black frames (one per buffer in the swap chain), then no more
    drawing; the display keeps showing black. The loop polls input every
    30 ms. Any real button or a front touch brings the screen back, and
    that press is swallowed.
  - On the PCH-1000's OLED, black pixels are unlit, so most of the
    display's power is saved. Lowering brightness as well was considered
    and dropped: `sceAVConfigSetDisplayBrightness` exists, but the current
    level can't be read back (the registry API is kernel-only), so it
    couldn't be restored; on an all-black OLED screen it would make
    little difference anyway.
  - Unsure: the battery saved compared with drawing the globe. Compare the
    battery % on the `stats:` lines.
- **System screen-off blocked while playing:** the Vita's own idle
  display-off would silence the app the same way, so while a stream is open
  the loop also ticks `SCE_KERNEL_POWER_TICK_DISABLE_OLED_OFF`. Dimming is
  still allowed. When playback stops (including by the sleep timer), the
  usual screen-off and standby come back.
- **Standby and PS-button resume:** still untested (no 00.19 log).

## Build 00.21: bundled station list, with positions checked

- **Decision by the tester (2026-10-21):** ship a Radio Browser snapshot
  in the VPK as the default list. This changes the earlier rule (no list
  in the VPK); CLAUDE.md, the README and the About screen now say so.
  - Licences: Radio Browser's API docs say it "is completely free and open
    source" and that "You may use it in free and non free software" and
    "mirror all its data"; no formal data licence is named. GeoNames town
    names are CC BY 4.0 (credited in the file header, README and About).
    Natural Earth is public domain.
  - `assets/stations/stations.txt` goes into the VPK as `app0:stations.txt`.
    On first run, if `ux0:data/dxgarden/stations.txt` doesn't exist, it is
    written as the format notes and examples followed by the bundled list.
    An existing file is never touched, so testers keep theirs; delete it to
    get the bundled one.
  - The header says which release it shipped with, the date, and that
    changes are the user's to make.
- **The list:** a fresh snapshot (2026-10-21), MP3 only, 7,538 stations in
  2,806 places, 162 countries. The test stations from the M4/fonts test
  files aren't in it (they were only ever in my combined test file), and
  test broadcasts (the word "test" in the name) are now skipped.
- **Positions** (`rb_to_stations.py --borders`): a station is fine if it is
  inside its country's Natural Earth shape (or a dependency of the same
  state, like the Isle of Man for GB), or within 30 km of one of its towns.
  Otherwise, in order:
  1. swapped or sign-flipped coordinates that land near one of its towns
     (8 stations);
  2. out at sea within 200 km of its coast: the nearest town ashore (18);
  3. within 300 km of its own country: left exactly as given (border
     towns, and disputed areas, where relabelling would take a side);
  4. a town named in the station's name: 50,000+ people in its country,
     200,000+ abroad, so "Forest" and "Roman" aren't read as towns (7);
  5. the pin on a 40,000+ city in another country: kept, and the country
     label corrected to that country, unless the name mentions the stated
     country (14; e.g. DK/DZ/NE for Germany, BQ for the Netherlands);
  6. Radio Browser's region as a town name (8); 7. the capital (33).
  Each change is explained in the station's Notes.
- **Known misses:** "Radio Caprice - Chicago Blues" (a Russian genre
  channel) is placed in Chicago; "057 radio" (Zadar) is labelled Gabon,
  where its pin is; a few Berlin-pinned stations keep Radio Browser's
  "Denmark" label (step 3). Stations pinned at the middle of their country
  (central Australia, Kazakhstan) stay there.
- **Script changes:** retries each server (2, 4, 8 s) before moving on,
  since one dropped connection ended a run; `--save-raw` / `--from-raw`
  rebuild offline from a saved download.
- **Country labels by territory (tester's decision, 2026-10-21):** "I am
  taking a political side." Stations whose position is in Ukraine,
  including Crimea and the occupied east and south, are labelled Ukraine;
  stations in Palestinian territory are labelled Gaza or Occupied West
  Bank. This overrides Radio Browser and the earlier border-area rule.
  - Decided by the nearest GeoNames town within 15 km (GeoNames has
    Crimean towns as UA and East Jerusalem neighbourhoods as PS), else by
    the Natural Earth shape, which puts Crimea in Russia, so there is an
    explicit Crimea box. Gaza and the West Bank are told apart by longitude
    (34.6° E).
  - In this snapshot: 4 relabelled to Ukraine (Simferopol, Melitopol,
    Mariupol, Henichesk) and 2 to Occupied West Bank (Battir, and KAN
    Gimel, an Israeli public station whose pin is at Tulkarm). No stations
    are pinned in Gaza.
  - Left as Israel: Radios 100FM (pinned near Kfar Saba; the coarse 1:50m
    shape puts it just inside the West Bank, GeoNames doesn't) and
    Mizrahit FM (no real position: placed at GeoNames' Jerusalem point
    from Radio Browser's region).
- **UK nations (tester's decision, 2026-10-21):** UK stations are labelled
  England, Scotland, Wales or Northern Ireland, from the nearest GeoNames
  town's admin1 code (ENG/SCT/WLS/NIR) within 15 km, or the nearest UK town
  within 60 km for remote pins. The Crown Dependencies aren't in the UK
  and get their own names: Isle of Man, Jersey, Guernsey. In this
  snapshot: England 277, Scotland 17, Wales 13, Northern Ireland 1, Isle of
  Man 3, Jersey 2, Guernsey 1.
  - Still "United Kingdom": UK Pressure Radio, whose Radio Browser pin is
    in Cherbourg, France (kept as given by the border-area rule).
  - Positions come from Radio Browser, so a wrong pin gives a wrong nation:
    "Hits Hull & East York" is pinned in Bethesda (Wales), "Radio X" in
    Pwllheli (Wales).

## Build 00.22: from the 00.21 test

- **00.21 on hardware:** the bundled list was copied on first run (1,419
  KB), loaded in 1.5 s (7,538 stations, 2,805 places, no errors), and the
  relabelled places played as expected.
- **First real mid-stream drop:** KAN Gimel trickled, then stopped. The
  buffer ran dry at 320 s, the 20 s no-data timeout fired at 342 s, and
  the second attempt was playing at 365 s, with no decoder errors (so
  mpg123 resyncs fine). About 45 s of silence in all, since a trickle
  doesn't trip the timeout; a low-speed limit would cut that (not done,
  proposed).
- **Wrapped URLs:** two stations' addresses were email link-scanner
  wrappers (Proofpoint urldefense, Outlook safelinks), which send every
  connection through the scanner first. `rb_to_stations.py` now unwraps
  them; the 00.22 list is rebuilt from the same 2026-10-21 snapshot.

## Build 00.23: faster reconnect on a stalled stream

- The network thread now treats a stream as dropped when under 1 KB/s
  arrives for 10 s (curl low-speed limit), instead of after 20 s of no
  data at all. A full buffer still reads at the stream's bitrate (16 kbps
  at the slowest in the list, 2 KB/s), so healthy streams don't trip it.
  The 00.21 stall would have reconnected roughly 25 s sooner.
- Uncertain on hardware: a station sending under 8 kbps would now
  reconnect every 10 s. None in the list does, but the Notes bitrate is
  unknown ("?k") for 809 stations.
- The bundled list's header now says 00.23 (same snapshot as 00.22).

## Build 00.24: placeholder LiveArea art

- The old LiveArea art is replaced by labelled placeholders for the tester
  to draw over (`art/livearea/`, made by `tools/make_livearea.py
  placeholders`): icon0 128x128, bg 840x500, startup 280x158, each with its
  size, purpose, a coordinate grid, a 5% margin guide and, for the icon,
  the circle the bubble crops to. They are installed too, so the build
  shows where each image lands.
- `make_livearea.py install [DIR]` checks sizes, flattens transparency onto
  black, reduces to 256 colours (dithered) and writes 8-bit palette PNGs
  into `sce_sys/` (the Vita refuses smaller bit depths: VitaShell error
  0x8010113D).
- Left out: the optional `sce_sys/pic0.png`, since where the system shows
  it isn't confirmed.
- Unsure on hardware: the Vita may keep showing the old bubble and LiveArea
  after an update install (cached); deleting the app first, or VitaShell's
  "Refresh LiveArea", should show the new ones.
- The helper tool for your own lists is planned in
  `docs/station-helper-plan.md` (recommended: a standard-library Python
  desktop app with tkinter).

## Build 00.25: globe LiveArea art

- The placeholders are replaced by the globe art from the cloud session's
  commit b2bc9e7 (kept on the `claude/tender-ramanujan-bfnswa` branch):
  an orthographic globe drawn from the app's own Natural Earth map
  (`assets/map/`, public domain), amber place dots, the crosshair and
  Noto Sans titles (OFL). No outside artwork.
- Only the three finished images were taken, copied into `art/livearea/`
  and installed with `make_livearea.py install`; they were already 8-bit
  palette PNGs, so the conversion doesn't change them. The cloud version of
  the script that drew them (needs numpy) is not merged; ours keeps the
  `placeholders` and `install` commands.
- The placeholders can be made again with `make_livearea.py placeholders`.
- Unsure on hardware: the gate's position over `bg.png` was assumed
  (centred), so the focused place (Istanbul) may sit under the gate; check
  on the Vita. The old bubble may stay cached after an update install
  (delete the app first, or VitaShell's "Refresh LiveArea").

## Build 00.26: no text on the LiveArea background

- **00.25 on hardware:** the bubble is fine, but on the LiveArea the gate
  (`startup.png`) sat over the background's title and tagline. The tagline
  ("Radio from your own station list") was out of date anyway now that a
  list ships with the app.
- The background is now just the globe, stars and the Istanbul ring; the
  gate carries the name.
- The cloud session's renderer is now in the repo as
  `tools/livearea_globe.py` (needs numpy), used by `make_livearea.py
  render`, which writes `art/livearea/` and installs. Its palette
  handling (reserved entries for amber and text) is now in `install` too.
- The gate's exact position is still not measured. If more needs to move,
  a photo of the LiveArea against the placeholder grid
  (`make_livearea.py placeholders`) would pin it down.

## Build 00.27: Swansea, and the gate's name moved up

- **00.26 on hardware:** much better, but the "DX Garden" name on the gate
  sat close to the Start button the Vita draws over the bottom of it. The
  name and its underline are now 28 px higher (underline at y 94-96 of
  158).
- **Focus moved to Swansea** (51.62 N, 3.94 W), where the tester lives, in
  all three images: under the crosshair on the bubble and gate, and under
  the rings on the background. The background globe is turned (view
  centre 26.5 S, 27.25 W, solved numerically) so Swansea lands where
  Istanbul was, right of the gate on the horizon; the North Atlantic now
  fills the horizon.

## Build 00.28: Swansea on the left of the gate

- **00.27 on hardware:** it worked, but with Swansea right of the gate,
  everything left of it on the horizon was Atlantic.
- The background is mirrored in layout: the globe's centre is at x 370
  (was 470) and the view (26.5 S, 19.25 E, solved numerically) puts
  Swansea at about (176, 364), left of the gate. The horizon to its right
  is now Europe, the Mediterranean, the Black Sea and the Middle East. The
  rings span about x 85-265, clear of a centred 280 px gate.

## Versions: 0.9.0 (build 29) onwards

- **Decision by the tester (2026-10-22):** a release version plus a build
  number, replacing the single 00.NN that went up every build. The first
  release is **0.9.0, a public beta**.
  - `VERSION` holds MAJOR.MINOR.PATCH and changes only for a new release.
    `BUILD` goes up by one for every build handed over (29 follows 00.28).
  - CMake checks both, derives the Vita's XX.YY (`param.sfo` APP_VER only
    takes that form: 0.9.0 -> 00.90, 1.0.1 -> 01.01, 1.2.3 -> 01.23, so
    minor and patch stay 0-9), and names the VPK
    `dxgarden-<version>-b<build>.vpk`, or `dxgarden-<version>.vpk` with
    `-DDXG_RELEASE=ON`.
  - The app shows "0.9.0 (build 29)" in Player details and About (with the
    git commit), logs it at start, and names the log
    `log-0.9.0-b29.txt`. The network user agent says `DXGarden/0.9.0`.
  - Catch: test builds of one version share the Vita's XX.YY, so the
    system's app information can't tell them apart; About can.
  - Releases are tagged `v0.9.0` in git.
- The bundled list's header now says "Correct as of" the date the Radio
  Browser snapshot was taken (the saved file's time with `--from-raw`),
  not the day the file was rebuilt.

## Release 0.9.0 (beta), 2026-10-22

- Build 30 from commit 173fb6e (same code as tested build 29; README
  changes only), built with `-DDXG_RELEASE=ON` in `build-release/`.
  SHA-256 of `dxgarden-0.9.0.vpk`:
  1844dbe86c0b5d695b61cb8ee53799af4a7b7b2a0105131941f86d1c4a955c21.
- Merged to `main` through pull request #1 with a merge commit (so 173fb6e,
  shown in About, stays in history), tagged `v0.9.0`, and published as a
  regular GitHub release marked Latest (a pre-release doesn't show on the
  repository's main page), titled "(beta)", with the VPK attached:
  https://github.com/hiraethsystems/DX-Garden/releases/tag/v0.9.0
- Next build numbers continue from 31. `VERSION` stays 0.9.0 until the next
  release is decided (0.9.1 for fixes, 1.0.0 when the beta is done).

## Windows port, W1: foundation and one stream (build 31, 2026-10-30)

Branch `windows-port`. The desktop app lives in `desktop/` beside `vita/`,
sharing `core/` unchanged. It is a Windows 11 app, but the same code builds
on Linux, which is the development build: it can be run and screenshotted
here before a Windows build goes out.

**Toolchain.** Windows builds are cross-compiled on Linux with MinGW-w64
(the `-posix` compilers), via `cmake/mingw-w64.cmake`. The MinGW runtime is
linked statically, so `dxgarden.exe` imports only DLLs that come with
Windows. Wine runs the Windows builds here for checks.

**Libraries** (`tools/build_win_deps.sh`, official source releases, SHA-256
pinned on first download, static, into `third_party/`, which git ignores):

| Library | Version | Notes |
|---|---|---|
| SDL3 | 3.4.18 | Window, input, threads, audio (WASAPI on Windows). Built for Linux too, so both builds use the same SDL. |
| libcurl | 8.23.0 | Schannel TLS: Windows' own certificate store, so no `cacert.pem` to ship or refresh. Revocation checks are off (`CURLSSLOPT_NO_REVOKE`), as they fail behind some proxies. |
| mpg123 | 1.33.7 | |
| FreeType, HarfBuzz, FriBidi | 2.15.0, 15.0.0, 1.0.18 | Built now, used from W2. |

On Linux the development build takes curl and mpg123 from the distribution.

**What changed from the Vita player** (`desktop/player.c`, a port of
`vita/player.c`): Sony threads, mutexes and condition variables became SDL
ones; the decode thread feeds an SDL audio stream on the default device
instead of a SceAudio BGM port. SDL resamples, so a stream changing rate
only changes the input format. The decoder keeps at most 250 ms queued at
the device, so the stream buffer (not the device queue) holds the slack, as
on the Vita. The network side, playlist following, ICY titles, error
messages and reconnects are unchanged.

**Data folder:** `%APPDATA%\DX Garden\` (Linux: `~/.local/share/DX Garden/`).
W1 writes only the log there, `log-<version>-b<build>.txt` and
`...-previous.txt`, as on the Vita.

**Checked here** (Linux build, and the Windows build under Wine 10):

- `core/` tests: 4,538 checks, 0 failures, on both.
- MP3 over HTTPS (Radio Camarthenshire), a PLS playlist and plain HTTP
  (SomaFM Groove Salad): connect in 1-2 s, titles shown, decoding under 1%
  of real time.
- An AAC stream gets the Vita's message, "This station streams AAC; DX
  Garden only plays MP3" (AAC arrives in W5).

**Headless check:** `dxgarden --play URL --seconds N` plays without a
window, prints the status each second and exits 0 if it played.

**Known issues**

- `core/search.c:93` gives a `-Walloc-size-larger-than` warning with MinGW
  GCC 14 at -O2. It is a false positive (`n` can't be negative there), and
  `core/` is shared with the Vita, so it is left alone for now.
- The .exe isn't code-signed, so Windows SmartScreen warns the first time
  it is run ("More info", then "Run anyway"). Signing is for W8.

## Decisions for the Windows globe (2026-10-30, for W4 and W5)

Made by Aisling, before any of it is built:

- **Imagery:** NASA Blue Marble (day) and Black Marble (night lights), both
  public domain, ship with the app, so the globe works fully offline.
  Optional online layers, off by default and cached on disk: Sentinel-2
  cloudless (EOX, 10 m per pixel; licence terms to be checked before use)
  and NASA GIBS daily imagery (today's clouds). These are the only live
  lookups for imagery. Google, Bing and Esri imagery are ruled out by their
  terms.
- **Satellites:** orbit data (TLEs) for a short list (ISS, Tiangong,
  Hubble) is fetched from CelesTrak at most once a day and cached. Stale
  data is labelled with its age; with none, satellites are hidden.
- **Download size:** about 300 MB with the imagery at 1 km per pixel, plus
  an optional 500 m detail pack that unpacks into the data folder.
- Sun, Moon, planets, stars and the grey line are computed offline.

**Test builds:** each one goes to GitHub as a pre-release (tag
`win-b<build>`) with the zip attached, for testing on a Windows PC.

## Windows port, W2: the Windows 9x interface (build 32, 2026-10-30)

**How it is drawn.** OpenGL 3.3 (`desktop/gl.c` loads it through SDL).
The interface is drawn at "logical" pixel size into an offscreen buffer
and scaled up by a whole number with no smoothing, so 1-pixel bevels stay
exactly 1 pixel at 100%, 200% or 300% (View, Interface Size; Automatic
picks from Windows' display scale: 125% stays 1x, 175% and up is 2x).
Glyphs, icons and solid fills share one 2048x2048 atlas, so a frame is a
few draw calls. The globe (W4) will draw at full resolution underneath.

**Toolkit** (`desktop/ui.c`, `ui_widgets.c`): windows and menus are kept
between frames; controls are immediate-mode, called from each window's
draw function. It has: the main window's own 9x frame and title bar (no
system frame; hit testing tells Windows where to drag and resize, so snap
still works), a menu bar with access keys (Alt, F10, Alt+letter, arrows),
submenus, check and radio items, a toolbar, child windows inside a
workspace (move, resize, minimise, maximise, cascade, tile, z-order,
modal dialogs), buttons (default, focus, disabled), check boxes, radio
buttons, group boxes, edit boxes (caret, selection, clipboard, IME),
drop-down lists, progress bars, sliders, scroll bars with auto-repeat,
tabs, a status bar with size grip, tooltips, message boxes, a virtual
list view (sortable, resizable columns) and a tree view with dotted lines.
Tab moves the focus between controls.

**Look.** The standard 9x colour scheme (grey 3D faces, navy selection,
navy-to-blue title gradient, grey workspace). The art is our own: every
icon is pixel art made for DX Garden in `desktop/icons.c`, from character
grids or drawn in code. No Microsoft artwork, fonts or sounds.

**Font.** Liberation Sans 11 px (8 point at 96 dpi), drawn without
smoothing as 9x drew screen fonts; View, Smooth Screen Fonts turns
smoothing on. Liberation Sans 2.1.5 (OFL 1.1, Arial's metrics) was the
closest match among free fonts in a side-by-side test against Noto Sans;
Noto stays as the fallback for everything else (Greek, Cyrillic and the
other scripts the Vita app supports). Both ship in `fonts/` with their
licences.

**What W2 shows.** "Now Playing" (the W1 player: a test stream list, an
address box, Play, Stop, volume) and a "Control Gallery" with every
control, including a list and tree of the bundled 7,538 stations
(double-click a station to play it). Settings are saved in `config.ini` in
the data folder.

**Checked here** with a new `--script` option that feeds clicks and keys
and takes screenshots: menus, submenus, tabs, list and tree keyboard
navigation, message boxes, minimise and maximise, font smoothing, at 100%
and 200%, on the Linux build and the Windows build under Wine.

**Known issues**

- Dragging, snapping and resizing the main window couldn't be checked
  here (the test display has no window manager). Needs a check on Windows.
- No system menu yet (the title bar icon, Alt+Space).
- The interface redraws every frame at the display's refresh rate. Cheap
  on any GPU, but W7 should let it idle when nothing changes.
- The gallery's station list sorts names with leading punctuation first
  ("'tis Vloms", "(((EBM-Radio)))"); W3 will use core's folded sort keys.

## Build 33: in-between interface sizes (2026-10-30)

From the build 32 test: 100% was too small and 200% too big. View,
Interface Size now has 100, 125, 150, 175, 200, 250 and 300%, plus
Ctrl+Plus, Ctrl+Minus and Ctrl+0 (back to Automatic). Automatic now
follows Windows' display scale to the nearest step (build 32 rounded 150%
down to 100%). Whole-number sizes stay pixel-exact; the others use "sharp
bilinear" scaling (enlarge by the next whole number without filtering,
then shrink to size with filtering), so every line keeps the same width
and edges are only slightly soft. `config.ini` now stores the size in
percent; build 32's 1-3 values are read as 100-300.

## Windows port, W3: the station library (build 34, 2026-10-30)

Same data as the Vita app, in `%APPDATA%\DX Garden\`: `stations.txt`
(created from the bundled list on first run, never overwritten),
`import\` for M3U/PLS files, `favourites.txt`, `recent.txt`, `config.ini`.
Loading goes through core's `station_source` interface and
`library_build()`, unchanged; `desktop/data.c` and `desktop/fs.c` are ports
of the Vita ones with paths set at run time.

**Stations window** (Explorer style): a tree on the left (Favourites,
Recently Played, search results, Countries with their towns, Genres,
Unplaced) and a sortable list of stations on the right (Name, Town,
Country, Genre, Notes), with a draggable divider. Double-click or Enter
plays; right-click (or the Menu key) gives Play, Add to/Remove from
Favourites, Copy Stream Address, Place This Station and Properties. Find
(Ctrl+F) uses core's search over station, town and country names. Empty
lists say why they are empty.

**Commands** (menus Station and File, and shortcuts): Favourite (Ctrl+D),
Properties (Alt+Enter), Place This Station, Random Place (Ctrl+R, picks a
place, opens it in the tree and plays one of its stations), Add Station by
Address (writes `import\added-by-url.m3u`, as on the Vita), Reload Station
Files (F5), Open Data Folder, and View, Load Report. Placing an imported
station appends it to `stations.txt` and opens its town in the tree.
Recently played is saved 5 s after a change, as on the Vita.

**Now Playing** shows the station's town (click it to show the town in
Stations), a favourite star, the title, buffer, volume and an address box
for playing any stream.

**UTF-8 file names.** Core uses `fopen()`, which on Windows takes the
ANSI code page, so a data folder under a user name with an accent (Grás)
would fail to open. `desktop/dxgarden.manifest` sets the process code page
to UTF-8 (Windows 10 1903 and later), which fixes every file call at once;
checked under Wine with a folder named `Grás`. The manifest also declares
per-monitor DPI awareness.

**Country names in the bundled list** (also affects the Vita app's list):
Radio Browser strips punctuation from some official names, so
`tools/rb_to_stations.py`'s table missed them. Added: "Coted Ivoire" to
Côte d'Ivoire, "Democratic Peoples Republic Of Korea" to North Korea,
"Dutch Part Sint Maarten" to Sint Maarten, "Falkland Islands Malvinas" to
"Falkland Islands (Malvinas)" (the ISO form, restored), "Reunion" to
Réunion, and "and", "of", "the" in lower case inside names (Trinidad and
Tobago). The same mapping was applied to `assets/stations/stations.txt`
(54 lines; nothing else in the file changed). Existing `stations.txt`
files are the user's and aren't touched.

**Checked here** with scripted sessions on a fresh data folder (Linux, and
the Windows build under Wine): first run, search, favourites, the
right-click menu, Properties, the Load Report with a broken line, an M3U
import placed through Place Station (the line written to `stations.txt`
was checked), Random Place and Add Station.

**Known issues**

- The Control Gallery moved to Help, Control Gallery.
- Dialog labels have no access keys yet (Alt+letter to jump to a field).
- Favourites can't be reordered in the app yet (the file can be edited).
- Sleep timer and dark screen (Vita M5) are planned for W7.

## Windows port, W4: the globe (build 35, 2026-10-30)

**Imagery.** NASA Blue Marble: Next Generation, July 2004, with topography
and bathymetry (Reto Stöckli, NASA Earth Observatory; public domain).
`tools/make_earth.py` downloads the eight 500 m pieces (433 MB, SHA-256
pinned, cached in `third_party/earth/`) and cuts a pyramid of 512 px JPEG
tiles in plate carrée layout, levels 0-5: the whole Earth on two tiles up
to 32768 x 16384 px, about 1.2 km per pixel at the equator. The tiles go
in one file, `earth-day.pack` (2,730 tiles, 71 MB), with an index at the
end, so Windows doesn't have to unzip and scan thousands of small files.
The pack isn't in git; CMake copies it next to the program when it exists.
That's well under the 300 MB planned, so a 500 m "detail pack" (level 6)
can come later without making the download large.

**Renderer** (`desktop/earth.c`): a perspective camera straight above a
point, at an altitude from 8 km to 60,000 km. Each frame a quadtree picks
the tiles that are on screen (inside the horizon and the view's corners)
and whose texels would be at most ~1.2 px; each is drawn as a curved grid
(32 segments for big tiles, 16 for small) whose vertices are placed on the
sphere in the vertex shader, camera-relative so floats stay precise near
the ground. Two loader threads read and decode tiles (stb_image, public
domain, vendored as `desktop/stb_image.h` at nothings/stb 003501f) and up
to 8 are uploaded per frame, with mipmaps and anisotropic filtering; 384
are kept on the GPU, least recently drawn first out, level 0 always kept.
A tile still loading is drawn from its nearest loaded ancestor, so the
view sharpens rather than having holes. The atmosphere is a glow from each
pixel's closest approach to the Earth, plus haze towards the edge of the
disc. Place dots are smooth (drawn at full resolution with a distance
function), slightly bigger for busier places.

**How it fits the interface.** The globe draws at the window's full
resolution, not the interface's logical pixels, so it stays sharp at 150%
or 200%: the interface punches a transparent hole for it (`draw_underlay`)
and is composited over it with premultiplied alpha. Windows, menus, the
crosshair and the text over the globe sit on top as normal.

**Globe window** (`desktop/globe_view.c`): drag to turn (the point under
the mouse stays under it), with coasting; wheel or Page Up/Down to zoom;
double-click to fly to a point; arrow keys pan; Home shows the whole
Earth. The Vita's crosshair rule is kept, using core's dwell timer: a place
under the crosshair for 0.8 s starts playing (an amber ring shows the
wait), Enter plays it at once, and clicking a dot turns to it and plays
it. View, Play Place Under Crosshair turns the automatic part off. The
panel underneath shows the place and its country, "station n of N" with
arrows to step through them, and zoom buttons. Playing a station from the
Stations window or Random Place flies the globe there. The camera is saved
in `config.ini` ([globe]).

**Checked here** (Linux build, and the Windows build under Wine): zoomed
from the whole Earth (with the atmosphere) down to 750 km over Wales;
clicking Manchester's dot flew there and played Bolton FM; dragging and
coasting; Random Place flying to Chiang Mai.

**Known issues**

- No day/night, grey line, stars, Moon or satellites yet: that's W5.
- The 1.2 km imagery gets soft below about 100 km altitude; the 500 m
  detail pack and the optional online layers will help there.
- Software rendering here is slow, so real frame rates are unknown until
  tested on Windows with a real GPU.

## Build 36: flicker fix, globe fills the window (2026-10-30)

From the build 35 test.

**Flicker while moving the globe.** New imagery tiles were uploaded while
the interface was being drawn. Uploading binds the tile's texture, and
interface drawing still waiting in the batch then went to the GPU with the
tile bound instead of the interface's atlas, so whole windows came out
black or tile-coloured for a frame. It showed in any window drawn after
the globe (any window clicked since start). Reproduced here: with build 35
code and the Stations window raised, frames during a zoom showed the
window black and smeared; with the fix, the window's pixels are identical
across those frames. Two changes: uploads happen in the globe's own pass
(`earth_update()` in the underlay), and the interface binds its atlas on
every batch, so nothing else can disturb it. The OS window title is also
only set when it changes.

**Layout.** The globe now fills the workspace with no frame, like a desktop
background (a new `UIW_BACKGROUND` window kind: kept behind the others,
frameless when maximised), with Now Playing and Stations floating over its
right side. View, Globe Fills Window turns it back into an ordinary window
on the left; the choice is saved.

## Linux port, L1: a proper native Linux build (build 37, 2026-11-11)

The Linux port starts from the Windows port (`linux-port` branch, from
`windows-port`). The desktop app was already SDL3 code that built on Linux
as a development build; L1 makes that build a real Linux app. The plan is
for Linux to replace the Windows build eventually; until then both build
from the same code, and Windows behaves as before.

**Title bar.** On Linux the desktop draws the title bar and border
(`DXG_NATIVE_FRAME` in `desktop/app.h`): the window isn't borderless, there
is no hit testing, and the menu bar starts at the top of the window. The
window title is still "Station - DX Garden". The 9x frame stays on Windows.
KDE Plasma draws the title bar itself (server-side decorations, checked in
a Wayland trace: KWin answers mode 2). GNOME on Wayland leaves title bars
to the app, so SDL3 is built with libdecor, which draws one there.

**KDE Plasma.** Checked on Plasma 6.3.6 (Wayland, screens at 160% and
100%, NVIDIA). SDL 3.4 runs the app through XWayland here by default
(SDL picks X11 on compositors without the newer frame-pacing protocols);
`SDL_VIDEO_DRIVER=wayland` runs it natively, and both work:
- The app ID / X11 window class is `club.hiraeth.dxgarden`, the same as the
  menu entry's file name and `StartupWMClass`, so Plasma's task manager
  groups the window under the menu entry and its icon.
- Under X11 window sizes are in pixels, so on the 160% screen the window
  opened at 1024 x 720 pixels, under two thirds of the intended size, and
  the Stations window came out squashed. The starting size is now scaled
  by the screen's scale (within 90% of the usable area). The interface
  scale itself was already right in both (150%, the nearest step to 160%).
- X11 title bars and Alt+Tab want the icon on the window, so the app sets
  it (`share/club.hiraeth.dxgarden/icon.png`, loaded with SDL 3.4's PNG loader).
- The first layout gives Stations at least its minimum height; in a short
  window Now Playing shrinks first.

**Folders.** Data moved from `~/.local/share/DX Garden/` to
`$XDG_DATA_HOME/dxgarden/` (normally `~/.local/share/dxgarden/`), matching
the internal name. On start, an old `DX Garden` folder is renamed to the
new name if the new one doesn't exist yet, so settings, favourites and
station files carry over. The app's own files (fonts, bundled
`stations.txt`, `earth-day.pack`) are found next to the program when
`fonts/` is there (the build folder), otherwise in `../share/club.hiraeth.dxgarden/`
from it: an installed copy, and later the AppImage. The first try used
`share/dxgarden/`, but with `--prefix ~/.local` that is the data folder
itself, so the install put the bundled list where the user's
`stations.txt` goes and a reinstall would have overwritten it. Found on
the first real install, before the app had run; the folder is now named
after the app ID so the two can't meet.

**Install.** `cmake --install build-linux --prefix ~/.local` puts in
`bin/dxgarden`, `share/club.hiraeth.dxgarden/`, a menu entry and a 128 px icon, both
named after the app ID SDL gives the desktop (`club.hiraeth.dxgarden`), so
the window and its menu entry match. The installed menu entry runs the
program by its full path (written at install time): Plasma on Debian
doesn't have `~/.local/bin` on its PATH, and the first real install failed
to launch with "Could not find the program 'dxgarden'". The icon is the Vita bubble
(`art/livearea/icon0.png`) cropped to a circle, in `desktop/linux/`.

**Libraries.** SDL3 3.4.18 is still built from source
(`tools/build_win_deps.sh linux`), so Linux and Windows use the same SDL;
Debian 13 only has 3.2.10. curl, mpg123, FreeType, HarfBuzz and FriBidi
come from the distribution's `-dev` packages. The AppImage (L3) will bundle
them.

**Checked here** (Debian 13, KDE Plasma 6.3.6 on Wayland, NVIDIA RTX
4060): the build and the 4538 core checks pass; an install into a test
prefix starts with assets from `share/club.hiraeth.dxgarden/`; an old `DX Garden` folder
with a favourite in it was moved and the favourite loaded; screenshots
under X11 (the default) and native Wayland show the menu bar at the top, no
9x title bar, and the same layout at 150%; the X11 window has its class and
icon; Deutschlandfunk (HTTPS MP3) played through PipeWire.

**Not checked:** the title bar itself (screenshots only show the window's
contents), the menu entry in Plasma's launcher after a real install, GNOME
(no GNOME machine here), and the Windows build (its libraries aren't built
on this machine yet). The interface still has the 9x look; that's L2.

**Known issues**

- `config.ini` gets a second `[ui]` header when a key is added to a section
  after another section was written (the file reads back correctly). In
  `core/config.c`; harmless, to tidy later.

## Linux port, L2a: a choice of looks (build 38, 2026-11-11)

From the build 37 test: the 9x look stays, and becomes one of three
(View, Look; `config.ini` `[ui] look = classic|system|breeze|adwaita`):

- **Classic**, the default: the Windows 9x look, unchanged. A screenshot of
  the main window is pixel-identical to build 37's.
- **System**: Breeze on KDE Plasma (`XDG_CURRENT_DESKTOP` contains KDE),
  Adwaita elsewhere on Linux.
- **Breeze (KDE)** and **Adwaita (GNOME)**, to pick one directly.

Windows has only Classic, so the Look menu isn't there.

**How.** The toolkit's fixed 9x colours became a palette (`desktop/theme.c`)
that the `UI_*` names now read from, so every window, list and panel
follows the look without changes of its own. The bevel styles, title bars,
menus and each control have a flat path: one-pixel frames (corner pixels
left out, which reads as slightly rounded), solid title bars, tinted hover,
the accent colour for focus, ticks, sliders and progress, round radio
buttons and slider handles, row selection across the whole row, and tree
arrows instead of dotted lines and boxes. Disabled text is plain grey
instead of embossed. The crosshair and captions over the globe stay black
and white in every look.

**Breeze's colours** come from the KDE colour scheme in use, read from
`~/.config/kdeglobals` over Plasma 6's defaults layer
(`~/.config/kdedefaults/kdeglobals`), first file wins, as KConfig does:
window, view, button, selection, tooltip and title bar colours, and the
focus and hover decoration colours (the accent). Keys a scheme leaves out
come from Breeze or Breeze Dark, whichever is nearer. The scheme is
re-read within a second of either file changing, so switching colour
schemes in System Settings changes the app while it runs.

**Adwaita's colours** are libadwaita's, light or dark as the system says
(SDL reads the desktop portal's colour-scheme setting), re-checked every
second. GNOME's accent colour setting isn't read yet: the accent is
Adwaita's blue.

**Checked here** (KDE Plasma 6.3.6, Wayland, Sweet-Ambar-Blue): Classic
main window identical to build 37; Breeze picks up Sweet-Ambar-Blue's
colours and name; a test kdeglobals with Breeze Light colours gives a
light Breeze; Adwaita dark; the Control Gallery's controls and ranges
tabs, the main window, the menus, in each flat look; core checks pass.

**Not checked:** Adwaita light (this desktop's system setting is dark),
GNOME itself, and changing the KDE scheme while the app runs.

**Known issues**

- The colour icons (toolbar, folders, title bar icons) are the 9x art.
  Some have dark outlines that are hard to see on dark schemes (the
  magnifier, the town, the gear). New icons would be a separate piece.
- Flat looks still use the 9x sizes and font (Liberation Sans 11 px,
  16 px rows), and the interface is still drawn small and scaled up, so at
  150% it is slightly soft. That's L2b: drawing at the screen's resolution
  and Breeze-like spacing.

## Linux port, L3: the AppImage (build 39, 2026-11-11)

`tools/make_appimage.sh` builds `dist/dxgarden-<version>-b<build>-x86_64.AppImage`
(`--release`: without the build number), about 81 MB, almost all of it the
globe's imagery. One file: make it executable and run it.

**Static libraries.** Debian's libcurl pulls in about 40 libraries
(Kerberos, LDAP, RTMP, GnuTLS, libidn2 and more) and has Debian's
certificate path built in, so copying it into the image would be heavy and
would break HTTPS on Fedora. Instead `tools/build_win_deps.sh linux-static`
builds, into `third_party/linux-static/`, the same versions the Windows
build uses: curl 8.23.0 (HTTP and HTTPS only), mpg123 1.33.7, FreeType
2.15.0, HarfBuzz 15.0.0 and FriBidi 1.0.18, plus mbedTLS 3.6.7 (the LTS
branch; the Vita build also uses mbedTLS) for curl's TLS. With
`-DDXG_STATIC_DEPS=ON` the program links these and the C++ runtime
statically, and needs only `libc` and `libm`; SDL loads the desktop's
graphics, sound and input libraries itself when it starts. The development
build keeps using the distribution's packages.

**Certificates.** The static curl has no certificate path built in. At
start the player looks for the system's bundle: `$SSL_CERT_FILE`, then
curl's own path if it has one (a distribution's curl), then the usual
places (Debian/Ubuntu/Arch, Fedora/RHEL, openSUSE, Alpine), and as a last
resort `/etc/ssl/certs/`. The log says which.

**glibc.** Built on Debian 13, the program needs glibc 2.38 or later: Ubuntu
24.04+, Fedora 39+, Debian 13+, current Arch and openSUSE Tumbleweed, but
not Ubuntu 22.04, Debian 12 or Mint 21 (my decision, 2026-11-11: build on
this machine rather than in an older container). The newer symbols come
from glibc 2.38's headers (C23 `strtol`/`sscanf`, `strlcpy`, `fmod`), in
our code and the libraries alike, so lowering the floor means building on
an older system.

**Tools.** appimagetool 1.9.1 and the AppImage runtime (type2-runtime
20251108) are downloaded once into `third_party/appimage/`, at those fixed
versions, and checked against the SHA-256 sums GitHub publishes for them.
appimagetool runs extracted, so packing needs no FUSE. The image's menu
entry runs `dxgarden` from inside the image; its files are in
`usr/share/club.hiraeth.dxgarden/`, found the same way as an installed
copy's.

**Checked here** (Debian 13, KDE Plasma 6.3.6): the static program links
only `libc` and `libm`; the AppImage plays Deutschlandfunk over HTTPS with
its own curl and mbedTLS (certificates from Debian's bundle); with an empty
`SSL_CERT_FILE` HTTPS is refused ("Can't check secure servers: no
certificate store"), so verification is on; run with a bare environment, as
a launcher starts it, it found its files inside the image, opened the
window with OpenGL on the NVIDIA driver, and loaded the globe imagery.

**Not checked:** other distributions (no containers or VMs here), so the
certificate paths for Fedora and openSUSE are untested; a machine without
FUSE (the image needs `fusermount3` to mount itself; otherwise run it with
`--appimage-extract-and-run`).

## Release 0.9.1 (beta), 2026-11-22

- **Decision by the tester (2026-11-22):** release the Linux desktop app as
  0.9.1 (minor is already 9, so a patch number is the only step before
  1.0.0), AppImage only. The Vita app is unchanged since 0.9.0 (`core/` and
  `vita/` are identical), so no new VPK; the README points Vita users at
  `dxgarden-0.9.0.vpk`. The Windows build stays in the code but isn't
  shipped (its libraries aren't built on this machine).
- Build 40: build 39 plus the version bump and README changes (an
  "Installing on Linux" section with menu-entry steps for the AppImage).
  Built with `tools/make_appimage.sh --release`.
- `linux-port` (which includes the Windows port commits, builds 31 to 36)
  goes to `main` through a pull request, then `v0.9.1` is tagged on `main`
  and published as Latest with the AppImage attached.
- The bundled station list is the same 2026-10-21 Radio Browser snapshot
  as 0.9.0, with 54 country labels spelled properly (for example Bosnia
  and Herzegovina, Côte d'Ivoire, North Korea, Réunion, Trinidad and
  Tobago); no stations added or removed.
- **Published 2026-11-22.** Pull request #4 merged into `main` with a merge
  commit (7c24817), tagged `v0.9.1` there, and the AppImage rebuilt from
  that commit with `--release`. Published as a regular release marked
  Latest, with the AppImage attached:
  https://github.com/hiraethsystems/DX-Garden/releases/tag/v0.9.1
  SHA-256 of `dxgarden-0.9.1-x86_64.AppImage`:
  64bb144621e81b379eef83dc13cba36de46acdaff28b43263eda7a65dd87637f
  (checked against the downloaded file).
- Next build numbers continue from 41. `VERSION` stays 0.9.1 until the
  next release is decided.

## Linux formats, F1: AAC, Ogg Vorbis, Opus, FLAC (build 41, 2026-11-22)

- **Decision by the tester (2026-11-22):** add every format we can to the
  **Linux version only**, so the station list and globe can cover all of
  Radio Browser; the Vita stays at 0.9.0 (MP3 only) for now. Then
  artist/title and station website links, a fuller list, and later an
  in-app list download (see "Plan" below). HLS was asked for explicitly.
- **Libraries** (static in the AppImage, built by `tools/build_win_deps.sh
  linux-decoders`, SHA-256 checked; Xiph's sums are the ones Xiph
  publishes, faad2's is of GitHub's tag archive): faad2 2.11.4 (GPL 2 or
  later, compatible with our GPL 3; AAC-LC, HE-AAC, HE-AAC v2, ADTS and
  LOAS/LATM), libogg 1.3.6, libvorbis 1.3.7, opus 1.6.1, opusfile 0.12
  (built without its own HTTP, which would need OpenSSL), libFLAC 1.5.0
  (native and Ogg FLAC). Not used: fdk-aac (licence not GPL-compatible),
  FFmpeg (far more than needed). The development build uses Debian's
  `libfaad-dev libvorbis-dev libopusfile-dev libflac-dev`.
- **`desktop/decode.c`:** one runner per format on the decode thread,
  pulling bytes through a blocking read from the ring buffer and handing
  out 16-bit stereo (mono doubled, more channels mixed to the front pair;
  faad2 mixes 5.1 itself). MP3 moved there unchanged.
- **Choosing the decoder:** the decode thread reads the first 16 KB (the
  prebuffer is bigger) and `audio_sniff()` (`core/stream_info.c`, unit
  tested) names the format from the bytes: Ogg, FLAC, or MPEG audio, ADTS
  or LOAS frames, counted only when two frames in a row line up, after any
  ID3 tag, anywhere in the first 8 KB (streams can join mid-frame). Only
  when the bytes aren't clear does the Content-Type decide. So AAC served
  as `audio/mpeg` plays. Inside Ogg, the first page says Vorbis, Opus or
  FLAC (`ogg_sniff()`). WebM and WAV are still refused with a message.
- **Track titles from the stream:** Ogg and FLAC streams carry
  ARTIST/TITLE comments (Icecast starts a new chained link per song);
  these become the Now Playing title, as ICY titles do.
- **Prebuffer scaled to the bitrate:** the setting is in bytes, sized for
  128 kbps (64 KB, 4 s). With faster streams it was under a second (FLAC
  at 1,000 kbps: 0.5 s), and a FLAC test had an underrun at 8 s. Once the
  decoder knows the bitrate, the prebuffer grows in proportion (up to half
  the ring), ignoring changes under 10%. FLAC's bitrate is measured from
  the bytes read.
- **Now Playing** shows the format: "HE-AAC, 64 kbps", or "Opus, 48000 Hz"
  until a bitrate is known.

**Checked here** (`--play`, 12-20 s each, no underruns after the prebuffer
change): Radio Paradise AAC 320, France Info AAC 48 kHz, Süper FM and Fox
News Radio HE-AAC, SomaFM Groove Salad HE-AAC 32 kbps, Deutschlandfunk Opus
mono (with ICY metadata inside the Ogg stream), WALM Opus, Nightwave Plaza
(chained Opus, titles change per song), Energy 90s Ogg Vorbis (titles from
comments), Pure Lounge Ogg FLAC 24 bit, Rondo Classic FLAC. Decode load
0-2%. NRJ refused (HTTP 403, probably geo-blocking).

**Not checked:** an HE-AAC v2 stream that says so (SomaFM's 32 kbps
stream reports v1), AAC actually served as `audio/mpeg` (none found yet;
the sniffer tests cover it), switching between formats in the window.

**Plan (Linux):** F2 HLS; F3 artist and title on separate lines, station
websites (`web:` in Notes, `icy-url`); F4 a fuller bundled list for the
desktop app only (the Vita keeps its MP3 list); F5 downloading a new list
from Radio Browser inside the app, with a dialog for formats and filters
(needs decisions first: it changes the "never fetches a directory"
promise for Linux, needs GeoNames data, and the script's rules in C).

**Known issue:** the Windows build no longer links, because
`build_win_deps.sh win64` doesn't build the new decoders. Fixing it means
adding them to the win64 target; left until we decide what happens to
the Windows port. *(Resolved in build 44: the Windows port was removed.)*

## Linux formats, F2: HLS (build 42, 2026-11-22)

- **Survey first** (the 60 most-clicked HLS stations in Radio Browser):
  most segments are MPEG-TS with AAC (BBC, Radio France, RTL, RNE, CCTV),
  then "packed" AAC or MP3 segments that start with one or two ID3 tags
  (Turkish, Korean and Taiwanese stations), one fragmented MP4 (NHK World
  in Japanese), one encrypted-key line with METHOD=NONE, and a handful of
  dead or geo-blocked ones.
- **`core/hls.c`:** playlist parsing (master and media playlists,
  EXT-X-MEDIA audio renditions, EXT-X-MAP, EXT-X-KEY, ENDLIST) and URL
  resolution (RFC 3986 references, "." and ".."). **`core/media_demux.c`:**
  audio out of segments: TS (PAT, PMT, the first ADTS/LOAS/MPEG audio
  stream, PES headers removed; AC-3 refused with a message), packed audio
  (ID3 tags skipped), fMP4 (the moov's AudioSpecificConfig, then each
  fragment's samples with an ADTS header added; for HE-AAC the core AAC-LC
  config goes in the header and faad2 finds SBR/PS in the stream). 66 new
  unit tests, including synthetic TS and MP4.
- **The network thread** recognises an HLS playlist (by `#EXT-X-` in it)
  and runs `hls_play()`: from a master playlist it takes the best
  audio-only version up to 320 kbps (or the separate audio rendition of a
  video stream, or the lowest version if all are video); then it reloads
  the media playlist and feeds each new segment's audio into the same
  buffer the decoders read, so everything after that (format sniffing,
  decoders, titles, reconnecting) is shared with ordinary streams. A live
  stream starts at least three segments and 20 s behind the live edge, for
  a cushion. Missing segments (404/410) are skipped; three failed playlist
  reloads in a row count as a drop and the usual reconnect takes over.
  Encrypted streams (EXT-X-KEY other than NONE) are refused with a message.
- Now Playing shows "(HLS)" after the format, e.g. "HE-AAC v2 (HLS)".

**Checked here:** 19 of 20 popular HLS stations played for 25 s without
underruns: BBC Radio 1, 4 and 6 Music, FIP, RTL (HE-AAC v2), RTL 102.5
(two ID3 tags per segment), RNE, CCTV-13, NHK World (fMP4) and NHK World
Radio, Air Vividh Bharti, Aaj Tak (a video stream: its audio only), KRAL
FM, Power POP, powerturk, Radyo Turkuvaz, TRT Türkü, Sinar FM, and an MP3
HLS stream (天籁之音). KBS refused (HTTP 403, geo-blocked). RTL, NHK World
and Radyo Turkuvaz ran for 2 minutes with steady playlist reloads. The
AppImage plays HLS too.

**Not done:** titles carried in HLS ID3 tags (F3), AES-128 encrypted
streams, AC-3 audio, Opus or FLAC inside fMP4, choosing a lower version
when the network is slow (it always takes the best up to 320 kbps).

## Linux now playing, F3: artist, song and website (build 43, 2026-11-22)

- **`core/track_info.c`:** `title_split()` splits a stream title at the
  first " - " (or a spaced en or em dash) when there is text on both
  sides; otherwise the whole thing is the song. `id3_track()` reads TIT2
  and TPE1 from ID3v2.2/2.3/2.4 tags (Latin-1, UTF-16 with or without a
  byte order mark, UTF-8). `notes_web()` and `notes_display()` handle the
  new `web:<url>` word in a station's Notes. Unit tests for all of them.
- **HLS titles:** the TS demuxer now also collects timed ID3 metadata
  (stream type 0x15) and packed-audio segments hand over their leading ID3
  tag; both go through `id3_track()`.
- **Now Playing:** the song (bold) on one line, the artist (grey) below it,
  and a "Website" link on the right of the artist line. The link opens in
  the desktop's browser (SDL_OpenURL). It comes from `web:` in the
  station's Notes, otherwise from the stream's `icy-url` header (http and
  https only). Station, Open Website does the same for the selected
  station. Station Properties shows a Website line; the Notes column and
  the Properties notes hide the `web:` and `rb:` words.
- **`tools/rb_to_stations.py`** adds `web:<homepage>` to the Notes when
  Radio Browser has a usable http(s) homepage (no "|", control characters
  or over 200 characters; spaces become %20). A homepage pointing at Radio
  Garden skips the station, as a stream URL does. The Vita ignores Notes,
  so its lists are unaffected; the bundled Vita list was not rebuilt.
- The headless player (`--play`) prints the artist, song and station site
  at the end.

**Checked here:** artist/song split and icy-url on SomaFM Groove Salad
(MP3), Nightwave Plaza (Opus tags), Radio Paradise (AAC); unit tests and
script tests pass. The build 43 AppImage plays and shows titles.

**HLS titles in practice:** of 19 popular HLS stations, only RTL (France)
sends standard TIT2/TPE1 frames, and they show up (presenter and show,
e.g. "Isabelle Langé" / "On refait le sport"). Most others carry only the
Apple timestamp (PRIV); RTL 102.5 puts JSON song data in a TEXT
(lyricist) frame, which is station-specific and not read. The other 17
still played; 天籁之音's server was not answering at all (curl timed out
too).

**Not checked:** the Now Playing layout and the Website link in the GUI
(clicking opens the browser), long titles at large interface sizes.

## Build 44: two apps, Windows removed, the Linux station list (F4), 2026-11-22

**Decisions (owner, 2026-11-22):**
- The Vita and Linux apps are separate products in one repo, sharing
  `core/`. Each has its own `VERSION` and `BUILD` (`vita/`, `linux/`), its
  own releases (tags `vita-vX.Y.Z`, `linux-vX.Y.Z`; the earlier `v0.9.0` is
  the Vita's and `v0.9.1` Linux's) and its own bundled list. A full split
  into two repos was rejected because `core/` would be copied and drift.
- The Windows port is removed (the owner doesn't use it).
- The Linux app may download Radio Browser, GeoNames and Natural Earth data
  when the user asks (for F5); the Vita never goes online for directory
  data. Recorded in CLAUDE.md and the README.
- No limits on the Linux list (no cap, no minimum bitrate, every format,
  HLS included). A map filter may come later if the globe gets cluttered.

**The split:**
- `desktop/` is now `linux/`, `desktop/linux/` is `linux/share/`,
  `cmake/desktop.cmake` is `cmake/linux.cmake` (option `DXG_LINUX`, target
  `dxgarden_linux`), `tools/build_win_deps.sh` is `tools/build_linux_deps.sh`.
- `VERSION`/`BUILD` moved: Linux 0.9.1, build 44; Vita 0.9.0, build 43
  (unchanged; the next Vita build is 44).
- README: status, installing, controls, versions and building are now per
  app. CLAUDE.md has a "Two apps, one repo" section.

**Windows removed:** `cmake/mingw-w64.cmake`, `dxgarden.rc`,
`dxgarden.manifest`, the win64 target of the deps script, every `_WIN32`
branch, the hand-drawn 9x title bar and its hit testing (Linux always used
the desktop's own frame), and the Windows-only Look menu removal. The
About box said "for Windows" on Linux too; it now says "for Linux". The
Classic look (Windows 9x style) stays.

**About box:** "By Aisling de Grás" and aisling@hiraeth.systems, which opens
the mail program (mailto:).

**F4, the Linux station list** (`assets/stations/linux/stations.txt`):
- Radio Browser snapshot taken 2026-11-22: 11,105 stations with
  coordinates (the API's `has_geo_info`); 11,063 written (2.4 MB): MP3
  7,221, AAC 1,312, AAC+ 1,562, Ogg 109, FLAC 3, HLS 856. Towns from
  GeoNames cities1000, positions checked against Natural Earth 1:50m
  countries (v5.1.2). 10,920 stations have a website (`web:`).
- Rebuild offline (the raw snapshot, GeoNames and Natural Earth files are
  in `third_party/rb/` and `third_party/geodata/`, not in git):
  `python3 tools/rb_to_stations.py --from-raw third_party/rb/rb_raw_2026-11-22.json
  --geonames third_party/geodata/cities1000.zip --borders
  third_party/geodata/ne_50m_admin_0_countries.geojson --codecs all --hls
  --max 100000 --max-mb 32 --bundle "for Linux X.Y.Z" -o assets/stations/linux/stations.txt`
- Script: `flac` codec, `--codecs all`, `--hls` (any codec; noted as e.g.
  "AAC/HLS", or "HLS" when Radio Browser doesn't know), `--max-mb`, and no
  `web:` when the "homepage" is just the stream URL.
- The Linux build reads stations files up to 32 MB (`STATION_FILE_MAX`,
  overridable; the Vita keeps 8 MB).
- Loads in 28 ms here: 3,757 places, 183 countries, no errors.
- Country labels: 356 England, 21 Scotland, 14 Wales, 1 Northern Ireland,
  3 Isle of Man, 2 Jersey, 1 Guernsey, 44 Ukraine, 2 Occupied West Bank,
  1 Gaza. **Known miss:** "UK Pressure Radio" is labelled United Kingdom:
  its pin is in the sea off Cherbourg, so no nation can be told from it.
- An existing `~/.local/share/dxgarden/stations.txt` is never replaced:
  rename or delete it to get the new list.

**Checked here:** unit tests (4,650) and script tests (24) pass; a fresh
data folder gets the new list; 4 of 5 random HLS/AAC+ stations from it
played (the fifth needs a login, HTTP 401); the build 44 AppImage plays
and contains the new list.

**Tested by the owner (desktop, build 44 AppImage):** looks good: the new
list loaded (11,063 stations, 3,757 places), the globe with the full list,
and Now Playing with song, artist and the Website link (РадиоКанск, Kansk).

**Not checked:** the About box layout.
The Vita build could not be compiled on this machine: the VitaSDK at
/usr/local/vitasdk has no vdpm packages (curl, libpng, vita2d, FreeType
are missing), so this is unrelated to the split; the Vita's own
VERSION/BUILD are picked up correctly and its core files compile.

## Linux station list download, F5 (build 45, 2026-11-22)

**Decision (owner, 2026-11-22):** the Linux app downloads GeoNames and
Natural Earth itself (not only Radio Browser), so a list it makes is as
good as the bundled one. Libraries approved: zlib (to unzip
`cities1000.zip`) and our own JSON reader. Nothing runs unless the user
asks; the Vita is unchanged.

**F5a, the list builder in C** (`core/`, Linux and tests only; not in
the Vita build):
- `rb_build.c` is a port of `tools/rb_to_stations.py`'s conversion:
  filters, places, position checks and fixes, country labels (nations of
  the UK, Crown Dependencies, Ukraine, Gaza, Occupied West Bank), notes,
  the header and the summary. `json.c` reads JSON, `zip_read.c` takes one
  file out of a zip with zlib, `ustr.c` gives the Python string behaviour
  the port needs (lower case, accent folding, stripping), with tables from
  the Unicode 15.1 database made by `tools/make_unicode_tables.py`
  (`unitab.c`, about 300 KB of source).
- The C output is byte-for-byte the script's: `tests/compare_listtool.sh`
  runs both on the same raw snapshot in 5 configurations (all formats,
  the Vita's MP3 list, no borders, no towns, 300 stations), and they
  match. `dxgarden_listtool` is the C side of that comparison. The full
  list takes 1.1 s in C.
- One deliberate difference: `rb_query()` leaves out the codec filter
  when HLS is included, since HLS stations can be any codec (the script
  asks Radio Browser per codec in that case). The conversion is the same.

**F5b, the download** (`linux/list_download.c`, `linux/list_dialog.c`):
- File, Download Station List: formats (all ticked, HLS too), countries,
  tag, lowest bitrate, most stations (100,000 by default, so no real cap),
  town distance; the cache's date and "Download the town names and
  borders again".
- A worker thread: Radio Browser's servers from a DNS lookup of
  `all.api.radio-browser.info` and each address's name (the documented
  way), in random order; pages of 500, one a second; each server tried 4
  times (waiting 2, 4, 8 s) before the next. GeoNames' `cities1000.zip`
  and Natural Earth 5.1.2's `ne_50m_admin_0_countries.geojson` are kept in
  `~/.local/share/dxgarden/cache/`. The result goes to
  `stations-new.txt`; a summary shows; **Replace My Station List** renames
  `stations.txt` to `stations-backup-<date>.txt` (`-2`, `-3`... if taken),
  moves the new one in and reloads. Cancel works at any point; closing
  the window cancels too.
- `player_tls_setup()` (player.c) gives the download the same
  certificate search the player uses, which the AppImage's static curl
  needs.
- `dxgarden --download-list [--codecs ...] [--hls] [--countries ...]
  [--max N] [--refresh]` does the same without a window.

**Bug found and fixed (script and C):** when a station's position wins
over its country ("country corrected to match the position"), the new
country's name came only from the stations in the snapshot. A download for
some countries only has no stations from most others, so the label stayed
wrong: EWTN Great Britain & Ireland, pinned in Birmingham, Alabama, stayed
"United Kingdom" in a GB/DE/FR download. Now the name falls back to the
borders file's ADMIN name (tidied the same way, so "United States of
America" becomes "United States"). Tests added on both sides. Rebuilding
the bundled Linux list from the same snapshot changed one line: "057
radio" (a station in Zadar, Croatia whose Radio Browser pin is in
Libreville) was labelled Ghana and is now Gabon. Its pin is still wrong;
that's Radio Browser's data. The bundled list's header now says build 45.

**Checked here:** unit tests (4,723) and script tests (24) pass; the
script and C match on 6 configurations (the 5 above and a GB/DE/FR-only
snapshot). A real download of everything (all formats, HLS) took 37 s:
11,110 stations, 11,068 written, in line with the bundled 11,063. A
download for Ireland and Iceland, then one for GB/DE/FR, through the
dialog by script: progress, summary, Replace (backups
`stations-backup-2026-11-22.txt` and `-2.txt`), reload (2,102 stations,
482 places), Cancel and Back. The build 45 AppImage downloads over HTTPS
with its own curl.

**Notes:**
- Only one Radio Browser server answered the name lookup here
  (de1.api.radio-browser.info), so moving on to the next server after
  failures isn't tested against a real second server.
- The progress bar is a rough guide while pages come in (Radio Browser
  doesn't say how many stations there will be).
- Replacing the list drops stations added to `stations.txt` by hand or by
  Place Station (they're in the backup); `import/` is untouched. The
  dialog says the old list is kept, but not this.
- "UK Pressure Radio" stays United Kingdom (the known miss from build 44).

**Not checked:** a download on a slow or failing connection; the Vita
build (still can't compile here; `core/` changes are Linux-only files).

## Linux stations without coordinates, F6 (build 46, 2026-11-22)

Radio Browser has about 53,000 working stations; only 11,000 have
coordinates. The rest were dropped. Owner's decisions: keep what can be
placed sensibly, put the rest in **one Country-wide place per country**,
in its own colour. UK stations with no town or nation are "United
Kingdom, Country-wide"; Palestinian ones with no town are "Palestine,
Country-wide" (Nablus and Bethlehem are placed and labelled Occupied
West Bank as usual).

**Placement**, first that works (`place_without_coords` in the script,
the same in `core/rb_build.c`):
1. **Another stream** of the same station (same country, same name, and
   a website or stream host in common) that has checked coordinates.
   Without the host check, generic names ("Radio 1") joined the wrong
   station.
2. **Region field names a town** of any size in that country ("Augsburg",
   "Sydney NSW", "Athens Greece"), unless it is also a region and the
   town is under 1M ("New York" the city wins, "Bavaria" the region).
3. **A town of 20,000+ in the name**, only in the region if one was
   given ("Joliet Rocks", region Illinois → Joliet, not Springfield).
4. **Region field names a region** (GeoNames admin1, its names, letter
   codes like TX, and a short list of aliases): placed at its capital.
5. **A region or UK county named in the name or tags** ("Bayern
   Klassik").
6. **Country-wide**: Natural Earth's label point for the country
   (`LABEL_X`/`LABEL_Y`), city "Country-wide". The territory relabelling
   is skipped there (the label point says nothing about where the
   station is).
A region equal to the country's name ("Mexico", a state of Mexico) is
ignored. UK counties map to a county town (`GB_AREAS`).

**Data:** GeoNames `admin1CodesASCII.txt` (CC BY 4.0, 150 KB), cached
with the other two files. Queries leave out `has_geo_info` when stations
without coordinates are wanted, so one download gets everything.

**App:** the dialog's "Stations without coordinates" (on by default),
`--download-list --without-coords`. `place.country_wide` in
`core/library.c` (the city folds to "country-wide"); the globe draws
those dots light blue, a little bigger and on top; the station tree
lists Country-wide first in each country, with a globe icon.

**Bundled list:** rebuilt from both raw snapshots:
`python3 tools/rb_to_stations.py --from-raw third_party/rb/rb_raw_2026-11-22.json
--from-raw third_party/rb/rb_raw_nogeo_2026-11-22.json --geonames
third_party/geodata/cities1000.zip --borders
third_party/geodata/ne_50m_admin_0_countries.geojson --admin1
third_party/geodata/admin1CodesASCII.txt --without-coords --codecs all
--hls --max 100000 --max-mb 32 --bundle "for Linux X.Y.Z" -o
assets/stations/linux/stations.txt`.
51,378 stations (13.6 MB, was 11,063 and 2.5 MB): 21,421 Country-wide,
7,355 by region's town, 6,310 by region, 4,341 by a town in the name,
567 by another stream, 321 by a region in the name or tags. 1,195 with no
country at all are still skipped. 5,285 places (226 countries); the app
loads it in 116 ms here.

**Checked here:** unit tests (4,755) and script tests (27) pass; the
script and C match byte for byte on 8 configurations (the 5 before, plus
without coordinates with borders and regions, without borders, and
without the regions file). A download for Ireland and Malta with
`--without-coords`: 207 stations, 95 Country-wide. Screenshots of the
globe and the dialog.

**Notes:**
- Placed-by-region stations sit at the region's capital, so a capital
  can collect stations from all over its region. The Notes say so.
- Country-wide places are big for some countries (the US, Germany,
  Brazil have hundreds of stations each in theirs).
- Town and region matching is by name, so a few will be wrong (a town
  that shares a name with something else in the station's name). The
  20,000 population floor and the stopword list keep this down.

A full headless download with the option on (all formats, HLS): 3
minutes, 51,386 stations written, in line with the bundled 51,378.

**Not checked:** the full download through the dialog (same code as the
headless run); globe frame rate on slower GPUs with 5,300 dots (fine
here).

## Release Linux 0.9.2 (beta), 2026-11-24

- **Decision by the tester (2026-11-24):** release the Linux app as 0.9.2,
  tagged `linux-v0.9.2` (the first tag under the per-app scheme). It holds
  builds 41 to 46: AAC, Ogg Vorbis, Opus, FLAC and HLS; artist, song and
  station websites in Now Playing; the larger bundled list (F4); Download
  Station List (F5); stations without coordinates and Country-wide places
  (F6). No Vita release: the Vita stays 0.9.0.
- Build 47: build 46 plus the version bump and README changes. The bundled
  list is the same snapshot as build 46 (51,378 stations), its header now
  "shipped with DX Garden for Linux 0.9.2".
- `linux-port` goes to `main` through a pull request (merge commit), then
  `linux-v0.9.2` is tagged there and the AppImage is built from that commit
  with `tools/make_appimage.sh --release`.

## The split into its own repository, 2026-11-24

- **Owner's decision (2026-11-24):** keeping the Vita and Linux apps in one
  repository was confusing, so the Linux app moves to
  `hiraethsystems/DX-Garden-Linux` (public). Its history is kept: a copy of
  DX-Garden's `main` at the 0.9.2 merge (`4b5ea62`) rewritten with
  `git filter-branch` to leave out `vita/`, `sce_sys/`, `art/`,
  `cmake/vita-gap.ld`, `assets/map/`, `assets/cacert.pem`, the Vita's
  `assets/stations/stations.txt` and the LiveArea and map tools. Old tags
  (`v0.9.0`, `v0.9.1`, `win-b*`) aren't carried over.
- `core/` is this app's own copy from now on (no longer built for the
  Vita). `CMakeLists.txt` is Linux only: `cmake -S . -B build-linux -G
  Ninja` builds the app (`-DDXG_APP=OFF` for the core tests alone, without
  SDL). README and CLAUDE.md are Linux only; the Vita screenshots are
  replaced by a Linux one.
- Linux 0.9.2 (build 47) is this repository's first release, tagged
  `linux-v0.9.2`. Linux 0.9.1 stays a release of the DX-Garden repository.
