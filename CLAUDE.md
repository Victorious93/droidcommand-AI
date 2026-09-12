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

- This is a 16-module pure-Kotlin/JVM Gradle project (confirmed directly
  against `settings.gradle.kts`; `core-mcp` and, later, `core-integration-tests`
  (a test-only module with no `src/main`, holding cross-module integration
  tests that need two sibling modules together — e.g. `core-shell` and
  `core-root`, neither of which depends on the other) were each added after
  an earlier module-count figure was first written elsewhere in this repo's
  docs — don't trust an older module count without checking
  `settings.gradle.kts`). No Android SDK, no
  device, no root, no LLM credentials, no MCP client exist in most build
  environments this project runs in — everything downstream of those
  (device control, root execution, live LLM calls, real builds/APKs) is
  correctly marked PLANNED/BLOCKED in the audit, not faked.
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
  scratch. What it found: the codebase has real, tested building blocks that
  *overlap* several `CAP-###` items (`ConversationContext`'s token budget,
  `ModelRouter`, `KnowledgeStore`, the `PolicyDecision`/`ApprovalPrompt`
  approval flow, `core-root`/`core-tools-android`'s provider-shaped modules),
  but none of P0's seven items is actually VERIFIED IMPLEMENTED as specified,
  and P1–P7 are almost entirely MISSING (no capability registry, no
  execution-target abstraction/router, no Shizuku/Termux/Docker/WireGuard/
  Headscale/VNC/X11/Proxmox provider of any kind). The next concrete step,
  per the reconciliation's own priority call and the roadmap's explicit
  "build P0 first, don't parallelize" rule, is **CAP-001 (Context Manager)**
  — everything else in P0 (`CAP-002` token budgeting, `CAP-003`
  local-context-first) explicitly depends on the `ContextSnapshot` shape it
  defines. Scope it via Plan Mode before implementing (matching the
  `TaskGraph`/`ObjectiveAnalyzer` precedent in the audit's addenda), not
  speculatively.
- **Magisk support** (2026-09-10, out of band from the CAP-001 recommendation
  above, by explicit owner request) is done for what this JVM-only
  environment can actually build and verify: `core-root.RootProvider`
  (generic abstraction, extends `RootExecutor`) + `MagiskProvider` (real
  detection/execution) + `NullRootProvider`, wired into the existing
  `SecureToolExecutor`/`SecurityPolicyEnforcer`/`GrantStore`/`AuditLog`
  stack unchanged. See `docs/AUDIT_2026-09-05.md`'s "Magisk support" addendum
  for the full record. Real on-device Magisk/root behavior is
  `IMPLEMENTED — NOT RUNTIME VERIFIED` (no rooted device in this
  environment); Magisk module management, `ExecutionRouter`/`CapabilityManager`
  integration, and Terminal/UI display were deliberately not built since
  none of those exist yet in this repository — don't mistake their absence
  for an oversight.
