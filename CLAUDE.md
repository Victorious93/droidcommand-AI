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

- This is a 20-module pure-Kotlin/JVM Gradle project (confirmed directly
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
  to build), `core-termux` (2026-09-13 — fills
  `core-security.ExecutionTargetType.TERMUX`, surfaced by a first-time
  survey of `android-code-studio`/AndroidIDE; see the "Termux
  ExecutionTarget" audit addendum), and, most recently, `core-prompt-regen`
  (2026-09-13 — the Prompt Regenerator, a new capability requested directly
  by the project owner and out of band from the `CAP-###` roster, not a
  fabricated `CAP-037`; a deterministic, LLM-free pipeline that turns a
  vague/incomplete user request into a structured, optimized prompt for
  Claude Code/Codex/GPT/Gemini/local models, wired into `cli` as
  `regenerate-prompt`; see the "Prompt Regenerator" audit addendum) were
  each added after an earlier module-count figure was first written
  elsewhere in this repo's docs — don't trust an older module count
  without checking `settings.gradle.kts`).
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

---

## Consumer Product Roadmap (DroidCommand AI — App Layer)

This section documents a phased plan to build DroidCommand AI into a
consumer-facing Android app: a privacy-first AI chat platform combining
on-device local model inference (`ProviderType.LOCAL`) with cloud BYOK
providers (`ProviderType.CLOUD`/`SELF_HOSTED`) in a Jetpack Compose UI. It
was drafted against this repo's actual current state (verified 2026-09-14
against `settings.gradle.kts` and the modules it names) — a session
proposed an initial version of this section; the numbers and a couple of
technical claims below are corrected from that draft rather than taken on
faith, per findings noted inline.

### Workflow rules (apply to every phase below)

- **No pull request is ever opened automatically for this roadmap's work.**
  Commit to the phase's feature branch; open a PR only when explicitly
  requested ("open PR" / "create PR for phase N"). This is a stricter,
  phase-scoped version of this file's own general "open a PR, watch it
  through CI" step above — that step still applies to ordinary audit-driven
  work; treat this override as specific to Consumer Roadmap phases.
- **One phase at a time.** Don't start Phase N+1 until the human approves
  Phase N.
- **No fabricated completions.** Every file delivered must be genuine,
  compilable, tested Kotlin — this is the same honesty convention this repo
  already applies everywhere else (`Null*`/`Mock*` self-documentation, the
  audit's VERIFIED IMPLEMENTED/PARTIAL/STUB/MISSING grading). Never mark
  something IMPLEMENTED unless `./gradlew test` passes for that
  module/slice; state exactly what was tested and what was not.
- **Preserve all existing modules.** The 20 modules currently in
  `settings.gradle.kts` (`core-agent`, `core-llm`, `core-security`,
  `core-config`, `core-remote`, `core-build`, `core-tools-android`,
  `core-shell`, `core-apk-lifecycle`, `core-root`, `core-termux`,
  `core-llm-anthropic`, `core-llm-openai`, `core-llm-factory`,
  `core-build-local`, `core-build-remote`, `core-mcp`,
  `core-integration-tests`, `cli`, `core-prompt-regen`) are source of truth.
  New work wires to them; it doesn't rewrite them unless a real bug is
  found. **Correction to an earlier draft of this section:** that draft
  said "19 existing pure-JVM modules" and listed 13 of them, omitting
  `core-llm-factory`, `core-mcp`, `core-integration-tests`, `cli`, and
  `core-prompt-regen` entirely — that was a stale/incomplete snapshot, not
  this repo's real state. Re-check `settings.gradle.kts` at the start of
  each phase rather than trusting this count once it ages, per this file's
  own standing rule above.
- **ARCHITECTURE.md and AUDIT docs stay current.** Every phase that adds or
  changes a component updates `docs/ARCHITECTURE.md`'s status table and
  appends a dated entry to `docs/AUDIT_2026-09-05.md`, per this repo's
  append-only convention — never rewrite an existing addendum entry.
- **Branch naming:** `feature/phase-N-<slug>` (e.g.
  `feature/phase-0-android-shell`).
- **Commit style:** matches existing repo convention —
  `feat(module): description (CAP-### if applicable)`.

---

### Phase 0 — Android App Shell (Foundation)
**Status:** NOT STARTED
**Branch:** `feature/phase-0-android-shell`
**Depends on:** nothing new — wires to existing `core-llm`, `core-agent`, `core-config`

Adds the `app/` module: the first Android-SDK-dependent module in this
repo (`docs/ARCHITECTURE.md`'s `app (Android shell)` row is currently
PLANNED — no directory, Gradle file, or manifest exists yet, and this
JVM-only environment has no Android SDK to build one; see that row and
`cli`'s own row for the already-documented distinction between the two).
A minimal, real, runnable APK: Hilt DI wired through, a Compose navigation
skeleton, and stub screens only (no inference, no chat) — scaffolding for
every later phase. `settings.gradle.kts` gets `include(":app")`.

**Acceptance:** `./gradlew :app:assembleDebug` succeeds; app launches on
an emulator (API 26+) with stub screens; `./gradlew test` still passes
across all 20 existing modules (zero regressions in anything above).

---

### Phase 1 — Multi-Provider Cloud BYOK (GPT, Claude, Gemini, Groq)
**Status:** NOT STARTED
**Branch:** `feature/phase-1-cloud-providers`
**Depends on:** Phase 0, existing `core-llm-anthropic`, `core-llm-openai`

Adds `core-llm-google` (Gemini — its own JSON shape via `generateContent`,
not OpenAI-compatible, but the same `LlmProvider` contract) and
`core-llm-groq` (OpenAI-compatible; can extend `core-llm-openai`'s
transport and override only base URL + model list — verified structurally
plausible: `core-llm-anthropic`/`core-llm-openai` each ship exactly two
files today, a `*Provider` and a `*Api`/`*MessagesApi`, a pattern
`core-llm-groq` can follow directly). Adds `core-conversations`
(Room-backed chat storage replacing `JsonFileConversationStore` for
Android — that class and the `ConversationStore` interface it implements
are real and exist today in `core-agent`; Room is genuinely new). Adds API
key entry in Settings, wired to the real `SecretsVault`/
`VaultBackedConfigSource` pair in `core-config` (both verified to exist,
CAP-013) via a new `KeystoreSecretsVault` backed by Android Keystore /
`EncryptedSharedPreferences`. Adds a model picker and a working end-to-end
chat screen (type → send → stream → display).

**Acceptance:** API keys enterable per provider; provider+model
selectable; chat streams a real response; conversations persist across
restarts; `./gradlew test` passes, and `core-llm-google`'s tests run
against a local mock server, never a live API.

---

### Phase 2 — Local Model Inference (llama.cpp / GGUF)
**Status:** NOT STARTED
**Branch:** `feature/phase-2-local-inference`
**Depends on:** Phase 1

Adds `core-llm-local`, filling the `ProviderType.LOCAL` slot with a real
llama.cpp JNI bridge (`core-llm/src/main/kotlin/.../ProviderType.kt`
already declares `LOCAL`/`SELF_HOSTED`/`CLOUD`; only `LOCAL` has zero
real-provider backing today). First NDK/CMake module in this repo — a
thin JNI shim only (`llama_jni.cpp` has `Java_*` entrypoints, no inference
logic of its own), linking a prebuilt `libllama.so` per ABI
(arm64-v8a/armeabi-v7a/x86_64). OpenCL (Adreno) and Vulkan backends
compiled in; CPU/OpenCL/Vulkan selection automatic by device capability,
user-overridable in Settings. Model management (`ModelMetadata`,
`ModelRepository` with SHA-256 integrity checking) extends
`core-conversations`.

**Correction to an earlier draft of this section:** that draft claimed
`AiProviderSelector` "already has latency/resource hooks" that this phase
would merely fill with real data. That's false as of 2026-09-14 —
`core-llm/src/main/kotlin/ai/droidcommand/llm/AiProviderSelector.kt`'s own
doc comment states plainly that P0.4 names latency and resource
(memory/GPU) as selection factors but "no real measurement of either
exists anywhere in this repository... Neither is implemented here."
`ProviderPreferences` today filters only on capability, context length,
`requireLocal`, and cost. This phase would be the **first** place real
latency/resource numbers exist in this codebase (via a `ModelBenchmark`
utility) — extending `ProviderPreferences`/`DefaultAiProviderSelector` to
actually use them is new work this phase must scope explicitly, not a
pre-existing hook being wired up.

**App size / distribution — raised directly by the project owner, not in
the original draft:** llama.cpp's native libraries (three ABIs) plus any
bundled or downloaded GGUF model (even a 1B-parameter Q4 quantization runs
several hundred MB) make this by far the heaviest phase in APK/storage
terms. Don't fold this into one ever-larger monolithic `app` APK by
default. Two real options, not mutually exclusive:
1. **Play Feature Delivery on-demand dynamic feature module** — same
   logical app, one Play listing, but the native libs + model-download
   flow ship as an on-demand module the base APK doesn't carry; a user who
   never enables local inference never downloads any of it. Simplest
   option if Play Store is the distribution target; no IPC design needed.
2. **A genuinely separate companion app**, communicating with `app` over
   AIDL/a bound service or intents, independently installable/
   uninstallable and reclaiming its own storage on removal. This is not a
   new pattern for this repo — `core-termux` already integrates with a
   separate, independently-installed app (Termux) purely through the
   public `com.termux.RUN_COMMAND` intent protocol, and `core-root`'s
   Magisk path assumes a separate privileged app too. A companion
   "DroidCommand AI: Local Models" app would extend that same established
   precedent rather than introduce a new one, and works outside Play
   distribution (sideloading/F-Droid-style), which this project has not
   ruled out — it has no `LICENSE` file and no stated distribution channel
   anywhere in its docs today.

Recommendation: default to (1) for the initial ship (far less design/IPC
surface), and treat (2) as the path if this project ends up outside Play
distribution — this is a design decision Phase 2 should make explicit and
record in its own addendum, not one this roadmap section can settle in
advance. The same choice applies to Phase 5's embedding model below.

**Acceptance:** downloads and runs a real GGUF model
(Llama-3.2-1B-Instruct-Q4_K_M minimum) fully offline; local↔cloud
mid-conversation switch works via the existing `AiProviderSelector`;
`./gradlew :core-llm-local:connectedAndroidTest` passes on an emulator
(loads a tiny test model, one inference call, non-empty output); every
downloaded model SHA-256-checked before load.

---

### Phase 3 — Prompt Templates + Skill Builder
**Status:** NOT STARTED
**Branch:** `feature/phase-3-templates-skills`
**Depends on:** Phase 1

Adds a user-facing prompt template library (`PromptTemplate`: named
`{{variable}}`-slotted reusable prompts, ~20 bundled across
coding/writing/analysis/creative categories) and a "Skill" concept
(`Skill`: name, description, system prompt, optional preferred
provider/model, ~5 bundled — Code Expert, Concise Assistant, Socratic
Teacher, Creative Writer, Research Analyst), both Room-backed, both
surfaced as bottom-sheet pickers in chat.

**Flag on the "Skill" entity, not in the original draft:** this repo
already ships a real, IMPLEMENTED `Persona`/`StyleProfile`/`PersonaManager`
stack (CAP-005, `core-agent`/`core-llm`) whose whole job is "a named,
reusable behavioral/style profile that contributes to
`LlmRequest.systemPrompt`" — the same job the proposed `Skill` entity
describes, down to the "system prompt + optional provider/model
preference" shape. Shipping `Skill` as a brand-new, parallel Room entity
risks exactly the "second, competing model manager concept" this
codebase's own doc comments (`ModelRouter`, `AiProviderSelector`) already
name and avoid elsewhere. Phase 3 should scope, up front, whether `Skill`
is genuinely a new concept (e.g. it needs Room persistence + a picker UI
`Persona` doesn't have today) or whether it's better built as **the UI/
Room-backed surface for the existing `Persona` type**, extended with the
two fields it's missing (`preferredProviderType`, `preferredModel`) rather
than a duplicate type with its own storage and its own drift risk from
`Persona` over time. This is a real design decision for that phase, not
one this section resolves.

**Acceptance:** bundled templates/skills seeded on first launch;
`{{var}}` slots expand to a fill-in form before send; user-created
templates/skills persist; an applied skill injects its system prompt on
every request; `./gradlew test` passes with no regressions.

---

### Phase 4 — Voice Input/Output + Web Search
**Status:** NOT STARTED
**Branch:** `feature/phase-4-voice-websearch`
**Depends on:** Phase 2, Phase 3

New `core-voice` module: `SpeechRecognizer`-based STT (on-device, works
offline with a local model active) and `TextToSpeech`-based TTS
(auto-speak/tap-to-speak/off, system voices only — no voice cloning; see
Out of scope). Optional, off-by-default web search: a chat-toolbar
toggle sends the query to a search API (Brave Search primary, SerpAPI
fallback), injecting the top results as system context before the LLM
call, reusing `core-remote`'s existing `JdkHttpTransport` rather than a
new HTTP client, with the API key stored in the same `SecretsVault` as LLM
keys.

**Acceptance:** mic button transcribes into the input field; responses
can be spoken aloud; web search is verified against a mock search server
in tests (same pattern as this repo's existing provider tests, never a
live API); every voice feature degrades to silent/no-crash on permission
denial.

---

### Phase 5 — Document Q&A (RAG)
**Status:** NOT STARTED
**Branch:** `feature/phase-5-document-rag`
**Depends on:** Phase 2

Drop a PDF/plain-text/Markdown file into a conversation and ask questions
about it: fixed-size chunking (512 tokens, 64 overlap), on-device
embedding via a small dedicated model (e.g. `nomic-embed-text` GGUF,
~270MB, loaded separately from the chat model), Room storage with a float
blob column and Kotlin-side cosine similarity (no SQLite vector extension
needed at this scale). PDF via Android's built-in `PdfRenderer` (no
third-party lib); DOCX/EPUB explicitly deferred (would need Apache POI or
similar, +5MB+). **Same app-size consideration as Phase 2's embedding
model applies here** — see that phase's note; the two downloadable-model
concerns (chat model, embedding model) should share one download/storage
strategy rather than each phase inventing its own.

**Acceptance:** a PDF can be attached; answers are grounded in its
content, verified against a known document + known Q&A pairs; the
embedding model downloads independently of chat models.

---

### Out of scope (explicitly excluded)

| Feature | Reason |
|---|---|
| Character cards / roleplay personas | Different product identity; not this app |
| Voice cloning | Needs a separate ML pipeline with no hook in this stack today |
| On-device image generation | Separate diffusion runtime; scope creep |
| Benchmark leaderboard UI | Not relevant to this project's scope |
| Subscription / proxy backend | Business decision, not (yet) an engineering task |
| NPU-specific kernels beyond OpenCL/Vulkan | Needs vendor-specific SDKs; deferred |
| PR auto-generation for this roadmap's phases | Disabled — see Workflow rules above |

**Note on this table, not in the original draft:** the earlier version of
this section named specific third-party products in a couple of these
rows (a roleplay-persona app, a named voice-cloning project, a
benchmark-leaderboard project) that appear nowhere else in this repo's
docs or history — there's no way to verify from this codebase whether
those were actually evaluated and rejected for this project, or carried
over from an unrelated template. The exclusions themselves are harmless
either way (excluding an unverified feature is a safe default), so they're
kept above in genericized form; don't cite the removed product names as if
this project had specifically evaluated them.

---

### Current module inventory (verified against `settings.gradle.kts`, 2026-09-14)

| Module | Type | Status |
|---|---|---|
| core-agent | JVM | IMPLEMENTED |
| core-llm | JVM | IMPLEMENTED |
| core-llm-anthropic | JVM | IMPLEMENTED |
| core-llm-openai | JVM | IMPLEMENTED |
| core-llm-factory | JVM | IMPLEMENTED |
| core-security | JVM | IMPLEMENTED |
| core-config | JVM | IMPLEMENTED |
| core-remote | JVM | IMPLEMENTED |
| core-shell | JVM | IMPLEMENTED |
| core-build | JVM | IMPLEMENTED |
| core-build-local | JVM | IMPLEMENTED |
| core-build-remote | JVM | IMPLEMENTED |
| core-tools-android | JVM | IMPLEMENTED (stubs) |
| core-apk-lifecycle | JVM | IMPLEMENTED (stubs) |
| core-root | JVM | IMPLEMENTED (stubs) |
| core-termux | JVM | IMPLEMENTED |
| core-mcp | JVM | IMPLEMENTED |
| core-integration-tests | JVM (test-only) | IMPLEMENTED |
| cli | JVM | IMPLEMENTED |
| core-prompt-regen | JVM | IMPLEMENTED |
| core-llm-google | JVM | PLANNED (Phase 1) |
| core-llm-groq | JVM | PLANNED (Phase 1) |
| core-conversations | Android (Room) | PLANNED (Phase 1) |
| core-llm-local | Android + NDK | PLANNED (Phase 2) |
| core-voice | Android | PLANNED (Phase 4) |
| app | Android | PLANNED (Phase 0) |

**Correction to an earlier draft of this table:** that draft omitted
`core-llm-factory`, `core-mcp`, `core-integration-tests`, `cli`, and
`core-prompt-regen` — all four real and IMPLEMENTED today — while labeling
itself "current... as of 2026-09-14." The table above is the actual
current state as of that date; re-verify against `settings.gradle.kts`
before trusting it further into the future, per this file's own standing
rule.
