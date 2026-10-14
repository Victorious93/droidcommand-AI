#!/usr/bin/env bash
# Called from android-emulator.yml: script: bash scripts/ci/emulator-smoke.sh
set -euo pipefail
APP_APK=app/build/outputs/apk/debug/app-debug.apk
TEST_APK=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
[[ -f "$APP_APK" ]] || APP_APK=app/build/intermediates/apk/debug/app-debug.apk
[[ -f "$TEST_APK" ]] || TEST_APK=app/build/intermediates/apk/androidTest/debug/app-debug-androidTest.apk
# Streamed adb install: Gradle's connected split-installer failed on the
# 208 MB x86_64 APK locally ("Failed to install-write all apks").
adb install -r -t -d --no-incremental "$APP_APK"
adb install -r -t -d --no-incremental "$TEST_APK"
# Launch smoke is the CI gate (PR #199). The screenshot walk is
# best-effort: TCG + System UI ANR often lose later Compose clicks.
adb shell am instrument -w -r \
  -e class ai.droidcommand.app.MainActivityLaunchTest \
  ai.droidcommand.app.test/androidx.test.runner.AndroidJUnitRunner \
  | tee "$GITHUB_WORKSPACE/instrument.log"
if grep -q 'FAILURES!!!' "$GITHUB_WORKSPACE/instrument.log"; then
  echo "launch smoke failed"; exit 1
fi
adb shell am instrument -w -r \
  -e class ai.droidcommand.app.PrimaryScreensScreenshotTest \
  ai.droidcommand.app.test/androidx.test.runner.AndroidJUnitRunner \
  | tee "$GITHUB_WORKSPACE/screenshot-instrument.log" || true
mkdir -p "$GITHUB_WORKSPACE/emulator-screenshots"
adb pull /sdcard/Android/data/ai.droidcommand.app/files/screenshots/. \
  "$GITHUB_WORKSPACE/emulator-screenshots/" || true
