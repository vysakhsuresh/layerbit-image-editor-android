#!/usr/bin/env bash
# Keep the test exit status in one shell; screenshot collection must never hide a failure.
set -uo pipefail
test_status=0
./gradlew connectedDebugAndroidTest --console plain || test_status=$?
mkdir -p device-screenshots
adb exec-out run-as com.layerbit.studio tar -cf - -C files screenshots | tar -xf - -C device-screenshots || true
exit "$test_status"
