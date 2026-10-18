# Signing

## Debug

`signing/droidcommand-debug.keystore` is **DroidCommand AI's own debug signing identity**. It is not a secret, not a release key, and **not a shared fleet keystore**.

- Alias: `androiddebugkey`
- Store/key password: `android`
- Algorithm: RSA 2048
- Distinguished name: `CN=DroidCommand Debug`
- Certificate SHA-256: `88:00:51:A6:D9:5D:38:DD:8D:81:E1:93:1E:17:05:48:03:87:D5:93:C3:09:43:53:99:68:E6:EF:11:A0:C8:4D`

`:app`'s debug `signingConfig` points at this file (`app/build.gradle.kts`). Every CI debug APK and every local `./gradlew :app:assembleDebug` build should carry that fingerprint, so a new debug build installs over an older one instead of failing with **App not installed**.

**One-time uninstall:** debug APKs built before this keystore landed used the default Android debug key (a random per-CI-runner identity). Those cannot be updated in place. Uninstall the old `ai.droidcommand.app` debug APK once, then install a build signed with this key.

## Release

Release keys are **not** in this repository. They stay in CI secrets (or a private store) and are not configured in `app/build.gradle.kts`. Do not reuse this debug keystore — or any fleet companion key — for release builds.

The intended shared-fleet RELEASE SHA-256 (`9E:A7:B3:14:94:5B:0A:62:97:15:6F:F7:06:E7:0C:0A:B1:B7:24:DE:06:D5:09:9C:A5:12:8A:B4:43:F7:2D:8D`) is firsthand verified for **HackerAI-Commander only**. For DroidCommand-AI itself it remains **[planned/unverified]** — documented on `KnownNodeSigningCatalog.SHARED_FLEET_RELEASE_SHA256`, not wired into `ai.droidcommand.app`'s expected certs. See `docs/FLEET_CONTROL.md`.
