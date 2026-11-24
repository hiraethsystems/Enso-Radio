# DX Garden for Android

**Work in progress: there is no release yet.** This is the Android port of
DX Garden, a radio player and explorer in the spirit of spinning a globe and
listening to what's on air somewhere else. Turn the globe, hold the
crosshair on a place, and hear its stations.

DX Garden started on the PS Vita
([hiraethsystems/DX-Garden](https://github.com/hiraethsystems/DX-Garden),
frozen) and then came to the Linux desktop
([hiraethsystems/DX-Garden-Linux](https://github.com/hiraethsystems/DX-Garden-Linux)).
This repository was split from the Linux one with its history, and keeps the
shared portable C code in `core/`. The apps share a station-file format but
are developed and released separately.

## Plans

- Android 13 or later, phones, tablets and foldables.
- A player and explorer for streams. It will ship a dated snapshot of
  [Radio Browser](https://www.radio-browser.info), a community station
  directory, as a default list that is yours to edit and keep up to date.
  The app downloads a fresh list only when you ask it to, never by itself.
- Released here on GitHub (APK) and on F-Droid. No Google Play, no Google
  Play Services, no analytics.
- Your files (`stations.txt`, favourites, M3U/PLS imports) will live in
  `Android/data/systems.hiraeth.dxgarden/files/`, which you can reach from a
  computer over USB. Android deletes that folder when the app is
  uninstalled, so copy your files off first.

Progress and design decisions are in `NOTES.md`.

## Tests

`core/` (station files, playlists, streams, the station-list builder) has
unit tests that need only a C compiler and zlib:

```sh
cmake -S . -B build-test -DDXG_APP=OFF && cmake --build build-test && ./build-test/dxgarden_test
python3 -I tools/test_rb_to_stations.py
```

`tests/compare_listtool.sh` checks that the app's list builder
(`core/rb_build.c`) writes the same list as `tools/rb_to_stations.py`.

## Credits and licences

- DX Garden is licensed under the GNU GPL v3 (see `LICENSE`).
- Linked into the app: SDL3 (zlib licence), libcurl (curl licence),
  Mbed TLS (Apache 2.0), mpg123 (LGPL 2.1), faad2 (GPL 2 or later),
  libogg, libvorbis, Opus, opusfile and libFLAC (BSD), FreeType (FreeType
  licence), HarfBuzz (MIT), FriBidi (LGPL 2.1), zlib (zlib licence) and
  stb_image (public domain or MIT).
- Unicode tables made from the Unicode Character Database (Unicode
  licence).
- Fonts: Liberation Sans, and Noto Sans, Noto Sans Arabic, Hebrew, Thai,
  Georgian and Ethiopic, and a subset of Noto Sans CJK SC made by
  `tools/make_fonts.py`; all SIL Open Font License 1.1
  (`assets/fonts/OFL.txt`, `assets/fonts/OFL-Liberation.txt`).
- Globe imagery from NASA: Blue Marble: Next Generation (Reto Stöckli,
  NASA Earth Observatory) for the day side, and Black Marble 2016 (NASA
  Earth Observatory images by Joshua Stevens, using Suomi NPP VIIRS data
  from Miguel Román, NASA Goddard Space Flight Center) for the night side.
  NASA imagery is not under copyright in the US; its use here implies no
  endorsement by NASA. `tools/make_earth.py` downloads the source images
  and builds the app's tile packs.
- Station lists made with `tools/rb_to_stations.py`, and lists the app
  downloads, come from Radio Browser (radio-browser.info), whose API
  documentation says it may be used in free and non-free software and its
  data mirrored. Town and region names come from GeoNames (geonames.org),
  licensed CC BY 4.0. Positions are checked against Natural Earth country
  shapes (public domain). GeoNames' `cities1000.zip` and
  `admin1CodesASCII.txt` (from download.geonames.org) and Natural Earth's
  `ne_50m_admin_0_countries.geojson` (v5.1.2, from the natural-earth-vector
  repository on GitHub) are downloaded only when you ask for a list.
