# Kai integration — phased implementation plan

Companion to `docs/KAI_INTEGRATION.md` (the `KAI-###` capability map — read
that first for what each phase below actually does and why). This document
maps that map onto dependency-ordered phases, per the project owner's "4.
Implement in dependency order" instructions, and tracks what's actually
been done vs. what's next.

**Governing rules, carried over from `CLAUDE.md`'s Consumer Product
Roadmap** (the owner's prompt explicitly asks to respect this gate rather
than silently remove it, and these phases are new work under the same
repo, so the same discipline applies unless the owner says otherwise):

- **One phase at a time** — don't start phase N+1 until the human approves
  phase N.
- **No pull request is opened automatically.** Commit to this session's
  branch; open a PR only when explicitly requested.
- **No fabricated completions** — a phase is marked done only once its
  stated tests actually pass; state exactly what was/wasn't verified.
- Every phase that lands updates `docs/ARCHITECTURE.md` and appends a dated
  entry to `docs/AUDIT_2026-09-05.md` (append-only — never rewrite a prior
  addendum).

---

## Phase 0 — Foundation (this session)

**Status: PARTIALLY COMPLETE.** Scope per the owner's prompt's own phase 1
("capability map, baseline, Android build inclusion/configuration,
compatible transport, settings/secrets/persistence and tool-schema/
security prerequisites") is larger than what a single session can
responsibly land — Android build inclusion specifically needs an Android
SDK this environment does not have (unchanged from every prior Consumer
Roadmap phase; see `CLAUDE.md`). What follows is what this session actually
did and verified, vs. what remains in Phase 0 before Phase 1 can start.

### Done, this session

1. **Baseline established.** Current branch (`claude/new-session-r17yev`),
   clean working tree at session start, confirmed before any change. Kai
   cloned fresh (shallow) from `https://github.com/Victorious93/kai`.
   Toolchain confirmed: JDK 21 (`openjdk 21.0.12.1`), Gradle via `./gradlew`
   (wrapper resolved 8.14.3), no `ANDROID_HOME`/`ANDROID_SDK_ROOT`, no `adb`
   binary on PATH — **unchanged from every prior session's finding**, not
   assumed stale. `settings.gradle.kts` confirmed to list the same 24 JVM
   modules `CLAUDE.md` already documents, plus the same `:app`/
   `:core-companion` exclusion comment — no drift to reconcile.
2. **`docs/KAI_INTEGRATION.md` written** — the full evidence-based
   capability map (KAI-001 through KAI-014), each row read against Kai's
   own `docs/features/*.md` (19 files) and cross-checked against the
   specific DCA source files each row's Decision depends on.
3. **Tool-schema/security prerequisite closed for `core-mcp`** (the one
   concrete, scoped, JVM-testable Foundation item identified — see
   KAI-006 in the capability map for the full rationale):
   - `McpToolServer`'s `executor` parameter is now typed as
     `core-agent.ToolRunner` instead of the concrete `ToolExecutor`, so a
     deployment that wants MCP-originated tool calls gated by
     `core-security.SecureToolExecutor`'s full policy/approval/grant/audit
     pipeline can now pass one in — closing the gap the owner's prompt
     named directly ("the inspected DCA MCP server accepts the concrete
     plain `ToolExecutor`... do not assume its mode checks provide the
     full `SecureToolExecutor` policy/approval flow"). `ToolExecutor`
     already implemented `ToolRunner`, so this is a pure widening — no
     existing caller changes.
   - A JSON array/object MCP tool-call argument is no longer silently
     dropped; it's re-serialized to its compact JSON text form and passed
     through to the tool, which can parse it itself. This is a
     compatibility adapter, not first-class structured arguments — the
     `Tool.execute(Map<String, String>)` contract every existing tool
     implements across all 24 modules is unchanged. Full first-class
     typed arguments (widening `Tool.execute`'s signature itself) is named
     explicitly as separate, larger, not-yet-scoped future work — not
     attempted here, per the owner's "evolve structured tool schemas...
     provide explicit compatibility adapters for existing string-map
     tools."
   - Tests: `core-mcp/src/test/.../McpToolServerTest.kt` — the prior test
     that explicitly asserted the drop behavior
     (`` `string and number arguments reach the tool as strings, a nested
     array argument is dropped` ``) was split into two: the string/number
     case (unchanged assertion) and a new case proving an array *and* an
     object argument both arrive as their JSON text form. A third new test
     proves a real `SecureToolExecutor` (not a fake/mock) now works as
     `McpToolServer`'s executor end-to-end over the real stdio transport.
     `core-mcp`'s test-only dependency on `core-security` was added for
     this (main source has no such dependency and needs none).

**Verification actually run:**
- `./gradlew :core-mcp:test` — BUILD SUCCESSFUL, 10 tests (up from 8), 0
  failures, 0 errors. (First two attempts hit `429 Too Many Requests` from
  Maven Central resolving the Kotlin Gradle plugin / `kotlin-sdk-server`'s
  transitive deps through this session's proxy — a transient infra
  condition, not caused by this change; it resolved on retry, consistent
  with `docs/AUDIT_2026-09-05.md`'s own prior note that `core-mcp`'s build
  has hit this exact 429 before.)
- `./gradlew test --continue` (full 24-module suite) — BUILD SUCCESSFUL.
  Per-module test-result XML sums to approximately 1,473 tests, 0
  failures, 0 errors, 12 skipped (the pre-existing real-device-only tests
  that skip without `adb`/a connected rooted device — unchanged from the
  2026-10-06 baseline). Stated as "approximately" honestly: a first
  aggregation pass produced a slightly different total (1,476) from a
  parsing artifact in how the script summed one XML file's attributes;
  the per-module breakdown (summed above) and Gradle's own `BUILD
  SUCCESSFUL` for every module's `test` task are the actual verified
  facts — zero test failures either way.

### Not done — remaining before Phase 0 can close

- **Android build inclusion.** `:app`/`:core-companion` remain excluded
  from `settings.gradle.kts` — unchanged, still gated on an Android SDK
  this environment does not have. Nothing in this session attempted to
  add one; attempting to "include" them without a real SDK to compile
  against would only produce an unverifiable, likely-broken build
  configuration — exactly the "fabricated completion" the owner's prompt
  and this repo's own conventions forbid.
- **Android-capable HTTP transport.** The owner's prompt flags this
  directly: "do not assume `java.net.http` can be used unchanged in the
  Android app." `core-remote.JdkHttpTransport` has not been audited this
  session for Android compatibility (API level restrictions on
  `java.net.http.HttpClient`, which requires API 26+ via desugaring or is
  natively available only on recent API levels) — that audit needs a real
  Android build target to verify against, same gating as above.
  **Recorded as a concrete next step**, not silently dropped.
- **Settings/secrets/persistence prerequisites.** `core-config.SecretsVault`/
  `VaultBackedConfigSource` already exist (CAP-013); an Android-backed
  implementation (`KeystoreSecretsVault`) is unchanged, still Phase-1-of-
  Consumer-Roadmap work per `CLAUDE.md`, not duplicated or reattempted
  here.
- **Persona/Memory/MCP-client/Skill/Scheduler extensions** named in
  KAI-002/003/006/007/008 of the capability map are **not yet implemented**
  — each needs its own Plan-Mode-scoped session (per this repo's own
  established precedent: "every [CAP-### P0/P1] first slice [was] scoped
  via Plan Mode before implementing," `CLAUDE.md`'s "Still open" section).
  This plan names them as Phase 1/2 work below; it does not implement them
  speculatively in this session.

**Phase 0 exit criteria (not yet all met):** capability map done ✅;
baseline done ✅; one real, tested tool-schema/security prerequisite closed
✅; Android build inclusion ❌ (blocked on SDK); Android transport audit ❌
(blocked on SDK); remaining Foundation-level extensions to
`KnowledgeStore`/`MacroScheduler`/`Persona` ❌ (correctly deferred to Phase
1/2 below, each needing its own scoping pass).

---

## Phase 1 — Core assistant (NOT STARTED)

**Depends on:** Phase 0's remaining items, or a deliberate decision to
proceed JVM-side first and defer Android-gated pieces further (a real
option — KAI-002/003/008's logic extensions are pure-JVM `core-agent`
work with no Android dependency at all; only their UI surfaces are
Android-gated).

**Scope (from the capability map):**
- KAI-002 — extend `KnowledgeStore`/`KnowledgeGraph` with Kai's four-
  category taxonomy, hit-count reinforcement, and a promotion path into
  `Persona`.
- KAI-003 — no logic work; a Settings UI for editing `Persona`/
  `StyleProfile`, Android-gated.
- KAI-006 — build the new `core-mcp-client` module: tool discovery,
  connection/settings management, invocation, wrapping each discovered
  tool as a `core-agent.Tool` through the existing registry/policy path.
- KAI-001 (partial) — scope (not yet build) the `LlmProvider` streaming
  contract extension, as an additive default-method change proven against
  at least one real provider (`core-llm-anthropic` or `core-llm-openai`).

**Each item needs its own Plan-Mode scoping pass before implementation**,
per this repo's established precedent — this plan does not pre-decide
their exact shape.

**Acceptance:** `KnowledgeStore` supports Kai's four categories +
reinforcement + promotion with full test coverage; a configured MCP
server's tools are invocable through `ToolRegistry`/`SecureToolExecutor`
with no second dispatch path; `./gradlew test` passes with zero
regressions across all 24 existing modules plus whatever new module(s)
this phase adds.

---

## Phase 2 — Local intelligence and skills (NOT STARTED)

**Depends on:** Phase 1 (skills need the tool-registry wiring pattern
Phase 1's MCP-client work establishes; local inference is independent but
grouped here per the owner's own phase-4 naming).

**Scope:**
- KAI-005 — a new `core-llm-litert` module, parallel to (not replacing)
  the Consumer Roadmap's planned `core-llm-local` (GGUF/llama.cpp). Shared
  download/digest/lifecycle-management design adapted from Kai's
  pinned-revision + SHA-256-at-download-and-load + idle-release pattern.
- KAI-007 — `core-agent.Skill`/registry type (SKILL.md parsing, install/
  uninstall, slash-command activation appending the skill body for one
  turn only), analogous in shape to but distinct from `MacroStore`.
- KAI-008 (partial) — the TIME/CRON/HEARTBEAT trigger taxonomy and
  failure-backoff policy on `MacroScheduler`; a cron parser.

**Acceptance:** per KAI-005/007/008's own acceptance criteria in the
capability map — each states explicitly what's JVM-testable (digest
logic, parser correctness, trigger semantics) vs. what needs a real
device (actual on-device inference, actual PRoot-backed skill scripts).

---

## Phase 3 — Developer workspace (NOT STARTED)

**Depends on:** Phase 2 (shares the PRoot runtime KAI-009 introduces).

**Scope:**
- KAI-009 — a new `core-proot` module implementing
  `core-security.ExecutionTarget`, reusing Termux/VictorSuite first where
  that already satisfies the request (per the owner's prompt), adding an
  embedded-PRoot path only as genuinely additive.
- KAI-010 — Kai Build's Developer-Mode workspace on top of KAI-009's
  runtime, connected to `core-build`'s `BuildPipeline` only once on-device
  Android build tooling is independently verified (never assumed).

**Acceptance:** per KAI-009/010's own criteria — path-traversal rejection,
digest verification, and SSH-config writing are JVM-testable without a
device; real PRoot execution, real coding-agent installation, and real
PTY rendering are explicitly deferred to a device-available session.

---

## Phase 4 — Automation and interaction (NOT STARTED)

**Depends on:** Phase 1 (tool-registry pattern), Phase 2 (skills, for
script-bearing skill execution).

**Scope:**
- KAI-008 (remainder) — heartbeat's active-hours gate and
  promotion-candidate surfacing.
- KAI-012 — personal tools: `fetch_url`'s SSRF guard and `web_search`
  first (pure-JVM/`core-remote`-based, no Android dependency); calendar/
  alarm/notification/email/SMS/TTS tools Android-gated.
- KAI-011 — generated interactive UI: the node-tree schema + tolerant
  parser first (pure-JVM, zero Android dependency, provably testable
  today without an SDK — this item could in principle move earlier than
  Phase 4 if prioritized, since it has no Phase 0-3 dependency; placed
  here only to match the owner's own phase-5 grouping, not because it's
  blocked until here).
- KAI-013 — settings/data portability, deliberately stricter than Kai on
  imported-but-not-yet-enabled MCP servers/tasks (see capability map).

**Acceptance:** per each KAI row's own criteria; `fetch_url`'s address-
class blocklist test coverage is the one item worth calling out as
non-negotiable (SSRF is a real vulnerability class, not a style
preference).

---

## Phase 5 — Hardening and optional features (NOT STARTED)

**Scope:** migration/regression passes across everything landed so far;
KAI-014 (Splinterlands) only if the project owner explicitly asks for it
by name, per the capability map's own recommendation; resource-behavior
verification (idle release, memory floors) on whatever real device
becomes available by then.

---

## What this plan deliberately does not do

- It does not pre-scope exact class/method signatures for Phase 1-5 work —
  each phase's items get their own Plan-Mode pass at implementation time,
  per this repo's established discipline (CAP-001 through CAP-014 were
  each scoped this way, not speculatively designed in a roadmap document).
- It does not attempt Android build inclusion speculatively. Every
  Android-gated item above stays named as gated, not attempted-and-
  unverified.
- It does not treat Kai's defaults (e.g. its two curated skill
  marketplaces, its on-device tool allowlist, its auto-reconnect-on-import
  behavior) as DCA's defaults by inheritance — each such choice is flagged
  in the capability map as a decision the project owner should make
  explicitly for DCA, not a settled fact copied over.

## Current approval checkpoint

Per the governing rules above: **Phase 0's remaining items (Android build
inclusion, Android transport audit) are blocked on an Android SDK this
environment does not have — the same blocker every prior Consumer Roadmap
phase has recorded.** The concrete, device-free Foundation item identified
this session (the `core-mcp` tool-schema/security fix) is done and
verified. Everything in Phase 1 onward needs the project owner's
go-ahead before implementation starts, per the "one phase at a time, no
automatic PR" rule — this session does not proceed into Phase 1 without
that.
