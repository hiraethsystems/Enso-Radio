#!/bin/sh
# Plays streams on a device through the app's test mode (intent extras
# play and seconds; debug builds only) and prints what the app logged:
# the decoder, the last status line and the result. No ears needed: "frames
# out" rising at the stream's rate means the audio device is taking samples.
#   tools/android_play_test.sh [-s serial] [-t seconds] [URL...]
# With no URLs, plays the format list below. Needs the debug APK installed.
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
. "$ROOT/tools/android_env.sh"
SERIAL=emulator-$ENSO_EMU_PORT
SECS=15
while getopts s:t: o; do
    case $o in
    s) SERIAL=$OPTARG ;;
    t) SECS=$OPTARG ;;
    *) exit 2 ;;
    esac
done
shift $((OPTIND - 1))

if [ $# -eq 0 ]; then
    set -- \
        "MP3|https://ice1.somafm.com/groovesalad-128-mp3" \
        "AAC LC 320|https://stream.radioparadise.com/aac-320" \
        "HE-AAC 32|https://ice1.somafm.com/groovesalad-32-aac" \
        "Opus (DLF)|https://st01.sslstream.dlf.de/dlf/01/high/opus/stream.opus" \
        "Opus chained|https://radio.plaza.one/opus" \
        "Ogg Vorbis|http://play.global.audio/energy-90s.ogg" \
        "Ogg FLAC|https://stream.radioparadise.com/rock-flac" \
        "FLAC|https://iradio.fi/klasu.flac" \
        "HLS (BBC R4)|http://as-hls-ww-live.akamaized.net/pool_55057080/live/ww/bbc_radio_fourfm/bbc_radio_fourfm.isml/bbc_radio_fourfm-audio%3d96000.norewind.m3u8" \
        "PLS playlist|https://somafm.com/groovesalad.pls"
fi

PKG=systems.hiraeth.ensoradio
VERSION=$(cat "$ROOT/android/VERSION")
BUILD=$(cat "$ROOT/android/BUILD")
LOG=/storage/emulated/0/Android/data/$PKG/files/log-$VERSION-b$BUILD.txt
fails=0
for item in "$@"; do
    case $item in
    *"|"*) name=${item%%|*} url=${item#*|} ;;
    *) name=$item url=$item ;;
    esac
    adb -s "$SERIAL" shell am force-stop $PKG
    adb -s "$SERIAL" shell am start -W -n "$PKG/.MainActivity" \
        -e play "'$url'" -e seconds "$SECS" >/dev/null
    sleep $((SECS + 4))
    out=$(adb -s "$SERIAL" shell cat "$LOG" 2>/dev/null | tr -d '\r')
    dec=$(printf '%s\n' "$out" | grep -v -e chosen -e finished | grep -m1 'decoder: ' | sed 's/^.*decoder: //')
    last=$(printf '%s\n' "$out" | grep 'status ' | tail -1 | sed 's/^.*status *//')
    res=$(printf '%s\n' "$out" | grep -m1 'result:' | sed 's/^.*result: //')
    echo "== $name"
    echo "   decoder: ${dec:-?}"
    echo "   last:    ${last:-?}"
    echo "   result:  ${res:-no result (see $LOG)}"
    case $res in played*) ;; *) fails=$((fails + 1)) ;; esac
done
echo "$(($# - fails)) of $# played"
[ "$fails" -eq 0 ]
