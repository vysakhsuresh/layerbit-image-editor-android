#!/usr/bin/env bash
# Keep the test exit status in one shell; screenshot collection must never hide a failure.
set -uo pipefail
test_status=0
./gradlew connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true --console plain || test_status=$?
mkdir -p device-screenshots
if ! adb exec-out run-as com.layerbit.studio tar -cf - -C files screenshots | tar -xf - -C device-screenshots; then
    echo "Screenshot collection failed. Inspect device test outputs."
    test_status=1
fi
exit "$test_status"
