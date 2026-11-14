# DroidCommand AI v0.1.8 (debug-signed pre-release)

Bug-fix release on top of v0.1.7. Debug-signed for sideloading with DroidCommand's own debug key (same signer as v0.1.6 and
v0.1.7, so it installs over them). Not Play Store, not production-signed.

## What changed
- **Connect devices: "Keep waiting on this Wi-Fi" no longer fails silently after the app restarts.** The pairing password is
  intentionally kept only in memory (it is the pairing secret), so after Android kills the app the listener cannot resume on
  its own. The Connect screen now says so and asks for the password again, instead of showing the switch on while nothing
  is listening.
- **Connect devices: opening the screen can no longer crash the app** when the pairing port (17890) is already in use; it now
  shows "Could not wait on this Wi-Fi" instead of throwing on the main thread.
- Version 0.1.8 (versionCode 108).

## Checks
See the GitHub Actions runs linked from the release (JVM tests, Android unit tests, ktlint, APK identity, emulator gate on
the same commit). Not run: any physical device, arm64 execution of the delivered APK, a real two-device pairing.

## Install
`sha256sum -c SHA256SUMS`, then install over the existing app. Do not uninstall or clear data.
