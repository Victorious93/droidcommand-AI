#!/usr/bin/env bash
# Cold-start fault matrix for the installed app. Emulator/device evidence, not a unit test.
#
#   startup-matrix.sh <fake-apk-dir> [cold-starts-per-scenario=3] [settle-seconds=8]
#
# For each scenario (no companion, or a fake companion provider that throws / returns null / malformed rows / blocks):
# install the fake, force-stop the app, cold start MainActivity, wait, press Home and launch it again, then require
#   - the app process is alive,
#   - MainActivity is the resumed activity,
#   - logcat has no "FATAL EXCEPTION" for the app's process and no "ANR in <app>".
# Never touches the app's data (no pm clear / uninstall of the app). Prints one TSV line per scenario and exits 1 on any FAIL.
set -uo pipefail
FAKE_DIR="${1:?fake apk dir}"; STARTS="${2:-3}"; SETTLE="${3:-8}"
APP=ai.droidcommand.app
ACT="$APP/.MainActivity"
scenarios=(none)
for apk in "$FAKE_DIR"/*.apk; do [ -f "$apk" ] && scenarios+=("$apk"); done
fail=0
printf 'scenario\tstart\tresult\tdetail\n'
for sc in "${scenarios[@]}"; do
  name=none; fakepkg=""
  if [ "$sc" != none ]; then
    name="$(basename "$sc" .apk)"; fakepkg="${name%-*}"
    adb install -r -t "$sc" > /dev/null 2>&1 || { printf '%s\t-\tFAIL\tfake install failed\n' "$name"; fail=1; continue; }
  fi
  for i in $(seq 1 "$STARTS"); do
    adb shell am force-stop "$APP" > /dev/null
    adb logcat -b all -c
    adb shell am start -W -n "$ACT" > /dev/null 2>&1
    sleep "$SETTLE"
    detail=""; result=PASS
    # Real background and return: Home, then launch again (same task, single top). The process must survive and come forward.
    adb shell input keyevent KEYCODE_HOME > /dev/null
    sleep 3
    adb shell am start -n "$ACT" --activity-single-top > /dev/null 2>&1
    sleep "$SETTLE"
    pid="$(adb shell pidof "$APP" | tr -d '\r')"
    [ -n "$pid" ] || { result=FAIL; detail="process dead;"; }
    adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" | grep -q "$APP/.MainActivity" \
      || { result=FAIL; detail="$detail MainActivity not resumed;"; }
    log="$(adb logcat -b all -d -v brief 2>/dev/null)"
    if grep -q "FATAL EXCEPTION" <<<"$log" && grep -A3 "FATAL EXCEPTION" <<<"$log" | grep -q "Process: $APP"; then
      result=FAIL; detail="$detail FATAL EXCEPTION: $(grep -A6 "Process: $APP" <<<"$log" | grep -m1 -E 'Exception|Error' | tr -d '\r' | cut -c1-160);"
    fi
    grep -q "ANR in $APP" <<<"$log" && { result=FAIL; detail="$detail ANR;"; }
    [ "$result" = FAIL ] && fail=1
    printf '%s\t%s\t%s\t%s\n' "$name" "$i" "$result" "$detail"
  done
  [ -n "$fakepkg" ] && adb uninstall "$fakepkg" > /dev/null 2>&1
done
exit "$fail"
