#!/usr/bin/env bash
# Keep the test exit status in one shell; screenshot collection must never hide a failure.
set -uo pipefail
test_status=0
./gradlew connectedDebugAndroidTest --console plain || test_status=$?
adb pull /sdcard/Android/data/com.layerbit.studio/files/screenshots device-screenshots || true
exit "$test_status"
