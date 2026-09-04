# DroidForge AI — Architecture Blueprint (Phase 2)

Status: DRAFT. Reflects the design adopted for implementation; components are
individually classified IMPLEMENTED / PARTIAL / PLANNED / CONFIGURATION-DEPENDENT
in Section 6 — do not read a component's presence in the diagram below as proof
it is built. Source of truth for what actually exists is the repository itself
plus `docs/PHASE1_AUDIT.md` (the empty-repo state recorded 2026-09-04).

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
│                            registry/executor, bounded retry policy.
│                            No Android dependency — testable on any JVM.
│
├── core-llm                 LLM provider abstraction (PLANNED)
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
├── core-security             Permission/root/security-level policy shared by
│                            every tool (PLANNED)
│
└── core-config               Provider/endpoint/model configuration, no
                             hard-coded secrets (PLANNED)
```

`core-agent` is implemented in this pass because it has no Android or network
dependency and can be honestly built, compiled, and unit-tested in this
environment. Every other module is scaffolding-only or not yet created —
see Section 6.

## 3. Two-mode architecture

```
                    DROIDFORGE AI
                         │
              ┌──────────┴──────────┐
              │                     │
          PILOT MODE            FORGE MODE
              │                     │
       core-agent.Tool         core-agent.Planner (PLANNED)
       Executor (direct,             │
       single-step)           core-agent.Objective loop
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

## 4. Agent state machine (implemented in `core-agent`)

`AgentState` is a sealed hierarchy: `Idle`, `Planning`, `AwaitingApproval`,
`ExecutingTool`, `Observing`, `Recovering`, `Completed`, `Cancelled`,
`Failed`. Transitions are validated — an illegal transition (e.g.
`Completed → ExecutingTool`) throws rather than silently succeeding, which is
the mechanism that prevents an agent from being resumed after it has already
terminated. Retry is bounded by an explicit `RetryPolicy(maxAttempts, backoff)`
passed into the executor; there is no unbounded loop anywhere in this module.

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

## 6. Component status (Section 3 naming — "Never fabricate features")

| Component | Status | Evidence |
|---|---|---|
| core-agent: AgentState | IMPLEMENTED | `core-agent/src/main/kotlin/.../AgentState.kt`, compiles, unit-tested |
| core-agent: Tool interface | IMPLEMENTED | `Tool.kt`, compiles, unit-tested |
| core-agent: ToolRegistry | IMPLEMENTED | `ToolRegistry.kt`, compiles, unit-tested |
| core-agent: ToolExecutor + bounded retry | IMPLEMENTED | `ToolExecutor.kt`, compiles, unit-tested |
| app (Android shell) | PLANNED | Manifest/Gradle scaffold only, not yet buildable — no Android SDK in this environment (Section 7) |
| core-llm | PLANNED | Not created |
| core-tools-android | PLANNED | Not created |
| core-shell | PLANNED | Not created |
| core-root | PLANNED | Not created |
| core-build | PLANNED | Not created |
| core-apk-lifecycle | PLANNED | Not created |
| core-remote | PLANNED | Not created |
| core-security | PLANNED | Not created |
| core-config | PLANNED | Not created |
| Pilot Mode (end-to-end) | PLANNED | Depends on core-tools-android |
| Forge Mode (end-to-end) | PLANNED | Depends on core-llm, core-build |
| Root capabilities | PLANNED | Depends on core-root; also requires a rooted test device this environment does not have |
| LLM integration | PLANNED | Depends on core-llm; also requires provider credentials not present in this environment |
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
