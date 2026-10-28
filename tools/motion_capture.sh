#!/usr/bin/env bash
# Records a few seconds of the app moving, on the emulator, after the tests: the device test
# runs with animations off so its screenshots are stable, so this switches them on again,
# opens the installed app and records the home screen, the quick tools, the tools page and
# the batch screen with screenrecord. Clips land in device-screenshots/motion as small MP4s.
# Everything here is best effort: a missing encoder or a changed layout must not fail the job.
set -uo pipefail
PKG=com.layerbit.still
OUT=device-screenshots/motion
mkdir -p "$OUT"
adb shell settings put global animator_duration_scale 1
adb shell settings put global transition_animation_scale 1
adb shell settings put global window_animation_scale 1

# Finds a node whose text or content description is the given string in a uiautomator dump
# and taps its centre. Prints 1 when it did.
tap_text() {
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 1
    local xml; xml=$(adb shell cat /sdcard/ui.xml 2>/dev/null) || return 1
    local b; b=$(printf '%s' "$xml" | grep -o "<node[^>]*\(text\|content-desc\)=\"$1[^\"]*\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" | head -1 | grep -o 'bounds="[^"]*"' | tr -dc '0-9,[]' | tr '[],' '   ')
    [ -n "$b" ] || return 1
    set -- $b
    adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}

# Like tap_text, but scrolls the page down up to three times looking for the text, then back up.
find_tap() {
    local tries=0
    while [ $tries -lt 4 ]; do
        if tap_text "$1"; then return 0; fi
        adb shell input swipe 540 1500 540 500 350; sleep 1
        tries=$((tries + 1))
    done
    return 1
}

home_top() { adb shell input swipe 540 500 540 1700 300; sleep 1; adb shell input swipe 540 500 540 1700 300; sleep 1; adb shell input swipe 540 500 540 1700 300; sleep 1; }

record() { # name seconds
    adb shell screenrecord --time-limit "$2" --bit-rate 6000000 "/sdcard/$1.mp4" || return 1
    adb pull "/sdcard/$1.mp4" "$OUT/$1.raw.mp4" >/dev/null || return 1
    if command -v ffmpeg >/dev/null; then
        ffmpeg -y -loglevel error -i "$OUT/$1.raw.mp4" -vf "scale=540:-2,fps=30" -c:v libx264 -crf 28 -preset veryfast -pix_fmt yuv420p -an -movflags +faststart "$OUT/$1.mp4" && rm -f "$OUT/$1.raw.mp4"
    else
        mv "$OUT/$1.raw.mp4" "$OUT/$1.mp4"
    fi
    echo "recorded $1"
}

adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 3
tap_text "Skip" && sleep 2
record home 10 || { echo "screenrecord is not available here"; exit 0; }
adb shell input swipe 540 1700 540 300 400; sleep 1; adb shell input swipe 540 1700 540 700 400; sleep 2
record home-quick 8
adb shell input swipe 540 500 540 1700 400; sleep 1; adb shell input swipe 540 500 540 1700 400; sleep 1
# The editor, on the picture the walk left behind, with the Looks strip open.
tap_text "walk-1.jpg" && { sleep 4; tap_text "Looks" && sleep 3; record editor-looks 8; adb shell input keyevent KEYCODE_BACK; sleep 2; }
home_top
find_tap "Every tool" && { sleep 2; record tools 8; adb shell input keyevent KEYCODE_BACK; sleep 2; }
home_top
find_tap "Process many" && { sleep 2; record batch 8; adb shell input keyevent KEYCODE_BACK; sleep 2; }
ls -la "$OUT"
