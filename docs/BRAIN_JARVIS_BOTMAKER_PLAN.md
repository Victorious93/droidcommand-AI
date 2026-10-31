# Brain, Jarvis, and Bot Maker

Plain-English plan for three features Don asked for. **This session builds
only the Brain.** Jarvis and Bot Maker stay on paper until he says to
start them. Nothing here turns on remote control, skips a confirmation,
or sends notes to a cloud of our own.

Written against `main` at `9243ea1` (Kai Phase 1, PR #259): nine
capability gates, all off; tool schema/codec; MCP validation;
`PlatformHttpTransport`; the five-tab chrome (Fleet, AI Chat, Models,
Settings, Advanced); local `.gguf` models; the existing Notes vault and
Memory graph. Open PR #265 (device connect + theme) is separate and is
not touched.

---

## What already exists (do not rebuild)

- **Memory** (Advanced → Memory) is the knowledge *graph*: typed facts
  and links extracted from a chat when you tap "Remember this chat".
  That is KAI-002's structured half. It stays.
- **Notes** (`core-notes.NoteVault`, Tools → Notes) is already a folder
  of markdown files with path-escape protection and a simple preview.
  It is not yet Obsidian-shaped (no `[[wikilinks]]`, tags, frontmatter,
  search, or backlinks) and chat cannot read or write it.
- **Kai gates** (`AssistantCapabilitySettings`): nine flags, all off.
  There is **no memory gate** today. Brain adds a tenth, `brainEnabled`,
  also off until the owner turns it on.
- **Voice** (`core-voice`, mic / speak / wake-word wiring in Chat) is
  the start of Jarvis, not the product.
- **Personas / skills / templates**, local GGUF + BYOK providers, the
  fleet, and `SecureToolExecutor` + confirmations are the pieces Jarvis
  and Bot Maker will *use*, not replace.

The Brain is the markdown, Obsidian-compatible layer of KAI-002. It is
**not** a second key-value memory store. Graph Memory and the Brain
vault sit side by side: graph for structured facts, vault for notes
you can also open in Obsidian.

---

## 1. Brain (built this session)

An Obsidian-compatible markdown vault. The AI can search it, put
matching notes into a chat turn, and save a chat or a fact as a note
— only after you confirm.

### What a note is

A `.md` file in a vault folder. Optional YAML frontmatter between
`---` lines (`title`, `tags`, extra keys kept as text). Body may
contain `[[Note Name]]` / `[[Note Name|label]]` and `#tags`. Hidden
names (dot-files) stay reserved. Paths that leave the vault are
refused, same as today's `NoteVault`.

### Where the vault lives

1. **Default:** app storage (`notes-vault`), same folder Notes already
   uses, so existing notes are not abandoned.
2. **Optional:** a folder you pick with the system file picker (SAF).
   Persist the tree URI with read+write permission so a real Obsidian
   vault on shared storage can be the same folder. No cloud sync. No
   account. Switching folders does not copy or delete the other one.

### What the owner sees

**Placement: Advanced → Brain, not a sixth tab.** The bar already has
Fleet, AI Chat, Models, Settings, and Advanced. A sixth tab would
crowd the bar, and Brain is off by default — a dead tab on first
launch would fight the "app makes sense" work in #263. Memory already
lives on Advanced; Brain sits next to it. Extra tools still has a
Notes entry that opens the same screen (relabelled Brain) so old
screenshots and habits keep working.

The screen, in plain words:

- A list of folders and notes.
- Search across titles, tags, and body.
- Open a note: edit markdown, or preview headings / bullets / code.
- When a note is open, show its tags and **backlinks** (other notes
  that `[[link]]` here).
- New note / new folder, save, up. Unsaved edits still save on Up, or
  stay open with the reason if save fails.
- "Use Obsidian folder" picks a SAF tree. "Use app folder" goes back
  to app storage.

### What chat does

Only when the Brain gate is **on** (off by default):

1. **Retrieve.** Each send runs a keyword / BM25 search on the vault.
   Matching notes are appended to that turn's *user* message, framed
   as untrusted reference data (same rule as the knowledge graph and
   web search). They are not saved into the transcript. If search
   throws, the turn continues without notes. Embeddings are **not**
   in this slice.
2. **Save.** "Save this chat to Brain" or "Save a fact" builds a
   markdown draft (frontmatter + body) and **does not write** until
   you confirm. Cancel leaves the vault unchanged. This is separate
   from "Remember this chat", which still writes the graph.

### Gate

New Kai capability `BRAIN` / `brainEnabled` / config key
`DROIDCOMMAND_BRAIN_ENABLED`. Default **false**. The other nine gates
stay read-only and off. The Brain switch on Advanced is the one that
can be turned on; it does not enable MCP, Linux, mailbox, SMS, cloud
transfer, or remote control. Chat reads the flag when the screen
opens (same "applies next time" pattern as Memory).

### Safety (unchanged)

- No cloud sync, no keys in the repo, no new network path.
- `externalControlEnabled` stays off by default.
- Destructive actions still ask.
- Vault IO never follows `..`, absolute paths, or hidden names.
- Retrieved note text is never treated as instructions or as a
  permission.
- Plugins stay data-only (literal replacements). Nothing from
  Obsidian's plugin runtime is bundled or executed.

### Data model (first slice)

| Piece | Meaning |
|---|---|
| `NoteDocument` | path, title, tags, wikilinks, frontmatter, body, raw text |
| `WikiLink` | target + optional display label |
| `NoteFrontmatter` | title, tags, leftover keys as strings |
| `NoteIndex` | all notes + backlinks (who links to whom) |
| `Bm25Search` | ranked keyword search; title and tags weighted higher |
| `NoteDraft` | proposed path + markdown; not on disk until `commit` |
| `BrainLibrarian` | search, retrieve-as-context, draft, commit |
| `VaultStorage` | Path (default + tests) or SAF (app) |

Later (not this session): embeddings, aliases as first-class, daily
notes, canvas, Obsidian plugin code.

### How this serves KAI-002

Kai's memory is categorized, reinforced, and promotable. The Brain is
the **file** the owner can read. Kai categories can later be a
`category:` frontmatter field (General / Learning / Error /
Preference). Hit-count and promotion into a Persona stay on the graph
/ `Persona` path already named in `docs/KAI_INTEGRATION_PLAN.md`
Phase 2 — not invented here. Do not add a third store.

---

## 2. Jarvis (plan only)

Hands-free assistant: you talk, it answers, and it can use tools you
already approved — with a confirmation when something can change the
phone or another app.

### What it is

A mode of AI Chat, not a second app and not a second agent runtime.

1. **Listen.** Push-to-talk (mic button, already there) and optional
   wake word (wiring already exists). Prefer on-device speech-to-text
   when a model is installed; otherwise the system recognizer. A
   denied mic permission is silent, not a crash.
2. **Speak.** On-device or system text-to-speech. Off / tap-to-speak /
   auto-speak, already a Chat setting. No voice cloning.
3. **Briefings.** Short spoken or on-screen summaries (morning, after
   a fleet change, after a long chat). Driven by the Brain (today's
   notes) plus Memory (graph facts). A briefing **reads**; it does not
   run tools. Behind `backgroundAutomationEnabled` if it runs while
   the app is in the background; otherwise only when you open Chat.
4. **Act.** Tool calls go through the existing
   `ToolRegistry` → `SecureToolExecutor` → approval / grant / audit
   path. Kai gates stay fail-closed: no MCP client, Linux, mailbox,
   SMS, or cloud transfer unless that gate is on. Fleet actions use
   the catalogued nodes only; pairing is not remote control.
5. **Confirm.** Anything that can wipe, flash, lock, run a security
   tool, or send a command to another app still shows the existing
   confirmation. Jarvis never auto-approves.

### Phases (after Brain)

| Phase | What lands | Needs |
|---|---|---|
| J1 | Reliable push-to-talk + speak of the last reply; honest "mic denied" | existing `core-voice` |
| J2 | Wake word that only starts listening, never a tool | neural wake-word model already optional |
| J3 | "What's on my plate?" briefing from Brain + Memory, on demand | Brain (this work) |
| J4 | Spoken tool use with the same confirmations as tapping | Kai gates + fleet |
| J5 | Scheduled briefings | `backgroundAutomationEnabled` + Doze honesty |

### Data model (later)

A `JarvisSession` is a ChatSession plus voice IO and a "last briefing"
note path in the Brain (`Brain/Briefings/`). No new secrets store.
Wake-word settings stay in the existing voice settings.

### Safety

Same as Chat + tools today. Jarvis cannot widen a grant, invent a
fleet node, or turn on `externalControlEnabled`. A briefing that
quotes a note still frames it as untrusted reference.

---

## 3. Bot Maker (plan only)

A way to create small, named bots for a job — closer to "Grok Bot"
(a saved persona + tools + memory + a trigger) than to a new
programming language.

### What a bot is

A saved recipe:

| Field | Meaning |
|---|---|
| Name | What you see in the list |
| Persona | Reuses `Persona` / `StyleProfile` (KAI-003). Not a second soul string |
| Instructions | Extra system text for this bot only |
| Model | A local `.gguf` you already imported, or a BYOK provider you already keyed |
| Allowed tools | A subset of registered tools. Empty means chat only |
| Memory folder | A folder inside the Brain (`Brain/Bots/<name>/`). That bot may read/write only there |
| Trigger | Optional: manual, a time (`MacroScheduler` / CRON), or a phrase in Chat |

Run, pause, delete. Delete removes the recipe and, if you confirm,
that bot's memory folder. It does not wipe the rest of the Brain.

### Sandbox

Each run is a ChatSession (or a one-shot `DroidCommandSession`) whose
`SecureToolExecutor` allow-list is **that bot's tools only**. A bot
cannot enable a Kai gate you left off. A bot cannot see another bot's
folder. A scheduled bot requires `backgroundAutomationEnabled` and
still confirms destructive work. No bot can turn on remote control.

### Phases (after Brain; after or with Jarvis J1)

| Phase | What lands | Needs |
|---|---|---|
| B1 | Template → name / persona / instructions / model / tools / folder. Run and delete by hand | Brain folders + existing Persona + model picker |
| B2 | Pause / resume; last-run log on the Activity log | audit log |
| B3 | Phrase trigger from Chat ("ask Research bot …") | Chat slash or a picker |
| B4 | Time trigger | `backgroundAutomationEnabled` + scheduler |
| B5 | Optional shared tools only through the same confirmation path | Kai gates |

### Data model (later)

`BotRecipe` persisted as markdown + frontmatter in
`Brain/Bots/<id>/recipe.md` (the Brain is the source of truth, so you
can edit it in Obsidian) **or** as a small JSON file in app storage
pointing at that folder. Prefer the Brain file so the vault stays
portable. Tool names are the existing `ToolSpec` ids. Model id is an
imported GGUF id or a `CloudProviderSpec` id. No API keys inside the
recipe; keys stay in the secrets vault.

### How this uses Kai and the Brain

- Persona and skills: already in the repo; Bot Maker picks one.
- Tools: existing registry + Kai gates as the ceiling.
- Memory: a Brain folder, not a new database.
- Schedules: existing `MacroScheduler`, gated.
- Local vs cloud model: existing Models tab / BYOK Settings.

---

## Shared rules for all three

1. **One phase at a time.** Brain first. Jarvis and Bot Maker wait.
2. **Fail closed.** New capability off until the owner turns it on.
3. **Confirmations stay.** No silent destructive work.
4. **No second stack.** No second router, vault, or policy engine.
5. **Honest status.** JVM-tested is not "ran on a phone."
6. **PR #265 stays untouched.**

### Suggested later order

Brain (this PR) → Jarvis J1/J2 (voice polish) → Bot Maker B1
(manual bots on Brain folders) → Jarvis J3 (briefings) → Bot Maker
B3/B4 (triggers) → Jarvis J4 (spoken tools).

---

## This session's acceptance (Brain only)

- Plan file is this document.
- JVM tests cover vault parsing (frontmatter, tags, wikilinks), the
  link graph / backlinks, BM25 search, and "draft does not write /
  commit after confirm does."
- Chat retrieve is gated and injects notes as untrusted user-turn
  context; save is confirm-only.
- UI: Brain on Advanced, list / search / editor / backlinks, SAF
  picker present.
- `./gradlew test` green. Emulator screenshots from CI (or a local
  emulator if one is here). **No claim of a real-phone run.**
- PR open, not merged.
