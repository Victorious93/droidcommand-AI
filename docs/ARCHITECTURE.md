# DroidForge AI — Architecture Blueprint (Phase 2)

Status: DRAFT. Reflects the design adopted for implementation; components are
individually classified IMPLEMENTED / PARTIAL / PLANNED / CONFIGURATION-DEPENDENT
in Section 6 — do not read a component's presence in the diagram below as proof
it is built. Source of truth for what actually exists is the repository
itself. (The Phase 1 repository/provenance/licensing audit that preceded this
document was conducted and reported in the originating chat session; it found
the repository empty of code at the time, with no license or branding
findings to carry forward. It was not committed as a standalone file.)

## 1. Why this shape

Phase 1 found no existing code, no Droid Pilot/OpenDroid source, no license
obligations, and no branding to migrate. There is nothing to preserve or
reconcile — this is a greenfield design, not a migration. That removes an
entire category of risk (accidental license violation, silent behavior
regression) but means every capability below starts from PLANNED.

## 2. Module tree

```
DroidForge AI
│
├── app                      Android application module (UI shell, DI wiring)
│
├── core-agent               Pure Kotlin/JVM. Agent state machine, tool
│                            registry/executor, bounded retry policy,
│                            conversation context, Planner contract, the
│                            bounded Forge objective loop (ObjectiveEngine),
│                            and DroidForgeSession, which coordinates
│                            Pilot/Forge mode switching. No Android
│                            dependency — testable on any JVM.
│
├── core-llm                 Provider-independent chat/tool-call abstraction
│                            (LlmProvider, LlmRequest/Response, LlmConfig)
│                            plus LlmPlanner, an adapter implementing
│                            core-agent's Planner against an LlmProvider.
│                            No concrete provider (Anthropic/OpenAI-compatible/
│                            local) is implemented yet (PARTIAL — see
│                            Section 6).
│
├── core-tools-android       DeviceController (the extension point for
│                            actual device control — mirrors
│                            core-build.BuildExecutor), a device-agnostic
│                            UiNode/UiTree/Selector domain model, and Tool
│                            wrappers (tap/swipe/type/pressKey/launchApp/
│                            findElement/tapElement/getUiTree/
│                            listInstalledApps/takeScreenshot) gated by
│                            core-security exactly like core-build's
│                            BuildTool. NullDeviceController is the only
│                            implementation — every method fails
│                            explicitly ("no real device is connected"),
│                            never fabricating a successful tap or UI
│                            read. A real Android-backed controller
│                            (PLANNED) needs the Android SDK and a
│                            connected/emulated device this environment
│                            does not have.
│
├── core-shell               Unlike core-tools-android/core-build, a real,
│                            working ShellExecutor genuinely belongs here:
│                            spawning a subprocess is a plain JVM
│                            capability, not an Android-only one.
│                            ProcessBuilderShellExecutor runs a command as
│                            a plain argv vector (never `sh -c "..."`, so
│                            shell-metacharacter injection is impossible
│                            by construction), fail-closed by default
│                            (empty executable allow-list — nothing runs
│                            until explicitly permitted), with a real
│                            enforced timeout and real cancellation.
│                            Tested against real subprocesses (echo, true,
│                            false, sleep, pwd, env, seq), not mocked.
│
├── core-root                RootTool declares requiresRoot=true and
│                            SecurityLevel.ROOT, closing the loop on
│                            core-security's rootEnabled/rootAvailable
│                            gate (built in an earlier phase, never
│                            exercised end-to-end until now).
│                            PolicyEnforcingRootExecutor adds a second,
│                            narrower fail-closed allow-list layer beneath
│                            that session-level gate. NullRootExecutor is
│                            the only RootExecutor — isRootAvailable()
│                            truthfully returns false, and execute() fails
│                            explicitly rather than fabricating a
│                            successful elevated command. A real
│                            rooted-device executor (PLANNED) needs an
│                            actual rooted device this environment does
│                            not have.
│
├── core-build               BuildRequest -> WorkspaceManager -> BuildPipeline
│                            -> BuildExecutor -> BuildResult -> Artifact.
│                            WorkspaceManager, WorkspacePathValidator,
│                            BuildPipeline, SystemBuildEnvironmentDetector,
│                            and DryRunPlanner are real, working
│                            implementations against real java.nio.file
│                            operations — not fakes. MockBuildExecutor is
│                            the only BuildExecutor (never performs a real
│                            build; see docs/CORE_BUILD.md). No
│                            AndroidGradleBuildExecutor/
│                            LocalProcessBuildExecutor/RemoteBuildExecutor
│                            exists yet (PLANNED — needs Android SDK/JDK/
│                            Gradle or a real build server this
│                            environment does not have).
│
├── core-apk-lifecycle        ApkLifecyclePipeline consumes an already-
│                            completed core-build.BuildResult (building
│                            and deploying are separate concerns) and
│                            orchestrates select-artifact → install →
│                            launch → (best-effort) collect logs →
│                            (optional) test → result through an
│                            ApkLifecycleExecutor extension point — same
│                            pattern as BuildExecutor/DeviceController.
│                            NullApkLifecycleExecutor is the only
│                            implementation; every method fails explicitly
│                            rather than fabricating a successful install
│                            or launch. A real adb-backed executor
│                            (PLANNED) needs a connected/emulated device
│                            this environment does not have.
│
├── core-remote               RemoteEndpoint (HTTPS-by-default, path-only
│                            resolution so a request can never be aimed at
│                            an unintended host), HttpTransport +
│                            JdkHttpTransport (a real java.net.http-backed
│                            implementation, tested against a real local
│                            HTTP server on loopback), and RemoteClient
│                            (bearer-token auth, bounded retry reusing
│                            core-agent's RetryPolicy, 5xx retried / 4xx
│                            never retried). The one module so far with a
│                            genuinely working implementation, not only an
│                            interface plus fakes — see Section 5d.
│
├── core-security             SecurityPolicy, SecurityPolicyEnforcer, and
│                            SecureToolExecutor: authorizes a tool
│                            invocation (root/permission/confirmation)
│                            before it ever reaches ToolExecutor — a
│                            PolicyDecision.Deny means the tool is never
│                            invoked at all, regardless of what an LLM or
│                            planner requested. Root/permission checks are
│                            injected functions (rootAvailable,
│                            grantedPermissions), so the policy itself
│                            stays device-agnostic and fully testable.
│
└── core-config               ConfigSource/ConfigReader plus LlmConfigLoader
                             and SecurityPolicyLoader, which build
                             core-llm's LlmConfig and core-security's
                             SecurityPolicy from a key/value source. No
                             secret is ever held as a plain field — an API
                             key is read from the source fresh on every
                             authToken() call, not captured at load time.
```

`core-agent`, `core-llm`, `core-security`, `core-config`, `core-remote`,
`core-build`, `core-tools-android`, `core-shell`, `core-apk-lifecycle`, and
`core-root` are implemented so far because none has a *compile-time*
Android dependency (none of this code needs `android.jar`): `core-llm`'s tests use
a scripted fake provider rather than a live network call, `core-security`'s
root/permission checks are injected functions rather than real device
queries, `core-config`'s `EnvConfigSource` wraps `System.getenv` behind an
injectable function so its tests never read or depend on real process
environment, `core-remote`'s network tests talk only to a real HTTP server
bound to loopback (127.0.0.1) that the test itself starts and stops,
`core-build`'s workspace/pipeline tests run against real `java.nio.file`
temporary directories with a real (mock, not fabricated) executor (see
`docs/CORE_BUILD.md`, including two real bugs its own tests caught before
they shipped), `core-tools-android`'s `DeviceController` is only
implemented by `NullDeviceController`, which fails every method
explicitly rather than fabricating a successful tap, swipe, or UI-tree
read, `core-shell` is the one exception to the "nothing real" pattern:
spawning a subprocess doesn't need Android, so `ProcessBuilderShellExecutor`
is a real, working, fail-closed-by-default shell executor, tested against
real subprocesses (`echo`, `sleep`, `pwd`, `env`, ...) rather than mocked,
and `core-apk-lifecycle` follows `core-tools-android`'s pattern again:
`ApkLifecyclePipeline` is real orchestration logic (consuming an
already-completed `core-build.BuildResult`), but `NullApkLifecycleExecutor`
is the only `ApkLifecycleExecutor`, and it fails every install/launch/log/
test call explicitly rather than fabricating a successful deployment, and
`core-root` closes a loop left open since Phase 6/7: `core-security`'s
`rootEnabled`/`rootAvailable` gate has existed for several increments but
was never exercised end to end against a real `Tool` until `RootTool`
existed to test it with — `NullRootExecutor` truthfully reports root as
unavailable and fails every command explicitly, and
`PolicyEnforcingRootExecutor` adds its own fail-closed executable
allow-list beneath that session-level gate. Every other module is
scaffolding-only or not yet created — see Section 6.

## 3. Two-mode architecture

```
                    DROIDFORGE AI
                         │
              ┌──────────┴──────────┐
              │                     │
          PILOT MODE            FORGE MODE
              │                     │
       core-agent.Tool         core-agent.ObjectiveEngine, driven by
       Executor (direct,       a core-agent.Planner — core-llm.LlmPlanner
       single-step)            is the only implementation so far, and it
              │                talks to LlmProvider (no concrete provider
              │                implemented — PARTIAL, Section 6)
              │                     │
              └──────────┬──────────┘
                         │
              core-agent.ToolRegistry
                         │
              core-tools-android (Tool wrappers implemented; only
              NullDeviceController exists — see Section 6) / core-shell
              (implemented and real) / core-root (Tool + gate wiring
              implemented; only NullRootExecutor exists)
```

Both modes route through the same `ToolRegistry` and `ToolExecutor` so a tool
is written once and works in either mode. Pilot Mode invokes a single tool per
user instruction; Forge Mode wraps repeated tool invocations in an objective
loop (UNDERSTAND → PLAN → SELECT TOOLS → EXECUTE → OBSERVE → VALIDATE →
DIAGNOSE → FIX → REBUILD → RETEST → ITERATE → COMPLETE). The active mode must
be surfaced in the UI at all times (UI itself: PLANNED, Phase 15).

`DroidForgeSession` (`core-agent`) is the mode coordinator: it exposes
`mode: AgentMode` (`PILOT`/`FORGE`), `runPilotInstruction(...)`, and
`runForgeObjective(...)`, and it is the single place a mode switch is
rejected while a task is active. A switch attempted mid-task throws
`IllegalModeSwitch` rather than silently queuing or corrupting state, and
this is tested for real concurrent-looking behavior, not just documented
intent: a test drives an active Pilot task whose cancellation callback
itself attempts `switchMode(FORGE)` mid-execution and asserts it is
rejected, then asserts the same switch succeeds once the task has actually
finished. The lock guarding this is held only for the mode/active-flag
check, never across the task's own execution, so a switch attempt from a
genuinely different thread fails fast instead of blocking on a long-running
tool or objective loop.

## 4. Agent state machine (implemented in `core-agent`)

`AgentState` is a sealed hierarchy: `Idle`, `Planning`, `AwaitingApproval`,
`ExecutingTool`, `Observing`, `Recovering`, `Completed`, `Cancelled`,
`Failed`. Transitions are validated — an illegal transition (e.g.
`Completed → ExecutingTool`) throws rather than silently succeeding, which is
the mechanism that prevents an agent from being resumed after it has already
terminated. Retry is bounded by an explicit `RetryPolicy(maxAttempts, backoff)`
passed into the executor; there is no unbounded loop anywhere in this module.

## 4b. Objective engine and LLM abstraction (implemented, partial)

`ConversationContext` (`core-agent`) is an append-only, ordered message
history with an optional system prompt. `Planner` (`core-agent`) is the
contract an objective loop consults for its next step —
`InvokeTool`/`Complete`/`Abort` — and `ObjectiveEngine` (`core-agent`) drives
the Forge loop against it: each iteration re-enters `Planning`, asks the
planner, and either runs a tool through `ToolExecutor` or terminates. Agent
safety is enforced by `maxIterations` (default 25, validated `>= 1`): a
planner that never returns `Complete` or `Abort` cannot loop forever — the
engine gives up and transitions to `Failed` once the bound is hit. This is
verified directly, not just claimed: `ObjectiveEngineTest`'s
"never exceeds maxIterations" case runs a planner that always requests a
tool and asserts the tool was invoked exactly `maxIterations` times, no more.

`core-llm` defines the provider-independent side: `LlmRequest`/`LlmResponse`/
`LlmError`, and `LlmProvider`, an interface with no implementation yet.
`LlmConfig.authToken` is a function (`() -> String?`), not a stored string,
so no code path in this module can hold or serialize a credential.
`LlmPlanner` implements `core-agent.Planner` against an `LlmProvider`: a
tool-call response becomes `InvokeTool`, plain text becomes `Complete`
(the model considers the objective satisfied), and a provider error becomes
`Abort` rather than an uncaught exception. Because no real provider exists,
every test — including the end-to-end `ObjectiveEngineIntegrationTest`,
which drives `ObjectiveEngine` + `LlmPlanner` + a real registered `Tool`
through one full tool-call-then-complete cycle — runs against a scripted
fake `LlmProvider`. That proves the wiring, not a live model; see Section 6.

## 5. Build strategy decision

[Likely — recommendation, not yet validated against real build workloads]
Hybrid: Pilot Mode's device-control tools run on-device (Accessibility, shell,
root are inherently on-device). Forge Mode's compilation step should target a
remote build server rather than running Gradle/AGP on-device — Android has no
supported, reliable on-device Android Gradle Plugin toolchain, and this
sandbox's own environment confirms the pattern: Gradle 8.14.3 and JDK 21 are
available, but there is no Android SDK, adb, or emulator installed, and none
of the standard Android device paths exist. A phone would be worse-equipped
than this sandbox, not better. `core-remote` is the client-side half of that
architecture; the server half is out of scope for this repository.

## 5b. Security policy (implemented, device-agnostic)

`SecurityPolicy` (`core-security`) is the configuration: `rootEnabled`,
`rootAvailable` (a function — a real on-device root check when core-root
exists, a fixture in tests), `grantedPermissions`, and `autoApprove` (which
`SecurityLevel`s bypass interactive confirmation; only `NORMAL` by default).
`SecurityPolicyEnforcer.authorize(spec: ToolSpec)` returns `Allow`,
`RequireApproval(reason)`, or `Deny(reason)` — root and permission checks
are evaluated first and are hard denials, never downgraded to a mere
prompt: a `ROOT` tool with a missing permission is denied outright, not
asked-and-approved-around.

`SecureToolExecutor` wraps `ToolExecutor` with that check: a `Deny` returns
a `ToolResult.Failure` without the underlying `Tool.execute` ever being
called — verified directly by a test that asserts a policy-denied tool's
invocation counter stays at zero. A `RequireApproval` transitions the
shared `AgentStateMachine` to the previously-unused `AgentState.
AwaitingApproval` and blocks on an injected `ApprovalPrompt` — this is the
first code that gives `AwaitingApproval` real behavior; it existed in the
state machine's sealed hierarchy since Phase 3 but nothing produced it
until now.

This closes the device-agnostic slice of Phase 7's "ROOT TEST MATRIX":
root unavailable, root available (then approved), user denies
authorization, and permission failure are all unit-tested. Command
failure/timeout/cancellation/invalid-command are unchanged from
`ToolExecutorTest` — `SecureToolExecutor` delegates to the same
`ToolExecutor` once a decision is `Allow` or an approval is granted, so it
inherits those guarantees rather than re-implementing them. What remains
un-testable here is real root detection and real permission grants on an
actual Android device — that is `core-root`/`core-tools-android`'s job,
not this module's, and both remain PLANNED (Section 6).

## 5c. Configuration loading (implemented, device-agnostic)

`core-config` is the seam between raw configuration (environment variables,
or any other `ConfigSource`) and the typed config objects `core-llm` and
`core-security` already define. `ConfigReader` gives typed access
(`require`/`optional`/`optionalInt`/`optionalDouble`/`optionalBoolean`)
over any `ConfigSource`; a missing required key throws
`MissingConfigException` rather than producing a half-built config object
that fails confusingly later.

`LlmConfigLoader.load(source)` builds an `LlmConfig`: `provider` and
`model` are required, `endpoint`/`temperature`/`maxOutputTokens` are
optional, and `authToken` is a lambda that reads `DROIDFORGE_LLM_API_KEY`
from the source fresh on every call — the loader itself never captures the
key into a field. This is verified directly, not just designed that way in
prose: a test changes the underlying source's value between two calls to
the returned config's `authToken()` and asserts each call sees the current
value, proving nothing was cached at load time.

`SecurityPolicyLoader.load(source, rootAvailable)` builds a
`SecurityPolicy` from comma-separated permission and auto-approve-level
lists, defaulting to root disabled, no granted permissions, and only
`SecurityLevel.NORMAL` auto-approved — the same conservative defaults
`SecurityPolicy` itself uses. `rootAvailable` is deliberately never sourced
from configuration (root availability is a device fact, not a setting);
the loader only threads through whatever function the caller supplies.

`EnvConfigSource` wraps `System.getenv` behind an injectable function
(defaulting to `System::getenv`), so production code gets real environment
variables while every test in this module supplies a fake — no test here
reads or depends on this sandbox's actual process environment.

## 5d. Remote client (implemented — the first module with a real, working implementation)

Every module up to this one has been an interface plus fakes: `LlmProvider`
has no concrete implementation, `SecurityPolicy`'s root/permission checks
are injected functions, `core-config`'s sources are env-var/map-backed.
`core-remote` is different — `JdkHttpTransport` is a real HTTP client
(`java.net.http.HttpClient`, part of the JDK, no external dependency), and
it is tested against a real server: `JdkHttpTransportTest` starts an actual
`com.sun.net.httpserver.HttpServer` bound to `127.0.0.1` on an
OS-assigned port, sends a real request over a real socket, and asserts on
the real response — headers included. A second test opens a raw
`ServerSocket` that accepts the TCP connection but never writes a
response, to prove the transport's own request timeout actually fires
rather than hanging.

This caught a real bug before it shipped: the first version compared
response header names case-sensitively, but HTTP header names are
case-insensitive by spec and `java.net.http.HttpHeaders.map()` does not
guarantee a server's exact casing survives into that map. The round-trip
test against the real server failed with the header coming back `null`
under the original casing — not a hypothetical, an actual assertion
failure in this session — and the fix (a case-insensitive
`TreeMap`) is what ships now.

`RemoteEndpoint` only ever resolves a caller-supplied relative path
against its own configured `baseUrl` — there is no code path by which a
caller can aim a request at a different host, so "server identity"
protection here is structural rather than a runtime allow-list check.
`RemoteClient` reuses `core-agent`'s `RetryPolicy` rather than
reimplementing bounded retry, retries a 5xx status or an I/O
error/timeout, and never retries a 4xx (the request itself was wrong;
retrying would just repeat the failure). `RemoteClientIntegrationTest`
proves the retry path for real too: a local server returns 503 twice then
200, and `RemoteClient` + the real `JdkHttpTransport` recover without any
test double standing in for the network layer.

What remains PLANNED: TLS certificate/server-identity verification beyond
"the URL must be HTTPS" (no mutual-TLS or pinning), and there is still no
concrete `LlmProvider` or build-server client actually built on top of
`RemoteClient` — this module is the transport, not a wired-up consumer of
it.

## 6. Component status (Section 3 naming — "Never fabricate features")

| Component | Status | Evidence |
|---|---|---|
| core-agent: AgentState | IMPLEMENTED | `core-agent/src/main/kotlin/.../AgentState.kt`, compiles, unit-tested |
| core-agent: Tool interface | IMPLEMENTED | `Tool.kt`, compiles, unit-tested |
| core-agent: ToolRegistry | IMPLEMENTED | `ToolRegistry.kt`, compiles, unit-tested |
| core-agent: ToolExecutor + bounded retry | IMPLEMENTED | `ToolExecutor.kt`, compiles, unit-tested |
| core-agent: ConversationContext | IMPLEMENTED | `Conversation.kt`, compiles, unit-tested |
| core-agent: Planner contract | IMPLEMENTED | `Planner.kt` (interface only — see LlmPlanner for the one implementation) |
| core-agent: ObjectiveEngine (bounded Forge loop) | IMPLEMENTED | `ObjectiveEngine.kt`, compiles, unit-tested incl. the maxIterations bound |
| core-agent: DroidForgeSession (Pilot/Forge mode switching) | IMPLEMENTED | `DroidForgeSession.kt`, unit-tested incl. a rejected mode switch attempted mid-task |
| app (Android shell) | PLANNED | Manifest/Gradle scaffold only, not yet buildable — no Android SDK in this environment (Section 7) |
| core-llm: request/response/error types, LlmProvider interface | IMPLEMENTED | `LlmTypes.kt`, `LlmProvider.kt`, compiles |
| core-llm: LlmPlanner (Planner adapter) | IMPLEMENTED | `LlmPlanner.kt`, unit-tested, and exercised end-to-end with `ObjectiveEngine` in `ObjectiveEngineIntegrationTest` |
| core-llm: concrete provider (Anthropic / OpenAI-compatible / local) | PLANNED | No implementation exists; every test uses a scripted fake `LlmProvider` — no live network call, no credentials, has never been run against a real model |
| core-tools-android: domain model (UiNode/UiTree/Selector/Rect) + UiTreeRenderer | IMPLEMENTED | Compiles, unit-tested — pure Kotlin, no Android dependency |
| core-tools-android: DeviceController + Tool wrappers (tap/swipe/type/pressKey/launchApp/findElement/tapElement/getUiTree/listInstalledApps/takeScreenshot) | IMPLEMENTED | Unit-tested against a scripted `DeviceController` fake, incl. invalid-input paths that never call the device |
| core-tools-android: core-security integration | IMPLEMENTED | `DeviceToolSecureExecutorIntegrationTest` — a denied tap never reaches the device controller |
| core-tools-android: NullDeviceController | IMPLEMENTED (explicitly non-real) | Every method fails explicitly ("no real device is connected"); never fabricates a successful tap, swipe, or UI-tree read |
| core-tools-android: a real Android-backed DeviceController | PLANNED | Needs the Android SDK (to compile against real Accessibility/PackageManager APIs) and a connected/emulated device, neither present in this environment |
| core-shell: ShellCommand / ShellSecurityPolicy / ShellExecutor | IMPLEMENTED | Compiles, unit-tested |
| core-shell: ProcessBuilderShellExecutor | IMPLEMENTED (real, not mocked) | Tested against real subprocesses (`echo`/`true`/`false`/`sleep`/`pwd`/`env`/`seq`) — real timeout, real cancellation, real working-directory containment, real fail-closed executable allow-list, real output truncation |
| core-shell: ShellTool + core-security integration | IMPLEMENTED | `ShellToolSecureExecutorIntegrationTest`, using the real executor — a denied command provably never spawns a process |
| core-root: RootCommand / RootSecurityPolicy / RootExecutor | IMPLEMENTED | Compiles, unit-tested |
| core-root: PolicyEnforcingRootExecutor | IMPLEMENTED | Unit-tested — rejects a command outside its allow-list without reaching the delegate; fail-closed by default (empty allow-list) |
| core-root: RootTool + core-security integration | IMPLEMENTED | `RootToolSecureExecutorIntegrationTest` exercises the full root test matrix (root disabled, root unavailable, user denies, approved-and-executed, command failure) against real `SecureToolExecutor`/`SecurityPolicyEnforcer` |
| core-root: NullRootExecutor | IMPLEMENTED (explicitly non-real) | `isRootAvailable()` truthfully returns false; `execute()` fails explicitly rather than fabricating a successful elevated command |
| core-root: a real rooted-device RootExecutor | PLANNED | Needs an actual rooted device this environment does not have |
| core-build: domain model (BuildRequest, ProjectType, BuildTarget, ArtifactType, BuildError, BuildResult, BuildEvent) | IMPLEMENTED | Compiles, unit-tested; see docs/CORE_BUILD.md |
| core-build: WorkspaceManager / WorkspacePathValidator (real filesystem, path security) | IMPLEMENTED | Real java.nio.file operations, unit-tested incl. traversal/absolute-escape/symlink-adjacent cleanup containment |
| core-build: BuildPipeline (orchestrator) | IMPLEMENTED | Unit-tested for every stage's success/failure path, cancellation, and a simulated timeout via a fake clock |
| core-build: SystemBuildEnvironmentDetector / PathExecutableDetector | IMPLEMENTED | Real detection (env vars, file existence, PATH scan — no process spawning), unit-tested against fixtures |
| core-build: DryRunPlanner | IMPLEMENTED | Unit-tested incl. "performs no filesystem mutation" |
| core-build: BuildTool + core-security integration | IMPLEMENTED | `BuildToolSecureExecutorIntegrationTest` — a denied build never creates a workspace, verified on disk |
| core-build: MockBuildExecutor | IMPLEMENTED (explicitly non-real) | Never performs a real build; default outcome is zero artifacts with an output message saying so |
| core-build: a real BuildExecutor (AndroidGradleBuildExecutor / LocalProcessBuildExecutor / RemoteBuildExecutor) | PLANNED | Needs Android SDK/JDK/Gradle or a real build server this environment does not have |
| core-apk-lifecycle: domain model (InstallRequest/Result, LaunchResult, LogEntry, TestCaseResult, ApkLifecycleError/Event) | IMPLEMENTED | Compiles, unit-tested |
| core-apk-lifecycle: ApkLifecyclePipeline | IMPLEMENTED | Unit-tested for every stage's success/failure path, incl. best-effort log collection vs. fatal install/launch/test-harness failures |
| core-apk-lifecycle: ApkLifecycleTool + core-security integration | IMPLEMENTED | `ApkLifecycleToolSecureExecutorIntegrationTest` — a denied deployment never reaches the executor |
| core-apk-lifecycle: NullApkLifecycleExecutor | IMPLEMENTED (explicitly non-real) | Every method fails explicitly ("no real device/adb is connected"); never fabricates a successful install, launch, or test run |
| core-apk-lifecycle: a real adb-backed ApkLifecycleExecutor | PLANNED | Needs a connected/emulated Android device this environment does not have |
| core-remote: RemoteEndpoint / HttpTransport / RemoteClient | IMPLEMENTED | `RemoteEndpoint.kt`, `HttpTransport.kt`, `RemoteClient.kt`, unit-tested against a fake transport |
| core-remote: JdkHttpTransport (real HTTP client) | IMPLEMENTED | `JdkHttpTransport.kt`, tested against a real local `HttpServer` on loopback — a genuine network round trip and a genuine timeout, not mocked |
| core-remote: TLS identity verification beyond "must be HTTPS" (pinning/mTLS) | PLANNED | Not built |
| core-remote: a concrete LlmProvider or build-server client using RemoteClient | PLANNED | RemoteClient exists as a transport; nothing in core-llm or core-build consumes it yet |
| core-security: SecurityPolicy / SecurityPolicyEnforcer | IMPLEMENTED | `SecurityPolicy.kt`, `SecurityPolicyEnforcer.kt`, compiles, unit-tested |
| core-security: SecureToolExecutor (controlled execution boundary) | IMPLEMENTED | `SecureToolExecutor.kt`, unit-tested incl. "denied tool is never invoked" and "AwaitingApproval before prompting" |
| core-security: real root detection / real Android permission grants | PLANNED | `rootAvailable`/`grantedPermissions` are injected functions, now exercised end-to-end by `core-root.RootToolSecureExecutorIntegrationTest` — but still only against fixtures, not a real device |
| core-config: ConfigSource / ConfigReader | IMPLEMENTED | `ConfigSource.kt`, `ConfigReader.kt`, compiles, unit-tested |
| core-config: LlmConfigLoader | IMPLEMENTED | `LlmConfigLoader.kt`, unit-tested incl. that `authToken()` re-reads the source on every call rather than caching |
| core-config: SecurityPolicyLoader | IMPLEMENTED | `SecurityPolicyLoader.kt`, unit-tested |
| core-config: a real, deployed configuration source (device settings UI, secure storage) | PLANNED | Only `EnvConfigSource`/`MapConfigSource`/`CompositeConfigSource` exist; no Android-backed source (e.g. EncryptedSharedPreferences) has been built |
| Pilot Mode (end-to-end) | PARTIAL | `DroidForgeSession.runPilotInstruction` is implemented and tested against `core-tools-android`'s real `Tool` wrappers, but every one of them is backed by `NullDeviceController` — no real Android-backed `DeviceController` exists yet |
| Forge Mode (end-to-end) | PARTIAL | The objective loop itself (planning/tool-selection/execution/observation/bounded iteration) is implemented and tested; it has never run against a real LLM or a real device tool; core-build's pipeline/workspace scaffolding now exists but has no real BuildExecutor to actually compile anything |
| Mode switching (Pilot <-> Forge) | IMPLEMENTED | `DroidForgeSession.switchMode`, unit-tested for the idle case and for rejection during an active task |
| Root capabilities | PARTIAL | `core-root`'s Tool/gate/policy wiring is implemented and tested end-to-end against `core-security`; no real root command has ever executed, since that requires a rooted test device this environment does not have |
| LLM integration | PARTIAL | The abstraction and the planner adapter are implemented and tested against a fake provider; no concrete provider is implemented, and this environment has no LLM credentials to test one against even if it existed |
| APK build/install/test pipeline | PARTIAL | `core-build.BuildPipeline` + `core-apk-lifecycle.ApkLifecyclePipeline` orchestration is implemented and tested end-to-end against fakes; neither has a real executor, so nothing has actually been built, installed, or launched on a device — that needs the Android SDK + device/emulator this environment does not have |

## 7. Environment constraints recorded for this implementation pass

Verified 2026-09-04 in this sandboxed session:
- JDK 21.0.10, Gradle 8.14.3, Kotlin 2.0.21 toolchain: present.
- `ANDROID_HOME`/`ANDROID_SDK_ROOT`: unset. No `adb`, `emulator`, `sdkmanager`,
  `avdmanager` on PATH. No `~/Android/Sdk` or `/opt/android-sdk`.
- Network reachability to `dl.google.com` and `maven.google.com` confirmed
  (HTTP 200/301), so an Android SDK *could* be provisioned in a session with
  time/disk budget for it — but no physical or emulated device exists here to
  install or launch an APK on regardless of SDK presence.
- No LLM provider credentials configured in this environment.
- No root-capable Android device attached.

These are session facts, not permanent project constraints — a developer
machine or CI runner with the Android SDK and a connected/emulated device
removes most of them. They are recorded here so that "PLANNED" status above
is auditable rather than asserted.
