# Claude vs. Codex: Task Assignment for DroidCommand AI

Draft for review. Standalone reference for deciding which AI coding agent to
hand a given task to — not wired into `CLAUDE.md`'s "where the real state
lives" index unless requested. Doesn't change any code or existing docs.

## How confident is this, really

[Guessing]/[Likely], not [Certain]. The external capability comparison below
came from public blog/comparison sites (Medium, morphllm.com, DataCamp,
codersera.com, etc.) as of September 2026, not primary benchmark
publications, and those sources disagree with each other on exact model
names and scores — SWE-bench Verified numbers cited for "the current Claude"
range from 85% to 97% depending on source and model generation, and OpenAI
has stopped publishing SWE-bench Verified for its newest models because the
benchmark is saturated (top models cluster within ~1 point of each other).
Treat every specific number as unreliable. What's more consistent across
independent sources is the *qualitative* trait pattern this assignment is
actually based on:

- **Claude Code**: reasoning depth, higher autonomy with self-correction on
  long multi-step tasks, stronger relative performance on harder/more-
  realistic benchmarks (SWE-bench Pro over SWE-bench Verified), first-class
  long-context and cross-file reasoning.
- **Codex CLI**: speed and token/cost efficiency, strong on terminal-native
  execution benchmarks, sustained multi-hour autonomous runs via its `/goal`
  mode (one stress test cited: ~25 hours, ~13M tokens, ~30k LOC), up to 6
  concurrent subagents for parallelizable work, open-source and generally
  cheaper per task.
- Recurring bottom line across sources: **use both**, matched to task shape
  rather than picking one as strictly "better."

Sources: [Claude Code vs Codex vs OpenCode (Medium)](https://medium.com/@unicodeveloper/claude-code-vs-codex-vs-opencode-which-ai-coding-agent-is-actually-the-best-in-2026-baa9f6fd5374), [Codex vs Claude Code, Sept 2026 (morphllm)](https://www.morphllm.com/comparisons/codex-vs-claude-code), [Claude Benchmarks 2026 (morphllm)](https://www.morphllm.com/claude-benchmarks), [Codex vs. Claude Code (DataCamp)](https://www.datacamp.com/blog/codex-vs-claude-code), [Run long horizon tasks with Codex (OpenAI Developers)](https://developers.openai.com/blog/run-long-horizon-tasks-with-codex), [Agentic AI Comparison: Claude Code vs Codex CLI](https://aiagentstore.ai/compare-ai-agents/claude-code-vs-codex-cli)

## What this repo actually contains

(From a read-only synthesis of this repo's own docs — `CLAUDE.md`,
`docs/ARCHITECTURE.md`, `docs/AUDIT_2026-09-05.md`, `docs/SECURITY.md` — per
this repo's own "don't re-derive what's already documented" convention.)

386 tracked files, ~36,200 lines of Kotlin across 20 built modules (5 more
PLANNED per the Consumer Product Roadmap: `app`, `core-llm-google`,
`core-llm-groq`, `core-conversations`, `core-llm-local`, `core-voice`).

- **The seam**: `core-agent` (6,997 lines) — `ObjectiveEngine`,
  `DroidCommandSession`, `ToolRunner`/`ToolExecutor`, `Planner`. Nearly
  every other module depends on it; recent changes here have threaded
  through llm, security, build, tools-android, apk-lifecycle, and remote in
  a single addendum.
- **Security-critical**: `core-security` (3,143 lines — `SecurityPolicyEnforcer`,
  `ApprovalFlow`, `GrantStore`, `AuditLog`, the fail-closed gate every tool
  call passes through), `core-root` (2,707 — root execution, opt-in
  `ai_root` grant), `core-shell` (fixed-executable/no-string-concat
  execution), `core-tools-android` (3,136 — device-control tools gated
  through the same pipeline), `core-apk-lifecycle` (install/launch/test via
  adb). `docs/SECURITY.md` itself flags several checklist items as
  still-open design decisions, not settled requirements — this is real
  security-review territory.
- **Substantial but mechanical-per-change**: `core-remote` (3,573),
  `core-llm` (3,151), `core-build` (2,629) — large modules overall, but
  most individual changes inside them (e.g. a new build executor) are
  narrow.
- **Small/mechanical**: `core-mcp` (303 lines); each single LLM provider
  adapter (`core-llm-anthropic`/`core-llm-openai`, ~2 files each — an
  explicitly copyable pattern the repo's own docs point to for the PLANNED
  `core-llm-google`/`core-llm-groq`); `core-build-local`/`core-build-remote`
  (~400–500 each, deliberately minimal adapters).
- **Consumer Product Roadmap** (Phases 0–5, all NOT STARTED): Phase 0
  (Android scaffolding) mostly mechanical; Phase 1 (multi-provider BYOK +
  Room + Keystore) mixes mechanical adapters with cross-cutting Android
  storage/secrets wiring; Phase 2 (local llama.cpp/JNI inference) is the
  heaviest, with an explicitly unresolved app-size/distribution design
  question (Play Feature Delivery vs. companion app); Phase 3
  (templates/skills) has one real judgment call — avoid duplicating the
  existing `Persona`/`StyleProfile` stack — plus routine CRUD; Phase 4
  (voice + web search) mostly mechanical, reuses existing HTTP/secrets
  infra; Phase 5 (RAG) moderately complex, shares Phase 2's design
  constraint.

## Assignment

### Lean Claude: deep, cross-cutting, or judgment-heavy work

- Anything touching `core-agent`'s `ObjectiveEngine`/`DroidCommandSession`/
  `ToolRunner`/`Planner` — the seam nearly every other module depends on;
  changes here need to be traced across call sites, not made in isolation.
- `core-security`/`core-root`/`core-shell` changes, and anything in
  `docs/SECURITY.md`'s still-open checklist items — these are security
  tradeoffs (default-deny posture, approval-flow semantics, grant scoping),
  not mechanical implementation, and a wrong call has real consequences on
  a tool-execution/root-access surface.
- Phase 2's app-size/distribution design decision (Play Feature Delivery vs.
  companion app) and Phase 3's `Skill`-vs-`Persona` scoping question — both
  are explicitly flagged in `CLAUDE.md` as open design calls a phase "should
  make explicit," not settled specs to implement.
- Anything that requires reconciling the audit trail (`docs/AUDIT_2026-09-05.md`'s
  addenda) against current code before making a change — judgment about
  what's actually still true vs. stale.

### Lean Codex: well-scoped, mechanical, or bulk-parallel work

- A new single-provider LLM adapter (e.g. `core-llm-google`, `core-llm-groq`)
  — the repo's own docs describe this as a copyable two-file pattern from
  `core-llm-anthropic`/`core-llm-openai`.
- A new build-executor adapter following the `core-build-local`/
  `core-build-remote` pattern.
- A single `cli` subcommand, or work in `core-prompt-regen`/`core-mcp`
  (small, self-contained modules).
- Phase 0's Android scaffolding (Hilt/Compose skeleton, stub screens) and
  Phase 4's voice/web-search wiring — both explicitly reuse existing
  infrastructure with routine implementation shape.
- Bulk/repetitive work: filling test coverage that follows an existing
  pattern, dependency bumps, lint/build-config sweeps across the 20 modules.

### Ambiguous — use judgment per instance

- `core-tools-android`/`core-apk-lifecycle`/`core-root`: adding one more
  device-control tool behind the existing `SecureToolExecutor` pipeline is
  Codex-shaped (precedented pattern); changing the pipeline itself or its
  approval semantics is Claude-shaped.
- Phase 1 (multi-provider BYOK): the Groq adapter is Codex-shaped; the
  Keystore-backed secrets wiring and Room migration are closer to
  Claude-shaped cross-cutting Android work.

## Caveat

This is a starting heuristic based on today's tool landscape and today's
(noisy, self-reported) benchmark claims — both change fast, as does this
repo's own module state (re-check `settings.gradle.kts` and the audit's
addenda before trusting the module list above too far into the future, per
this repo's own standing rule). Re-derive rather than trust this file once
either model's capabilities or this repo's structure materially shift.
