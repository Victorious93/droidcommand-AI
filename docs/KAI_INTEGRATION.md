# Kai → DroidCommand AI capability map

This document records an evidence-based, read-only review of
`Victorious93/Kai` against this repo's existing architecture, per the
project owner's "Integrate Kai capabilities into DroidCommand AI" prompt.
It is the `KAI-###` companion to `docs/AUDIT_2026-09-05.md`'s
`ROADMAP-###`/`DP-###`/`OD-###`/`CAP-###` rows and `docs/
CAPABILITY_ROADMAP_PROMPT.md`'s target architecture — read those first for
what DCA already has; this document does not repeat their content, only
what Kai adds on top of it.

**Commits reviewed (navigation anchors, not instructions to re-derive from
scratch if newer commits exist by the time this is read):**

- DroidCommand AI: `ba1deea41a22f516ea22cffadfcefc098550d8e1` (the prompt's
  stated anchor) through the branch head this document was committed on.
- Kai: cloned fresh (shallow, `--depth 1`) from
  `https://github.com/Victorious93/kai` on 2026-10-07. The prompt's stated
  anchor is `3818b2fccc0fb9bc977ab198b8bdb4a647dbab47`; this review reads
  whatever commit the shallow clone's default branch head resolved to on
  that date, not a pinned checkout of the stated SHA — Kai's own
  `docs/features/*.md` files are internally dated "Last verified:
  2026-10-02", consistent with a recent `main`.

**Method:** Kai's `docs/features/*.md` (19 files, each dated "Last
verified: 2026-10-02", each naming its own "Key Files") were read in full
and treated as the primary source for behavior and platform support, cross-
checked against the actual source files they name for every claim that
drives a Decision below (not for every incidental detail). No Kai code was
copied; no Kai code was modified. `LICENSE.txt` (Apache-2.0, top-level) and
`THIRD_PARTY_LICENSES.md` (PRoot GPL-2.0, talloc LGPL-3.0, both native
binaries under `androidApp/src/main/jniLibs/`, dynamically linked, run as
a separate process) were read directly — see **Licensing** at the end.

**Format:** one row per capability area (not one row per Kai source file —
several Kai files back one DCA-facing capability). Each row gives Kai's
real behavior/platform support, DCA's existing overlap, a destination
module, and a **Decision**: *extend existing* (add to a module DCA already
has), *adapt source* (port Kai's documented *behavior* into a new or
existing DCA module as a non-verbatim rewrite — Kai's source/docs were read
directly to produce it, which is adaptation with attribution, not a formal
clean-room process; see **Licensing** for how that distinction is tracked
and why "clean-room" is not claimed anywhere in this document), *new adapter* (a new module
implementing an existing DCA contract), *optional later phase* (real work,
correctly deferred), or *blocked* (needs something this environment/repo
does not have).

---

## KAI-001 — Rich chat (history, streaming, attachments, markdown/kai-ui rendering, model selection)

**Kai:** `ui/chat/`, `data/RemoteDataRepository.kt`, `ui/markdown/`. Full
Compose chat UI: conversation history, per-provider streaming, image/PDF
attachments (gated per-provider/per-model — see KAI-004), unified markdown
parser with a `kai-ui` fence as a first-class AST block (see KAI-011),
copy/share, a service-selector dropdown for live model switching,
provider-attempt visibility during fallback (KAI-004).

**DCA overlap:** `core-agent.Conversation`/`ConversationStore`/
`JsonFileConversationStore` (CAP-002/CAP-006) already model persistent,
provider-agnostic conversation storage. `app` (ON DISK, EXCLUDED — no
Android SDK) has Phase 0 Compose nav + a stub Chat screen, never
build-verified. DCA has **no streaming contract anywhere** — every
`LlmProvider.complete`/`core-llm-anthropic`/`core-llm-openai` call is
request/response, not token-by-token (`docs/ARCHITECTURE.md` §4b, §5e/5f
confirm this directly — not inferred). DCA has no attachment/image/PDF
type anywhere in `core-llm` request/response models.

**Destination:** `app` (UI), a new `LlmProvider.completeStreaming()`
contract extension in `core-llm` (additive — see KAI-004 for why this must
be a backward-compatible default-method addition, not a signature change).

**Decision:** *optional later phase*, gated on Phase 0 (`app` build
inclusion) landing first. Streaming and attachments are both genuinely
missing contract surface in `core-llm`, not merely missing UI — scoping
either means extending `LlmProvider` itself, which only the next session
with a real Android build target to verify against should do. Until then,
`app`'s existing stub Chat screen can wire to `DroidCommandSession`'s
existing request/response path with no new contract.

**Tests:** none yet (no code changed). **Acceptance (future phase):** a
streamed response renders incrementally in `app`; `LlmProvider` implementors
that don't override the new streaming method keep compiling and behaving
exactly as today (the same additive-default-method discipline
`ApprovalPrompt.requestApproval(toolName, input, reason)` already
established in `core-security`, 2026-10-06 addendum).

---

## KAI-002 — Persistent memory (categorized, reinforced, promotable)

**Kai:** `data/MemoryStore.kt`, `data/ChatSystemPromptBuilder.kt`,
`data/HeartbeatManager.kt`. Key-value memories with 4 categories
(General/Learning/Error/Preference), hit-count reinforcement (5+ hits =
promotion candidate), promotion = append to system prompt + delete the
memory, per-variant system-prompt injection budgeted to 2,000 chars for
on-device chat, uncapped for remote. Tools: `memory_store`/`memory_learn`/
`memory_forget`/`memory_reinforce`/`promote_learning`.

**DCA overlap:** `core-agent.KnowledgeStore`/`KnowledgeGraph`/
`KnowledgeContext` (CAP-006/CAP-016, part of P0's Knowledge Graph) +
`core-agent.TokenBudgetManager` (CAP-002) already cover "persistent,
budgeted, injectable-into-context knowledge." This is the single
strongest overlap in the whole capability list — Kai's `MemoryStore` and
DCA's `KnowledgeStore` solve the same problem from different starting
points (Kai: flat categorized KV; DCA: a graph). Duplicating Kai's KV
shape as a second, parallel store would recreate exactly the "second,
competing concept" drift this repo's own `ModelRouter`/`AiProviderSelector`
doc comments already warn against for provider routing.

**Destination:** `core-agent.KnowledgeStore`/`KnowledgeGraph` (extend, not
replace).

**Decision:** *extend existing*. Concretely, not yet scoped in code:
- Add Kai's four-category taxonomy (General/Learning/Error/Preference) as
  a `KnowledgeCategory` (or equivalent) on top of the existing graph node
  shape, if `KnowledgeGraph`'s nodes don't already carry an analogous
  field — **needs a fresh read of `KnowledgeGraph.kt`/`KnowledgeContext.kt`
  before implementing**, not assumed here.
- Add hit-count reinforcement and a promotion path (promote = move a node's
  content into `Persona.systemPromptContribution` or equivalent, per
  KAI-003 below, then delete the node) as new, additive methods.
- Route the *budgeting* through the existing `TokenBudgetManager` rather
  than a new fixed-2000-char constant — Kai's on-device cap is a product
  decision (small models can't attend to much context) that
  `TokenBudgetManager` should express as a policy, not a hardcoded number
  duplicated in a second place.
- New tools (`memory_store`, `memory_forget`, `memory_reinforce`,
  `memory_learn`, `promote_learning` equivalents) register through the
  existing `core-agent.Tool`/`ToolRegistry`/`core-security.SecureToolExecutor`
  pipeline like any other tool — no new execution path.

**Acceptance (future phase):** a stored memory survives a process restart
(reusing existing `KnowledgeStore` persistence); reinforcement count is
visible; promotion moves content into persona/system-prompt scope and
removes the original entry; local vs. remote token budgets are enforced
through `TokenBudgetManager`, not a second cap.

---

## KAI-003 — Personality / system instructions

**Kai:** `data/ChatSystemPromptBuilder.kt`, `data/AppSettings.kt` (soul
text). A single free-text "soul" string, always included, plus baked-in
constant sections (honesty rule, tool-use guidance, acting guidance) that
"soul" customization cannot remove while their gating condition holds (e.g.
the tool-use section can't be user-disabled while tools exist).

**DCA overlap:** `core-agent.Persona`/`StyleProfile`/`PersonaManager`
(CAP-005) is **already a strictly richer model** than Kai's single free-text
soul string — `Formality`/`Verbosity`/`VocabProfile`/`StructureProfile`/
`HumorProfile`, `PersonaCategory`, `contextContribution` as the
LLM-ready representation. Kai has nothing DCA's `Persona` lacks.

**Destination:** `core-agent.Persona` (no change) + `app` Settings UI
(future, gated on Phase 0).

**Decision:** *extend existing*, UI-only. There is no logic to adapt from
Kai here — the only real gap is DCA has no Compose settings screen for
editing a `Persona`/`StyleProfile` yet. **Precedence rule to establish
explicitly when that UI is built** (named in the owner's prompt, not yet
decided): user-edited persona fields vs. an active Skill's instruction body
(KAI-007) vs. runtime policy (e.g. `SecureToolExecutor` denials) — Kai's
own model is instructive here: its baked-in sections (honesty, tool-use,
acting) are **not** user-removable, which maps cleanly to "policy always
wins over persona," and an active skill's body is appended **on top of**
the persona-derived system prompt for that turn only, never replacing it
— the same layering DCA should adopt.

**Acceptance (future phase):** a `Persona`'s `contextContribution`
continues to flow into `LlmRequest.systemPrompt` unchanged; an editable
Settings UI exists; a written precedence rule (persona < active skill
addition < non-overridable policy text) is documented and tested.

---

## KAI-004 — Provider configuration, fallback, and streaming

**Kai:** `data/Service.kt`, `data/RemoteDataRepository.kt`. 31 providers
across 3 wire formats (OpenAI-compatible / Gemini-native / Anthropic-native)
plus LiteRT on-device; multiple instances per provider; ordered fallback
chain with per-service retry, context-window-fit skipping, visible
per-attempt status, and strict on-device isolation (**"a cloud-service
failure never silently starts a local model load"**; **"on-device entries
are also never used as fallback targets"**).

**DCA overlap:** `core-llm.AiProviderSelector`/`ModelRouter`/
`LocalFirstOrdering` (CAP-003/CAP-004) + `core-llm-factory.LlmProviderFactory`
(config-driven multi-provider construction, 2026-09-12) already implement
local-first ordering and a real composed `Planner`. DCA has exactly 2 real
`LlmProvider`s (`core-llm-anthropic`, `core-llm-openai`) vs. Kai's
3-wire-format, 31-service catalog. DCA's `AiProviderSelector` doc comment
(quoted verbatim in `CLAUDE.md`'s Phase 2 correction) **already states
honestly** that it has no latency/resource-aware selection — Kai doesn't
either (its fallback order is purely user-configured priority, not
measured).

**Destination:** `core-llm` (fallback chain semantics on `ModelRouter`),
new sibling modules per missing wire-format family — `core-llm-google`
(Gemini-native; Phase 1 of the existing Consumer Roadmap already names
this), a generic OpenAI-compatible-family module for the ~27 remaining
Kai-supported OpenAI-compatible services (most need only a base-URL +
model-list override on `core-llm-openai`'s existing transport, per that
module's own two-file pattern — **not** 27 new modules).

**Decision:** *extend existing* for fallback-chain semantics (context-window
skip, visible per-attempt status, on-device isolation rules) on
`ModelRouter`; *new adapter* per genuinely new wire format
(`core-llm-google` for Gemini-native; Anthropic/OpenAI-compatible already
exist). **Preserve existing error semantics** per the owner's prompt — Kai's
"errors a retry can't fix skip further retries" classification (quota,
invalid key, unknown model, moderation-rejected, context-overflow,
attachment-rejected) is a real, testable policy worth adapting
*behaviorally* into `ModelRouter`'s own retry logic as a non-verbatim
rewrite (Kai's classification rules were read directly, not reused as
code), as a documented, tested change — not a silent one.

**Streaming contract** (shared with KAI-001): Kai's streaming is per-wire-
format (SSE for OpenAI-compatible, Gemini's own chunking, Anthropic's own
SSE). DCA's `LlmProvider` interface has no streaming method at all today.
Adding one is Phase-gated the same way as KAI-001 — see that row.

**Acceptance (future phase):** `ModelRouter` skips a provider whose
context window can't fit the request instead of failing the whole chain;
retry-vs-fail-fast classification is unit-tested against each error
category Kai documents; an on-device (`ProviderType.LOCAL`) failure
short-circuits the chain rather than silently falling back to cloud,
and a local provider is never selected as a non-primary fallback target —
both already expressible in `ProviderType`/`LocalFirstOrdering` but not
yet enforced as chain *rules* (today `LocalFirstOrdering` only orders; it
doesn't forbid a local provider from occupying a fallback slot).

---

## KAI-005 — Local inference (LiteRT)

**Kai:** `inference/LocalInferenceEngine.kt`, `inference/LocalModelCatalog.kt`.
Google LiteRT LM SDK, `.litertlm` files from HuggingFace's
`litert-community`, pinned immutable revisions with SHA-256 + size
verification at download and load time, GPU-first/CPU-fallback, a 512MB
headroom floor (Android), 5-minute idle auto-release, a small on-device
tool allowlist (8 tools) because small Gemma/Qwen/LFM2.5 models can't
reliably emit complex tool-call syntax.

**DCA overlap:** `ProviderType.LOCAL`/`SELF_HOSTED` are **declared but have
zero real-provider backing** (confirmed directly in `ProviderType.kt`'s own
doc comment and `CLAUDE.md`'s Phase 2 correction). The Consumer Roadmap's
own Phase 2 already commits to **llama.cpp/GGUF**, not LiteRT — a different
backend and model format from Kai's.

**Destination:** a new module parallel to Phase 2's planned `core-llm-local`
— e.g. `core-llm-litert`, *not* a replacement for the GGUF plan.

**Decision:** *optional later phase*, and explicitly **not** a reason to
drop or delay the already-planned GGUF backend. The owner's prompt itself
says "integrate a real LiteRT adapter where compatible; preserve the
planned GGUF backend and keep format-specific loaders explicit" — both can
exist behind the same `ProviderType.LOCAL` contract, selected by which
model file format the user has. **Shared, adaptable regardless of which
backend lands first** (per the prompt's "share download, storage and
lifecycle management"): Kai's pinned-revision + SHA-256-at-download +
SHA-256-at-load + atomic-finalization + idle-release pattern is a real,
concrete design worth adopting *behaviorally* for GGUF too, not something
specific to LiteRT.

**Acceptance (future phase):** one local-inference lifecycle/download
manager shared by whichever backend(s) exist, each pinned model verified
by digest at both download and load time, idle release on a timer,
refuses load under a memory floor — all stated honestly as JVM-testable
(digest/size/timer logic) vs. requiring a real device/GPU (actual
inference) per the owner's "genuine offline inference when available" vs.
"JVM test pass does not verify ... inference" distinction.

---

## KAI-006 — MCP client

**Kai:** `mcp/McpClient.kt`, `mcp/McpServerManager.kt`, `mcp/McpTool.kt`.
Streamable-HTTP-only MCP client (no stdio), per-server custom headers,
parallel reconnect sweep, tools wrapped to "behave like a built-in tool,"
collision-safe renaming (`<server>_<tool>`), **full JSON Schema preserved
for nested `items`/`properties`/`enum`** for accurate API serialization —
explicitly the capability DCA's MCP side currently lacks.

**DCA overlap:** `core-mcp.McpToolServer` is an MCP **server** (DCA's tools
exposed *to* an external MCP client), the complementary direction to Kai's
MCP **client** (external tools pulled *into* Kai) — confirmed directly by
reading both; these are genuinely complementary, not competing, exactly as
the owner's prompt states. Fixed this session (see **Foundation work
delivered**, below): `McpToolServer` previously (a) accepted only the
concrete `core-agent.ToolExecutor`, bypassing `core-security.SecureToolExecutor`'s
policy/approval/grant/audit pipeline for any caller that wanted it, and (b)
silently **dropped** any JSON array/object argument value rather than
preserving it.

**Destination:** a new `core-mcp-client` module (parallel to `core-mcp`,
which stays server-only — a `McpServer`/`McpClient` split mirroring Kai's
own naming is the honest module shape, not overloading `core-mcp`).

**Decision:** *new adapter* for the client itself (not yet built — this
session's work was limited to the Foundation-level server-side
prerequisites below, per the phase-gating in `KAI_INTEGRATION_PLAN.md`).
Once built: wrap each discovered MCP tool as a `core-agent.Tool`
implementation (per-tool `ToolSpec` with `Initiator`/`SecurityLevel` set
conservatively — an externally-discovered tool is not inherently
`Initiator.AI`-safe merely because the agent wants to call it) and register
it through the same `ToolRegistry`/`SecureToolExecutor` path as every other
tool — never a second, parallel dispatch path, per the owner's "wrap
imported tools in DCA's tool registry and security pipeline."

**Foundation work delivered this session (see root-level summary):**
`McpToolServer`'s `executor` parameter is now typed as `core-agent.ToolRunner`
(satisfied by both `ToolExecutor` and `SecureToolExecutor`) instead of the
concrete `ToolExecutor`, and a JSON array/object argument value is now
re-serialized to its compact JSON text form and passed through rather than
dropped — a compatibility adapter (the `Tool.execute(Map<String,String>)`
contract itself is unchanged; a tool that wants structured data parses the
JSON text itself). Full first-class typed arguments — widening
`Tool.execute`'s signature across all 24 existing modules — remains real,
separately-scoped future work, named explicitly rather than attempted here.

**Acceptance (future phase, client module):** a configured MCP server's
tools are discoverable and callable through the existing tool pipeline;
nested JSON Schema (array/object parameters) round-trips through a call
without silent loss; policy denial/revocation is re-checked at call time,
not only at discovery time (per the owner's "recheck authorization at
execution time ... after reconnection").

---

## KAI-007 — SKILL.md skills

**Kai:** `skills/SkillManifest.kt`, `skills/SkillRegistry.kt`,
`skills/SkillManager.kt`. Installable instruction bundles (Anthropic's
SKILL.md format / agentskills.io), stored as sandbox folders
(`~/skills/<id>/`), slash-command activation appends the skill body to the
system prompt for that turn only, curated+vetted marketplace sources,
256KB/text-only file limit on install.

**DCA overlap:** none structurally — DCA has no skill-bundle concept.
`core-agent.Persona` is identity/style (KAI-003), not a reusable
task-instruction bundle; they are genuinely distinct, per the owner's
prompt's explicit flag ("keep reusable instruction bundles distinct from
persona identity where their behavior differs").

**Destination:** a new `core-agent.Skill`/`SkillRegistry`-equivalent type,
analogous in shape to `core-agent.MacroStore` (save/load/list/delete) but
for instruction bundles rather than tool-call macros — **not** the same
store, since a `Macro` (per its own doc comment, ROADMAP-127) is a saved,
replayable sequence of tool calls, while a Skill is a prose instruction
body with no replay semantics of its own.

**Decision:** *adapt source* (behavior, as a non-verbatim rewrite — SKILL.md's
frontmatter shape is itself an open, documented standard, not Kai-proprietary
code, though Kai's specific handling of it was read directly) for
parsing/storage; *optional later phase* for marketplace browsing (needs
network access + a curated source list, a product decision the owner
should make explicitly, not inherit Kai's two curated sources by default).
Kai's "skill installation does not grant tool permissions" and "script-
bearing skills can depend on an explicitly selected execution environment"
rules translate directly onto DCA's existing `SecureToolExecutor`/
`ExecutionTarget` model with no new security primitive needed — a skill's
body is just system-prompt text; any tool call it leads the agent to make
still goes through the ordinary registry/policy path.

**Acceptance (future phase):** a skill installs without granting any tool
capability by itself; slash-command activation appends the skill body for
one turn only (verified by a test asserting the system prompt reverts on
the next turn); install validates frontmatter/size/path before writing
anything.

---

## KAI-008 — Tasks, heartbeat, and background execution

**Kai:** `data/TaskScheduler.kt`, `data/HeartbeatManager.kt`. One-shot
(TIME)/recurring (CRON)/standing (HEARTBEAT) tasks, 60-second poll loop,
exponential backoff on one-time-task failure, cron tasks advance past a
failure rather than retry-flooding, a scheduler-shutdown mid-run is
explicitly **not** a failure (resumes next cycle), heartbeat = a silent
self-check prompt on an active-hours window with memory-promotion
surfacing.

**DCA overlap:** `core-agent.MacroScheduler`/`MacroStore`/`Scheduler`
(ROADMAP-127) already implement "a saved, named unit re-run on a real
recurring cadence via a real `Scheduler`" — structurally the closest
existing analog of any Kai capability reviewed (closer even than
KAI-002's KnowledgeGraph overlap, since both are schedule-a-named-thing
systems). `MacroScheduler` today only supports `scheduleRecurring` (one
cadence shape); it has no TIME/CRON/HEARTBEAT trigger taxonomy, no
failure-backoff policy, and nothing analogous to a heartbeat self-check.

**Destination:** `core-agent.MacroScheduler`/new sibling types (extend, not
replace) + a new `core-agent.HeartbeatManager`-equivalent (no existing DCA
analog for heartbeat specifically — it's not scheduling, it's a periodic
self-check prompt with its own active-hours gate).

**Decision:** *extend existing* for the trigger taxonomy
(TIME/CRON/HEARTBEAT as named `Trigger` types feeding `MacroScheduler`,
replacing/extending its current single `scheduleRecurring` shape) and the
failure-backoff policy (adapt Kai's documented rules — exponential backoff
capped at 1h for one-shot, advance-past-failure for cron, no-failure-record
for scheduler-shutdown — as a non-verbatim behavioral rewrite); *adapt
source* (behavior) for heartbeat's active-hours gate and
promotion-candidate surfacing, which has no existing DCA analog to extend.
A cron parser is new code either way (DCA has none today) — Kai's own
5-field parser (minute/hour/day-of-month/month/day-of-week,
both-restricted-fields-must-match semantics) is a reasonable, standard
shape worth reimplementing as a non-verbatim rewrite rather than inventing
a DCA-specific one from nothing.

Background execution itself (Kai's Android foreground service /
`DaemonController`) is Android-specific and squarely gated on `app`
(PLANNED/ON-DISK-EXCLUDED) — not buildable or testable in this JVM-only
environment today.

**Acceptance (future phase):** a `MacroScheduler`-driven task supports all
three trigger shapes with the documented failure semantics, unit-tested
without any real clock (Kai's own `TaskScheduler`/`CronExpression` are
pure/unit-tested — the same discipline DCA's own `Scheduler` abstraction
already permits); heartbeat is gated by DCA's existing `Persona`/context
budget, not a new unrelated budget; real Android foreground-service
persistence is explicitly deferred to `app` + Phase 0, not faked here.

---

## KAI-009 — Linux environment and terminal

**Kai:** `linux/`, `sandbox/`. PRoot-based Debian/Alpine userspace,
per-conversation persistent bash sessions, PTY-free `fresh`/`background`
execution modes, SSH host config management, graduated SIGINT→SIGTERM→
SIGKILL cancellation, self-healing on shell death, mirror-fallback
downloads with SHA-verified extraction and traversal-path rejection.

**DCA overlap:** `core-termux` (2026-09-13) already integrates with a
**separate, independently-installed** Linux-capable app (Termux) via the
public `com.termux.RUN_COMMAND` intent protocol — a fundamentally different
architecture from Kai's **embedded** PRoot runtime bundled inside the app's
own APK. `core-shell`/`ShellSecurityPolicy`/`ShellTool` already model
fixed-executable (never string-concatenated) command execution generically,
`core-root` already has a real, runtime-verified `AdbRootExecutor` for a
rooted-device topology. VictorSuite (the `Victorious93/VictorSuite`
companion, GPL, Termux-derived) is already noted as *"already covered by
`core-termux`, no new module needed"* per `CLAUDE.md`.

**Destination:** a new `core-proot` (or similar) module implementing
`core-security.ExecutionTarget`, parallel to `core-termux`/`core-root` —
**not** a replacement for either.

**Decision:** *optional later phase* — real, substantial, Android-gated
work (PRoot is a native ARM/x86 binary requiring bundling + an embedded
rootfs download/extraction pipeline; none of it is JVM-testable beyond
pure logic like path-traversal rejection or SSH-config-file writing, both
of which Kai's own `HomeMigration.kt`/`SshConfigManager.kt` are already
"pure `java.io`, unit-tested" — a real, portable design worth adapting as
a non-verbatim rewrite regardless of backend). **Explicitly reuse Termux/VictorSuite
first** per the owner's prompt ("reuse existing Termux/VictorSuite support
when that satisfies the requested operation") — an embedded PRoot backend
is additive for users who want zero-additional-app-install Linux, not a
replacement for the existing `core-termux` path, and `core-shell`'s
existing "unprivileged execution separate from root" boundary already
matches Kai's own "PRoot is not a VM or independent strong security
boundary" caveat.

**Acceptance (future phase):** an embedded PRoot `ExecutionTarget`
implementation passes the same `ExecutionTarget` contract tests
`core-root`/`core-termux` already define; rootfs download verifies a
digest before extraction and rejects any extracted path escaping its root
(pure-JVM-testable without a real device); real command execution inside
a real PRoot sandbox is explicitly deferred to a device-available session,
per this repo's existing "don't fabricate runtime verification" discipline.

---

## KAI-010 — Kai Build (coding workspace)

**Kai:** `build/`, `build/runtime/BuildEnvironmentManager.kt`,
`build/runtime/BuildProotExecutor.kt`. A Debian-only project workspace with
a real PTY (Python `pty` module) terminal, three vendor coding-agent
installers (Claude Code, Grok, OpenCode), per-project multi-session tabs,
a cell-grid VT emulator.

**DCA overlap:** `core-build`/`core-build-local`/`core-build-remote`
(ROADMAP, implemented) already model a `BuildPipeline`/`BuildExecutor`
abstraction for **building this app itself** (APK lifecycle), not for
running arbitrary third-party coding-agent CLIs in a workspace — a
different job that happens to share the word "build." `docs/CORE_BUILD.md`
is the existing source of truth for that pipeline and is unaffected by
Kai Build, which solves a different problem (an in-app developer
workspace, not DCA's own build/CI).

**Destination:** Developer Mode (named explicitly in the owner's prompt as
where "optional coding-agent CLIs" belong) + the same new `core-proot`
module as KAI-009 (Kai Build's Debian-only PTY workspace is PRoot-based,
same runtime as the chat sandbox) — **connected to** `core-build`'s
`BuildPipeline`/APK-lifecycle contracts only once real on-device
Android-build tooling is verified, never assumed.

**Decision:** *optional later phase*, correctly ordered **after** KAI-009
(no PTY/PRoot runtime to build a terminal workspace on top of otherwise)
and explicitly **not** a substitute for `core-build`'s existing
remote-build fallback — the owner's prompt is direct about this:
"Kai Build is a coding workspace, not proof of a working Android compiler
toolchain... preserve remote-build fallback where on-device Android builds
are unsupported." Vendor coding-agent installers (Claude Code, Grok,
OpenCode) each need their current official install source/platform-support
re-verified against primary documentation at implementation time, not
assumed stable from this review.

**Acceptance (future phase):** a Developer Mode project workspace opens a
real PTY session in a PRoot-backed Debian environment; installing a coding
agent is user-initiated (never automatic), probes the resulting binary
rather than trusting the installer's exit code (Kai's own documented
pattern); `core-build`'s remote-build fallback is unaffected and still the
default path when on-device build tooling isn't verified.

---

## KAI-011 — Generated interactive UI (kai-ui)

**Kai:** `ui/dynamicui/KaiUiNode.kt` (28 node types), `KaiUiParser.kt`
(tolerant JSON-repair + field-by-field fallback-to-default decoding),
`KaiUiRenderer.kt`. A bounded, validated component schema (layout/content/
interactive/feedback/navigation/display/data node kinds) rendered natively
in Compose; a malformed field degrades to "field missing," never a crash;
callbacks route back through the normal chat/tool flow, never direct
system operations.

**DCA overlap:** none — DCA has no generated-UI concept at all. This is a
pure net-new capability, not an extension of anything existing.

**Destination:** new types in `app`'s Compose layer, parallel/analogous to
Kai's `KaiUiNode`/`KaiUiParser`/`KaiUiRenderer` — necessarily Android-gated
(Compose rendering), though the node-tree schema and its tolerant-parsing
logic are themselves plain Kotlin data classes + JSON parsing, provably
JVM-testable independent of any Android dependency (Kai's own
`KaiUiNodeBuilders.kt`/parser logic has no Android import — confirmed by
its file living under `commonMain`, not `androidMain`).

**Decision:** *adapt source* (behavior/schema shape, as a non-verbatim
rewrite — the node-type taxonomy and the "always render something, never
crash on malformed input" discipline are design decisions read directly
from Kai's docs/source, not transcribed code)
for the schema + parser (buildable and testable in `core-agent` or a new
pure-JVM module today, with zero Android dependency); *optional later
phase*, Android-gated, for the Compose renderer itself. Every constraint
the owner's prompt names is already how Kai designed it, so adapting
rather than inventing is the honest path: bounded depth/node counts, URL
actions validated, no arbitrary executable code (every node is declarative
data, never a script), callbacks re-enter the normal agent/policy pipeline
exactly like a typed tool call, and disabling the feature must disable
interactive behavior (not merely stop generating instructions for it) —
Kai's own "parsing and rendering stay active regardless" design is the
wrong default to copy here if the owner wants a true kill switch; that
needs an explicit decision at implementation time, named here rather than
silently inherited.

**Acceptance (future phase):** the parser/schema builds and is unit-tested
in a pure-JVM module with zero Android dependency; a malformed component
tree degrades to a readable fallback rather than crashing or losing the
whole message; a callback button's action enters the same
`ToolRegistry`/`SecureToolExecutor` path as any other agent-initiated
action; the disable switch actually disables interactivity, decided and
tested explicitly rather than inherited from Kai's parse-always design.

---

## KAI-012 — Personal tools (web search, URL fetch, notifications, calendar, alarms, TTS, email/SMS)

**Kai:** `tools/WebSearchTool.kt`, `tools/FetchUrlTool.kt`,
`tools/EmailTools.kt`, `tools/SmsTools.kt`, `tools/NotificationTools.kt`,
Android `SendNotificationTool`/`CreateCalendarEventTool`/`SetAlarmTool`.
`fetch_url` blocks loopback/private/link-local/CGNAT addresses on every
redirect hop — a real SSRF guard, not a documentation-only claim (verified
in the tool's own doc paragraph, which names the exact address classes and
the redirect-hop re-check). Email/SMS/notification tools each gated behind
their own enable switch + system permission, independent of DCA's generic
`PermissionCategory`.

**DCA overlap:** `core-tools-android.SystemTools`/`DeviceController` already
has analogous device-action tools (`ListNotificationsTool`,
`SendBroadcastTool`, battery/network/storage/clipboard — ROADMAP-037) under
the existing `ToolSpec.permissionCategory`/`SecurityLevel` model. `core-remote`
(`JdkHttpTransport`) is the existing HTTP client every new network tool
should reuse rather than hand-rolling a second one — named explicitly in
the owner's prompt for web search. DCA has **no** email/SMS/calendar/alarm/
TTS tool today — these are fully net-new, Android-permission-gated
capabilities.

**Destination:** `core-tools-android` (Android-specific: calendar/alarm/
SMS/notifications/TTS) + a new pure-JVM-testable `web_search`/`fetch_url`
pair (device-independent, reusing `core-remote.JdkHttpTransport`) landing
wherever `core-tools-android`'s device-independent siblings already live.

**Decision:** *adapt source* (behavior, as a non-verbatim rewrite) for
`fetch_url`'s SSRF guard specifically — this is a real, non-trivial security property (private/
loopback/link-local/CGNAT/IPv4-mapped-IPv6/numeric-shorthand blocking,
re-checked on every redirect hop, GET/HEAD-only redirects) worth replicating
exactly, not loosely; *new adapter* for `web_search` (needs a real search-API
backend choice — Kai uses a server-side proxy for its Free tier and the
user's own key otherwise; DCA has no existing search-API integration to
extend); *optional later phase, Android-gated* for
calendar/alarm/notification/email/SMS/TTS, each needing its own real
Android permission + `core-tools-android`-shaped tool, built and tested
only once `app`/Android SDK access exists. Per the owner's prompt: "keep
draft/preview and approved-send behavior clear" for anything that sends a
real message (email/SMS) — DCA's existing `ApprovalFlow`/`RiskTier`
(`SecurityLevel.SENSITIVE`+`requiresConfirmation`) already models exactly
this distinction and should gate any send-tool's `ToolSpec`, not a new
confirmation mechanism.

**Acceptance (future phase):** `fetch_url`'s address-class blocklist is
unit-tested against every class Kai's doc names, including the redirect-
hop re-check; `web_search`/`fetch_url` reuse `JdkHttpTransport`, not a new
HTTP client; any send-capable tool (email/SMS compose/reply) declares
`SecurityLevel.SENSITIVE` and is exercised only with test fixtures, never a
real send, per the owner's explicit "test with fixtures without sending
real messages."

---

## KAI-013 — Settings and data portability

**Kai:** `data/AppSettingsImportExport.kt`. Section-selective JSON export/
import (Services/Soul/Memory/Scheduling/Heartbeat/Email/Tools/MCP/
Conversations/Splinterlands/SMS), a documented **Excluded** list (API keys'
*presence* exported per-section but posting keys/encryption keys never
exported; `daemon_enabled`, analytics counters, device-local UI prefs never
exported), Replace-vs-Merge import modes, auto-sanitization of malformed
task/memory entries on import rather than rejecting the whole file.

**DCA overlap:** `core-config.SecretsVault`/`VaultBackedConfigSource`
(CAP-013) already models secret storage generically; DCA has **no**
export/import feature at all today for conversations/persona/knowledge/
config.

**Destination:** a new `core-config`-adjacent export/import module (or a
method set on existing stores — `ConversationStore`, `KnowledgeStore`,
`PersonaStore`, `SecretsVault` each already expose enough surface to build
a selective export against, without a new storage format).

**Decision:** *adapt source* (behavior, as a non-verbatim rewrite) for the
selective-section + Replace/Merge + malformed-entry-sanitization design —
these are real, well-thought-out product decisions worth replicating;
*optional later phase* for the actual implementation (needs a defined
export schema covering DCA's own store shapes, a decision this document
doesn't make). Per the owner's prompt: secrets are exported "only by
explicit selection with appropriate protection," and an import "must not
silently activate permissions, remote endpoints, recurring actions or
tools" — Kai's own exclusion of posting/encryption keys and its
sanitize-rather-than-silently-trust approach to imported scheduled tasks
are the right behavioral model to adapt, but DCA's import must additionally
never let an imported MCP server config or scheduled task become *live*
(connected/armed) without the same enable gate a user-created one would
need — Kai's docs don't fully resolve this (an imported enabled MCP server
does reconnect automatically per its own `mcp.md`), so DCA should make a
deliberately more conservative choice here rather than inherit Kai's.

**Acceptance (future phase):** export excludes every secret-equivalent DCA
has (vault entries, grant tokens); import of a malformed section doesn't
fail the whole import; an imported MCP server/scheduled task/skill starts
disabled and requires an explicit enable step, a deliberately stricter
behavior than Kai's own auto-reconnect-on-import.

---

## KAI-014 — Domain-specific: Splinterlands auto-battle

**Kai:** `splinterlands/SplinterlandsTeamPicker.kt`,
`splinterlands/SplinterlandsBattleRunner.kt`. A fully self-contained
feature: Hive-blockchain posting-key-signed battle submission, parallel
multi-LLM team-picking with a local scoring-based fallback, its own
isolated prompt-building path (confirmed directly in `system-prompts.md`:
"fully isolated, does not use the chat prompt builder").

**DCA overlap:** none, and none expected — this is a single-game
integration specific to Kai's own product scope.

**Destination:** none in DCA core; if adopted at all, a wholly separate,
optional module (e.g. `core-splinterlands`) with no dependency from any
core DCA module onto it.

**Decision:** *optional later phase*, lowest priority, exactly as the
owner's prompt frames it ("keep them optional and separately scoped; do
not silently discard them or make them core dependencies"). Not discarded
here — recorded for completeness — but there is no finding that this adds
general-purpose agent capability DCA's broader user base needs, and it
introduces a real external dependency (a Hive blockchain posting key,
funds-adjacent) DCA has no other reason to take on. Recommend revisiting
only if the project owner explicitly asks for it by name.

**Acceptance:** N/A — not scheduled.

---

## Capabilities checked for and not found needing a separate row

- **Reasoning-mode toggling / appearance (theme) / multi-service UI
  chrome** (`reasoning.md`, `appearance.md`) — pure UI polish with no
  architectural counterpart to reconcile; folds into KAI-001's `app` work
  whenever that phase lands, not a separate capability.
- **Notifications-as-a-feature vs. notifications-as-a-tool** — covered
  under KAI-012; Kai's `notifications.md` describes the same
  listener-access-gated capability `core-tools-android.ListNotificationsTool`
  already has a DCA-side analog for (device-side listing), plus a net-new
  "read/reply to a captured notification" tool DCA lacks — captured in
  KAI-012's scope, not a 15th row.

---

## Licensing

- **Kai itself:** Apache License 2.0 (`LICENSE.txt`, verified by reading
  the file directly — standard Apache-2.0 header text). Safe to read,
  reference, and adapt behavior/design from with attribution, per Apache-2.0's
  own terms, consistent with the owner's "preserve original licenses...
  for adapted source" instruction. **Provenance note, stated precisely
  rather than claimed more favorably than it is:** no DCA code in this
  session copies any Kai source file verbatim, and every "adapt source"
  decision above is a **non-verbatim rewrite of documented behavior**
  produced after reading Kai's actual docs/source directly — this is
  ordinary licensed adaptation under Apache-2.0's own terms (which permits
  exactly this, with attribution), **not a formal clean-room process**
  (clean-room specifically requires the implementer never to have seen the
  reference; that precondition does not hold here, and this document does
  not claim it does). Any future session that implements a Phase 1+ "adapt
  source" item should record, at the point of implementation, which Kai
  file(s) informed the design and keep this same honest framing rather than
  asserting independent authorship.
- **Kai's bundled native binaries are NOT Apache-2.0**, confirmed by
  reading `THIRD_PARTY_LICENSES.md` directly: `libproot.so`/
  `libproot-loader.so`/`libproot-loader32.so` are **GPL-2.0** (Termux's
  PRoot fork), and `libtalloc.so` (PRoot's own dependency) is **LGPL-3.0**.
  Per the owner's prompt's explicit warning, **Kai's top-level Apache
  license does not cover these** — any future PRoot-based work (KAI-009,
  KAI-010) must source its own PRoot binary independently (e.g. directly
  from `https://github.com/termux/proot`, the same upstream Kai's own
  `THIRD_PARTY_LICENSES.md` names) and carry its own GPL-2.0/LGPL-3.0
  notices, never inherit them silently through "adapting Kai."
- **This repo (DroidCommand AI) has no `LICENSE` file at all** (confirmed
  directly — `ls` of the repo root, consistent with `CLAUDE.md`'s own
  "this repo has no LICENSE file" note elsewhere). This document does not
  invent or assign one; that remains the project owner's decision, named
  here only because any future PRoot/GPL-adjacent work makes the absence
  of a stated project license more consequential than it is today, not
  because this document is resolving it.
- **SKILL.md / agentskills.io** (KAI-007): an open, documented format
  (Anthropic's `anthropics/skills` repo + the `agentskills.io` standard),
  not Kai-proprietary — adapting it carries no Kai-specific licensing
  obligation beyond whatever each individual marketplace skill's own
  license states (out of scope for this document; a future skills-browsing
  phase should surface each skill's own license, not assume one).

---

## Summary table

| ID | Capability | Decision | Destination |
|---|---|---|---|
| KAI-001 | Rich chat (streaming/attachments/markdown) | optional later phase | `app`, `core-llm` |
| KAI-002 | Persistent memory | extend existing | `core-agent.KnowledgeStore`/`KnowledgeGraph` |
| KAI-003 | Personality/system instructions | extend existing (UI only) | `core-agent.Persona`, `app` |
| KAI-004 | Provider config/fallback/streaming | extend existing + new adapter | `core-llm`, `core-llm-google`, etc. |
| KAI-005 | Local inference (LiteRT) | optional later phase | new `core-llm-litert` |
| KAI-006 | MCP client | new adapter (client); **Foundation prerequisites delivered this session** | new `core-mcp-client`; `core-mcp` (done) |
| KAI-007 | SKILL.md skills | adapt source + optional later phase | new `core-agent.Skill*` |
| KAI-008 | Tasks/heartbeat/background | extend existing + adapt source | `core-agent.MacroScheduler`, new `HeartbeatManager` |
| KAI-009 | Linux environment/terminal | optional later phase | new `core-proot` |
| KAI-010 | Kai Build | optional later phase | Developer Mode, `core-proot` |
| KAI-011 | Generated interactive UI | adapt source (schema/parser) + optional later phase (renderer) | new pure-JVM module, `app` |
| KAI-012 | Personal tools | adapt source + new adapter + optional later phase | `core-tools-android`, `core-remote` |
| KAI-013 | Settings/data portability | adapt source + optional later phase | new `core-config`-adjacent module |
| KAI-014 | Splinterlands | optional later phase (lowest priority) | none in core; separate optional module if ever adopted |

Implementation and verification status for each row is tracked separately
in `docs/KAI_INTEGRATION_PLAN.md` and, as work lands, in dated addenda to
`docs/AUDIT_2026-09-05.md` — this document is the capability map, not the
build log.
