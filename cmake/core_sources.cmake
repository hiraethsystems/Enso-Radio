# The core/ sources, shared by the PC build (CMakeLists.txt) and the app's
# native build (android/jni/CMakeLists.txt). Paths are relative to
# DXG_CORE_ROOT, the repository root.
# Building a station list from Radio Browser:
# portable C, plus zlib for GeoNames' zip.
set(RB_SOURCES
    ${DXG_CORE_ROOT}/core/json.c
    ${DXG_CORE_ROOT}/core/rb_build.c
    ${DXG_CORE_ROOT}/core/unitab.c
    ${DXG_CORE_ROOT}/core/ustr.c
    ${DXG_CORE_ROOT}/core/zip_read.c
)

set(CORE_SOURCES
    ${DXG_CORE_ROOT}/core/bidi_order.c
    ${DXG_CORE_ROOT}/core/config.c
    ${DXG_CORE_ROOT}/core/controls.c
    ${DXG_CORE_ROOT}/core/globe.c
    ${DXG_CORE_ROOT}/core/hls.c
    ${DXG_CORE_ROOT}/core/icy.c
    ${DXG_CORE_ROOT}/core/library.c
    ${DXG_CORE_ROOT}/core/load_report.c
    ${DXG_CORE_ROOT}/core/media_demux.c
    ${DXG_CORE_ROOT}/core/playlist.c
    ${DXG_CORE_ROOT}/core/ring_buffer.c
    ${DXG_CORE_ROOT}/core/search.c
    ${DXG_CORE_ROOT}/core/station.c
    ${DXG_CORE_ROOT}/core/stations_file.c
    ${DXG_CORE_ROOT}/core/stream_buffer.c
    ${DXG_CORE_ROOT}/core/stream_info.c
    ${DXG_CORE_ROOT}/core/strmap.c
    ${DXG_CORE_ROOT}/core/text.c
    ${DXG_CORE_ROOT}/core/track_info.c
    ${DXG_CORE_ROOT}/core/userlist.c
)
