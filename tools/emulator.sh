#!/bin/sh
# Headless emulator for tests.
#   tools/emulator.sh start   start the AVD with no window, wait for boot
#   tools/emulator.sh stop    shut it down
#   tools/emulator.sh status  print the serial if it is running
#   tools/emulator.sh create  make the AVD (Pixel 8, Android 16, 16 KB pages)
# Afterwards use: adb -s "emulator-$DXG_EMU_PORT" ...
set -eu
. "$(dirname "$0")/android_env.sh"
SERIAL="emulator-$DXG_EMU_PORT"
LOG="${TMPDIR:-/tmp}/dxg-emulator.log"

running() { adb devices | grep -q "^$SERIAL[[:space:]]"; }

case "${1:-}" in
create)
    echo no | avdmanager create avd -n "$DXG_AVD" -k "$DXG_AVD_IMAGE" -d pixel_8 --force
    ;;
start)
    if running; then echo "$SERIAL already running"; exit 0; fi
    nohup emulator -avd "$DXG_AVD" -port "$DXG_EMU_PORT" -no-window \
        -gpu swiftshader_indirect -no-audio -no-boot-anim -no-snapshot \
        >"$LOG" 2>&1 &
    adb -s "$SERIAL" wait-for-device
    i=0
    until [ "$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do
        i=$((i + 1))
        if [ "$i" -gt 180 ]; then echo "boot timed out; see $LOG" >&2; exit 1; fi
        sleep 1
    done
    adb -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP
    adb -s "$SERIAL" shell wm dismiss-keyguard 2>/dev/null || true
    echo "$SERIAL booted in about ${i} s (log: $LOG)"
    ;;
stop)
    if running; then adb -s "$SERIAL" emu kill >/dev/null; fi
    i=0
    while running && [ "$i" -lt 30 ]; do sleep 1; i=$((i + 1)); done
    echo "$SERIAL stopped"
    ;;
status)
    if running; then echo "$SERIAL"; else echo "not running"; exit 1; fi
    ;;
*)
    echo "usage: $0 start|stop|status|create" >&2; exit 2
    ;;
esac
