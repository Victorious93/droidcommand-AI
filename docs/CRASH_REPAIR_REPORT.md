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

Everything below ran on **x86_64 emulators under software emulation (no `/dev/kvm`)**, which is 10 to 50 times slower
than a phone. An x86_64 run proves nothing about arm64 native code or a real GPU. Raw outputs are in
`docs/evidence/2026-11-13-crash-repair/`.

### Device / API / ABI matrix

| Target | API | ABI | Result |
|---|---|---|---|
| Local emulator, clean install of main `25c202c` (baseline) | 28 | x86_64 | **FAIL, crash on cold start** (section 3) |
| Same emulator, in-place `adb install -r` of 0.1.7 over that install (versionCode 1 to 107, same signer) | 28 | x86_64 | Installed, `firstInstallTime` preserved, launched. Little user data existed (the baseline crashed before writing any), so this proves the installer path and not a data migration. |
| Local emulator, final source: 6 required instrumented tests | 28 | x86_64 | **PASS** 6 of 6, `INSTRUMENTATION_CODE -1`, `OK (6 tests)` |
| Local emulator, cold-start fault matrix, 2 cold starts x 11 scenarios | 28 | x86_64 | **PASS** 22 of 22 (resume 9.4 to 12.7 s) |
| Local emulator, 10-minute UI soak (`SoakTest`, 5 tabs one step at a time) | 28 | x86_64 | **PASS** 607.6 s, no `FATAL EXCEPTION`, no ANR |
| Local emulator, 22 repeated cold starts plus the 10-launch test | 28 | x86_64 | **PASS** (the matrix runs and `repeatedActivityLaunchesStayAlive`); this is more than the ten cold starts asked for |
| GitHub Actions, hardware-accelerated emulator, same gate, commit `14a3293` | 28 | x86_64 | **success** (the validation-summary artifact is only written after every check passed; I did not read the log line) |
| Local emulator, cold start control and 45 s-blocking provider | 35 | x86_64 | **PASS** 16.9 s and 19.1 s to resume |
| Local emulator, instrumented tests | 35 | x86_64 | **NOT RUN, BLOCKED**: the system killed the runner twice before any test started (see "Android 15 limitation") |
| Physical phone | any | arm64-v8a | **BLOCKED**: none available. The delivered APK itself was never executed. |

### Fail-before / pass-after

| Defect | Before | After |
|---|---|---|
| Shortcut crash | Real runner log: crash, code 0, adb exit 0. New verifier: FAIL. Old gate: would pass. `LauncherShortcutsTest`: 1 of 2 fail. | `MainActivityLaunchTest` passes; `LauncherShortcutsTest` 2 of 2. |
| Trust hint over the tabs | `everyPrimaryTabOpensAndStaysUsable` timed out tapping a tab | passes |
| Main-thread provider wait | 45 s provider: resumed after **56,819 ms** (control 9,516 ms), FAIL | **10,403 ms** (control 10,744 ms), PASS. Earlier measurement with a 20 s provider: both `FleetTheme` log lines came from the main thread, 20.3 s apart. |

### Other checks

| Check | Result |
|---|---|
| `:app:testDebugUnitTest` | PASS, 279 tests, 0 failures (includes the 2 new ones) |
| `:core-security:test` | PASS, 305 tests, 0 failures |
| `:app:ktlintCheck` (includes the `androidTest` source set), `:core-security:ktlintCheck` | PASS |
| `scripts/ci/test_verify_instrumentation.py` | PASS, 17 tests (two use real captured runner logs) |
| `scripts/ci/verify-apk-identity.sh` on the 0.1.7 arm64 APK | PASS; on the v0.1.6 asset it FAILS (version, tag, versionName), as intended |
| Full JVM `./gradlew test` and `ktlintCheck` | Not run locally. GitHub `CI` workflow: success on `14a3293` |
| Android Lint | Not run |

### Monkey runs (kept, not hidden)

Two random-touch `monkey` runs on API 28 stopped with "System appears to have crashed" (events 17 and 49): the system raised an
input-dispatch ANR while the app's main thread was `Runnable` inside Compose composition or draw. Idle CPU afterwards was
0.0 % with 4 frames rendered in 60 s, so there is no continuous redraw. **[Likely]** this is a slow emulated CPU receiving
input faster than it can draw, **not proven**; I cannot rule out that a first composition is genuinely heavy. It is why the
soak above drives the UI one step at a time and waits for idle. Nobody should read the soak PASS as "monkey passed".

### Android 15 limitation

On API 35 both instrumented runs were killed by the system before any test ran. Run 1: `am_kill ... bg anr` while load average
was 19.6 (first boot, `com.android.phone` also being killed). Run 2, at load 2: `ANR in ai.droidcommand.app, Reason: Process
... failed to complete startup`. The stack at that moment was inside `handleBindApplication`, `AndroidJUnitRunner.onCreate`,
`ServiceLoader` scanning the 217 MB APK's zip directory: **the app's own code had not run**. The new verifier rejected both
runs, correctly. Running the app directly (no test runner) on API 35 worked, so API 35 evidence is cold start only, not the
instrumented suite.

## 7. Release validation changes

* `scripts/ci/verify-instrumentation.py` replaces the `FAILURES!!!` grep. Rejects: crashed process, runner error, missing final
  code, zero tests, started-but-unfinished tests, assertion failures, `numtests` or `OK (N tests)` mismatch, a required test not
  passing, ignored tests. Screenshots are a separate best-effort step.
* Harness regression cases: crashed mid-test, crashed before tests, zero tests, assertion failure, runner missing, truncated,
  success, plus the real crash log and the real passing log. `OldGateRegression` pins that the old grep accepted five of them.
* Publishing (`android-apk.yml`): `publish-release` needs `apk` and `validate` (the emulator workflow on the same commit), a
  successful `ci.yml` for the commit, and a validation summary naming the commit. Release notes are built from the summary.
  **Not yet exercised on Actions**: no release was published (not asked). The pieces that run on every PR did run.
* Two bugs in my own new identity check were found by the first Actions run and fixed (newer `apksigner` wording; 32-bit ABIs).

## 8. Artifact

| Item | Value |
|---|---|
| File | `dist/app-arm64-debug.apk` (not committed: 216 MB) with `dist/SHA256SUMS` |
| SHA-256 | `35268faadc5af0623e67461a838f09a6adda30a0856366751cbf5145a1c31483` |
| Built from | commit `8a5fe823e5255284ac34139d91a85ee917a59a1e`; `git diff 8a5fe82 HEAD` shows no change to `app/src/main`, any `core-*`, or Gradle files, only tests, CI scripts and docs |
| Identity | `ai.droidcommand.app`, versionName `0.1.7`, versionCode `107`, targetSdk 36, minSdk 28, `arm64-v8a` only, debuggable |
| Signer | **debug-signed** with DroidCommand's own debug key, SHA-256 `88:00:51:A6:...:C8:4D`, the same key as v0.1.6, so it installs over it. No release key exists in the repo or was invented. |
| 16 KB alignment | all 15 `arm64-v8a` libraries `0x4000` |
| What was run | The **x86_64 build of the same source** was run on emulators. **This arm64 file was not executed anywhere.** |

## 9. What is not proven, and the smallest evidence I need

* That this is the crash on the owner's phone. One of these settles it (any one):
  `adb logcat -b crash -d` right after a crash on v0.1.6, or the exception line from the crash dialog's "App info".
  If it is not `Manifest shortcut ID=... may not be manipulated via APIs`, I have found a real bug but not yours.
* Anything on arm64 or a real GPU (llama.cpp, Vulkan, sherpa-onnx loading). Only alignment was checked statically.
* The Android 15 instrumented suite and a real 16 KB page-size device.
* Shizuku live, VictorLaunch or VictorSuite really installed, PC-hosted models, on-device models. Inference policy (Chat, Plan,
  Pilot/Forge, assist pop-ups, "Remember this chat"), tool approvals and audit logging: **no code in those paths was changed**,
  and their existing unit tests are in the green JVM suite, but none was exercised on a device in this work.
* Offline mode and unreachable PC servers were not tested on an emulator.
* A data-preserving upgrade from a build that really held user data. No Room schema changed since v0.1.3 (static), and malformed
  persisted files are survived (tested), but an upgrade from v0.1.3 with real conversations was not run.

## 10. Found, not fixed

* `DeviceConnectRuntime.startIfKeptOn()` can never resume the LAN listener after a process restart: the pairing password it needs
  exists only in memory (`DeviceConnectTransport.pendingPassword`). "Keep waiting" silently does nothing. No crash.
* `hs_err_pid15759.log` (a JVM crash log from an earlier session's host harness) is tracked in the repo root since v0.1.6.
* `ChatViewModel.init` launches `refreshLocalModels()` on `Dispatchers.IO` with no handler; an exception there would kill the app.
  I found no input that throws (the registry decoder catches `RuntimeException`), so I left it.

## 11. Commands

```
./gradlew :app:assembleDebug -PincludeAndroid=true -PabiFilter=arm64-v8a            # the delivered APK
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PincludeAndroid=true -Pandroid.injected.build.abi=x86_64   # emulator build
./gradlew :app:testDebugUnitTest :app:ktlintCheck :core-security:test :core-security:ktlintCheck -PincludeAndroid=true
python3 -I scripts/ci/test_verify_instrumentation.py
scripts/ci/verify-apk-identity.sh dist/app-arm64-debug.apk v0.1.7
bash scripts/ci/emulator-smoke.sh            # on a running emulator (APP_APK / TEST_APK override the paths)
adb shell am instrument -w -r -e class ai.droidcommand.app.SoakTest#cyclesPrimaryTabsForTheRequestedMinutes -e soakMinutes 10 ai.droidcommand.app.test/androidx.test.runner.AndroidJUnitRunner
```
Maven Central answered HTTP 429 through this container's proxy; builds used a retry loop (Gradle caches what it fetched).
