#!/usr/bin/env bash
# Runs the instrumented end-to-end pipeline tests on a connected device or emulator.
# Usage: tools/run_device_tests.sh <dir containing ddcolor.onnx and ddcolor.data> [snapdragon|universal]
set -euo pipefail
MODEL_DIR=${1:?model dir}; FLAVOR=${2:-universal}
cd "$(dirname "$0")/.."
F=${FLAVOR^}
./gradlew ":app:assemble${F}Debug" ":app:assemble${F}DebugAndroidTest"
adb push "$MODEL_DIR/ddcolor.onnx" "$MODEL_DIR/ddcolor.data" /data/local/tmp/
adb install -r "app/build/outputs/apk/$FLAVOR/debug/app-$FLAVOR-debug.apk"
adb install -r "app/build/outputs/apk/androidTest/$FLAVOR/debug/app-$FLAVOR-debug-androidTest.apk"
PKG=colourfilm.app; [ "$FLAVOR" = universal ] && PKG=colourfilm.app.universal
adb shell am instrument -w -r "$PKG.test/androidx.test.runner.AndroidJUnitRunner" | tee device-test-output.txt
adb logcat -d -s PipelineTest ModelSessions | tail -50
