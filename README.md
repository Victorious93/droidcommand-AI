# DroidForge AI

An Android AI-agent platform in early development. See
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the target architecture
and an honest, per-component status (IMPLEMENTED / PARTIAL / PLANNED /
CONFIGURATION-DEPENDENT), and [`docs/CORE_BUILD.md`](docs/CORE_BUILD.md)
for the build/workspace pipeline specifically — this README will be
rewritten to describe the finished product only once there is a finished
product to describe.

## Current status

Thirteen pure-Kotlin/JVM modules are implemented and tested:

- `core-agent` — agent state machine, tool interface/registry/bounded-retry
  executor, conversation context, the `Planner` contract, a bounded Forge
  objective loop (`ObjectiveEngine`), and `DroidForgeSession`, which
  coordinates Pilot Mode and Forge Mode over that shared infrastructure and
  rejects a mode switch attempted while a task is active.
- `core-llm` — provider-independent LLM request/response/error types, the
  `LlmProvider` interface, and `LlmPlanner` (a `Planner` implementation
  backed by an `LlmProvider`). No local-model-specific provider is
  implemented yet, but `core-llm-anthropic` and `core-llm-openai` now
  supply real ones — every `LlmPlanner` test still runs against a scripted
  fake provider, since that's what proves the planner's own logic in
  isolation.
- `core-security` — `SecurityPolicy`/`SecurityPolicyEnforcer` decide whether
  a tool invocation is allowed, needs explicit approval, or is denied
  (root/permission requirements), and `SecureToolExecutor` enforces that
  decision before a tool ever runs: a denied tool is never invoked, no
  matter what an LLM or planner requested. Root/permission checks are
  injected functions — now exercised end-to-end against a real `Tool` by
  `core-root`'s integration test — but real on-device root detection and
  Android permission grants remain PLANNED (need a real device).
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
  200). Still PLANNED: TLS pinning/mTLS beyond "must be HTTPS", and a
  build-server client actually built on top of this transport —
  `core-llm-anthropic` is now the first real consumer, described next.
- `core-llm-anthropic` — the first real `LlmProvider`: `AnthropicLlmProvider`
  encodes/decodes Anthropic's actual Messages API JSON shape
  (`kotlinx.serialization`) over `core-remote`'s real `RemoteClient` and
  `JdkHttpTransport`, the same "real capability, so build it for real"
  reasoning that made `core-shell`'s subprocess executor real rather than
  mocked. The API key is sent as an `x-api-key` header — Anthropic doesn't
  use `Authorization: Bearer` — read fresh from `LlmConfig.authToken()` on
  every call and never cached; a missing key fails closed with
  `LlmError.Authentication` before any request is sent, proven by a test
  asserting zero bytes reach the server. HTTP status codes map to
  `LlmError` variants (401/403 → `Authentication`, 429/5xx →
  `ModelUnavailable`, other non-2xx → `InvalidResponse`), and a malformed
  response body becomes `LlmError.InvalidResponse` rather than throwing.
  `AnthropicLlmProviderIntegrationTest` proves all of this against a real
  local `HttpServer` speaking Anthropic's JSON shape — text responses,
  tool-use responses (including a nested-object input value flattened to
  compact JSON text, not dropped), and the `SYSTEM`/`TOOL` role-folding
  this module does because `Message` carries no `tool_use_id` and
  `ToolSpec` carries no parameter schema, both documented as explicit,
  honest simplifications rather than silent gaps. This has never been run
  against the real `api.anthropic.com` — this environment has no LLM
  credentials — so the JSON shape is modeled from Anthropic's published
  API, not verified against a live response.
- `core-llm-openai` — a second real `LlmProvider`, mirroring
  `core-llm-anthropic` structurally but speaking the OpenAI Chat
  Completions API shape (`POST /v1/chat/completions`) instead — the shape
  OpenAI itself serves and that most self-hosted "OpenAI-compatible"
  servers (Ollama, vLLM, LM Studio, llama.cpp's server) implement too, so
  pointing `config.endpoint` at a local server is the expected case here,
  not an edge case. One deliberate difference from `core-llm-anthropic`:
  this provider genuinely uses `RemoteClient`'s built-in
  `Authorization: Bearer` auth, since OpenAI's real API accepts it (unlike
  Anthropic's `x-api-key` requirement), and a missing API key is *not*
  rejected up front — many self-hosted OpenAI-compatible servers accept
  requests with no key at all, so failing closed there would misrepresent
  what this shape actually requires. Same status-code → `LlmError` mapping
  and the same two documented simplifications (open tool schema, `TOOL`
  role mapped to `user`) as `core-llm-anthropic`. Tested the same way,
  against a real local `HttpServer` — never a live call to `api.openai.com`
  or a real self-hosted server.
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
- `core-root` — closes a loop left open since `core-security` was first
  built: its `rootEnabled`/`rootAvailable` gate existed for several
  increments but was never exercised end to end against a real `Tool`
  until `RootTool` existed to test it with. `RootTool` declares
  `requiresRoot = true` and `SecurityLevel.ROOT`; `core-security`'s
  `SecurityPolicyEnforcer` denies it outright — no prompt, not even an
  approval opportunity — unless the session's `rootEnabled` is true *and*
  `rootAvailable()` reports true, only asking for explicit approval after
  both hold. `PolicyEnforcingRootExecutor` adds a second, narrower
  fail-closed executable allow-list beneath that session-level gate — a
  command outside it never reaches the delegate. `NullRootExecutor` is
  the only `RootExecutor`: `isRootAvailable()` truthfully returns false,
  and `execute()` fails explicitly rather than fabricating a successful
  elevated command. `RootToolSecureExecutorIntegrationTest` runs the full
  root test matrix (root disabled, root unavailable, user denies,
  approved-and-executed, command failure) against real `core-security`
  code. A real rooted-device executor remains PLANNED — needs an actual
  rooted device this environment does not have.

Everything else described in the architecture doc — the Android app shell,
a local-model-specific LLM provider, a real build executor, a real device
controller, a real adb-backed APK lifecycle executor, and a real
rooted-device executor — is not yet built.

```
./gradlew test
```
