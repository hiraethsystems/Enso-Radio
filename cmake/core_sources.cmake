# The core/ sources, shared by the PC build (CMakeLists.txt) and the app's
# native build (android/jni/CMakeLists.txt). Paths are relative to
# ENSO_CORE_ROOT, the repository root.
# Building a station list from Radio Browser:
# portable C, plus zlib for GeoNames' zip.
set(RB_SOURCES
    ${ENSO_CORE_ROOT}/core/json.c
    ${ENSO_CORE_ROOT}/core/rb_build.c
    ${ENSO_CORE_ROOT}/core/unitab.c
    ${ENSO_CORE_ROOT}/core/ustr.c
    ${ENSO_CORE_ROOT}/core/zip_read.c
)

set(CORE_SOURCES
    ${ENSO_CORE_ROOT}/core/bidi_order.c
    ${ENSO_CORE_ROOT}/core/config.c
    ${ENSO_CORE_ROOT}/core/controls.c
    ${ENSO_CORE_ROOT}/core/globe.c
    ${ENSO_CORE_ROOT}/core/hls.c
    ${ENSO_CORE_ROOT}/core/icy.c
    ${ENSO_CORE_ROOT}/core/library.c
    ${ENSO_CORE_ROOT}/core/load_report.c
    ${ENSO_CORE_ROOT}/core/media_demux.c
    ${ENSO_CORE_ROOT}/core/playlist.c
    ${ENSO_CORE_ROOT}/core/ring_buffer.c
    ${ENSO_CORE_ROOT}/core/search.c
    ${ENSO_CORE_ROOT}/core/station.c
    ${ENSO_CORE_ROOT}/core/stations_file.c
    ${ENSO_CORE_ROOT}/core/stream_buffer.c
    ${ENSO_CORE_ROOT}/core/stream_info.c
    ${ENSO_CORE_ROOT}/core/strmap.c
    ${ENSO_CORE_ROOT}/core/sun.c
    ${ENSO_CORE_ROOT}/core/text.c
    ${ENSO_CORE_ROOT}/core/track_info.c
    ${ENSO_CORE_ROOT}/core/userlist.c
)
