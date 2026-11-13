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
# Also requires time-to-resumed (activity manager START to MainActivity's onResume, from the events log; the event is am_on_resume_called up to Android 14 and wm_on_resume_called on Android 15) within
# RESUME_LIMIT_MS (default 40 s). A software-emulated cold start takes ~11 s here and the blocking fake provider holds a
# query for 45 s, so a main-thread wait on another app's provider is a FAIL even though the app eventually comes up.
# (`am start -W` and the "Displayed" log line were tried and cannot show this: both are satisfied by the splash window.)
# Never touches the app's data (no pm clear / uninstall of the app). Prints one TSV line per scenario and exits 1 on any FAIL.
set -uo pipefail
FAKE_DIR="${1:?fake apk dir}"; STARTS="${2:-3}"; SETTLE="${3:-8}"; RESUME_LIMIT_MS="${RESUME_LIMIT_MS:-40000}"
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
    # Wait for MainActivity's onResume (only possible after Application.onCreate and Activity.onCreate returned on the main thread).
    t_start=""; t_resume=""; waited=0
    while [ -z "$t_resume" ] && [ "$waited" -le $(( RESUME_LIMIT_MS / 1000 + 30 )) ]; do
      ev="$(adb logcat -b all -d -v threadtime | tr -d '\r' | grep -E "START u0 .*cmp=$ACT|(am|wm)_on_resume_called: .*ai.droidcommand.app.MainActivity")"
      t_start="$(grep -m1 "START u0" <<<"$ev" | awk '{print $2}')"
      t_resume="$(grep -m1 "_on_resume_called" <<<"$ev" | awk '{print $2}')"
      [ -n "$t_resume" ] || { sleep 2; waited=$(( waited + 2 )); }
    done
    sleep "$SETTLE"
    detail=""; result=PASS
    to_ms() { awk -F'[:.]' '{print (($1*60+$2)*60+$3)*1000+$4}' <<<"$1"; }
    ms=""
    [ -n "$t_start" ] && [ -n "$t_resume" ] && ms=$(( $(to_ms "$t_resume") - $(to_ms "$t_start") ))
    if [ -z "$ms" ]; then result=FAIL; detail="MainActivity never resumed;"
    elif [ "$ms" -lt 0 ]; then ms=$(( ms + 86400000 )); fi
    if [ -n "$ms" ] && [ "$ms" -gt "$RESUME_LIMIT_MS" ]; then result=FAIL; detail="resumed ${ms} ms after launch (limit ${RESUME_LIMIT_MS});"; fi
    resume_ms="${ms:-none}"
    # Real background and return: Home, then launch again (same task, single top). The process must survive and come forward.
    adb shell input keyevent KEYCODE_HOME > /dev/null
    sleep 3
    adb shell am start -n "$ACT" --activity-single-top > /dev/null 2>&1
    sleep "$SETTLE"
    pid="$(adb shell pidof "$APP" | tr -d '\r')"
    [ -n "$pid" ] || { result=FAIL; detail="$detail process dead;"; }
    adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" | grep -q "$APP/.MainActivity" \
      || { result=FAIL; detail="$detail MainActivity not resumed;"; }
    log="$(adb logcat -b all -d -v brief 2>/dev/null)"
    if grep -q "FATAL EXCEPTION" <<<"$log" && grep -A3 "FATAL EXCEPTION" <<<"$log" | grep -q "Process: $APP"; then
      result=FAIL; detail="$detail FATAL EXCEPTION: $(grep -A6 "Process: $APP" <<<"$log" | grep -m1 -E 'Exception|Error' | tr -d '\r' | cut -c1-160);"
    fi
    grep -q "ANR in $APP" <<<"$log" && { result=FAIL; detail="$detail ANR;"; }
    [ "$result" = FAIL ] && fail=1
    printf '%s\t%s\t%s\t%s\n' "$name" "$i" "$result" "resume_ms=${resume_ms} ${detail}"
  done
  [ -n "$fakepkg" ] && adb uninstall "$fakepkg" > /dev/null 2>&1
done
exit "$fail"
