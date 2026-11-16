#!/usr/bin/env bash
# Called from android-system-settings.yml (inside the emulator runner). Runs only on a throwaway emulator.
#
# Judged the same way as emulator-smoke.sh: verify-instrumentation.py reads the runner's raw status stream; adb's exit status is not evidence.
set -euo pipefail

ROOT="${GITHUB_WORKSPACE:-$(pwd)}"
OUT="$ROOT/system-settings-logs"
SHOTS="$ROOT/system-settings-screenshots"
mkdir -p "$OUT" "$SHOTS"
APP_APK="${APP_APK:-app/build/outputs/apk/debug/app-debug.apk}"
TEST_APK="${TEST_APK:-app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk}"
[[ -f "$APP_APK" ]] || APP_APK=app/build/intermediates/apk/debug/app-debug.apk
[[ -f "$TEST_APK" ]] || TEST_APK=app/build/intermediates/apk/androidTest/debug/app-debug-androidTest.apk
PKG=ai.droidcommand.app
RUNNER=$PKG.test/androidx.test.runner.AndroidJUnitRunner

REQUIRED=(
  "ai.droidcommand.app.SystemSettingsFlowTest#everyTabOpensAndShowsHonestStates"
  "ai.droidcommand.app.SystemSettingsFlowTest#editReadbackHistoryRestoreReadback"
  "ai.droidcommand.app.SystemSettingsFlowTest#advancedAddIsRestoredByDeletingBecauseItWasAbsent"
)

adb wait-for-device
until [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == "1" ]]; do sleep 2; done
{
  echo "device: $(adb shell getprop ro.product.model | tr -d '\r') / $(adb shell getprop ro.product.cpu.abi | tr -d '\r') / API $(adb shell getprop ro.build.version.sdk | tr -d '\r') / Android $(adb shell getprop ro.build.version.release | tr -d '\r')"
  echo "fingerprint: $(adb shell getprop ro.build.fingerprint | tr -d '\r')"
  echo "qemu: $(adb shell getprop ro.kernel.qemu | tr -d '\r')"
} | tee "$OUT/device.txt"

# This emulator is a disposable CI machine, so changing its settings is authorized.
adb install -r -t -d --no-incremental "$APP_APK"
adb install -r -t -d --no-incremental "$TEST_APK"
adb shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS
adb shell appops set "$PKG" WRITE_SETTINGS allow
# Animation scales must exist as rows for the flow to read a real previous value.
adb shell settings put global window_animation_scale 1.0
adb shell settings put global transition_animation_scale 1.0
adb shell settings put global animator_duration_scale 1.0
adb shell settings get global window_animation_scale | tee "$OUT/before.txt"

req_args=()
for t in "${REQUIRED[@]}"; do req_args+=(--require "$t"); done
class_list="$(IFS=,; echo "${REQUIRED[*]}")"

set +e
adb shell am instrument -w -r -e class "$class_list" "$RUNNER" 2>&1 | tee "$OUT/instrument.log"
set -e
set +e
python3 -I scripts/ci/verify-instrumentation.py "$OUT/instrument.log" "${req_args[@]}" --expect-count "${#REQUIRED[@]}" --summary "$OUT/verdict.md"
verdict=$?
set -e

adb shell settings get global window_animation_scale | tee "$OUT/after.txt"
adb shell settings get global dca_ss_test_key | tee "$OUT/after-test-key.txt"
adb pull "/sdcard/Android/data/$PKG/files/screenshots-system-settings/." "$SHOTS/" || true
# Artifact download may be unavailable to the person reading this run, so the PNGs are also printed into the log (base64) and can be rebuilt from it.
for f in "$SHOTS"/*.png; do
  [[ -f "$f" ]] || continue
  echo "SSIMG_BEGIN $(basename "$f") $(sha256sum "$f" | cut -d' ' -f1) $(stat -c%s "$f")"
  base64 -w0 "$f"; echo
  echo "SSIMG_END"
done
echo "=== SSFLOW (screen dump on failure) ==="; adb logcat -d -s SSFLOW:I 2>/dev/null | cut -c1-260 | head -400 || true
adb logcat -d -v threadtime 2>/dev/null | grep -E "FATAL EXCEPTION|AndroidRuntime|ANR in|ai\.droidcommand" | head -400 > "$OUT/logcat-excerpt.txt" || true

if [[ $verdict -ne 0 ]]; then
  echo "::error::System Settings flow did not pass (see system-settings-logs/instrument.log)"
  exit 1
fi
echo "SYSTEM SETTINGS FLOW PASSED"
