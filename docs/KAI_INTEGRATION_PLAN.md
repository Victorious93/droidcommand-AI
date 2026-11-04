# Kai integration — phased implementation plan

Companion to `docs/KAI_INTEGRATION.md` (the `KAI-###` map). Maps that
map onto dependency-ordered phases from the owner's prompt §4.

**Numbering:** the owner prompt's six phases are 1–6 (Foundation →
Hardening). A 2026-10-07 draft in this repo numbered Foundation as
"Phase 0". This document uses the owner's numbers. Historical audit
addenda that say "Phase 0" mean the same Foundation slice.

**Governing rules** (Consumer Product Roadmap + owner prompt; this
gate is not removed):

- **One phase at a time.** Do not start phase N+1 until the human
  approves phase N.
- **No fabricated completions.** A phase is done only when its stated
  tests pass; say what was not run.
- Every landing updates `docs/ARCHITECTURE.md` and appends a dated
  `docs/AUDIT_2026-09-05.md` addendum (append-only).
- **Do not change** the inbound 13 included / 4 excluded
  `COMPANION_ASSIST` list, cert gating, `externalControlEnabled`
  default (OFF), or destructive `confirmation_required`.
- Never commit keys or passwords.

---

## Phase 1 — Foundation (this session)

**Status: COMPLETE for the device-free slice.** Android APK/device
checks remain the same pre-existing environment limit (`ANDROID_HOME`
unset here); they are recorded, not faked.

Owner-prompt scope: "capability map, baseline, Android build
inclusion/configuration, compatible transport, settings/secrets/
persistence and tool-schema/security prerequisites."

### Baseline (re-established 2026-10-30)

- Branch: `cursor/kai-foundation-4619` from `origin/main` `c9d9116`.
- Kai cloned read-only to `/tmp/kai-readonly` at
  `3818b2fccc0fb9bc977ab198b8bdb4a647dbab47`. Not modified.
- Toolchain: JDK 21, Gradle wrapper, no `ANDROID_HOME` /
  `ANDROID_SDK_ROOT`, no `adb` on PATH.
- `settings.gradle.kts`: always-on JVM modules plus opt-in Android
  via `-PincludeAndroid=true` / user `gradle.properties`. `:app` is
  **not** force-included (would break the JVM-only CI job).
- Recent `main` reviewed so this work does not collide with Claude
  Code's inbound COMPANION_ASSIST / CODE_ASSIST / cert-pin commits.

### Done this session (code)

1. **`AssistantCapabilitySettings`** (`core-config`) — nine
   Kai-integration gates, all default **false** (MCP client, Linux
   environment, background automation, mailbox, SMS, cloud transfer,
   generated UI, skill scripts, Kai Build). Loaded from `ConfigSource`
   (`DROIDCOMMAND_*_ENABLED`) and persisted via
   `InMemoryAssistantCapabilitySettingsStore` /
   `FileAssistantCapabilitySettingsStore`. Corrupt files fail closed
   to all-off. The type has **no** `externalControlEnabled` field;
   an imported `externalControlEnabled=true` line is ignored.
2. **Tool schema + call ids** (`core-agent`) — optional
   `ToolSpec.parameterSchema`, `ToolArgumentValue` /
   `ToolArgumentCodec` (JSON → typed values → existing
   `Map<String, String>`), `ToolCallEnvelope` with a new call id per
   invocation. `McpToolServer` uses the codec (behavior-preserving
   for untyped tools) and **refuses** a schema mismatch before
   `ToolRunner.run`.
3. **`PlatformHttpTransport`** (`core-remote`) — on Android returns
   `HttpUrlConnectionTransport`; on a desktop JVM returns
   `JdkHttpTransport`. `:app` already injects
   `HttpUrlConnectionTransport` explicitly; this factory documents
   the choice for new call sites.

### Already on `main` (counted as Foundation, not re-implemented)

- `McpToolServer` executor is `ToolRunner` (accepts
  `SecureToolExecutor`); nested JSON args preserved; JSON `null`
  omitted (2026-10-07).
- `HttpUrlConnectionTransport` + `:app` Hilt binding (Consumer
  Phase 1).
- `EncryptedSecretsVault` / `KeystoreSecretsVault`.
- Android modules opt-in; CI `android-apk.yml` /
  `android-emulator.yml` exist on `main`.
- `LlmProvider` streaming contract.
- Inbound 13/4 catalogue and remote-control OFF-default — **left
  untouched**.

### Not done (named, not silently dropped)

- `:app:assembleDebug` / emulator — no Android SDK in this
  environment. Next step when SDK is present: `-PincludeAndroid=true
  ./gradlew :app:assembleDebug`.
- First-class `Tool.execute` signature widening (typed map on every
  tool) — still a separately scoped change; the codec is the
  compatibility adapter the prompt asked for first.
- Any Phase 2 feature (MCP client, memory taxonomy, persona UI).

### Phase 1 exit criteria

| Criterion | Result |
|---|---|
| Capability map current | yes |
| Baseline recorded | yes |
| Android inclusion documented, not force-enabled | yes |
| Compatible transport factory + existing Android transport | yes |
| Settings default OFF + persistence | yes |
| Secrets vault unchanged / still used | yes |
| Tool-schema/security adapter on MCP path | yes |
| 13/4 inbound list unchanged | yes |
| `externalControlEnabled` default false | yes |
| Destructive confirmation_required unchanged | yes |
| `./gradlew test ktlintCheck` (JVM, no Android SDK) | 2568 tests, 0 failures, 0 errors, 20 skipped (pre-existing device-only); ktlint green |

**Approval required to start Phase 2:** explicit owner go-ahead to
implement **Phase 2 — Core assistant** as scoped below. Do not treat
this PR merge (if any) as that go-ahead.

**2026-10-31 Brain note:** the owner separately asked for a markdown
Brain (this repo: `docs/BRAIN_JARVIS_BOTMAKER_PLAN.md`). That slice
adds the markdown-vault half of KAI-002 behind a new `BRAIN` gate.
It is **not** Phase 2 and does not start MCP client, persona UI, or
Kai category/promotion work. **2026-10-31e:** the same vault grew
into a from-scratch notes app (file tree, graph, daily notes, AI
read/write switch OFF). Still not Phase 2.

---

## Phase 2 — Core assistant (NOT STARTED — needs approval)

**Depends on:** Phase 1 (done).

**Scope:**

- KAI-002 — extend `KnowledgeStore` / Second Brain with Kai's four
  categories, hit-count reinforcement, and a reversible promotion
  path into `Persona`. Budget via `TokenBudgetManager`.
- KAI-003 — Settings UI to edit `Persona` / `StyleProfile`; document
  precedence (user persona vs skill vs policy).
- KAI-006 — new `core-mcp-client`: discovery, connection settings,
  invocation. Each imported tool is a `core-agent.Tool` through
  `SecureToolExecutor`. **Off** until
  `AssistantCapabilitySettings.mcpClientEnabled`.
- KAI-001 remainder (no attachments yet) — markdown/copy/share and
  visible provider-attempt status in `:app` if an Android build
  target is available; otherwise JVM-only helpers only.
- KAI-004 remainder — visible attempt status; still no unapproved
  local-to-cloud fallback.

**Out of this phase:** LiteRT, SKILL.md, PRoot, Kai Build, heartbeat
daemon, generated UI, Splinterlands, changing inbound callers or
certs.

**Acceptance:** memories persist and promote with tests; a configured
MCP server's tools run only through `ToolRegistry` /
`SecureToolExecutor`; `mcpClientEnabled=false` starts no connections;
`./gradlew test` has no new failures; inbound 13/4 still frozen.

**Each item still gets a Plan-Mode pass at implementation time** —
this plan does not freeze class names beyond the settings/schema
seams Phase 1 already shipped.

---

## Phase 3 — Local intelligence and skills (NOT STARTED)

**Depends on:** Phase 2 (skills reuse the tool-registry pattern the
MCP client establishes).

- KAI-005 — optional `core-llm-litert` beside GGUF; shared digest/
  download/lifecycle.
- KAI-007 — SKILL.md parse/install/slash-command; install ≠ tool
  grant; scripts need `skillScriptsEnabled` + an execution target.
- KAI-008 partial — TIME/CRON/HEARTBEAT taxonomy on `MacroScheduler`.

---

## Phase 4 — Developer workspace (NOT STARTED)

**Depends on:** Phase 3 if script-bearing skills share the runtime.

- KAI-009 — `core-proot` as an `ExecutionTarget`; Termux/VictorSuite
  first; independent PRoot binaries + GPL notices; `linuxEnvironmentEnabled`.
- KAI-010 — Kai Build on that runtime; `BuildPipeline` only after
  tooling is verified; `kaiBuildEnabled`.

---

## Phase 5 — Automation and interaction (NOT STARTED)

- KAI-008 remainder — heartbeat active hours; Android daemon with
  honest Doze/time-limit behavior; `backgroundAutomationEnabled`.
- KAI-012 remainder — `fetch_url` SSRF; calendar/mail/SMS behind
  real permissions + grants; fixtures, no live sends in tests.
- KAI-011 — bounded kai-ui schema/parser, then Compose renderer;
  `generatedUiEnabled` off disables interaction.
- KAI-013 remainder — selective export/import that cannot silently
  enable MCP/schedules/tools or export secrets unless chosen.

---

## Phase 6 — Hardening and optional features (NOT STARTED)

Migration/regression across landed phases; resource/idle-release
measurement on a real device; KAI-014 Splinterlands **only** if the
owner asks by name.

---

## What this plan will not do

- Pre-scope exact Phase 2–6 method signatures beyond the Phase 1
  seams.
- Force-include `:app` in the default JVM build.
- Copy Kai's PRoot/talloc binaries.
- Turn on `externalControlEnabled` or add inbound callers.
- Treat Kai marketplace defaults, auto-reconnect-on-import, or
  unlimited memory injection as DCA defaults.

## Current approval checkpoint

**Phase 1 (Foundation) is the last permitted phase for this PR.**

To continue, the owner needs to approve **Phase 2 — Core assistant**
explicitly (memory taxonomy + persona UI + MCP client, all still
fail-closed behind the settings Phase 1 shipped). Until that
approval, no Phase 2 code.
