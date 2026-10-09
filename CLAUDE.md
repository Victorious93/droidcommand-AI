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
- **`docs/SECURITY.md`** — the security requirements doc (2026-09-26),
  reconciling a Codex engineering brief's storage/networking/secrets/
  logging/tool-execution requirements against what `core-security`/
  `core-shell` already implement (`SecureToolExecutor`, `ApprovalFlow`/
  `RiskTier`, `GrantStore`, `AuditLog`, `CapabilityRegistry`,
  `ShellSecurityPolicy`) and what's gated on the still-PLANNED Android
  `:app` module (SAF, Android Keystore, Network Security Configuration).
  Update it as each gated item moves to IMPLEMENTED — don't let it go
  stale the way an earlier draft of this file's module count did.
- **`docs/KNOWLEDGE_GRAPH_PHASE_SCOPE.md`** — scoping for putting the existing knowledge graph into the APK: what exists, the real gaps,
  options A/B/C, risks and the owner questions. Owner chose Option A (2026-10-08); K1 (Room storage,
  `core-knowledge-android`), K2 (`GraphRetriever` + `ChatSession` `knowledge` param), K3
  (`ChatSession.rememberConversation()` + `knowledgeGraph` param) and K4 (the `:app` Memory screen,
  `MemoryController`/`MemoryViewModel`, and `ChatViewModel`'s persisted on/off gate + "Remember this
  chat" button) are all built. **Note on PR #132:** that PR's title/description claimed "K3 + K4", but
  its actual merged diff was K3 only — no `MemoryController`/`MemoryScreen`/`:app` wiring existed on
  `main` until a later session built K4 for real (see the audit addendum correcting this). Don't trust a
  PR description's claim of what shipped without checking the actual diff. Read this doc before
  starting any graph/memory work.
- **`docs/HACKERAI_SOURCE_AUDIT.md`** — a focused audit (2026-09-26) of
  `Victorious93/hackeraiETC` ("HackerAI"), the project owner's other repo,
  read against a migration brief that referred to it as "HackerGPT" (no
  such separate repo exists). Covers its AI agent/tool orchestration
  domain only (multi-agent delegation, runtime re-authorization, step-
  budget-reserved forced finalization, provider-error-category retry,
  evidence-gated security-finding schemas, the Strix pentest skill
  catalog) and classifies each against what this repo already has. No
  code changed; nothing in it is scheduled — read it before assuming any
  of those gaps still need scoping from scratch.
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
- **`docs/KAI_INTEGRATION.md`** / **`docs/KAI_INTEGRATION_PLAN.md`** /
  **`docs/CAPABILITY_REPAIR_AUDIT.md`** — the project owner's "integrate
  `Victorious93/Kai`'s useful capabilities into DCA" prompt and its
  corrected-intent follow-up (2026-10-07). The map document holds one
  `KAI-###` row per capability (chat, memory, personality, provider
  routing, local inference, MCP client, skills, tasks/heartbeat, Linux
  sandbox, Kai Build, generated UI, personal tools, data portability,
  Splinterlands), each read against Kai's own `docs/features/*.md` and
  decided against DCA's existing architecture — **architectural
  independence with selective, licensed reuse**, not a merge: DCA's build
  and runtime do not require a Kai checkout or running Kai service, and
  nothing here claims a formal clean-room process (Kai's docs/source were
  read directly; adaptation is tracked with attribution instead — see
  that document's own Licensing section for why the distinction matters).
  The plan document maps the capability map onto 6 dependency-ordered
  phases, all NOT STARTED except Phase 0's one concrete, tested item: `core-mcp.McpToolServer`'s
  executor is now typed as `core-agent.ToolRunner` (so it accepts
  `core-security.SecureToolExecutor`, not only the bare `ToolExecutor`),
  and a JSON array/object MCP tool-call argument is now preserved as its
  JSON text form instead of silently dropped. The repair-audit document
  is a self-audit against the corrected intent, written in the same
  session as the map/plan (there was no prior commit to recover) — it
  found and fixed one real defect (the clean-room mischaracterization
  above) and no others. Same governance gate as the Consumer Product
  Roadmap below applies: Phase 1 onward needs the owner's go-ahead before
  implementation starts.

## What's already been verified (don't re-derive)

- This is a 25-module pure-Kotlin/JVM Gradle project (confirmed directly
  against `settings.gradle.kts` 2026-10-07 — `core-rootforge`, the optional
  RootForge node client, is the 25th and was added after the 2026-10-06
  count of 24; the enumeration below predates it, and was first confirmed
  2026-10-06; the enumeration that follows
  stops at the 20th module, `core-prompt-regen` — the four security-tooling
  modules `core-tools-metasploit`, `core-tools-setoolkit`, `core-hackerai`
  and `core-pentest-swarm` came after it and are listed in the Consumer
  Product Roadmap's module inventory below; `core-mcp`, `core-integration-tests`
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

## When something isn't buildable in this environment (owner standing rule, 2026-10-09)

If a needed component can't be built here (Android SDK/NDK, native libs, model runtimes), don't stall
and don't fake it: **use an already-built application's or library's published, prebuilt package**
(release AAR/`.so`/JAR/APK) instead of compiling it. Guardrails, because "prebuilt" changes the risks,
not the honesty rules:

- **Pin and verify.** Pin an exact released version and record its SHA-256 (and source repo/release URL)
  in the addendum. Fetch only from the project's official release channel; never an unofficial mirror.
- **License first.** Check the package's license (and bundled components' licenses) before adding it.
  This repo has no `LICENSE` file, and GPL/AGPL code must not be linked in without an explicit owner
  decision (see the `android-code-studio` GPLv3 note above). Keep attribution.
- **Isolate behind a seam.** Consume it through an existing interface (e.g. `TextToSpeechEngine`) so the
  JVM tests still run against fakes and the dependency stays swappable.
- **Status stays honest.** A prebuilt dependency makes the build possible, not the feature verified:
  anything that hasn't run on a device/emulator stays ON DISK / UNVERIFIED in the audit.
- **Record it** in `docs/ARCHITECTURE.md` and a dated audit addendum, like any other component.

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

## Companion app fleet

DroidCommand AI is the hub of a small fleet of separate, independently
licensed and independently versioned Android companion apps, reached over
a local IPC/network boundary (AIDL binding or loopback REST) rather than
folded into `:app` — see `docs/ARCHITECTURE.md`'s §8 "Companion app fleet"
for the full per-app breakdown and `docs/AUDIT_2026-09-05.md`'s "Phase 3:
core-hackerai and core-pentest-swarm modules" (2026-09-28) and "VictorSuite
added to the companion app fleet" (2026-09-29) addenda for status. Three
repos, three different stages — don't re-derive this list, and don't
inflate any entry's status past what those addenda actually say:

- **`Victorious93/hackeraiETC`** ("HackerAI") — `core-hackerai` (this repo)
  is a Kotlin port of its subagent-orchestration logic; the companion AIDL
  binding it will eventually run over is still PLANNED.
- **`Victorious93/Pentest-Swarm-AI`** (a fork of the AGPL-3.0
  `Armur-Ai/Pentest-Swarm-AI` — keep that upstream attribution and license
  intact) — `core-pentest-swarm` (this repo) is a real REST client for its
  Go server (loopback, port 18080); needs that project's own companion APK
  installed and its server running.
- **`Victorious93/VictorSuite`** (GPL, Termux/ZeroTermux-derived) —
  scoped 2026-09-29b: **already covered by `core-termux`, no new module
  needed.** VictorSuite ships under Termux's own `applicationId`
  (`com.termux`), unmodified `RUN_COMMAND` contract included, so
  `AdbTermuxExecutor`'s existing `"com.termux"` default already reaches
  it — DCA can't and doesn't need to distinguish a VictorSuite install
  from genuine Termux, though the two can never be installed on the same
  device (colliding package identity). VictorSuite's real differentiators
  (multi-distro switching, backup/restore, its plugin framework) are
  UI-only and unreachable from `RUN_COMMAND` — closing that gap needs new
  surface area in VictorSuite's own repo, out of scope here. See
  `docs/AUDIT_2026-09-05.md`'s addendum for the full record.

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
- **After every push, ask about a PR.** Standing instruction from the project
  owner (2026-10-07): whenever you push an update to the remote, end your
  reply by asking whether they want a pull request opened — or, if one is
  already open for the branch, say that the push updated it and ask whether
  they also want a new one. Asking is not opening: still never open a PR
  until they say so.
- **No fabricated completions.** Every file delivered must be genuine,
  compilable, tested Kotlin — this is the same honesty convention this repo
  already applies everywhere else (`Null*`/`Mock*` self-documentation, the
  audit's VERIFIED IMPLEMENTED/PARTIAL/STUB/MISSING grading). Never mark
  something IMPLEMENTED unless `./gradlew test` passes for that
  module/slice; state exactly what was tested and what was not.
- **Preserve all existing modules.** The 24 modules (plus `core-rootforge`, added 2026-10-07 — 25 total) currently in
  `settings.gradle.kts` (`core-agent`, `core-llm`, `core-security`,
  `core-config`, `core-remote`, `core-build`, `core-tools-android`,
  `core-shell`, `core-apk-lifecycle`, `core-root`, `core-termux`,
  `core-llm-anthropic`, `core-llm-openai`, `core-llm-factory`,
  `core-build-local`, `core-build-remote`, `core-mcp`,
  `core-integration-tests`, `cli`, `core-prompt-regen`,
  `core-tools-metasploit`, `core-tools-setoolkit`, `core-hackerai`,
  `core-pentest-swarm`) are source of truth. New work wires to them; it
  doesn't rewrite them unless a real bug is found. **Correction to an
  earlier draft of this section:** that draft said "19 existing pure-JVM
  modules" and listed 13 of them, omitting `core-llm-factory`, `core-mcp`,
  `core-integration-tests`, `cli`, and `core-prompt-regen` entirely — that
  was a stale/incomplete snapshot, not this repo's real state. The four
  security-tooling modules (`core-tools-metasploit`, `core-tools-setoolkit`,
  `core-hackerai`, `core-pentest-swarm`) were added since then. Re-check
  `settings.gradle.kts` at the start of each phase rather than trusting
  this count once it ages, per this file's own standing rule above.
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
**Status:** ON DISK — `:app:assembleDebug` VERIFIED 2026-10-07 (2026-10-07e addendum); NOT launched/tested on a device or emulator. Android modules are opt-in: add `includeAndroid=true` to user-level `~/.gradle/gradle.properties` and set `ANDROID_HOME`.
**Branch:** `feature/phase-0-android-shell` (merged to `main` via PR #104)
**Depends on:** nothing new — wires to existing `core-llm`, `core-agent`, `core-config`

The `app/` module exists on disk with real Kotlin/Compose/Hilt source
(Hilt DI, Compose navigation, stub screens for Chat/Home/Settings/Tools
including the Metasploit and SET UI stubs). It is **not** included in
`settings.gradle.kts` because this JVM-only environment has no Android
SDK/AGP. It has never been build-verified — `./gradlew :app:assembleDebug`
has not been run against it; the plugin/dependency versions in
`app/build.gradle.kts` are best-effort starting points, not build-confirmed.
See `docs/AUDIT_2026-09-05.md`'s `app/` row and the "Phase 0 Compose app
scaffold" entry in PR #104 for the full record.

**Acceptance (still unmet):** `./gradlew :app:assembleDebug` succeeds; app
launches on an emulator (API 26+) with stub screens; `./gradlew test` still
passes across all existing JVM modules (24 when this was written; 25 with `core-rootforge`) (zero regressions in anything above).
This requires a real Android SDK — it cannot be verified in this JVM-only
environment. The module inventory table below records `app` as
ON DISK, EXCLUDED rather than IMPLEMENTED for exactly this reason.

---

### Phase 1 — Multi-Provider Cloud BYOK (GPT, Claude, Gemini, Groq)
**Status:** IN PROGRESS (started 2026-10-07 on owner go-ahead) — JVM half done: `core-llm-google` and `core-llm-groq` implemented and wired into `core-llm-factory` (`google`/`gemini`/`groq`). Android half: Room store (8 JVM/Robolectric tests), Keystore vault glue, Settings key entry, model picker and streaming chat screen COMPILE and `:app:assembleDebug` succeeds; chat core is JVM-tested against mock servers (2026-10-07f). Never launched on a device, never called a live API. See the 2026-10-07 Phase 1 audit addendum.
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
**Status:** JVM slice on `main` (PR #119, 2026-10-07; reviewed and fixed 2026-10-07g) — `core-llm-local` (`LocalLlmProvider`, SHA-256-verifying `ModelRepository`, `BackendSelector`, `ModelBenchmark`) tested against FAKE backends only. Android/NDK half, Phase A–C done (2026-10-08): `core-llm-local-android` fetches and builds llama.cpp (pinned b11484, CPU-only, arm64-v8a/x86_64 — `:assembleDebug` verified in an earlier session's container) and now has a JNI shim + `LlamaCppBackend` (`InferenceBackend`), verified only by host `g++ -fsyntax-only` against the real pinned headers in this session's container (no Android-SDK/network access here to attempt `:assembleDebug` — see the 2026-10-08 Phase C audit addendum). Still NO device/emulator/model has ever run real inference. Phase D (OpenCL/Vulkan) and Phase E (`connectedAndroidTest`) not started. Not wired into `core-llm-factory` or the app. Distribution decided: dynamic feature module (see 2026-10-07 addendum).
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
**Status:** IN PROGRESS — JVM half `core-templates` and Room storage `core-templates-android` done (9 Robolectric tests; never run on a device). Pickers, fill-in form and chat-screen skill application NOT STARTED (need `:app` UI). `Skill` kept separate from `Persona` (see addenda).
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
**Status:** ON DISK — web search (`core-websearch`, `ChatSession` toggle) and voice logic (`core-voice`) JVM-tested; `core-voice-android` wrappers and `:app` toolbar/mic/speak/Settings glue UNBUILT (no Android SDK) and never run on a device. See the 2026-10-09b/c audit addenda.
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
**Status:** IN PROGRESS — JVM slice `core-rag` (chunker, cosine store, `FileVectorStore`, `DocumentRetriever`, strict plain-text loader, `ChatSession` `documents` param) and the `LocalEmbedder` seam are JVM-tested with FAKE embedders only (2026-10-09i–k). `LlamaCppEmbeddingBackend` (JNI, `core-llm-local-android`): the native shim is RUNTIME VERIFIED on a host with a real model (nomic-embed-text-v1.5 Q4_K_M, 768-dim, 2026-10-09o); the Kotlin wrapper is not compiled or run (no Android SDK), and nothing has run on a device. Still missing: PDF extraction, Room storage, `:app` attach UI, grounded-answer test with a real model. See the 2026-10-09m reconciliation and the 2026-10-09n review fixes (use-after-free, model-own pooling, nomic query/document prefixes).
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

### Phase 6 — Subscription Accounts (BYOK Alternative)
**Decision (2026-10-08):** Option A, standalone — a Play subscription unlocks a "Pro" tier on top of BYOK (local-model downloads, document RAG, extended templates/skills). No key substitution, no backend. JVM slice `core-billing` done; Play Billing Library glue + UI NOT STARTED.
**Status:** IN PROGRESS (JVM slice only) — scoping requested by the owner 2026-10-08
(off the Social-Engineer Toolkit/Settings screenshots asking for "login for
subscriptions, not just API keys"); no code written for this phase.
**Branch:** `feature/phase-6-subscriptions` (not yet created)
**Depends on:** Phase 1 (cloud providers — a subscription is a second,
parallel way to reach the same `LlmProvider` call sites Phase 1 already
wires)

**Note on numbering:** this is the Consumer Product Roadmap's own Phase 6,
unrelated to `docs/ARCHITECTURE.md` §8's "Phase 3 / Phase 4 / Phase 6" label
for the companion-APK work — that numbering comes from a different, older
phase scheme for the companion app fleet. Don't conflate the two.

Today `core-config.SecretsVault`/`SettingsScreen` only support BYOK: the user
supplies their own Anthropic/OpenAI/Google/Groq key, stored in
`KeystoreSecretsVault`. "Login for subscriptions" means an account the user
signs into that entitles them to use the app without supplying their own
key, metered against a paid plan — a different model entirely. **This is new
product infrastructure, not a UI change**: nothing resembling an account
system, payment processor, or token-proxy server exists anywhere in this
25-module codebase today. An earlier draft of this roadmap named exactly
this ("Subscription / proxy backend") as out of scope — see the correction
to that table below.

Two real shapes, not mutually exclusive, with very different engineering
cost:

**Option A — Google Play Billing only.** Subscriptions sold entirely through
the Play Store; entitlement checked on-device via the Play Billing Library
(`purchaseState`, `acknowledgePurchase`). No login, no payment processor of
our own — Google is both the identity provider (the signed-in Play account)
and the processor. A new `core-billing` module would query entitlement and,
if granted, substitute a server-held key for the user's own BYOK key — which
immediately raises the problem this option alone doesn't solve: **a
server-held key still has to live somewhere the client can reach it**,
meaning either (a) a backend anyway, purely as a key-vending/LLM-proxy
endpoint (so a raw provider key is never embedded in or fetched
unauthenticated into the APK), or (b) accepting a shared production key
baked into the client, which is not a real security posture. Option A alone
avoids building our own login/identity system (Play already provides that);
it does not by itself avoid needing a backend if the product promise is
"subscribe and the app just works without your own key."

**Option B — Full account system + LLM-proxy backend.** Email/password or
OAuth (Google/Apple Sign-In) login against a server we run; that server
holds the real provider API keys, meters usage per account/plan, and proxies
chat requests (`LlmProvider`'s existing request/response shapes could be
reused as the wire format, since the client already speaks them). Payment
via Play Billing (mobile) and/or Stripe (if a web account-management surface
is ever wanted). This is the only option that actually delivers "log in, no
API key needed, works across devices" — and it is real, multi-week
infrastructure: an auth provider, an accounts/entitlements database, a
metering/rate-limit layer, and a proxy service deployed and operated
somewhere with its own on-call/security surface (it would hold every
subscriber's effective LLM spend behind one set of provider keys — a
meaningfully higher-value attack target than today's per-user BYOK vault).
None of this exists in `core-*` today; it would not live in this Gradle
project at all except for the client-side login/billing UI and the
proxy-aware `LlmProvider` implementation that talks to it.

**Recommendation:** don't start Option A's entitlement-check code until one
explicit decision is made up front — either (i) ship Option A standalone,
gating something Play Billing alone can gate with no key substitution at all
(e.g. a usage ceiling raise on BYOK, or Phase 2's heavier local-model
downloads), or (ii) commit to also building Option B's proxy backend, with
Option A's entitlement check becoming one input to it. Discovering "where
does the server key live" only after Option A ships is the failure mode to
avoid. This is a product/business decision this roadmap can describe but not
make.

**Acceptance:** not yet defined — to be written against whichever option is
chosen, per this roadmap's own precedent of scoping each phase against the
actual codebase before implementing, not in advance of that choice.

---

### Out of scope (explicitly excluded)

| Feature | Reason |
|---|---|
| Character cards / roleplay personas | Different product identity; not this app |
| Voice cloning | Needs a separate ML pipeline with no hook in this stack today |
| On-device image generation | Separate diffusion runtime; scope creep |
| Benchmark leaderboard UI | Not relevant to this project's scope |
| NPU-specific kernels beyond OpenCL/Vulkan | Needs vendor-specific SDKs; deferred |
| PR auto-generation for this roadmap's phases | Disabled — see Workflow rules above |

**Correction (2026-10-08):** "Subscription / proxy backend" was listed here
as out of scope in an earlier draft. It has since been scoped as **Phase 6**
above, at the owner's request — removed from this table because it is no
longer excluded, only not-yet-started pending the Option A/B decision that
phase describes. Don't re-add it here without re-removing Phase 6.

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

### Current module inventory (verified against `settings.gradle.kts`, 2026-09-29)

25 modules are wired into the build via `include(...)` in
`settings.gradle.kts`. `app/` also exists on disk with real source but is
deliberately **not** in `settings.gradle.kts` (gated on an Android SDK this
environment does not have — see the excluded-module comment at the bottom of
`settings.gradle.kts`).

| Module | Type | Status |
|---|---|---|
| core-agent | JVM | IMPLEMENTED |
| core-llm | JVM | IMPLEMENTED |
| core-security | JVM | IMPLEMENTED |
| core-config | JVM | IMPLEMENTED |
| core-remote | JVM | IMPLEMENTED |
| core-build | JVM | IMPLEMENTED |
| core-tools-android | JVM | IMPLEMENTED (stubs) |
| core-shell | JVM | IMPLEMENTED |
| core-apk-lifecycle | JVM | IMPLEMENTED (stubs) |
| core-root | JVM | IMPLEMENTED (stubs) |
| core-termux | JVM | IMPLEMENTED |
| core-llm-anthropic | JVM | IMPLEMENTED |
| core-llm-openai | JVM | IMPLEMENTED |
| core-llm-factory | JVM | IMPLEMENTED |
| core-build-local | JVM | IMPLEMENTED |
| core-build-remote | JVM | IMPLEMENTED |
| core-mcp | JVM | IMPLEMENTED |
| core-integration-tests | JVM (test-only) | IMPLEMENTED |
| cli | JVM | IMPLEMENTED |
| core-prompt-regen | JVM | IMPLEMENTED |
| core-tools-metasploit | JVM | IMPLEMENTED |
| core-tools-setoolkit | JVM | IMPLEMENTED |
| core-hackerai | JVM | IMPLEMENTED |
| core-pentest-swarm | JVM | IMPLEMENTED |
| core-rootforge | JVM | IMPLEMENTED (passive operations only; 2026-10-07) |
| app | Android | ON DISK, opt-in via includeAndroid (assembleDebug verified 2026-10-07; never run on a device) |
| core-llm-google | JVM | IMPLEMENTED (mock-tested, not live-verified) |
| core-llm-groq | JVM | IMPLEMENTED (mock-tested, not live-verified) |
| core-conversations | Android (Room) | ON DISK, opt-in (8 Robolectric tests pass; never run on a device) |
| core-llm-local | JVM (backend seam) | IMPLEMENTED (JVM slice, fake-backend tested; no llama.cpp/JNI/NDK) |
| core-voice | Android | PLANNED (Phase 4) |

**Correction to earlier drafts of this table:** the 2026-09-14 draft
labeled itself "current" while omitting five modules that existed then
(`core-llm-factory`, `core-mcp`, `core-integration-tests`, `cli`,
`core-prompt-regen`). Its corrected form was then itself left to age. The
table above (2026-09-29) adds the four security-tooling modules built since
(`core-tools-metasploit`, `core-tools-setoolkit`, `core-hackerai`,
`core-pentest-swarm`, all in `settings.gradle.kts`) and re-classifies `app`
from PLANNED to ON DISK, EXCLUDED — it now holds real Kotlin/Compose/Hilt
source but is still not in the build and has never been build-verified here.
Re-verify against `settings.gradle.kts` before trusting this table further
into the future, per this file's own standing rule.

---

## RootForge OS (external node — NOT a companion)

`core-rootforge` (2026-10-07) lets DCA optionally ask an independently installed RootForge
OS machine questions (capabilities, attached adb/fastboot devices) over an SSH-carried JSON
protocol. RootForge is an independent product: it is **exempt from the companion
`DependencyGuard`** and from the AIDL/signature-permission model below — never add either to
it. Read `docs/ROOTFORGE_INTEGRATION.md` before touching the module; the canonical protocol
lives in the `rootforge-os` repo (`docs/DROIDCOMMAND_INTEGRATION.md`). Only passive reads
exist; flashing, restore, builds, jobs and terminals are deliberately absent until RootForge
repairs the gates listed there.

## Companion APK Integration

DCA integrates with two companion APKs via Android Bound Services:

- **HackerAI companion** (`hackeraiETC/android/`) — exposes HackerAI's agent
  orchestration, skill catalog, and finding validation via `IHackerAIService`
- **Pentest-Swarm companion** (`Pentest-Swarm-AI/android/`) — exposes the Go
  pentestswarm binary's campaign/chain/playbook capabilities via
  `IPentestSwarmService`

**For session orientation on the full integration protocol**, read
`COMPANION_PROTOCOL.md` at the repo root before modifying AIDL contracts,
adding companion capabilities, or working on the modules below.

**Modules involved (all verified 2026-09-29):**

| Module | Location | Type | Status |
|---|---|---|---|
| `core-hackerai` | `droidcommand-AI/core-hackerai/` | JVM | IMPLEMENTED |
| `core-pentest-swarm` | `droidcommand-AI/core-pentest-swarm/` | JVM | IMPLEMENTED |
| `core-companion` | `droidcommand-AI/core-companion/` | Android (excluded from build) | IMPLEMENTED (source only) |
| HackerAI companion APK | `hackeraiETC/android/` | Android standalone | IMPLEMENTED (source only) |
| Pentest-Swarm companion APK | `Pentest-Swarm-AI/android/` | Android standalone | IMPLEMENTED (source only) |

**Critical rule — AIDL sync**: The AIDL files in each companion repo are
copies of the canonical files in `core-companion/src/main/aidl/`. A diverged
copy causes a `RemoteException: Binder interface mismatch` at runtime with no
compile-time warning. Always update all three locations together. Details in
`COMPANION_PROTOCOL.md` § "AIDL Contract Sync Rule".
