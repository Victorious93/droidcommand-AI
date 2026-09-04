# DroidForge AI

An Android AI-agent platform in early development. See
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the target architecture
and an honest, per-component status (IMPLEMENTED / PARTIAL / PLANNED /
CONFIGURATION-DEPENDENT), and [`docs/CORE_BUILD.md`](docs/CORE_BUILD.md)
for the build/workspace pipeline specifically — this README will be
rewritten to describe the finished product only once there is a finished
product to describe.

## Current status

Nine pure-Kotlin/JVM modules are implemented and tested:

- `core-agent` — agent state machine, tool interface/registry/bounded-retry
  executor, conversation context, the `Planner` contract, a bounded Forge
  objective loop (`ObjectiveEngine`), and `DroidForgeSession`, which
  coordinates Pilot Mode and Forge Mode over that shared infrastructure and
  rejects a mode switch attempted while a task is active.
- `core-llm` — provider-independent LLM request/response/error types, the
  `LlmProvider` interface, and `LlmPlanner` (a `Planner` implementation
  backed by an `LlmProvider`). No concrete provider (Anthropic, an
  OpenAI-compatible endpoint, a local model) is implemented yet — every test
  runs against a scripted fake provider, not a live model.
- `core-security` — `SecurityPolicy`/`SecurityPolicyEnforcer` decide whether
  a tool invocation is allowed, needs explicit approval, or is denied
  (root/permission requirements), and `SecureToolExecutor` enforces that
  decision before a tool ever runs: a denied tool is never invoked, no
  matter what an LLM or planner requested. Root/permission checks are
  injected functions, tested with fixtures — real on-device root detection
  and Android permission grants remain PLANNED (need `core-root` /
  `core-tools-android` and a real device).
- `core-config` — `LlmConfigLoader`/`SecurityPolicyLoader` build `core-llm`'s
  and `core-security`'s config objects from a `ConfigSource` (environment
  variables by default). No secret is ever held as a plain field: an LLM
  API key is read from the source fresh on every call, not captured at load
  time — verified by a test that changes the underlying value between two
  calls and checks each call sees the current one. Only in-process sources
  exist; a real Android-backed source (e.g. `EncryptedSharedPreferences`)
  remains PLANNED.
- `core-remote` — the first module with a genuinely working implementation,
  not just an interface plus fakes: `JdkHttpTransport` is a real HTTP
  client (`java.net.http`), tested against a real local `HttpServer` on
  loopback (127.0.0.1) — a real network round trip and a real timeout,
  never leaving the sandbox. That test actually caught a real bug this
  session (HTTP header names are case-insensitive; the first version
  wasn't, and the round-trip test failed for real until it was fixed).
  `RemoteEndpoint` only ever resolves a relative path against its own
  configured host, so a request can't be aimed elsewhere by construction,
  and `RemoteClient` reuses `core-agent`'s `RetryPolicy` to retry 5xx/I-O
  failures while never retrying a 4xx. `RemoteClientIntegrationTest`
  proves the retry path against a real local server too (503, 503, then
  200). Still PLANNED: TLS pinning/mTLS beyond "must be HTTPS", and any
  concrete `LlmProvider` or build-server client actually built on top of
  this transport.
- `core-build` — the workspace/build-pipeline foundation for Forge Mode
  (`BuildRequest → WorkspaceManager → BuildPipeline → BuildExecutor →
  BuildResult → Artifact`). `WorkspaceManager` does real, path-secured
  filesystem work (creates/imports/cleans real directories under an
  explicitly authorized root, rejects traversal and absolute-path
  escapes), `BuildPipeline` orchestrates seven explicit stages each with
  its own success/failure semantics, and `MockBuildExecutor` is the only
  `BuildExecutor` — it never performs a real build, and its default
  outcome says so explicitly. `BuildTool` routes through `core-security`'s
  real `SecureToolExecutor`, proven by a test where a denied approval
  means no workspace is ever created on disk. Two real bugs were caught
  and fixed by this module's own tests before shipping (a workspace
  lifecycle gap, and an artifact-path containment check that was missing
  entirely) — see `docs/CORE_BUILD.md`. No real `BuildExecutor`
  (Android/local-process/remote) exists yet — that needs an Android SDK,
  JDK, Gradle, and/or a real build server this environment doesn't have.
- `core-tools-android` — device-control `Tool`s (tap/swipe/type/pressKey/
  launchApp/findElement/tapElement/getUiTree/listInstalledApps/
  takeScreenshot), a device-agnostic UI-tree domain model
  (`UiNode`/`UiTree`/`Selector`) with real query logic behind it, and
  `DeviceController` — the extension point for real device control,
  mirroring `core-build`'s `BuildExecutor` role for compilation.
  `NullDeviceController` is the only implementation, and unlike
  `MockBuildExecutor` it has no coherent "success" default: every method
  fails explicitly ("no real device is connected") rather than fabricating
  a successful tap or UI read. Tap/swipe/type/pressKey/launchApp are
  `SENSITIVE` and route through `core-security`'s real
  `SecureToolExecutor`, proven by a test where a denied tap never reaches
  the device controller. A real Android-backed `DeviceController` remains
  PLANNED — it needs the Android SDK to even compile against (real
  Accessibility/`PackageManager` APIs) plus a connected/emulated device.
- `core-shell` — the one module so far where a real implementation was
  the *only* honest choice: spawning a subprocess is a plain JVM
  capability, not an Android-only one, so unlike `core-build`'s
  `MockBuildExecutor` or `core-tools-android`'s `NullDeviceController`,
  `ProcessBuilderShellExecutor` genuinely runs real commands. Every
  command is passed as a plain argument vector — never `sh -c "..."` —
  so shell-metacharacter injection is impossible by construction, not
  merely discouraged. Fail-closed by default: an empty executable
  allow-list means nothing runs until explicitly permitted, and the same
  applies to working-directory overrides. Tested against real
  subprocesses (`echo`, `true`, `false`, `sleep`, `pwd`, `env`, `seq`) —
  a real enforced timeout, real cancellation, real working-directory
  containment, real output truncation. `ShellTool` routes through
  `core-security`'s real `SecureToolExecutor` exactly like the other
  device/build tools, proven with the real executor: a denied command
  provably never spawns a process.
- `core-apk-lifecycle` — the same pattern as `core-build`/`core-tools-android`
  applied to deployment: `ApkLifecyclePipeline` consumes an
  already-completed `core-build.BuildResult` (building and deploying are
  separate concerns) and orchestrates select-artifact → install → launch
  → (best-effort) collect logs → (optional) test → result through an
  `ApkLifecycleExecutor` extension point. A log-collection failure is
  just a warning — the app installed and launched, which is what
  actually matters; a failed *test harness* (as opposed to a test that
  ran and failed) is a real pipeline failure, since it means the intended
  work never happened. `NullApkLifecycleExecutor` is the only
  implementation — every method fails explicitly rather than fabricating
  a successful install or launch. `ApkLifecycleTool` routes through
  `core-security`'s real `SecureToolExecutor`, proven the same way as the
  other tools: a denied deployment never reaches the executor. A real
  `adb`-backed executor remains PLANNED — needs a connected/emulated
  device this environment does not have.

Everything else described in the architecture doc — the Android app shell,
root execution, a real LLM provider, a real build executor, a real device
controller, and a real adb-backed APK lifecycle executor — is not yet
built.

```
./gradlew test
```
