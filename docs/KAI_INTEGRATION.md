# Kai → DroidCommand AI capability map

This document records an evidence-based review of `Victorious93/Kai`
against this repo's existing architecture, per the project owner's
"Integrate Kai capabilities into DroidCommand AI" prompt. It is the
`KAI-###` companion to `docs/AUDIT_2026-09-05.md` and
`docs/CAPABILITY_ROADMAP_PROMPT.md`. Read those first for what DCA
already has; this document only records what Kai adds on top, and the
decision for each area.

**Architectural independence.** DCA keeps its identity, modules, security
pipeline, and companion-app integrations. Kai is a **read-only
reference**. This build does not require a Kai checkout, submodule, or
running Kai service. Adaptation is licensed reuse with attribution, not
a merge and not a formal clean-room process (Kai's docs/source were read
directly).

**Commits reviewed this session (2026-10-30):**

- DroidCommand AI `main`: `c9d9116fe1ea778cd686afb6190a3f5191f9a08c`
  (`fix: fleet live connect bundle (#258)`) is the branch point for
  this refresh. The owner's prompt
  named `ba1deea41a22f516ea22cffadfcefc098550d8e1` as a 2026-10-07
  navigation anchor, not a downgrade target. Recent `main` commits
  reviewed before branching include inbound COMPANION_ASSIST (#256),
  VictorFiles CODE_ASSIST (#255), cert-pin fail-close (#254), HackerAI
  `executeEnvelope` (#252), and bugsweep #257.
- Kai `main`: `3818b2fccc0fb9bc977ab198b8bdb4a647dbab47` (prompt
  anchor; still HEAD on 2026-10-30). Cloned read-only, `--depth 1`,
  not modified.

**Method:** Kai's `docs/features/` (21 markdown files, each "Last
verified: 2026-10-02") were listed and the rows below re-checked against
the files they name. DCA overlap was re-read against current
`settings.gradle.kts`, `core-*` sources, and the latest audit addenda —
not the 2026-10-07 module count. No Kai file was copied into this tree.

**Inbound / remote-control constraints this map does not change:**

- 13 included / 4 excluded inbound `COMPANION_ASSIST` callers
  (`docs/ENTRY_POINTS.md`, `InboundEntryPointCatalog`). Cert pins stay
  in `KnownNodeSigningCatalog` / `KnownFleet` only.
- `RemoteControlConfig.externalControlEnabled` stays **false** by
  default.
- Destructive actions stay `confirmation_required`.

**Format:** one row per capability. **Decision** is one of: *extend
existing*, *adapt source* (non-verbatim rewrite of documented behavior
after reading Kai), *new adapter*, *optional later phase*, *blocked*.
Implementation status is separate from the decision.

---

## KAI-001 — Rich chat (history, streaming, attachments, markdown/kai-ui, model selection)

**Kai:** `ui/chat/`, `data/RemoteDataRepository.kt`, `ui/markdown/`.
Compose chat: history, per-provider streaming, image/PDF attachments
gated per model, markdown including a `kai-ui` fence (KAI-011),
copy/share, live model switching, visible fallback attempts.

**DCA overlap (2026-10-30):** `ConversationStore` /
`JsonFileConversationStore` / Android `RoomConversationStore` persist
chats. `:app` Chat screen + `ChatSession` stream through
`LlmProvider.completeStreaming` / `StreamingLlmProvider.stream` (no
longer request/response-only). Model picker and Settings key entry
exist. `LlmRequest` still has **no** image/PDF attachment type.

**Destination:** `app` (UI), `core-llm` (already has streaming).

**Decision:** *extend existing* for remaining UI (markdown/math, copy/
share, visible provider-attempt status); *optional later phase* for
attachments (needs a real `LlmRequest` modality field and per-provider
limits). Do not treat a stub screen as done.

**Implementation status:** PARTIAL — streaming + history + model
selection exist; attachments and kai-ui rendering do not.
**Acceptance (later):** streamed tokens render incrementally; a
provider that cannot take an image fails clearly rather than pretending.

---

## KAI-002 — Persistent memory (categorized, reinforced, promotable)

**Kai:** `data/MemoryStore.kt`, `data/ChatSystemPromptBuilder.kt`,
`data/HeartbeatManager.kt`. Four categories (General/Learning/Error/
Preference), hit-count reinforcement, promotion into soul text, tools
`memory_store` / `memory_learn` / `memory_forget` / `memory_reinforce` /
`promote_learning`. Injected into the system prompt (2 000-char on-device
cap; uncapped remote).

**DCA overlap:** `KnowledgeStore` / `KnowledgeGraph` / Second Brain
`memory/` (`MemoryClass`, `MemoryWriter`, `MemoryRetriever`,
`TokenBudgetManager`) is a **richer** graph store. It does not yet
carry Kai's four-category taxonomy, hit-count promotion, or those five
tools. Do not add a second KV store.

**Destination:** `core-agent.KnowledgeStore` / `memory/` (extend).

**Decision:** *extend existing*. Budget through `TokenBudgetManager`,
not a second 2 000-char constant. New tools register through
`ToolRegistry` / `SecureToolExecutor`.

**Implementation status:** NOT STARTED (logic extensions). Graph +
budgeted retrieval already exist.
**Acceptance (Phase 2+):** restart-surviving memories; visible
reinforcement; promotion into `Persona` is user-visible and reversible.

---

## KAI-003 — Personality / system instructions

**Kai:** `data/ChatSystemPromptBuilder.kt`, `data/AppSettings.kt` soul
text. One free-text soul plus baked-in honesty/tool-use sections the
user cannot strip while tools exist.

**DCA overlap:** `Persona` / `StyleProfile` / `PersonaManager` is
already richer. No Compose editor for an active persona was found
under `:app` this session. Precedence between persona, `core-templates`
`Skill`, and `SecureToolExecutor` policy is not a single documented
stack.

**Destination:** `core-agent.Persona`, `:app` Settings.

**Decision:** *extend existing* (UI + documented precedence). Do not
add a parallel "soul" string.

**Implementation status:** NOT STARTED (UI). Domain model exists.
**Acceptance:** editing a persona changes the next turn's system
prompt; policy denials still win over persona text.

---

## KAI-004 — Provider configuration, fallback, streaming

**Kai:** `data/Service.kt`, `data/RemoteDataRepository.kt`. Multiple
configured instances, ordered fallback, capability discovery, visible
attempt status.

**DCA overlap:** `MultiLlmConfigLoader`, `LlmProviderFactory`,
`ModelRouter`, `AiProviderSelector`, Anthropic/OpenAI/Google/Groq
providers, `StreamingLlmProvider`, `HttpUrlConnectionTransport` (Android)
and `JdkHttpTransport` (JVM, pinning/mTLS). No competing router should
be added. Local-to-cloud fallback must stay off unless the user allowed
the cloud provider.

**Destination:** `core-llm`, `core-llm-factory`, `core-remote`.

**Decision:** *extend existing*. Add protocol adapters only when a
provider is genuinely missing. Foundation delivered
`PlatformHttpTransport` (this session) so new call sites pick an
Android-safe transport without loading `java.net.http` on device.

**Implementation status:** PARTIAL — multi-provider + streaming +
Android transport exist; visible per-attempt UI and a LiteRT adapter
do not.
**Acceptance:** the provider that answered is named; no unapproved
endpoint is used; unsupported modalities are unavailable, not faked.

---

## KAI-005 — Local inference (LiteRT vs GGUF)

**Kai:** LiteRT on-device models, pinned downloads, idle release.
**DCA:** `core-llm-local` + `core-llm-local-android` (llama.cpp / GGUF),
`ModelRepository` SHA-256, `ChatSession.sendLocal`. Different backend
and format from LiteRT. No LiteRT module exists.

**Decision:** *optional later phase* for a parallel `core-llm-litert`
adapter; **do not replace** GGUF. Share download/digest/lifecycle.

**Implementation status:** GGUF path IMPLEMENTED (fake-backend JVM
tests; device inference UNVERIFIED). LiteRT NOT STARTED.

---

## KAI-006 — MCP client (beside the existing MCP server)

**Kai:** `mcp/McpClient`, `McpServerManager` — client that imports
remote tools. **DCA:** `core-mcp.McpToolServer` is an MCP **server**.
Complementary, not competing. Server executor is `ToolRunner` (accepts
`SecureToolExecutor`); JSON array/object args are preserved as JSON
text; explicit JSON `null` is omitted. This session added
`ToolParameterSchema` validation and `ToolCallEnvelope` call ids on
that path. There is still **no** MCP client module.

**Destination:** new `core-mcp-client` (Phase 2); `core-mcp` (server
prerequisites: done).

**Decision:** *new adapter* for the client. Wrap imported tools as
`core-agent.Tool` through `SecureToolExecutor`. Disabled until
`AssistantCapabilitySettings.mcpClientEnabled` is on (Foundation
default **false**).

**Implementation status:** server prerequisites IMPLEMENTED; client
NOT STARTED.

---

## KAI-007 — SKILL.md skills

**Kai:** SKILL.md parse/install, slash-command activation, bundled
resources, per-turn injection. **DCA:** `core-templates.Skill` is a
named system-prompt + optional provider hint, Room-backed on Android —
**not** SKILL.md. `Persona` stays style identity; skills stay
instruction bundles.

**Decision:** *adapt source* for SKILL.md lifecycle in a later phase;
keep `core-templates.Skill` as the prompt-bundle UI. Skill install
must not grant tool permissions. Script-bearing skills wait on
`skillScriptsEnabled` (default off) and an explicit execution target.

**Implementation status:** prompt-bundle skills PARTIAL; SKILL.md
NOT STARTED.

---

## KAI-008 — Tasks, heartbeat, background execution

**Kai:** `TaskScheduler.kt`, `HeartbeatManager.kt`, daemon foreground
service (`docs/features/daemon.md`, `docs/features/heartbeat.md`,
`docs/features/tasks.md`). **DCA:** `MacroScheduler` /
`ScheduledExecutorServiceScheduler` persist and fire macros on a JVM
thread — not Android WorkManager/Doze-aware, no heartbeat, no active
hours.

**Decision:** *extend existing* scheduler taxonomy; *optional later
phase* for Android daemon/heartbeat. Background automation stays off
until `backgroundAutomationEnabled` is on.

**Implementation status:** JVM macros IMPLEMENTED; heartbeat/daemon
NOT STARTED.

---

## KAI-009 — Linux environment and terminal

**Kai:** Debian (Kai Build) + Alpine, PRoot GPL binaries, package
manager, conversation shells, PTY. **DCA:** `core-termux` /
VictorSuite `RUN_COMMAND`, `core-shell`, `core-root`. No embedded
PRoot module. Kai's bundled `libproot.so` is **GPL-2.0** — do not
copy it; source independently from Termux PRoot if this phase is
approved later.

**Decision:** *optional later phase* (`core-proot`). Prefer Termux/
VictorSuite when that already satisfies the request. Gated on
`linuxEnvironmentEnabled` (default off). PRoot is not a VM.

**Implementation status:** Termux/root PARTIAL; PRoot NOT STARTED.

---

## KAI-010 — Kai Build (coding workspace)

**Kai:** `build/`, `build/runtime/`, `BuildAgents.kt`,
`BuildProotExecutor.kt`. Project folders + PTY for optional CLIs.
**DCA:** `core-build` `BuildPipeline`, `core-coding-session`, Developer
Mode / Forge. Kai Build is not proof of an on-device Android compiler.

**Decision:** *optional later phase*. Connect to `BuildPipeline` only
after SDK/JDK/Gradle compatibility is verified. Gated on
`kaiBuildEnabled` (default off). User-initiated CLI installs only.

**Implementation status:** NOT STARTED.

---

## KAI-011 — Generated interactive UI

**Kai:** `ui/dynamicui/`, validated declarative tree, callbacks, chat
fallback. **DCA:** none.

**Decision:** *adapt source* for a bounded schema/parser (pure JVM);
*optional later phase* for Compose renderer. Disabling
`generatedUiEnabled` must disable interactive behavior, not only omit
prompt text.

**Implementation status:** NOT STARTED.

---

## KAI-012 — Personal tools (search, fetch, calendar, mail, SMS, TTS)

**Kai:** `tools/WebSearchTool.kt`, `FetchUrlTool.kt`, notifications,
calendar, alarms, TTS, email/SMS. `fetch_url` SSRF-guards private
address classes on every redirect hop.

**DCA overlap:** `core-websearch` (Brave + SerpAPI fallback,
`ChatSession` toggle, vault keys); `core-voice` TTS/STT (JVM +
Android wrappers); `core-tools-android` notification listing stub.
No `fetch_url` SSRF tool. Calendar/email/SMS send paths are not
wired as Kai-style tools.

**Decision:** *extend existing* web search/voice; *adapt source* for
`fetch_url` SSRF (later); Android send tools stay gated on real
permissions + `mailboxAccessEnabled` / `smsAccessEnabled` (default
off) and `ApprovalFlow` `SENSITIVE` + confirmation.

**Implementation status:** web search + voice PARTIAL; fetch_url /
mail / SMS send NOT STARTED.

---

## KAI-013 — Settings and data portability

**Kai:** `data/AppSettingsImportExport.kt`,
`docs/features/settings-export-import.md`,
`docs/features/encryption.md`. Section-selective export; posting keys
never exported; Replace vs Merge; secure platform storage.

**DCA overlap:** `SecretsVault` / `EncryptedSecretsVault` /
`KeystoreSecretsVault`; unified fleet backup spec
(`docs/specs/UNIFIED_BACKUP.md`) which already forces
`externalControlEnabled=false` on import. No Kai-style
section-selective assistant export. This session added
`AssistantCapabilitySettings` (all capabilities default **off**,
persisted, corrupt file fails closed).

**Decision:** *extend existing* vault + capability settings
(Foundation, this session); *optional later phase* for selective
conversation/memory export. Imports must not silently enable MCP,
schedules, or tools. Secrets export only by explicit selection.

**Implementation status:** PARTIAL — secrets + capability gates
exist; selective export/import NOT STARTED.

---

## KAI-014 — Splinterlands

**Kai:** isolated Hive-signed auto-battle. **DCA:** none, and none
expected.

**Decision:** *optional later phase*, lowest priority, only if the
owner asks by name. Never a core dependency.

**Implementation status:** NOT SCHEDULED.

---

## Capabilities checked and folded into the rows above

- `reasoning.md` / `appearance.md` — UI chrome; folds into KAI-001.
- `encryption.md` — vault; KAI-013.
- `daemon.md` — Android foreground service; KAI-008.
- `multi-service.md` — provider list; KAI-004.
- `notifications.md` / `sms.md` — KAI-012.
- `tools.md` — native tool catalog; KAI-012 + existing DCA tools.

No 15th capability row is required.

---

## Foundation delivered this session (Phase 1)

| Item | Where | Status |
|---|---|---|
| Capability map (this file) | `docs/KAI_INTEGRATION.md` | refreshed against current commits |
| Phased plan | `docs/KAI_INTEGRATION_PLAN.md` | Phase 1 done; Phase 2+ gated |
| Android-capable HTTP transport | `core-remote.HttpUrlConnectionTransport` (pre-existing); `PlatformHttpTransport` factory (this session) | JVM-tested; `:app` already injects `HttpUrlConnectionTransport` |
| Per-capability settings, default OFF | `core-config.AssistantCapabilitySettings` + file/in-memory stores | JVM-tested; does not touch `externalControlEnabled` |
| Secrets persistence | `EncryptedSecretsVault` / `KeystoreSecretsVault` | pre-existing; unchanged |
| Tool schema + call ids + string-map adapter | `ToolParameterSchema`, `ToolArgumentCodec`, `ToolCallEnvelope`; `McpToolServer` validates when a schema is set | JVM-tested |
| MCP server `ToolRunner` + nested JSON args | `core-mcp` | pre-existing 2026-10-07; still in place |
| Android modules opt-in | `settings.gradle.kts` `includeAndroid=true` | pre-existing; this environment has no `ANDROID_HOME`, so `:app:assembleDebug` was not re-run here |
| Inbound 13/4 + cert gating | `InboundEntryPointCatalog` | **unchanged** |
| `externalControlEnabled` default false | `RemoteControlConfig` | **unchanged** |
| Destructive `confirmation_required` | Companion assist + remote-control transports | **unchanged** |

Do not count a toggle, placeholder screen, or fake executor as an
integrated Kai capability. The settings object is a gate, not the
feature.

---

## Licensing

- **Kai:** Apache License 2.0 (`LICENSE.txt`, read 2026-10-30). Safe
  to adapt behavior with attribution.
- **Kai native binaries are not Apache-2.0** (`THIRD_PARTY_LICENSES.md`):
  PRoot GPL-2.0, talloc LGPL-3.0 under `androidApp/src/main/jniLibs/`.
  Future PRoot work must source binaries independently and carry those
  notices — never inherit them by copying Kai's jniLibs.
- **This repo** still has no `LICENSE` file. This document does not
  invent one.
- Provenance: no Kai source file is copied here. "Adapt source" means
  a non-verbatim rewrite after reading Kai. Future implementers must
  record which Kai file informed the design at the point of coding.

---

## Summary table

| ID | Capability | Decision | Status (2026-10-30) |
|---|---|---|---|
| KAI-001 | Rich chat | extend existing + later attachments | PARTIAL (streaming exists) |
| KAI-002 | Persistent memory | extend existing | NOT STARTED (graph exists) |
| KAI-003 | Personality | extend existing (UI) | NOT STARTED (model exists) |
| KAI-004 | Providers / fallback | extend existing | PARTIAL |
| KAI-005 | Local inference (LiteRT) | optional later; keep GGUF | GGUF PARTIAL; LiteRT NOT STARTED |
| KAI-006 | MCP client | new adapter | server done; client NOT STARTED |
| KAI-007 | SKILL.md | adapt source later | prompt-skills PARTIAL |
| KAI-008 | Tasks / heartbeat | extend existing + later | macros done; heartbeat NOT STARTED |
| KAI-009 | Linux / PRoot | optional later | Termux PARTIAL |
| KAI-010 | Kai Build | optional later | NOT STARTED |
| KAI-011 | Generated UI | adapt source later | NOT STARTED |
| KAI-012 | Personal tools | extend + adapt later | websearch/voice PARTIAL |
| KAI-013 | Settings / portability | extend + later export | PARTIAL (gates this session) |
| KAI-014 | Splinterlands | optional later | NOT SCHEDULED |

Per-phase work is in `docs/KAI_INTEGRATION_PLAN.md`.
