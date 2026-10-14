#!/usr/bin/env bash
# Capture :app primary-screen PNGs on an API 28 x86_64 emulator.
#
# Writes docs/images/{chat,tools,notes,memory,settings,pilot,forge,logs,
# tool-approval,tools-metasploit,tools-setoolkit}.png via
# PrimaryScreensScreenshotTest (instrumented, production MainActivity).
#
# This does NOT run live LLM calls or real Root/Termux/Metasploit/SET
# backends. Tool approval is the real Confirm-action dialog over the
# NullMetasploitExecutor path, then Denied.
#
# Usage (from the repo root, after ANDROID_HOME is set):
#   ./scripts/capture-app-screenshots.sh
#
# Optional env:
#   ANDROID_HOME          SDK root (default: $HOME/android-sdk)
#   AVD_NAME              default: dca_ui
#   SKIP_SDK_INSTALL=1    do not download packages
#   SKIP_ASSEMBLE=1       reuse already-built APKs
#   EMULATOR_ACCEL=off    force TCG (this host's kernel has previously
#                         hit kvm_arch_vcpu_create BUG; the script tries
#                         KVM first and falls back to TCG)

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

SDK="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export PATH="$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$SDK/emulator:$SDK/cmake/3.22.1/bin:$PATH"

CMDLINE_ZIP_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
CMDLINE_ZIP_SHA="2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258"
AVD_NAME="${AVD_NAME:-dca_ui}"
OUT_DIR="$ROOT/docs/images"
DEVICE_DIR="/sdcard/Android/data/ai.droidcommand.app/files/screenshots"
APP_APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
# assembleDebug writes outputs/; intermediates can be an older copy (seen 2026-10-14).
if [[ ! -f "$APP_APK" ]]; then
  APP_APK="$ROOT/app/build/intermediates/apk/debug/app-debug.apk"
fi
if [[ ! -f "$TEST_APK" ]]; then
  TEST_APK="$ROOT/app/build/intermediates/apk/androidTest/debug/app-debug-androidTest.apk"
fi

log() { printf '%s\n' "$*"; }

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "missing command: $1" >&2
    exit 1
  }
}

install_sdk() {
  mkdir -p "$SDK"
  if [[ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]]; then
    need_cmd curl
    need_cmd sha256sum
    need_cmd unzip
    tmp="$(mktemp -d)"
    log "Downloading Android command-line tools…"
    curl -fsSL "$CMDLINE_ZIP_URL" -o "$tmp/cmdline-tools.zip"
    echo "$CMDLINE_ZIP_SHA  $tmp/cmdline-tools.zip" | sha256sum -c -
    unzip -q "$tmp/cmdline-tools.zip" -d "$tmp"
    mkdir -p "$SDK/cmdline-tools"
    rm -rf "$SDK/cmdline-tools/latest"
    mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
    rm -rf "$tmp"
  fi
  yes | sdkmanager --licenses >/dev/null || true
  sdkmanager \
    "platform-tools" \
    "platforms;android-36" \
    "build-tools;36.0.0" \
    "ndk;28.2.13676358" \
    "cmake;3.22.1" \
    "emulator" \
    "system-images;android-28;default;x86_64"
}

write_local_props() {
  mkdir -p "$HOME/.gradle"
  if ! grep -q '^includeAndroid=true' "$HOME/.gradle/gradle.properties" 2>/dev/null; then
    echo "includeAndroid=true" >> "$HOME/.gradle/gradle.properties"
  fi
  if [[ ! -f "$ROOT/local.properties" ]] || ! grep -q '^sdk.dir=' "$ROOT/local.properties"; then
    echo "sdk.dir=$SDK" > "$ROOT/local.properties"
  fi
}

ensure_avd() {
  if ! avdmanager list avd | grep -q "Name: $AVD_NAME"; then
    echo "no" | avdmanager create avd \
      -n "$AVD_NAME" \
      -k "system-images;android-28;default;x86_64" \
      -d pixel \
      --force
  fi
}

emulator_accel_args() {
  if [[ "${EMULATOR_ACCEL:-}" == "off" ]]; then
    echo "-accel off"
    return
  fi
  if [[ -e /dev/kvm && -r /dev/kvm && -w /dev/kvm ]]; then
    echo "-accel on"
  else
    echo "-accel off"
  fi
}

start_emulator() {
  if adb devices | awk 'NR>1 && $2=="device" {found=1} END {exit !found}'; then
    log "Using already-booted emulator"
    return
  fi
  local accel
  accel="$(emulator_accel_args)"
  log "Starting emulator $AVD_NAME ($accel)"
  # shellcheck disable=SC2086
  emulator -avd "$AVD_NAME" \
    -no-snapshot-save -no-window -gpu swiftshader_indirect \
    -noaudio -no-boot-anim -camera-back none \
    $accel >/tmp/dca-emulator.log 2>&1 &
  echo $! > /tmp/dca-emulator.pid
  adb wait-for-device
  local i
  for i in $(seq 1 90); do
    if [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
      log "Emulator booted (${i}0s window)"
      adb shell settings put global window_animation_scale 0
      adb shell settings put global transition_animation_scale 0
      adb shell settings put global animator_duration_scale 0
      # TCG is slow enough that System UI ANRs during first compose. Give it
      # time, then tap Wait if the dialog is already up.
      sleep 15
      adb shell input tap 540 1100 || true
      return
    fi
    sleep 10
  done
  echo "emulator did not boot; last log:" >&2
  tail -50 /tmp/dca-emulator.log >&2
  exit 1
}

assemble() {
  local delay=15 attempt
  for attempt in 1 2 3 4 5 6; do
    log "assemble attempt #$attempt"
    if ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest \
      -PincludeAndroid=true \
      -Pandroid.injected.build.abi=x86_64 \
      --no-daemon; then
      return 0
    fi
    if [[ "$attempt" -eq 6 ]]; then
      echo "assemble failed after all retries" >&2
      exit 1
    fi
    log "assemble failed; sleeping ${delay}s (Maven 429 backoff)"
    sleep "$delay"
    delay=$((delay * 2))
    if [[ "$delay" -gt 300 ]]; then delay=300; fi
  done
}

run_test_and_pull() {
  # Gradle's connected installer uses split/incremental install, which failed
  # on this 208 MB x86_64 APK ("Failed to install-write all apks"). Streamed
  # `adb install --no-incremental` is the path the 2026-10-13g launch smoke used.
  adb install -r -t -d --no-incremental "$APP_APK"
  adb install -r -t -d --no-incremental "$TEST_APK"
  local instr="/tmp/dca-instrument.log"
  adb shell am instrument -w -r \
    ai.droidcommand.app.test/androidx.test.runner.AndroidJUnitRunner \
    | tee "$instr"
  mkdir -p "$OUT_DIR"
  adb pull "$DEVICE_DIR/." "$OUT_DIR/" || true
  log "Screenshots in $OUT_DIR:"
  ls -la "$OUT_DIR"/*.png || true
  if grep -q 'FAILURES!!!' "$instr"; then
    echo "instrumented walk reported FAILURES!!! — keep whatever PNGs were pulled" >&2
    exit 1
  fi
}

if [[ "${SKIP_SDK_INSTALL:-0}" != "1" ]]; then
  install_sdk
fi
write_local_props
if [[ "${SKIP_ASSEMBLE:-0}" != "1" ]]; then
  assemble
fi
ensure_avd
start_emulator
run_test_and_pull
log "Done. Tool backends were not executed for real (Null* stubs)."
