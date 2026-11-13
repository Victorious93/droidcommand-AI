#!/usr/bin/env bash
# Called from android-emulator.yml: script: bash scripts/ci/emulator-smoke.sh
#
# MANDATORY behavioural validation (a failure here fails the job, and therefore the release):
#   1. every required instrumented test below must PASS, judged by verify-instrumentation.py from the runner's raw
#      status stream. adb's exit status and a grep for "FAILURES!!!" are NOT used as evidence: adb returns 0 for a
#      crashed process, a runner that never started, and a run of zero tests (docs/CRASH_REPAIR_REPORT.md).
#   2. scripts/qa/startup-matrix.sh: cold starts with other fleet apps' ContentProviders absent, throwing, returning
#      null / malformed rows, or blocking; the app must stay alive with MainActivity resumed.
# BEST-EFFORT, never gates: the screenshot walk (README images). It cannot turn a red run green or a green run red.
#
# Evidence kept in $OUT: raw runner logs, verdict summaries, sanitized crash/ANR logcat on failure, validation-summary.md.
set -euo pipefail

ROOT="${GITHUB_WORKSPACE:-$(pwd)}"
OUT="$ROOT/emulator-logs"
mkdir -p "$OUT"
# APP_APK / TEST_APK override the defaults (a local machine can hold a stale APK of another ABI under outputs/).
APP_APK="${APP_APK:-app/build/outputs/apk/debug/app-debug.apk}"
TEST_APK="${TEST_APK:-app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk}"
[[ -f "$APP_APK" ]] || APP_APK=app/build/intermediates/apk/debug/app-debug.apk
[[ -f "$TEST_APK" ]] || TEST_APK=app/build/intermediates/apk/androidTest/debug/app-debug-androidTest.apk
RUNNER=ai.droidcommand.app.test/androidx.test.runner.AndroidJUnitRunner
STARTS="${MATRIX_COLD_STARTS:-2}"

# One entry per mandatory test. Adding a test to the gate means adding it here; the count check then follows.
REQUIRED=(
  "ai.droidcommand.app.MainActivityLaunchTest#launchesToResumedWithoutCrashing"
  "ai.droidcommand.app.StartupResilienceTest#survivesCorruptPersistedState"
  "ai.droidcommand.app.StartupResilienceTest#survivesRecreationAndBackgroundResume"
  "ai.droidcommand.app.StartupResilienceTest#everyPrimaryTabOpensAndStaysUsable"
  "ai.droidcommand.app.StartupResilienceTest#noTrustHintCoversTheTabBarWithoutVictorLaunch"
  "ai.droidcommand.app.StartupResilienceTest#repeatedActivityLaunchesStayAlive"
)

# Crash / ANR evidence with obvious secrets masked. Kept only when something failed.
save_failure_logs() {
  adb logcat -b crash,main,system -d -v threadtime 2>/dev/null \
    | grep -E "FATAL EXCEPTION|AndroidRuntime|ANR in|Process: |Caused by|	at ai\.droidcommand|DEBUG +: |signal [0-9]+" \
    | sed -E 's/(sk-[A-Za-z0-9_-]{8})[A-Za-z0-9_-]+/\1.../g; s/([Bb]earer )[A-Za-z0-9._~+\/=-]+/\1.../g; s/([?&](key|token|api_key)=)[^& ]+/\1.../g' \
    > "$OUT/crash-logcat.txt" || true
  adb shell getprop > "$OUT/device-props.txt" 2>/dev/null || true
}

adb wait-for-device
until [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == "1" ]]; do sleep 2; done

# Streamed adb install: Gradle's connected split-installer failed on the 208 MB x86_64 APK locally
# ("Failed to install-write all apks").
adb install -r -t -d --no-incremental "$APP_APK"
adb install -r -t -d --no-incremental "$TEST_APK"

# ---- 1. mandatory instrumented tests ------------------------------------------------------------------------------
req_args=()
for t in "${REQUIRED[@]}"; do req_args+=(--require "$t"); done
class_list="$(IFS=,; echo "${REQUIRED[*]}")"

# adb's own status is recorded but never trusted: set +e keeps the verdict in the verifier's hands.
set +e
adb shell am instrument -w -r -e class "$class_list" "$RUNNER" 2>&1 | tee "$OUT/instrument.log"
adb_status=${PIPESTATUS[0]}
set -e
echo "adb shell am instrument exit status: $adb_status (informational only)"

set +e
python3 -I scripts/ci/verify-instrumentation.py "$OUT/instrument.log" \
  "${req_args[@]}" --expect-count "${#REQUIRED[@]}" --summary "$OUT/instrument-verdict.md"
verdict=$?
set -e
if [[ $verdict -ne 0 ]]; then
  echo "::error::mandatory instrumented tests did not pass (see emulator-logs/instrument.log)"
  save_failure_logs
  exit 1
fi

# ---- 2. mandatory cold-start fault matrix -------------------------------------------------------------------------
FAKES="$OUT/fake-providers"
mkdir -p "$FAKES"
for mode in throw_iae throw_ise throw_security null_cursor malformed blob slow; do
  bash scripts/qa/fake-provider/build.sh "$mode" com.victorious.launch.theme fake.victorlaunch "$FAKES" > /dev/null
done
# The My PC tile reads a second foreign authority; probe the same failure shapes there.
for mode in throw_iae null_cursor malformed; do
  bash scripts/qa/fake-provider/build.sh "$mode" com.termux.victor.pc fake.victorsuite "$FAKES" > /dev/null
done
set +e
bash scripts/qa/startup-matrix.sh "$FAKES" "$STARTS" 8 | tee "$OUT/startup-matrix.tsv"
matrix=${PIPESTATUS[0]}
set -e
if [[ $matrix -ne 0 ]] || ! grep -q $'\tPASS\t' "$OUT/startup-matrix.tsv"; then
  echo "::error::startup fault matrix failed (see emulator-logs/startup-matrix.tsv)"
  save_failure_logs
  exit 1
fi

# ---- validation summary: what was ACTUALLY executed, for the release notes ----------------------------------------
{
  echo "Emulator validation for commit \`${GITHUB_SHA:-unknown}\` ($(adb shell getprop ro.product.cpu.abi | tr -d '\r'), API $(adb shell getprop ro.build.version.sdk | tr -d '\r')):"
  cat "$OUT/instrument-verdict.md"
  echo "- Cold-start fault matrix, ${STARTS} cold starts per scenario: $(grep -c $'\tPASS\t' "$OUT/startup-matrix.tsv") passed, $(grep -c $'\tFAIL\t' "$OUT/startup-matrix.tsv" || true) failed (scenarios: no companion, 7 VictorLaunch-theme provider fault shapes, 3 VictorSuite My PC provider fault shapes)."
} > "$OUT/validation-summary.md"
echo "VALIDATION PASSED"

# ---- 3. best-effort screenshots (README images). Never gates, never hides a failure above. ------------------------------
adb shell am instrument -w -r \
  -e class ai.droidcommand.app.PrimaryScreensScreenshotTest \
  "$RUNNER" | tee "$OUT/screenshot-instrument.log" || true
mkdir -p "$ROOT/emulator-screenshots"
adb pull /sdcard/Android/data/ai.droidcommand.app/files/screenshots/. "$ROOT/emulator-screenshots/" || true
