# DroidCommand AI — orientation for Claude sessions

Read this first. It exists so a new session doesn't have to re-derive
context that already lives elsewhere in this repo — don't re-audit the
project or re-clone the reference repos unless the task genuinely requires
re-verifying something.

## Where the real state lives

- **`docs/REQUIREMENTS_PROMPT.md`** — the project owner's master
  audit/build prompt, stored verbatim. This is the requirements baseline
  (`ROADMAP-###` IDs are derived from its 34 numbered sections). Treat it as
  a durable substitute for chat memory, not as something to re-paste.
- **`docs/AUDIT_2026-09-05.md`** — the current source of truth for status:
  a full `ROADMAP-###`/`DP-###`/`OD-###` requirements matrix, each item's
  real status (VERIFIED IMPLEMENTED / PARTIAL / STUB / MISSING / BLOCKED,
  never inflated), a priority list (P0–P4), and a dated addendum trail of
  what's shipped since. **Read its addenda section first** — it tracks what
  changed after the original audit ran, newest at the bottom.
- **`docs/ARCHITECTURE.md`** — per-component implementation status, kept in
  sync with the audit.
- **`docs/CORE_BUILD.md`** — the build/workspace pipeline specifically.
- **`docs/CAPABILITY_ROADMAP_PROMPT.md`** — the project owner's master
  priority & architecture roadmap, stored verbatim. This is the previously
  unrecovered `CAP-###` prompt referenced below under "Still open" (now
  supplied). It defines the target architecture — Context Manager, Token
  Budget Manager, AI Provider Abstraction, Persona/Style System,
  Conversation Import, Knowledge Graph (P0); Capability Registry, Policy
  Engine, Execution Router, Secrets Vault, Approval Flow, Provider/Adapter
  architecture (P1); universal Android/root/Shizuku/Termux execution (P2);
  WireGuard/Headscale/remote device control (P3); Docker/container-aware
  execution (P4); VNC/X11/remote desktop (P5); Proxmox/infrastructure
  control (P6); and a future provider ecosystem (P7) — plus the
  non-negotiable development rules, verification requirements, and strict
  dependency-ordered build sequence (P0 → P1 → P2 before P3–P7) that govern
  how it's all built. **Reconciled** against `docs/AUDIT_2026-09-05.md`'s
  `ROADMAP-###` matrix as `CAP-001` through `CAP-036` in that document's §8
  addenda (the "CAP-### reconciliation" entry, 2026-09-10) — read that entry
  for per-item status; almost everything in P1–P7 is MISSING or PARTIAL
  (only root/Android device-control and the LLM-provider/remote-transport
  layers have any real code backing them), and the entry's own priority
  recommendation is `CAP-001` (Context Manager) as the correct next build
  target, per the roadmap's own P0-first dependency-ordering rule.

## What's already been verified (don't re-derive)

- This is a 19-module pure-Kotlin/JVM Gradle project (confirmed directly
  against `settings.gradle.kts`; `core-mcp`, `core-integration-tests`
  (a test-only module with no `src/main`, holding cross-module integration
  tests that need two sibling modules together — e.g. `core-shell` and
  `core-root`, neither of which depends on the other), `core-llm-factory`
  (2026-09-12 — the provider-construction factory closing the second half
  of "config-driven multi-provider construction"; `core-config.MultiLlmConfigLoader`
  had already closed the config-loading half), `cli`
  (2026-09-12 — the device-free CLI entrypoint that dispatches Pilot
  instructions/Forge objectives through a real `DroidCommandSession`,
  built with `LlmProviderFactory.createPlanner`; distinct from the
  still-PLANNED Android `:app` module, which this environment has no SDK
  to build), and, most recently, `core-termux` (2026-09-13 — fills
  `core-security.ExecutionTargetType.TERMUX`, surfaced by a first-time
  survey of `android-code-studio`/AndroidIDE; see the "Termux
  ExecutionTarget" audit addendum) were each added after an earlier
  module-count figure was first written elsewhere in this repo's docs —
  don't trust an older module count without checking `settings.gradle.kts`).
  No Android SDK, no
  device, no root, no LLM credentials, no MCP client exist in most build
  environments this project runs in — everything downstream of those
  (device control, root execution, live LLM calls, real builds/APKs) is
  correctly marked PLANNED/BLOCKED in the audit, not faked. **This is a
  per-machine/per-session fact, not a permanent one — check `adb devices`
  before assuming it still holds.** On 2026-09-12, a real Android device
  (rooted via Magisk) was connected over USB/`adb` to the machine one
  session ran on, and `core-root.AdbRootExecutor` was built and actually
  runtime-verified against it — the first real device/root this project has
  ever had. See `docs/AUDIT_2026-09-05.md`'s "AdbRootExecutor" addendum for
  the full record, including what real-device work still remains open
  (an adb-backed `core-tools-android.DeviceController`, an adb-backed
  `core-apk-lifecycle` executor, Shizuku). Don't assume a device is present
  by default; don't assume one never will be either.
- The codebase is unusually honest: every intentional non-real
  implementation (`MockBuildExecutor`, `Null*Executor`, `Null*Controller`)
  is self-documented as such in its own code. If you find something that
  looks like a stub, check whether it's already an acknowledged one before
  treating it as a new finding.
- `docs/AUDIT_2026-09-05.md` already did the full three-way comparison
  against the two reference projects, DroidPilot
  (`https://github.com/Victorious93/droidpilot`) and OpenDroid
  (`https://github.com/Victorious93/opendroid`). Their relevant findings
  are extracted into `DP-###`/`OD-###` rows — read those rather than
  re-cloning and re-inspecting either repo, unless a specific task needs a
  fresh lookup neither doc covers.
- A third repo, `android-code-studio` (AndroidIDE,
  `https://github.com/Victorious93/android-code-studio`), was surveyed for
  the first time on 2026-09-13 (`AC-###` rows, same addendum). It's a full
  Android IDE — almost none of it overlaps this project's domain, and it's
  **GPLv3** (this repo has no `LICENSE` file at all, so copying its source
  would create real copyleft obligations — don't). The one relevant finding
  was its bundled Termux app surfacing `core-security`'s already-declared-
  but-unimplemented `ExecutionTargetType.TERMUX` slot; the adopted capability
  is the *public, documented* `com.termux.RUN_COMMAND` intent protocol,
  implemented clean-room in the new `core-termux` module — not any GPLv3
  source. Don't re-clone/re-survey `android-code-studio` unless a specific
  task needs a fresh lookup the addendum doesn't cover.

## How to continue the work

1. Run `./gradlew test --continue` to see real, current status — don't
   trust a stale claim (including this file's).
2. Read the audit doc's priority list and addenda to find the next
   unclaimed item.
3. Implement it in the smallest safe unit, following the pattern already
   established in the module you're touching (e.g. `core-security`'s
   fail-closed, additive-by-default style).
4. Add tests, run the full suite, keep the change minimal.
5. Update `docs/ARCHITECTURE.md` and append a dated addendum to
   `docs/AUDIT_2026-09-05.md` (don't rewrite its existing entries — append,
   per the project's own "no silent history rewriting" rule).
6. Open a PR, watch it through CI, keep going.

If the branch's own PR has already merged before you start new work,
restart the branch from `origin/main` (`git fetch origin main && git
checkout -B <branch> origin/main`) rather than stacking on stale history.

## Still open

- The `CAP-###` reconciliation is done (see above) — don't re-run it from
  scratch. It originally found none of P0's seven items VERIFIED IMPLEMENTED
  and recommended CAP-001 (Context Manager) as the next step — **that
  recommendation is now stale and has been fully acted on.** Per
  `docs/AUDIT_2026-09-05.md`'s own addenda trail (read the bottom of that
  file, not this bullet, for the current picture — this note is a pointer,
  not a substitute): **P0 (CAP-001 through CAP-007) and P1 (CAP-008 through
  CAP-014) are both now fully IMPLEMENTED, each at an honestly-scoped first-
  slice level** (`ContextManager`/`ContextSnapshot`, `TokenBudgetManager`,
  local-first provider ordering, `AiProviderSelector`, Persona/Conversation
  Import/Knowledge Graph, the Capability Manager/Policy Engine/Execution
  Router/Target stack, Secrets Vault, Approval Flow — every one scoped via
  Plan Mode before implementing, per that precedent, not speculatively).
  Every one of those first slices names its own real, honestly-stated gaps
  (e.g. no LLM-based task-complexity classifier, no latency/resource-aware
  provider selection) — read the specific addendum entry for the item you're
  touching before assuming it's either complete or untouched. One concrete,
  device-free follow-up is now fully closed: config-driven multi-provider
  construction (`core-config.MultiLlmConfigLoader` closed the config-loading
  half; `core-llm-factory.LlmProviderFactory`, 2026-09-12, closed the
  provider-construction half, in a new 17th module — not built into an
  existing one, per that addendum's own dependency-shape reasoning).
  `LlmProviderFactory.createSelector`/`createModelRouter`/`createPlanner`
  (same-day follow-ups, 2026-09-12) now compose that all the way into a
  real `Planner` — the exact type `core-agent`'s `ObjectiveEngine`/
  `DroidCommandSession.runForgeObjective` take as a parameter — from a
  `ConfigSource`, proven end to end against a real `DroidCommandSession`
  in `LlmProviderFactoryPlannerIntegrationTest` (`core-agent` itself was
  correctly left unchanged: `Planner` was already the seam). **That
  remaining entrypoint gap is now closed too, device-free half only:**
  the new `cli` module (2026-09-12) calls `createPlanner` from real
  `EnvConfigSource`/`JdkHttpTransport` instances and dispatches Pilot
  instructions/Forge objectives against a real `DroidCommandSession` —
  see `docs/ARCHITECTURE.md`'s `cli` row and this same-day audit addendum
  for the full record. **That one deliberately-scoped-out follow-up is now
  also closed:** `core-agent.ToolRunner` (a later 2026-09-12 addendum) let
  `DroidCommandSession`/`ObjectiveEngine` accept `core-security.SecureToolExecutor`
  in place of the plain `ToolExecutor`, and a same-day follow-up wired `cli`
  to build a real `SecureToolExecutor` and register `ShellTool`/`RootTool`/
  `BuildTool` behind it, each denied-by-default until explicitly configured
  or approved — see those two addenda for the full record. The Android
  `:app` module itself remains PLANNED, unchanged, still gated on an
  Android SDK/UI this environment does not have.
  Beyond that, everything remaining is **P2–P7** — universal Android/root/
  Shizuku/Termux execution, WireGuard/Headscale remote control, Docker,
  VNC/X11, Proxmox, and the future provider ecosystem — all correctly gated
  behind real device/root/Shizuku/Termux/Docker/Proxmox/VNC/X11/WireGuard/
  Headscale/SSH environments and a UI (`app` module) this JVM-only
  environment does not have.
- **Magisk support** (2026-09-10, out of band from the CAP-001 recommendation
  above, by explicit owner request) is done for what this JVM-only
  environment can actually build and verify: `core-root.RootProvider`
  (generic abstraction, extends `RootExecutor`) + `MagiskProvider` (real
  detection/execution) + `NullRootProvider`, wired into the existing
  `SecureToolExecutor`/`SecurityPolicyEnforcer`/`GrantStore`/`AuditLog`
  stack unchanged. See `docs/AUDIT_2026-09-05.md`'s "Magisk support" addendum
  for the full record. Real on-device Magisk/root behavior (the
  JVM-runs-*on*-the-device topology `MagiskProvider` itself assumes) remains
  `IMPLEMENTED — NOT RUNTIME VERIFIED` — that would need a JDK/Termux
  installed on a real phone, which no session has done. The *other* real
  topology — a PC driving a rooted phone over USB/`adb` — **is** now
  `IMPLEMENTED — RUNTIME VERIFIED`, via `core-root.AdbRootExecutor`
  (2026-09-12); see the note above and `docs/AUDIT_2026-09-05.md`'s
  "AdbRootExecutor" addendum. Magisk module management,
  `ExecutionRouter`/`CapabilityManager` integration, and Terminal/UI display
  were deliberately not built since none of those exist yet in this
  repository — don't mistake their absence for an oversight.
