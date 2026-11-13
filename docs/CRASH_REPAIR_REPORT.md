# Crash repair report: DroidCommand AI v0.1.4 to v0.1.6 launch crash (2026-11-13)

Branch `ccr-82520757-orluts`, release candidate version `0.1.7` (versionCode `107`). Labels: **[Certain]** observed
directly in this session, **[Likely]** strong inference, **[Guessing]** assumption. Nothing here was run on the
owner's phone; see "What is not proven".

## 1. Verdict

* **Confirmed cause [Certain]:** `MainActivity.onCreate` called `ShortcutManager.setDynamicShortcuts` with the ids
  `chat`, `toolbox` and `notes`. `app/src/main/res/xml/shortcuts.xml` already declares those three ids as *manifest*
  shortcuts. The platform refuses that with
  `IllegalArgumentException: Manifest shortcut ID=notes may not be manipulated via APIs`; nothing caught it, so the
  process died on **every cold start**. It entered with PR #291 (`bd1a724`) and is present in v0.1.4, v0.1.5 and v0.1.6.
* **Why CI said it was fine [Certain]:** the emulator gate grepped for `FAILURES!!!` and trusted adb's exit status.
  A crashed instrumented process prints neither. The log of the "green" run on `25c202c` contains
  `INSTRUMENTATION_STATUS_CODE: -2`, `INSTRUMENTATION_RESULT: shortMsg=Process crashed.`, `INSTRUMENTATION_CODE: 0` and
  the same stack; its screenshot step then found no files.
* **Is it the owner's crash? [Likely], not [Certain].** It is deterministic on API 28 (reproduced), the code path is
  AOSP `ShortcutService` and not device specific, and the symptom (newest release crashes every time) fits. No log from
  the owner's device was available. The commands in section 9 will settle it in one minute.
* **The theme fix on `main` (`25c202c`) did not address this [Certain].** It guards a different call. The clean-install
  crash reproduced on a build that already contained it.

## 2. Baseline

| Item | Value |
|---|---|
| Failing release | `v0.1.6`, tag commit `9cba5486c9eaabbbfe1c94e5af08303d08d93bcf`, asset `app-arm64-debug.apk` |
| Asset checksum (re-downloaded, matches the release `SHA256SUMS`) | `20d1347850932c73db70c0f46ec8d75f40a18e0e5a4346947c73c26ad697853a` |
| Asset identity | `ai.droidcommand.app`, versionCode `1`, versionName `0.1.0-phase0`, targetSdk 36, minSdk 28, `arm64-v8a` only, debuggable |
| Signer | `CN=DroidCommand Debug`, SHA-256 `88:00:51:A6:D9:5D:38:DD:8D:81:E1:93:1E:17:05:48:03:87:D5:93:C3:09:43:53:99:68:E6:EF:11:A0:C8:4D`, identical to `signing/droidcommand-debug.keystore` and `docs/SIGNING.md` |
| Starting commit of this work | `25c202ca0c108e4d6abd0e8be0e8c7a4a45bb99a` (main) |
| Environment | Ubuntu container, JDK 21.0.12, Android SDK installed this session (platform 36, build-tools 36.0.0, NDK 28.2.13676358, CMake 3.22.1), emulator 37.2.13. **No `/dev/kvm`, no adb device, no phone.** |

All releases up to v0.1.6 shipped `versionCode 1`. v0.1.4 introduced the crash (`ShortcutPublisher` and
`shortcuts.xml` are both present at that tag), so three consecutive releases were unusable on launch.

## 3. Reproduction

On an API 28 x86_64 emulator, clean install of main `25c202c` (x86_64 build), `am start -n ai.droidcommand.app/.MainActivity`:

```
FATAL EXCEPTION: main   Process: ai.droidcommand.app
java.lang.RuntimeException: Unable to start activity ...MainActivity: java.lang.IllegalArgumentException:
    Manifest shortcut ID=notes may not be manipulated via APIs
  at android.content.pm.ShortcutManager.setDynamicShortcuts(ShortcutManager.java:326)
  at ai.droidcommand.app.shortcuts.ShortcutPublisher.publish(ShortcutPublisher.kt:30)
  at ai.droidcommand.app.MainActivity.onCreate(MainActivity.kt:54)
```

Trigger: opening the app, any time, with or without saved data, VictorLaunch, Shizuku or a model. Repeatable every time.
Distinguished from other process deaths: it is an uncaught exception on the main thread (`FATAL EXCEPTION`), not an ANR,
not a native crash and not a low-memory kill.

Evidence kept in `scripts/ci/fixtures/instrument/real_launch_crash_25c202c.txt` (the raw runner output of this crash).

## 4. Defects found and fixed

| # | Defect | Status | Fix | Regression test |
|---|---|---|---|---|
| 1 | Launch crash: dynamic shortcut ids collide with manifest shortcuts | **Confirmed [Certain]** | Delete `ShortcutPublisher` and its call. The dynamic shortcuts duplicated the manifest ones exactly (ids, labels, actions, target), so nothing is lost. `QuickAction` still routes the intents. | `LauncherShortcutsTest` (JVM): fails with the publisher present, passes without. Emulator: `MainActivityLaunchTest` fails before, passes after. |
| 2 | Without VictorLaunch the "Trust installed fleet share apps" hint showed on every launch, drawn over the bottom tab bar (tabs could not be tapped) | **Confirmed [Certain]** on API 28 | On API 28 `registerContentObserver` for a missing authority throws `SecurityException`; `AndroidFleetThemeClient.start()` reported that as "denied". Now a denial is reported only if the provider resolves. | `StartupResilienceTest.noTrustHintCoversTheTabBarWithoutVictorLaunch` and `everyPrimaryTabOpensAndStaysUsable` (the latter could not tap a tab before this fix). |
| 3 | `Application.onCreate` ran a synchronous query on another app's provider on the main thread | **Confirmed [Certain]** (main-thread log lines 20.3 s apart with a 20 s provider) | Theme refresh runs on a single daemon thread. | See section 6, "blocking provider". |
| 4 | Release gate accepted crashed, empty and incomplete runs | **Confirmed [Certain]** | `scripts/ci/verify-instrumentation.py` | `scripts/ci/test_verify_instrumentation.py` (14 tests incl. the real crash log). |
| 5 | Publishing depended only on the build; notes claimed an emulator launch the publishing job never did | **Confirmed [Certain]** by reading the workflow | `validate` job + gates + notes from the summary | Workflow YAML parses; not run on Actions (see blockers). |
| 6 | `versionCode 1` / `0.1.0-phase0` on every release | **Confirmed [Certain]** | versionCode `107`, versionName `0.1.7`, checked by `verify-apk-identity.sh` | Script rejects the v0.1.6 asset (run, section 8). |

## 5. Hypotheses investigated and ruled out

| Hypothesis | Result |
|---|---|
| Theme provider exception (`25c202c`) is the crash | Not the cause: crash reproduced with that guard present. Guard is still correct and kept. |
| 16 KB page-size native library load failure on Android 15+ | **Ruled out [Certain]** for the shipped asset: every `LOAD` segment of all 15 `.so` files is `0x4000` aligned and the APK passes `zipalign -c -P 16`. (Only a 4 KB emulator was available, so the *runtime* check on a 16 KB device was not run.) |
| Keystore / vault failure at startup | Not a crash path by reading: `EncryptedSecretsVault.getSecret` fails closed to `null`. Not fault-injected. |
| Room migration breaks saved data | **Ruled out [Certain]**: no schema JSON changed since v0.1.3 and all four databases are version 1. |
| New persisted files (`inference-policy.properties`, `pc-hosts.properties`, local-model registry, status tray) crash when malformed | **Ruled out [Certain]** on API 28: `StartupResilienceTest.survivesCorruptPersistedState` writes invalid UTF-8, a malformed `\u` escape and NULs into all four and the app stays usable. |
| Shizuku missing or revoked | Every Shizuku call in `AndroidShizukuHost` is guarded; app launched with no Shizuku. Not tested with a live Shizuku. |
| VictorSuite "My PC" provider throwing / malformed | Off by default; `AndroidPcProfileReader` guards every call. Fault-injected (throw, null, malformed): no crash. |
| `DeviceConnectRuntime.startIfKeptOn` crash | Not a crash. Separate functional bug, **not fixed**: see section 10. |

## 6. Android evidence

Targets actually used: **API 28, x86_64, software emulation (no KVM)**. An API 35 image was installed; see the matrix.
ABI note: an x86_64 run proves nothing about arm64 native code.

(Filled in from the final runs below.)

PLACEHOLDER_RESULTS
