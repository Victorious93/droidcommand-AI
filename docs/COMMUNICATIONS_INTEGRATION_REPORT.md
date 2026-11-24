# Victorious Fleet — communications integration report (2026-11-24)

Victorious Framework. Covers `droidcommand-AI`, `VictorBots`, `VictorSMS` (workspace repos). **VictorLinux was not in the
workspace**, so section 22 of the brief is Blocked, not skipped.

## Honest summary

This session built and tested the **cores** of the system on the JVM: the conversation archive and incremental import engine
(VictorSMS `comms-core`), the authorised communications contract and broker (DroidCommand `core-communications`), and the
writing-style, profile and agent-configuration engine (VictorBots). Together they implement the headline requirement of the
brief at the logic level: import an export, save it permanently, update it repeatedly without duplicates or loss, build a
style profile from the owner's own words, and draft a reply from budgeted context through a policy-checked model route.

They are **not yet connected to each other on a phone.** There is no Android transport between the apps, no import screen,
no grant/approval screen, no voice-message or interactive-call work, and no device, emulator, carrier or live-model run. The
end-to-end flow in section 26 is therefore **Partially implemented**: every link has a tested core, none is wired through
Android. Do not read the test counts below as device verification.

### Status vocabulary used here

| Term | Means in this report |
|---|---|
| Requested | in your brief, no code written in this session |
| Implemented | code exists |
| Built | compiled in a real Gradle/javac build in this environment |
| Tested | automated tests ran and passed here (JVM, real SQLite; fakes for other apps) |
| Verified | observed working on a real device, carrier, provider or model. **Nothing is Verified.** |
| Partially implemented / Blocked / Unsupported | as stated per row |

## Baseline (recorded before any change)

| Repo | Branch | HEAD at start | Working tree | Baseline tests |
|---|---|---|---|---|
| droidcommand-AI | `ccr-538bb07e-r50sh3` = `origin/main` | `220bb43` | clean | `core-security` 361 · `core-agent` 359 · `core-llm-factory` 208 · `core-bots` 53 · `core-fleet-tools` 13 = **994, 0 failures** |
| VictorBots | same | `4b5bd4c` | clean | full JVM suite green |
| VictorSMS | same | `7856f68` | clean | CI-only (needs Android SDK to configure); not run before the change |

Result commits: droidcommand-AI `3106f50`, `822efbe` · VictorBots `491f804` · VictorSMS `230af36` (documentation commits follow
and are listed on each PR). No APK was built in this session; see Deliverables.

## Findings that shaped the design

1. `COMPANION_ASSIST` and `COMPANION_EXEC` both need a human tap per request; EXEC exposes one read-only capability. Neither can
   serve Automatic Reply or Autonomous modes. **A new contract was necessary**, not an extension of EXEC (which would have
   silently removed its "no standing grants" property).
2. DroidCommand already had `victorsms_send` (queues, owner approves on the phone) and `send_sms`/`make_call` through
   `DeviceController` (bypasses VictorSMS). The new tools go only through VictorSMS; the bypass tools were left untouched.
3. VictorSMS had no archive: it reads `Telephony.Sms`. Its persona drafter calls a PC LLM through its own `PcEndpoints`
   (the duplicate AI path to consolidate; **not yet removed**). Its docs state "never auto-sent" (§13, §20).
4. VictorBots forbids a second model path (`GatewayTest` scans sources) and requires bot-originated jobs to be `PROPOSED`.
   Therefore autonomous sends cannot run through VictorBots' Jobs; their authority lives in a DroidCommand grant plus a
   VictorSMS rule. VictorBots only requests, records and displays.
5. TextNow: Export Conversations exists on Android only, format undocumented; no official public API; community libraries use
   cookies and reportedly risk account action. No TextNow parser is claimed and no live TextNow provider was built.
6. Inbound caller catalogue marks VictorSMS and VictorBots as **pending-refused** (certificates not pinned). The owner must pair
   them (password pairing) or pin certificates before any Android transport will accept them.

## Decisions needed from the owner

1. **Auto-send reverses a documented VictorSMS invariant.** `AutomationGate` supports Auto/Autonomous modes because the brief
   requires them, but VictorSMS docs promise "never auto-sent". Default is Approval mode and everything is disabled until the
   owner enables a rule for one bot in one conversation. Do not wire the real sender until you accept this change.
2. A real (redacted) **TextNow export** is needed to write and verify a TextNow-specific parser.
3. Whether VictorBots style profiles should join backup v2 (currently they have their own export/import only).
4. The user guide is Markdown in each repo (their convention). Say if you want PDF or DOCX regenerated.

## Status by section of the brief

| § | Area | Status |
|---|---|---|
| 1,2 | Architecture, inspection | Implemented (ownership documented in `COMPANION_COMMS.md`); baseline recorded above |
| 3 | Extend companion interface | **Broker: Implemented, Built, Tested.** Discovery, versioning, graceful incompatibility: Tested. **Android bound-service transport, cert verification wiring: Requested.** Integration with Chat/Plan/Pilot/Forge: 27 `communications_*` tools Built+Tested against fakes; not run by a real agent loop. Assist/Remember: Requested |
| 4 | VictorSMS gateway | Pre-existing SMS/MMS/dialer/Twilio/mail code untouched and, per its own docs, never device-tested. New provider-capability architecture, multi-SIM selection, failed-message recovery, drafts, scheduled messages, auto-replies UI: Requested |
| 5 | Communications tools | 27 capabilities catalogued with permissions/risk/approval; **Tested with a fake backend**. A real VictorSMS/VictorBots backend for them: Requested. Natural-language/voice command mapping depends on a model; not tested |
| 6 | Import Center | Importers CSV, JSON, SMS-Backup XML (XXE-hardened), WhatsApp-style logs, pasted text, ZIP: **Built, Tested**. Preview, participants, date range, duplicates, errors, unresolved senders, column mapping API, self-identification, original file preserved: **Tested**. **HTML: Unsupported. TextNow-specific parser: Unsupported (format unknown). SAF/share-target/UI/manual-mapping screen: Requested** |
| 7 | Updating imported conversations | **Built, Tested** (the headline requirement): auto and manual matching, file hashing, stable ids, content+time fingerprinting, legitimate repeated texts, precision tolerance, time-zone shift *suggestion*, conflicts with resolution, import history, rollback, nothing deleted when missing from a newer export, historical never live. "Update Conversation" button: Requested. Incremental search index: Tested (FTS4) |
| 8 | Conversation intelligence | Archive, FTS search, budgeted retrieval, approved facts, summaries store, encrypted backup/restore, selective deletion incl. index: **Built, Tested**. Semantic/embedding retrieval, follow-up reminders, important-message flags: Requested. At-rest database encryption: Unsupported in this slice (app-private storage only, same limit as VictorBots) |
| 9–11 | Style engine, profiles, corrections | **Built, Tested** (Java): outgoing-only analysis, versions/rollback/compare/duplicate/reset/export/import, learned preferences reversible, no self-reinforcement. Quality against real conversations: **not measured**. Preview-generation and comparison screens: Requested |
| 12 | Communication agents | Config, default Approval mode, one bot per conversation, pause one/all: **Tested**. Avatar/personality use the existing VictorBots bot. Voice configuration, per-bot model choice, activity monitoring UI: Requested |
| 13 | Incoming message pipeline | `AutomationGate` and `Outbox` (hours, rate, loop, flood, idempotency, restart recovery): **Tested**. Orchestration in `SmsDeliverReceiver`, batching/debounce: Requested |
| 14 | Voice messages | Requested (DroidCommand's existing STT/TTS modules are not yet integrated) |
| 15 | Calls | Catalogue entry `place_call` Tested against a fake. Native placing already exists in VictorSMS (not device-tested). **Interactive AI calling: Requested; not claimed** — ordinary cellular calls give apps no two-way audio access |
| 16 | TextNow | Import: Built (generic formats). Live TextNow: **Unsupported** (no official API). Handoff workflow: Requested |
| 17,18 | Unified use, shared AI | Ownership enforced in design; `ReplyModel` seam Tested. `PolicyInference` wiring and per-purpose model selection: Requested |
| 19 | Secure IPC | Broker authorisation model **Tested** (grants, per-call approval, replay, rate limit, idempotency, audit, kill switch). Android IPC, pairing, UIs: Requested |
| 20 | Automation/scheduling | Persistent outbox state machine Tested. Scheduler, retries engine, translation: Requested |
| 21 | User interface | **Requested; no screens were built** |
| 22 | VictorLinux | **Blocked: not in the workspace** |
| 23 | Testing | JVM unit/integration tests: **Tested** (below). Android instrumentation, emulator, device, carrier, multi-SIM, battery, memory: **Blocked (none available); not run** |
| 24 | Documentation | Protocol, report, per-repo architecture/manual/verification updates: Implemented. **Screenshots: Blocked (no UI, no emulator).** Guide-app export: Requested |
| 25 | Deliverables | Source, schemas/migrations, importers, tests, reports: yes. **Debug APKs: not produced in this session** (the PR CI jobs build them; see each PR). Release APKs: Blocked (no signing keys) |
| 26 | End-to-end | **Partially implemented**: every link has a tested core; none is wired through Android |

## Test evidence (this environment, JVM + real SQLite)

| Module | Tests | Notes |
|---|---|---|
| VictorSMS `comms-core` | 58, 0 failing | `ImportEngineTest` (22 incl. a 20,000→20,500-message re-import) ran in 2.9 s total |
| DroidCommand `core-communications` | 34, 0 failing | ktlint passes; baseline modules unchanged |
| VictorBots | `StyleTest` 81 · `CommsTest` 68 · `MigrationTest` 25; **full JVM suite green** | includes `GatewayTest`: no second model path |

**Mutation checks** (a deliberate break, run, restored byte-for-byte): comms-core 5 of 5 caught after strengthening one test that
initially missed "one saved message absorbs several incoming ones"; core-communications 7 of 7 (approval bypass, auto-claim
without grant, replay, kill switch, bot scope, double execution, unknown links); VictorBots 6 of 6.

Bugs found by tests during this work, all fixed: day-first dates in chat logs rejected every line; outbox states compared
in the wrong case; `FEWER_EMOJIS` preference kind mismatch; a ktlint failure that would have failed CI.

## Security review (design level; no external audit, no device)

Strengths, each backed by a test: owner-only grants; per-call approval bound to exact arguments; auto-claim ignored without a
grant; replay/idempotency/rate limits; audit-before-act that fails closed; unknown outcomes never retried; emergency stop;
imported history can never trigger automation; XXE-hardened XML; ZIP entry/size limits; FTS query quoting; prompt-injection
containment and output validation; style examples exclude links/emails/numbers; encrypted (AES-256-GCM, PBKDF2 210k) backup.

Known gaps: archive database is not encrypted at rest; no transport exists so caller-identity verification is untested on
Android; heuristics (auto-responder detection, time-shift detection, formality score) are unmeasured against real data;
the broker's rate limits and replay cache are in memory (lost on restart; receipts and grants need a persistent store before
production); prompt-injection defences reduce, not eliminate, risk — the real boundary is that model output cannot execute.

## Performance review

Only one measurement exists: a 20,500-message archive imports and re-imports (matching 20,000 existing) well inside 3 s on
this container's JVM. Memory, battery, on-device SQLite speed and model latency are **not measured**.

## Next slice (suggested order)

1. VictorSMS: `SqlDb` adapter over `SQLiteDatabase`, import screen (SAF + share target + preview + self-identification + mapping),
   "Update Conversation" button, saved-conversations list. Needs an emulator run.
2. DroidCommand: bound-service transport + grant/approval screens + `PolicyInference` `ReplyModel` + persistent stores.
3. VictorSMS backend connector implementing `CommsBackend`; VictorBots connector for profiles.
4. Only then: auto-send wiring (decision 1), voice messages, scheduling.
