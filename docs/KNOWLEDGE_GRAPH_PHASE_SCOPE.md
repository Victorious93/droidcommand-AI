# Knowledge Graph in the APK — scoping (NOT approved, NOT started)

Date: 2026-10-08. Status: **proposal for the owner's decision.** Nothing below is implemented. Per this
repo's precedent (every CAP slice was scoped before building) and `CLAUDE.md`'s "one phase at a time,
owner approves" rule, no code starts until the questions in §6 are answered.

## 1. What exists today (each point checked against the repo on 2026-10-08)

| Piece | Where | State |
|---|---|---|
| `KnowledgeGraph` interface: typed `Entity` (15 `EntityType`s) + free-text-typed `Relationship`, `neighbors`, BFS `traverse(startId, maxDepth, relType)`, cascade delete | `core-agent/KnowledgeGraph.kt` | implemented, tested |
| In-memory and file-backed implementations | `InMemoryKnowledgeGraph`, `JsonFileKnowledgeGraph` | implemented, tested on the JVM |
| LLM-driven extraction of entities/relationships from a conversation | `core-llm/LlmGraphExtractor`, `GraphExtractionService` | implemented, tested against scripted providers |
| A way to put graph results into a prompt | `ContextManager.kt` (`KnowledgeContextProvider`-style providers that take a caller-supplied query function) | formatter exists |
| Flat memory store (`KnowledgeEntry`, tags, literal-substring search) | `KnowledgeStore`, `JsonFileKnowledgeStore` | implemented |

## 2. What does NOT exist (the actual gap)

1. **Nothing in any Android module references the graph or the knowledge store.** `app/`,
   `core-conversations` and `core-companion` contain no use of them. `ChatSession` (the chat flow) takes
   only a vault and a `ConversationStore`.
2. **No retrieval policy.** The code deliberately leaves "what to pull from the graph for this task" to the
   caller (its own doc comments say so). Nobody has written that caller. Without it a graph that is filled
   but never read changes nothing the user sees.
3. **No extraction trigger.** `GraphExtractionService` documents that *when* to call it is the caller's job.
4. **No Android storage.** `JsonFileKnowledgeGraph` stores one file per entity and per relationship and its
   own docs state that relationship queries scan every relationship file. That is fine for hundreds of
   entries and unproven beyond; it has never run on Android.
5. **Search is literal substring only** (`searchEntities`, case-insensitive). No semantic matching. The
   embedding model planned for Phase 5 (document Q&A) does not feed the graph.
6. **No UI** (viewing, editing or deleting what the app "remembers").
7. **Extraction quality is unmeasured** for every model class, and especially unknown for the small local
   models Phase 2 targets (135M–1B parameters): no evaluation exists in this repo.

## 3. Relationship to other plans (so it isn't built twice)

- Capability roadmap **P0.7 / CAP-007**: this is where the graph came from. The consumer roadmap
  (Phases 0–5) has no graph phase.
- **Phase 5 (document Q&A)** is embeddings + cosine similarity over file chunks: a different retrieval
  mechanism for a different data shape. Not a substitute, and not a dependency either way.
- **KAI-002 (persistent memory)** in `docs/KAI_INTEGRATION*.md` plans to *extend* `KnowledgeStore` /
  `KnowledgeGraph` with categorized, reinforced, promotable memory. It is NOT STARTED and needs the owner's
  go-ahead. Any graph phase here must not duplicate it: decide the order, or fold one into the other.

## 4. Options

**A — Minimal, read-mostly (recommended starting point).** Android-side storage plus *explicit* use:
- A Room-backed `KnowledgeGraph` implementation (two tables, indexed edges; traversal in Kotlin over indexed
  queries), replacing the file scan on Android. Testable here under Robolectric, like the other Room modules.
- A retrieval step in the chat flow: seed from entities whose labels match the current message, take
  `traverse(depth ≤ 1–2)`, format via the existing context provider, cap by the existing token budget.
  Deterministic and testable with fakes; no LLM needed to *read* the graph.
- Extraction only on an explicit user action ("remember this conversation"), never automatic.
- A minimal settings screen: on/off, "view what is remembered", "delete all". (UI is not buildable or
  viewable in this environment; it would be compiled only.)

**B — Automatic extraction after every conversation.** Everything in A plus a background trigger. Larger
privacy and cost surface (§5), needs a provider call per conversation, and extraction quality is unmeasured.
Not recommended until A's retrieval is proven useful.

**C — Fold into KAI-002 memory.** Skip a separate graph phase and build the richer memory model (categories,
reinforcement, promotion) on top of the same store. Larger and gated on the Kai go-ahead.

## 5. Risks and constraints to decide on up front

- **Privacy.** Extraction sends conversation text to whichever provider runs it. With a cloud provider that
  means stored personal facts are derived from, and re-sent inside, later prompts. This should be opt-in,
  per provider-type (e.g. "local model only"), and visibly deletable. Retrieved graph text is also injected
  into prompts, so it is an untrusted-input path: stored content must never be treated as instructions or as a
  policy/permission source (the `Persona` isolation rule in `Persona.kt` is the precedent to copy).
- **Quality vs. cost.** Poor extraction fills the graph with wrong "facts" that then bias every answer.
  Needs an evaluation set before enabling by default. None exists.
- **Retrieval can hurt.** Over-retrieval burns the token budget on small-context local models (Phase 2).
  The cap must come from the existing `TokenBudgetManager`, and the feature must degrade to "no graph
  context" rather than fail.
- **Scale.** Define a ceiling (entities/edges) and a pruning rule before shipping, or the store grows without
  bound.
- **API 28+ local inference vs. API 26 app** (Phase 2 decision) is unrelated to the graph, which is plain
  Kotlin and runs on API 26.

## 6. Questions for the owner (these change what gets built)

1. Option A, B or C? (Recommendation: A, then reassess.)
2. Extraction: user-initiated only, or automatic? Allowed on cloud providers, or local models only?
3. Should users see and edit/delete the stored graph in the UI at launch? (Recommendation: yes — at minimum
   view + delete-all — if extraction exists at all.)
4. Room-backed store as a new module (`core-knowledge-android`, mirroring `core-conversations`), or add the
   tables to an existing database? (Recommendation: new module; separate lifecycle and schema.)
5. Order relative to KAI-002 and to Phase 3's remaining UI work.

## 7. Proposed sub-phases if Option A is approved (each stops for review)

- **K1 — Room `KnowledgeGraph`:** schema, DAO, store, Robolectric tests (cascade delete, traversal order,
  cycles, dangling-edge rejection, reopen-persistence), mutation-checked.
- **K2 — Retrieval + context wiring:** deterministic seed/traverse/format with a token cap; tests with
  scripted data. No LLM.
- **K3 — Explicit extraction trigger:** wire `GraphExtractionService` behind a user action; tests with a
  scripted provider; opt-in gate and provider-type restriction.
- **K4 — UI (compile-only here):** on/off, view, delete-all.

Acceptance for the whole phase would need a device (Phase 5/E-style) to claim anything about real behavior;
until then everything is graded "JVM/Robolectric-tested, never run on a device".
