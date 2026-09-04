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
├── core-tools-android       Android-API-backed tools: UI inspection/
│                            interaction, app launch/management, screenshots,
│                            notifications, device info (PLANNED)
│
├── core-shell               Non-root shell execution tool (PLANNED)
│
├── core-root                Root-authorized execution boundary, isolated from
│                            core-tools-android (PLANNED)
│
├── core-build               Forge Mode build/compile engine — workspace,
│                            source→artifact pipeline (PLANNED, likely
│                            remote/hybrid — see Section 5)
│
├── core-apk-lifecycle        Build → install → launch → log → test → result
│                            (PLANNED, device-dependent)
│
├── core-remote               Client for remote LLM/build servers: auth, TLS,
│                            timeouts, retries (PLANNED)
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

`core-agent`, `core-llm`, `core-security`, and `core-config` are implemented
so far because none has an Android dependency: `core-llm`'s tests use a
scripted fake provider rather than a live network call, `core-security`'s
root/permission checks are injected functions rather than real device
queries, and `core-config`'s `EnvConfigSource` wraps `System.getenv` behind
an injectable function so its tests never read or depend on real process
environment.
All three build and test honestly in this environment. Every other module
is scaffolding-only or not yet created — see Section 6.

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
              core-tools-android / core-shell / core-root (PLANNED)
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
| core-tools-android | PLANNED | Not created |
| core-shell | PLANNED | Not created |
| core-root | PLANNED | Not created |
| core-build | PLANNED | Not created |
| core-apk-lifecycle | PLANNED | Not created |
| core-remote | PLANNED | Not created |
| core-security: SecurityPolicy / SecurityPolicyEnforcer | IMPLEMENTED | `SecurityPolicy.kt`, `SecurityPolicyEnforcer.kt`, compiles, unit-tested |
| core-security: SecureToolExecutor (controlled execution boundary) | IMPLEMENTED | `SecureToolExecutor.kt`, unit-tested incl. "denied tool is never invoked" and "AwaitingApproval before prompting" |
| core-security: real root detection / real Android permission grants | PLANNED | `rootAvailable`/`grantedPermissions` are injected functions exercised only with test fixtures; depends on core-root / core-tools-android and a real device |
| core-config: ConfigSource / ConfigReader | IMPLEMENTED | `ConfigSource.kt`, `ConfigReader.kt`, compiles, unit-tested |
| core-config: LlmConfigLoader | IMPLEMENTED | `LlmConfigLoader.kt`, unit-tested incl. that `authToken()` re-reads the source on every call rather than caching |
| core-config: SecurityPolicyLoader | IMPLEMENTED | `SecurityPolicyLoader.kt`, unit-tested |
| core-config: a real, deployed configuration source (device settings UI, secure storage) | PLANNED | Only `EnvConfigSource`/`MapConfigSource`/`CompositeConfigSource` exist; no Android-backed source (e.g. EncryptedSharedPreferences) has been built |
| Pilot Mode (end-to-end) | PARTIAL | `DroidForgeSession.runPilotInstruction` is implemented and tested against fake tools only — no real Android-backed tool exists yet (depends on core-tools-android) |
| Forge Mode (end-to-end) | PARTIAL | The objective loop itself (planning/tool-selection/execution/observation/bounded iteration) is implemented and tested; it has never run against a real LLM or a real device tool, and core-build (compile step) does not exist |
| Mode switching (Pilot <-> Forge) | IMPLEMENTED | `DroidForgeSession.switchMode`, unit-tested for the idle case and for rejection during an active task |
| Root capabilities | PLANNED | Depends on core-root; also requires a rooted test device this environment does not have |
| LLM integration | PARTIAL | The abstraction and the planner adapter are implemented and tested against a fake provider; no concrete provider is implemented, and this environment has no LLM credentials to test one against even if it existed |
| APK build/install/test pipeline | PLANNED | Depends on core-apk-lifecycle; also requires Android SDK + device/emulator not present in this environment |

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
