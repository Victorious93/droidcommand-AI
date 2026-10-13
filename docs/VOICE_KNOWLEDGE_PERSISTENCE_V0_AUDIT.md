# Voice Capture & Knowledge Persistence (v3) — V0 Audit

**Date:** 2026-10-13. **Phase:** V0 (audit only, per the v3 prompt's own instruction — no code
changed in this pass). **Gate:** per the Consumer Roadmap rules this repo already follows (see
`CLAUDE.md`), V1 does not start until the owner reviews this audit and approves a slice.

This reads the current repository (not memory) and classifies every CAP-VOX-### requirement
against what actually exists, using the v3 prompt's own status vocabulary: VERIFIED IMPLEMENTED,
IMPLEMENTED BUT UNVERIFIED, PARTIAL, STUB, PLANNED, MISSING, INTENTIONALLY EXCLUDED, BLOCKED.
Nothing below is inferred from a module's name or doc comment alone — each claim points at the
specific file(s) read.

## 0. Headline finding

**The existing voice stack (`core-voice`, `core-voice-android`, `core-voice-neural-android`,
Consumer Roadmap Phase 4 + its follow-on, `docs/VOICE_PHASE_SCOPE.md`) is a different, smaller
system than what this prompt asks for.** It is a one-shot dictate-into-chat-input /
speak-the-reply loop bound to the existing chat screen's lifecycle — not a persistent voice
*session* with its own tool-calling, transcript ledger, or pipelines. It is real, useful prior
art for several CAP-VOX seams (the `SpeechToText`/`TextToSpeechEngine` interfaces, the neural TTS
and wake-word work, the strict-phrase voice-approval parser), but almost none of CAP-VOX-001
through -036 is actually built. The two best-evidenced assets in the whole repo for this prompt
are (1) the Second Brain memory system (`core-agent.memory`), which is substantially more mature
than CAP-VOX-033's "if it exists" framing assumes, and (2) a real, measured on-device embedding
model already proven to run GGUF-based embeddings on a host via the real compiled JNI backend —
direct evidence for CAP-VOX-019's semantic-retrieval feasibility question. Everything schema/
pipeline/UI-shaped that CAP-VOX describes is new work.

## 1. Voice — what exists

| File | What it is |
|---|---|
| `core-voice/VoiceEngines.kt` | `SpeechToText`/`TextToSpeechEngine` seams, `SttEvent` (Partial/Final/Error), `Null*` fail-closed stand-ins. One-shot: one `start()` call yields exactly one `Final` or `Error`, not a continuous session. |
| `core-voice/VoiceController.kt` | Owns one STT + one TTS engine for the chat screen. `startListening`/`stopListening`, `speak`/`stopSpeaking`, degrades every failure to a `VoiceState.error` string — chat itself is never affected. No persistence, no tool access, no transcript concept. |
| `core-voice/WakeWord.kt` | `WakeWordController`/`AudioWakeWordDetector` seam (JVM-tested) — emits `Detected` only, never starts an action itself; triggers `VoiceController.startListening`. |
| `core-voice/VoiceModelRepository.kt`, `VoiceModelCatalog.kt` | SHA-256-verified, atomic, resumable model download — reuses the same pattern as `core-llm-local.ModelRepository` (not a second download strategy). Catalog pins two real sherpa-onnx models (a wake-word keyword spotter, a VITS voice), both no-GPL per an explicit owner decision. |
| `core-voice/NeuralTextToSpeech.kt`, `FallbackTextToSpeech.kt` | Engine-choice seam (SYSTEM/NEURAL) with fallback to system TTS on missing model or failure — JVM-tested. |
| `core-voice/VoiceApproval.kt` | `VoiceApprovalProvider`/`VoiceApprovalParser` — strict-phrase, deny-by-voice-only (approve requires the screen), matched to a specific pending `ApprovalRequest.requestId`; never creates grants itself. Wired into `:app`'s `ComposeApprovalPrompt` as of 2026-10-09g. |
| `core-voice-android/AndroidSpeechToText.kt` | Wraps platform `SpeechRecognizer`, requests `EXTRA_PREFER_OFFLINE`. **Its own doc comment: "NEVER COMPILED OR RUN... whether [on-device recognition] is honoured... is the device vendor's behaviour and is NOT verified here — do not describe this as guaranteed-offline."** |
| `core-voice-android/AndroidTextToSpeech.kt` | Wraps platform `TextToSpeech`. Never compiled/run. |
| `core-voice-neural-android/SherpaOnnxSynthesizer.kt`, `AudioTrackSink.kt` | Real neural TTS via sherpa-onnx v1.13.8's official release AAR (Apache-2.0, SHA-256 pinned). Synthesizer compiles against the real `classes.jar`; `AudioTrackSink` (actual audio output) has never been compiled, let alone run. |
| `core-voice-neural-android/SherpaKeywordEngine.kt`, `AudioRecordSource.kt`, `WakeWordService.kt` | Real wake-word keyword spotting + a foreground `Service` (the one foreground-service precedent in this repo, besides `AdbTermuxExecutor`). Engine compiles against the real AAR; the `Service`/recorder have never been compiled. |
| `:app` Chat screen/toolbar/Settings "Voice" section | Wires `VoiceController` into the chat UI; neural voice and wake word are feature-flagged by whether their model is installed. `:app:assembleDebug`/`lintDebug` pass; **never run on a device** (confirmed across every voice-related `docs/VOICE_PHASE_SCOPE.md` entry). |

**Not present anywhere:** a realtime speech-to-speech provider (OpenAI Realtime, Gemini Live, or
any provider with function/tool calling over an audio session) — grepped for "Realtime"/"Gemini
Live" across every `core-llm*` module, no matches. A cascaded cloud STT provider the app
configures (today's cloud path is only the platform recognizer, which may or may not be cloud-
backed depending on the device). Any connection between a voice interaction and the agent's tool-
calling path — dictation produces chat *text*; the existing Pilot/Forge tool-calling machinery and
the voice loop are two parallel systems that have never been connected. A transcript concept
distinct from ordinary chat history. VAD as a named component (push-to-talk-shaped manual
start/stop exists; no automatic voice-activity boundary detection). Barge-in beyond "stop any TTS
before listening." Whisper.cpp or any other on-device full-dictation STT engine (grepped
repo-wide — no matches); sherpa-onnx's own STT capability (the library supports it — see
`docs/VOICE_PHASE_SCOPE.md` §2) has only been used here for keyword spotting and TTS, never full
transcription.

## 2. Memory — what exists (stronger than CAP-VOX-033 assumes)

`core-agent.memory` ("Second Brain," built 2026-10-09 through 2026-10-13, most recently in this
session) is real, JVM-tested, and already wired into the chat pipeline's read *and* write paths —
not merely a documented seam:

- `MemoryWriter` — validate → sensitivity (`SensitiveContentClassifier`, pattern-based credential/
  personal-data screening) → provenance (a `MODEL`-origin claim can never be stored `VERIFIED`) →
  duplicate detection (token-Jaccard) → conflict detection by `subject` → store → relationship
  update. `writeRelationship` (added this session) completes the last stage: remaps extracted
  entity ids to their final stored ids and screens the relationship `type` string for sensitive
  content before writing, mirroring entity-level screening.
- `MemoryRetriever` — hybrid keyword + optional semantic scorer + graph proximity + recency +
  confidence + reliability + pinning, hard scope isolation, hard token budget.
- `MemoryExtractionService` (`core-llm`) — runs an `LlmGraphExtractor` over a conversation and
  feeds every extracted entity *and* relationship through `MemoryWriter`, exactly the "extraction
  that feeds the write pipeline" CAP-VOX-013 asks for, except today it runs over **text chat**
  conversations, not voice transcripts.
- `core-llm-factory.ChatSession` already exposes `memory`/`memoryWriter`/`memoryScopes`/
  `memoryWriteScope` parameters — real, tested integration, not a stub.
- **Not done:** none of this is wired into `:app` (no `MemoryRetriever`/`MemoryWriter` is
  constructed anywhere in the Android app), so it is JVM-tested infrastructure with no
  user-visible feature yet, and it has no connection to voice at all.

This means CAP-VOX-033 is not a "leave a seam, mark PLANNED" situation — it's the single
strongest piece of reusable infrastructure this audit found. A distillation pipeline that wants
to propose memory candidates has a real `MemoryWriter`/`MemoryCandidate` API to call today.

## 3. Tool registry & Policy Engine — what exists

- `core-agent.ToolRegistry`/`Tool`/`ToolSpec` — the real tool-registration mechanism CAP-VOX-010's
  "exposed through the existing tool registry" refers to. Every real tool in this repo (38+
  across `core-shell`/`core-root`/`core-build`/`core-tools-android`/etc.) already goes through it.
- `core-security.SecureToolExecutor`/`SecurityPolicyEnforcer`/`ApprovalFlow`/`GrantStore`/
  `AuditLog`/`CapabilityRegistry`/`DefaultExecutionRouter` — the Policy Engine, Capability
  Manager, and Execution Router CAP-VOX-010/021/026 need to route through. All VERIFIED
  IMPLEMENTED at a "first slice" level per `docs/ARCHITECTURE.md`, proven against real components
  (not just scripted fakes) in multiple `*SecureExecutorIntegrationTest` files.
- `RiskTier`/`TimeoutApprovalProvider` exist (bounded-wait approval with default-deny on timeout)
  but — per `docs/ARCHITECTURE.md`'s own CAP-013/014 entries — are **not wired into
  `SecureToolExecutor`** today; the production approval gate blocks indefinitely on a prompt. Any
  voice feature that needs a bounded-wait approval (CAP-VOX-021's dispatch-with-confirmation) would
  hit this same unwired gap, not a voice-specific problem.

**Conclusion:** building CAP-VOX-010's capture tools means writing new `Tool` implementations
against a proven, real registration/policy path — low architectural risk, no new infrastructure
needed for the *mechanism*, even though every specific tool (`save_note`, `create_artifact`, etc.)
is new code.

## 4. Model abstraction — what exists

- `core-llm.ProviderType` (`LOCAL`/`CLOUD`/`SELF_HOSTED`), `AiProviderSelector`, `ModelRouter`,
  `LocalFirstOrdering` — real, tested provider selection/fallback/local-first ordering.
  `core-llm-factory.LlmProviderFactory.createModelRouter`/`createPlanner` compose a `ConfigSource`
  all the way to a running router. This is directly reusable for CAP-VOX-016's "configurable,
  typically cheaper or local model for distillation, separate from the conversation model" — the
  routing primitive already exists; only a distillation-specific wiring call is missing.
- `core-llm-local`/`core-llm-local-android` — real llama.cpp JNI bridge. `LlamaCppBackend` is
  **runtime-verified on an x86_64 host** (not Android) via `scripts/host-smoke-test.sh`: real
  model load, multi-turn generation, early stop, multi-byte UTF-8 correctness, prompt-overflow
  handling. `ChatSession.sendLocal` already runs chat turns through a registered local model.
  Never run on an actual Android device/ABI.
- No realtime/speech provider abstraction exists (see §1) — the model abstraction CAP-VOX-002
  would need is a new concept, not an extension of an existing one.

## 5. Storage — what exists, and the real gaps for CAP-VOX's specific asks

Real Room precedent exists in four places: `core-conversations` (chat history), `core-rag-android`
(`RoomVectorStore`, schema v1 exported), `core-knowledge-android` (`RoomKnowledgeGraph`, four
tables, `ON DELETE CASCADE`, `@Upsert` not `REPLACE` — deliberate, tested design choices worth
reusing), `core-templates-android` (`RoomTemplateStore`/`RoomSkillStore`). All are Robolectric-
tested, **none has ever run on a device** (same "no Android SDK by default in this environment"
fact as everything else).

Grepped repo-wide for the specific primitives CAP-VOX's storage requirements name:

| Primitive | CAP-VOX needs it for | Found? |
|---|---|---|
| Room FTS3/FTS4/FTS5 (`@Fts4`/`@Fts3`) | CAP-VOX-017 full-text search | **No matches anywhere.** |
| `WorkManager`/`CoroutineWorker` | CAP-VOX-013 distillation job | **No matches anywhere.** |
| SQLCipher / Room `SupportFactory` | CAP-VOX-025 encryption at rest | **No matches anywhere.** |
| `DocumentFile`/`ACTION_CREATE_DOCUMENT`/`ACTION_OPEN_DOCUMENT_TREE` | CAP-VOX-028/029 SAF export | **No matches anywhere.** (A SAF *file picker* for GGUF import exists in `:app`'s `LocalModelImporter`, but that's a read-one-file picker, not a write/export tree.) |

None of these four are present at all — not partial, not stubbed. Each would be the first use of
its kind in this repository. That's not a blocker (Room supports FTS and SQLCipher integration is
well-documented), but it means CAP-VOX-017/013/025/028 start from zero on the infrastructure
level, not just the schema level.

**Real, reusable precedent that *is* directly relevant:**
- Encryption-at-rest pattern: `core-config.EncryptedSecretsVault` (AES-256/GCM, versioned
  envelope, AAD-binding, adapted from OpenDroid with attribution) and `:app`'s `AndroidSecretsVault`
  (real `AndroidKeyStore` key loader) prove the Keystore-backed crypto pattern this repo would
  reuse for a SQLCipher key, even though no Room database has used it yet.
- Versioning precedent: `MemoryWriter`'s supersede logic (the old record is kept, marked
  `SUPERSEDED`, linked by a `FACT_SUPERSEDES` relationship, never deleted) is a different but
  real prior design for "never silently lose an earlier version," worth looking at before
  inventing `ArtifactVersion` from scratch.
- Untrusted-content framing precedent, directly reusable for CAP-VOX-026: `GraphRetriever`/
  `MemoryRetriever`'s injected context and web-search results are both explicitly "framed as
  untrusted reference/third-party data... never gains system authority" — the same principle
  CAP-VOX-026 asks for distillation output, already proven out twice in this codebase.
- Evaluation-harness precedent, directly reusable for CAP-VOX-034: `core-llm-local-android/
  scripts/host-rag-eval/` is a real, committed, re-runnable measurement harness (corpus,
  questions, raw results checked in) that reported a genuinely unflattering result (embeddings
  "roughly tied with word overlap" at small chunk sizes) rather than a flattering unmeasured
  claim — the exact discipline CAP-VOX-034 asks for.

## 6. On-device STT/embedding feasibility for the target device class

The repo does not define a single "target device class" (no minSdk/hardware-tier doc beyond
`minSdk 28` for local inference and the Vulkan/OpenCL GPU-backend work); the two closest answers
this audit can give are below.

**Embedding feasibility: the best-evidenced claim in this whole audit, though still not
device-verified.** `core-llm-local-android.LlamaCppEmbeddingBackend` running
`nomic-embed-text-v1.5` (Q4_K_M, ~84 MB, Apache-2.0, pinned SHA-256) is **runtime-verified on a
real host** (Linux x86_64, not Android) — it loads, embeds, and was measured for retrieval quality
against a real 8-document/50-question corpus (`core-llm-local-android/scripts/host-rag-eval/`,
results committed). Honest result: retrieval quality is roughly tied with a trivial word-overlap
baseline at small chunk sizes, and worse than it at the 512-word default — not a strong result,
but a *real* one. **Never run on an actual ARM Android device**, so battery/thermal/latency on
real mobile silicon remain completely unmeasured. Feasibility verdict: **PARTIAL — proven
possible off-device, unverified on-device.**

**On-device STT feasibility: unconfirmed, not attempted either of the two available ways.**
(1) Platform `SpeechRecognizer` with `EXTRA_PREFER_OFFLINE` — requested in code, but by that
code's own doc comment "NEVER COMPILED OR RUN... whether it is honoured... is the device vendor's
behaviour and is NOT verified here." Whether a given test device even has an on-device
recognition model downloaded is itself outside this app's control. (2) sherpa-onnx's own STT
capability — the library supports it (confirmed in `docs/VOICE_PHASE_SCOPE.md`'s own description:
"does STT, VAD and keyword spotting"), but this repo has only ever built the keyword-spotting and
TTS halves of that library, never attempted full dictation through it. No whisper.cpp or other
STT engine exists anywhere in the repo. Feasibility verdict: **BLOCKED on a real device/emulator
session actually attempting either path** — this audit cannot upgrade that to PARTIAL or better
without one.

## 7. CAP-VOX-### traceability (current status, V0)

| ID | Requirement | Status |
|---|---|---|
| CAP-VOX-001 | Provider-agnostic voice session interface | MISSING (real but differently-shaped prior art: `SpeechToText`/`TextToSpeechEngine`, one-shot not session-shaped) |
| CAP-VOX-002 | Realtime speech-to-speech provider with tool calling | MISSING |
| CAP-VOX-003 | Cascaded cloud pipeline, identical tool set | MISSING (cloud text LLMs exist; no cloud STT provider is configured by this app; cloud TTS deliberately excluded per `docs/VOICE_PHASE_SCOPE.md` §0–1; no tool access from voice at all) |
| CAP-VOX-004 | Fully offline pipeline (STT + llama.cpp + TTS) | PARTIAL (llama.cpp chat: IMPLEMENTED, host-verified, not on-device; neural TTS: ON DISK/UNVERIFIED; on-device STT: unconfirmed, see §6; the three-part pipeline composition itself: MISSING) |
| CAP-VOX-005 | Automatic pipeline fallback, transcript continuity | MISSING |
| CAP-VOX-006 | VAD, barge-in, push-to-talk, AEC/NS | PARTIAL (manual push-to-talk-shaped start/stop exists; no VAD, no mid-speech barge-in, AEC/NS is whatever the OS does, unconfigured) |
| CAP-VOX-007 | Foreground service; screen-off/background survival | MISSING for a voice session (real foreground-service precedent exists — `WakeWordService` — but ON DISK/UNVERIFIED, and scoped to wake-word, not a dictation session) |
| CAP-VOX-008 | Crash-safe append-only transcript ledger with markers | MISSING (Room/DAO pattern precedent is real; this schema does not exist) |
| CAP-VOX-009 | Custom vocabulary and transcript correction | MISSING |
| CAP-VOX-010 | P0 live capture tools via Policy Engine | MISSING (the tool registry + Policy Engine path they'd use is VERIFIED IMPLEMENTED and proven; no specific tool exists) |
| CAP-VOX-011 | Retrieval/update/verbatim tools | MISSING (`GraphRetriever`/`MemoryRetriever` are real retrieval prior art, not this) |
| CAP-VOX-012 | Save confirmation / offer-to-save behavior | MISSING |
| CAP-VOX-013 | Distillation pipeline (chunked, idempotent, schema-validated) | MISSING (no `WorkManager` anywhere; `MemoryExtractionService`/`GraphExtractionService` are a real but narrower "LLM-extract-then-write" precedent, over chat text, not voice, with no chunking/retry/dedup machinery) |
| CAP-VOX-014 | Grounding check, UNGROUNDED flagging | MISSING |
| CAP-VOX-015 | Verbatim enforcement | MISSING |
| CAP-VOX-016 | Distillation model routing; rolling distillation | PARTIAL (routing/local-first infrastructure is real and reusable; no distillation-specific wiring) |
| CAP-VOX-017 | Artifact store with FTS and versioning | MISSING (no Room FTS anywhere in the repo; Room/DAO/versioning-concept precedent exists elsewhere, not this schema) |
| CAP-VOX-018 | Projects and cross-linking | MISSING (`KnowledgeGraph` relationships/traversal are a real analogous precedent at the entity level) |
| CAP-VOX-019 | Hybrid semantic + keyword retrieval, local embeddings | PARTIAL (embedding backend real and measured, see §6; no Room FTS to combine it with; `core-rag` and the Second Brain's keyword+graph retrieval are separate, unconnected subsystems) |
| CAP-VOX-020 | Voice retrieval with citations; session resume; read-back | MISSING (the TTS primitive for "read aloud" exists and works for any text; citations/session-resume do not exist) |
| CAP-VOX-021 | Agent Mode handoff via Approval Flow | MISSING (the Approval Flow/Policy Engine it must route through is real; `RiskTier`/bounded-wait approval exists but is itself unwired into the production gate — a pre-existing gap, not voice-specific) |
| CAP-VOX-022 | Capture settings, dependency rules | MISSING for this feature (real precedent: `VoiceSettings`/`VoiceFeatures` for the *existing* voice feature, incl. a `normalized()` dependency-enforcement pattern worth copying) |
| CAP-VOX-023 | Per-session toggle, state indicator | MISSING |
| CAP-VOX-024 | Quick Settings tile; spoken capture commands | MISSING (the strict-phrase voice-command parser pattern exists in `VoiceApprovalParser` and is directly reusable) |
| CAP-VOX-025 | Encryption at rest, Keystore-held key | MISSING for a Room database specifically (no SQLCipher/Room-encryption anywhere); the Keystore-backed AES-GCM pattern itself is real and proven (`EncryptedSecretsVault`, `AndroidSecretsVault`) |
| CAP-VOX-026 | Prompt-injection containment for distillation | MISSING as a control (nothing to contain yet); the "untrusted data, never system authority" design principle is real and proven twice already (`GraphRetriever`/web search) |
| CAP-VOX-027 | App lock; retention policies | MISSING |
| CAP-VOX-028 | Markdown/Obsidian export via SAF | MISSING (no SAF *write*/tree usage anywhere; a SAF file *picker* exists for GGUF import, a different operation) |
| CAP-VOX-029 | Continuous export folder; encrypted backup/restore | MISSING |
| CAP-VOX-030 | Pluggable export targets | INTENTIONALLY EXCLUDED for now (P2, correctly not started) |
| CAP-VOX-031 | Compose UI: voice, sessions, detail, library | MISSING (real, differently-scoped precedent: the existing chat screen's voice toolbar/mic UI and Settings "Voice" section — ON DISK, never run on a device) |
| CAP-VOX-032 | Live artifact panel; glossary editor; diagnostics screen | MISSING |
| CAP-VOX-033 | Memory-system integration | PARTIAL, and understated by the prompt's own framing — see §2. The memory system exists, is JVM-tested and wired into `ChatSession`, but is not wired into `:app` or connected to voice. |
| CAP-VOX-034 | Capture evaluation harness and metrics | MISSING (real, directly reusable precedent: `core-llm-local-android/scripts/host-rag-eval/`) |
| CAP-VOX-035 | Camera image attachment | INTENTIONALLY EXCLUDED for now (P2, correctly not started) |
| CAP-VOX-036 | Raw audio retention (opt-in) | INTENTIONALLY EXCLUDED for now (P2, correctly not started) |

## 8. What this means for V1

Per the v3 prompt's own gate ("Stop at the end of each phase... unless told to run through") and
this repo's existing Consumer Roadmap rule ("one phase at a time... no PR until requested"), **no
code changes were made in this pass**, and V1 (encrypted Room database, entities, DAOs, FTS,
versioning, migrations) does not start without an explicit go-ahead. Three things worth deciding
before V1 starts, since they shape it directly:

1. **Device-session availability.** On-device STT feasibility (§6) cannot be resolved by more
   repository reading — it needs either a real Android device/emulator session or an explicit
   decision to ship V1–V2 "ON DISK, UNVERIFIED" the way every other Android-only slice in this
   repo already is, and test later.
2. **Scope of "existing memory system" reuse.** CAP-VOX-033 undersells what's there (§2). Worth
   deciding explicitly whether distillation should write through the real `MemoryWriter`/
   `MemoryCandidate` API from day one, rather than building a separate `Artifact` store that
   duplicates it — the same "no duplicate systems" question this repo's own Second Brain work
   already had to answer once (see `docs/AUDIT_2026-09-05.md`'s 2026-10-09v entry) and answered
   by extending the existing `KnowledgeGraph`, not building a second one.
3. **FTS vs. embeddings vs. both, for V1's artifact store.** CAP-VOX-017 names Room FTS;
   CAP-VOX-019 names local embeddings. Both are genuinely new infrastructure here (§5). Building
   both at once in V1 is more surface area than the "small, testable, no regret" first slice this
   repo's own precedent favors (see `docs/VOICE_PHASE_SCOPE.md`'s own V1/V2/V3 staging).
