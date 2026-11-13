# DroidCommand AI v0.1.7 (release candidate, debug-signed) — DRAFT, NOT PUBLISHED

**Fixes an app that crashed on every launch in v0.1.4, v0.1.5 and v0.1.6.** Debug-signed pre-release for sideloading
(DroidCommand's own debug key, the same as v0.1.6, so it installs over it). Not Play Store, not production-signed.

## What changed
- **Launch crash fixed.** The app registered launcher shortcuts that clash with the ones declared in the manifest; Android rejected
  that and the app died as soon as it opened. The three shortcuts (Chat, Toolbox, Notes) still work, from the manifest.
- Without VictorLaunch installed, a "Trust installed fleet share apps" hint no longer sits over the bottom tab bar on every launch.
- A slow or stuck VictorLaunch can no longer freeze the app's start-up.
- Version is now 0.1.7 (versionCode 107). Earlier releases all reported versionCode 1.

## Checks that were actually run (x86_64 emulators, software-emulated, Android 9 and 15)
- Android 9 (API 28): 6 of 6 required instrumented tests, a 22-run cold-start fault matrix (VictorLaunch / VictorSuite
  providers absent, throwing, returning nothing or bad data, or blocking), a 10-minute UI soak: all passed.
- Android 15 (API 35): cold start with and without a blocking provider passed. The instrumented tests could not run there.
- `:app` 279 unit tests, `:core-security` 305, ktlint: passed. The CI workflows passed on the pull request.

## Not checked
- **This arm64 APK was never run on any device or emulator.** The checks above used an x86_64 build of the same source.
- No physical phone, no arm64 native-library execution, no GPU backend, no live Shizuku, VictorLaunch, VictorSuite, PC-hosted or
  on-device model run in this work. Many backends are still stubs.
- Upgrade from a build that held real conversations was not run (no database schema changed since v0.1.3).

## Install
`sha256sum -c SHA256SUMS`, then install over the existing app. Do not uninstall or clear data. If it still crashes, run
`adb logcat -b crash -d` and send the exception line.

SHA-256 of `app-arm64-debug.apk`: `35268faadc5af0623e67461a838f09a6adda30a0856366751cbf5145a1c31483`
(built from commit `8a5fe823e5255284ac34139d91a85ee917a59a1e`).
