#!/usr/bin/env bash
# Runs the instrumented flow on the emulator and collects the screenshots it takes. The test's
# exit status is kept in one shell so screenshot collection can never hide a failure.
set -uo pipefail
test_status=0
./gradlew connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true --console plain || test_status=$?
# The failure text of any failed test, since the HTML report is only in the artifact.
find app/build/outputs/androidTest-results -name '*.xml' -exec grep -h -A4 '<failure' {} \; 2>/dev/null | head -60
mkdir -p device-screenshots
if ! adb exec-out run-as com.layerbit.still tar -cf - -C files screenshots | tar -xf - -C device-screenshots; then
    echo "Screenshot collection failed. Inspect device test outputs."
    test_status=1
fi
# A few clips of the app moving, for the docs; never a reason to fail the job.
bash tools/motion_capture.sh || echo "motion capture skipped"
exit "$test_status"
