# Victorious Fleet — communications integration report (2026-11-24)

Victorious Framework. Covers `droidcommand-AI`, `VictorBots`, `VictorSMS` (workspace repos). **VictorLinux was not in the
workspace**, so section 22 of the brief is Blocked, not skipped.

## Honest summary

This work built and tested the **cores** of the system and, in a second pass, the **Android shells around two of them**:
the conversation archive and incremental import engine (VictorSMS `comms-core`, now with an Android SQLite adapter and an Import Center in the app),
the authorised communications contract and broker (DroidCommand `core-communications`, now with durable state, a bound-service transport and an owner
screen), and the writing-style, profile and agent-configuration engine (VictorBots, still core only). Together they implement the headline requirement at
the logic level: import an export, save it permanently, update it repeatedly without duplicates or loss, build a style profile from the owner's own words,
and draft a reply from budgeted context through a policy-checked model route.

They are **still not connected to each other on a phone.** A read-only VictorSMS-to-DroidCommand connector now exists on both sides (built and tested, never run
against each other or on a device; see `COMPANION_COMMS.md` → VictorSMS connector), live SMS are not recorded into the archive, there is no VictorBots screen or service, and no voice-message or interactive-call work. Nothing has run on a device,
emulator, carrier or real model: this environment has no `/dev/kvm`, so verification is JVM tests, Android's SQLite via Robolectric, Compose screens
rendered on the JVM, local Gradle compiles, and CI. The end-to-end flow in section 26 is therefore **Partially implemented**. Do not read test counts as
device verification.

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
| 3 | Extend companion interface | **Broker and Android bound-service transport: Implemented, Built (compiles; manifest policy test); broker Tested, transport NEVER RUN.** Discovery, versioning, graceful incompatibility: Tested. **VictorSMS read-only archive connector (list, read, search, reply/summary context): Implemented, Built, Tested on the JVM and Robolectric, NEVER RUN against a device or the real counterpart app; its reply frames are pinned as literal copies in both repos.** VictorSMS write-side capabilities and the VictorBots connector: Requested (see below). Integration with Chat/Plan/Pilot/Forge: the 27 `communications_*` tools are Built+Tested against fakes. **Five of them (3 archive reads, `generate_reply`, `summarize_conversation`) are now registered for droidcommand-AI's own agent only while the owner's default-OFF switch (Communications screen) is on, and each refuses live when it is off; the other 22 (sends, calls, contacts, imports, automation) are deliberately never offered. Tested on the JVM, mutation-checked, Hilt graph compiles; start-up call and toggle never run on a device, no real agent loop has called them (2026-11-24f).** Assist/Remember: Requested |
| 4 | VictorSMS gateway | Pre-existing SMS/MMS/dialer/Twilio/mail code untouched and, per its own docs, never device-tested. New provider-capability architecture, multi-SIM selection, failed-message recovery, drafts, scheduled messages, auto-replies UI: Requested |
| 5 | Communications tools | 27 capabilities catalogued with permissions/risk/approval; **Tested with a fake backend**. A real VictorSMS/VictorBots backend for them: Requested. Natural-language/voice command mapping depends on a model; not tested |
| 6 | Import Center | Importers CSV, JSON, SMS-Backup XML (XXE-hardened), WhatsApp-style logs, pasted text, ZIP: **Built, Tested**. **Screens now exist** (VictorSMS Settings → Saved conversations): preview before anything is saved, who-is-me, provider/account, date order, column mapping, manual conversation choice, SAF picker and a share target: **Built, Tested on the JVM (Robolectric + Compose rendered on the JVM), NEVER RUN on a device.** **HTML: Unsupported. TextNow-specific parser: Unsupported (format unknown).** |
| 7 | Updating imported conversations | **Built, Tested** (the headline requirement): auto and manual matching, file hashing, stable ids, content+time fingerprinting, legitimate repeated texts, precision tolerance, time-zone shift *suggestion* (owner opt-in), conflicts with resolution, import history, **Undo**, nothing deleted when missing from a newer export, historical never live. **"Update conversation" button now exists** on each saved conversation (Built, UI tested on the JVM, never run on a device). Incremental search index: Tested (FTS4, also on Android's SQLite) |
| 8 | Conversation intelligence | Archive, FTS search, budgeted retrieval, approved facts, summaries store, encrypted backup/restore, selective deletion incl. index: **Built, Tested**. Semantic/embedding retrieval, follow-up reminders, important-message flags: Requested. At-rest database encryption: Unsupported in this slice (app-private storage only, same limit as VictorBots) |
| 9–11 | Style engine, profiles, corrections | **Built, Tested** (Java): outgoing-only analysis, versions/rollback/compare/duplicate/reset/export/import, learned preferences reversible, no self-reinforcement. Quality against real conversations: **not measured**. Preview-generation and comparison screens: Requested |
| 12 | Communication agents | Config, default Approval mode, one bot per conversation, pause one/all: **Tested**. Avatar/personality use the existing VictorBots bot. Voice configuration, per-bot model choice, activity monitoring UI: Requested |
| 13 | Incoming message pipeline | `AutomationGate` and `Outbox` (hours, rate, loop, flood, idempotency, restart recovery): **Tested**. Orchestration in `SmsDeliverReceiver`, batching/debounce: Requested |
| 14 | Voice messages | Requested (DroidCommand's existing STT/TTS modules are not yet integrated) |
| 15 | Calls | Catalogue entry `place_call` Tested against a fake. Native placing already exists in VictorSMS (not device-tested). **Interactive AI calling: Requested; not claimed** — ordinary cellular calls give apps no two-way audio access |
| 16 | TextNow | Import: Built (generic formats). Live TextNow: **Unsupported** (no official API). Handoff workflow: Requested |
| 17,18 | Unified use, shared AI | Ownership enforced in design; `ReplyModel` seam Tested. `PolicyInference` wiring and per-purpose model selection: Requested |
| 19 | Secure IPC | Broker authorisation model **Tested**. **Android transport Built, never run:** bound Messenger service, identity from `sendingUid` through the existing catalogue + owner pairing (never starts pairing, refuses shared uids), manifest allowlist test. VictorSMS/VictorBots are `pending-refused` until the owner pairs them. No client exists yet to call it |
| 20 | Automation/scheduling | Persistent outbox state machine Tested. Scheduler, retries engine, translation: Requested |
| 21 | User interface | **VictorSMS: saved conversations, import wizard, conversation view with Update/Search/Undo/conflicts/Delete (Built, JVM-tested).** **DroidCommand: Advanced → Communications (emergency stop, approvals, per-app grants) Built, not run.** VictorBots, Unified Inbox integration, Calls, Voice, Providers, Backup/Restore screens: Requested |
| 22 | VictorLinux | **Blocked: not in the workspace** |
| 23 | Testing | JVM unit/integration tests and Android-SQLite/Compose-on-JVM tests: **Tested** (below). Emulator, device, carrier, multi-SIM, battery, memory: **Blocked: this environment has no `/dev/kvm`; not run.** CI emulator launch exists for DroidCommand only and exercises the existing app |
| 24 | Documentation | Protocol, report, per-repo architecture/manual/verification updates: Implemented. **Screenshots: Blocked (no UI, no emulator).** Guide-app export: Requested |
| 25 | Deliverables | Source, schemas/migrations, importers, tests, reports: yes. **Debug APKs are built by each PR's CI**; I did not download or checksum them. Release APKs: Blocked (no signing keys) |
| 26 | End-to-end | **Partially implemented**: every link has a tested core; none is wired through Android |

## Test evidence (this environment; JVM, real SQLite, Android SQLite via Robolectric)

| Module | Tests | Notes |
|---|---|---|
| VictorSMS `comms-core` | 59, 0 failing | JDBC SQLite. Includes a 20,000→20,500-message re-import |
| VictorSMS `:app` | 108, 0 failing (17 new) | `AndroidArchiveTest` 9 (Android SQLite code, Robolectric native, API 34), `ImportSessionTest` 5, `ImportScreensTest` 3 (Compose rendered on the JVM). **Not an emulator** |
| DroidCommand `core-communications` | 42, 0 failing | ktlint clean; `ManifestPolicyTest` 20 (updated deliberately); `:app:compileDebugKotlin` with `-PincludeAndroid=true` passes |
| VictorBots | `StyleTest` 81 · `CommsTest` 68 · `MigrationTest` 25; **full JVM suite green** | includes `GatewayTest`: no second model path |

**Mutation checks** (a deliberate break, run, restored byte-for-byte): comms-core 5 of 5 caught after strengthening one test that
initially missed "one saved message absorbs several incoming ones"; core-communications 11 of 11 (approval bypass, auto-claim
without grant, replay, kill switch, bot scope, double execution, unknown links, and four durability breaks); VictorBots 6 of 6.

Bugs found by tests during this work, all fixed: day-first dates in chat logs rejected every line; outbox states compared
in the wrong case; `FEWER_EMOJIS` preference kind mismatch; a ktlint failure that would have failed CI; only the checkbox, not its label, was
tappable in the import wizard (found by the Compose test); a search box needed every-word matching, not any-word.

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

1. **VictorSMS connector: read side DONE (never run on a device).** Remaining: its first device run (pair, grant Archive read, switch one conversation on, call
   `discover` and `read_conversation`); then the write side (sends, drafts, calls) behind VictorSMS's own gate.
2. **Record live SMS into the archive** (`SmsDeliverReceiver`), then wire `AutomationGate` and `Outbox` to the real sender. Only after decision 1 below.
3. **VictorBots:** a screen for profiles/agents and a service that answers `get_style_profile`; add its tables to backup.
4. **First device run** (needs a phone or a KVM host): bind the service from a paired app and call `discover`; import a real redacted export.
5. Then voice messages, scheduling, interactive calls.

## Final status — 2026-11-25 (Victorious Framework)

This section supersedes the "Next slice" list above for the pieces named here. Nothing below is **Verified** in the report's own sense: **nothing has run on a device, an emulator with the communications screens open, a carrier, or a real model.** Everything is JVM-tested, and the `:app` code is compiled by CI only.

### What was merged this session (all in `droidcommand-AI`, squash commits on `main`)

| PR | Merge commit | What it added |
|---|---|---|
| #343 | `8cdc740` | `FileShareEngine` (default-deny, per-conversation file grants), attachment validator + `ShareGatedBackend`, `ConversationOrchestrator` |
| #344 | `329a9ce` | Engine and gate wired into `CommunicationsModule`; owner request / stop-sharing UI |
| #345 | `1d6020d` | Owner share form (read-only derived from the operations), file access history |
| #347 | `a50f1a8` | Reply drafts for new live messages (archive poller + scheduler + draft inbox). **Drafts only; nothing is sent** |
| #351 | `106ffcf` | Optional automatic checking (WorkManager, default off, 15-minute minimum) |
| #355 | `39ae6a2` | Android folder and file pickers (`content://` scopes, read-only), `ContentResolverMediaGateway` |

`core-communications` on `main`: **137 tests, 0 failures**, ktlint clean (some of the growth since the first report came from PR #349, which was not mine). Mutation checks of the security rules: caught in every set tried (file-share engine, draft pipeline, content-reference containment: 4 of 4 for the last). Bugs found by tests or CI and fixed along the way: recipient rule applied only to the first matching grant; two `content://` parser holes (a malformed `/tree/<id>/document` accepted as opaque; `primary:../x` passing the `..` check); text sniffing rejecting a file cut mid-character; a non-exhaustive `when` and a JVM `setOn` signature clash that only CI's compiler could see.

### Synthetic load (fake model, 2 ms per turn, 10 messages per conversation, one JVM)

| Conversations | Messages | Inference slots | Wall time | Max concurrent model calls |
|---|---|---|---|---|
| 20 | 200 | 4 | 112 ms | 4 |
| 50 | 500 | 6 | 196 ms | 6 |
| 100 | 1000 | 8 | 292 ms | 8 |

Each run kept strict per-conversation order, handled every message once, and no conversation's model call saw another's memory. This proves scheduler behaviour only. **No physical-device, multi-SIM, battery, memory or real-model load test was run.**

### Status against the brief (what exists now)

| Area | Status |
|---|---|
| Per-conversation file permissions (folder, subfolders, file, collection, read-only, expiry, approval, recipient limit, revocation, history, no inheritance, incoming message cannot authorise) | **Implemented, JVM-tested.** Owner form and request screens: Built, never run |
| Scoped storage / Storage Access Framework | **Implemented** for picked folders and files (read-only). Photo Picker not used (grants are not persistable); MediaStore not used |
| Attachment validation (content sniffing, executable and double-extension blocking, size and route limits) | **Implemented, JVM-tested.** SMS and TextNow routes refuse attachments (no supported automated path). The 1 MB MMS limit is an assumption, not a verified carrier limit |
| 20 / 50 / 100 concurrent conversations | **Scheduler implemented and synthetically load-tested** (table above); not wired to a live feed |
| Reply drafts from new messages, manual and automatic checking | **Implemented, JVM-tested, `:app` compiled by CI.** Never run on a device. No notification when a draft is ready |
| Emergency stop | Applies to file access, drafting and the existing broker paths |

### Not built, or blocked (do not read any of this as done)

- **Sending anything.** There is no way to place or send a draft, an attachment, a voice message or a call from this work. VictorSMS's write side is not built.
- **Recipient verification (blocks attachment sending).** VictorSMS deliberately exposes no phone number or account in its conversation list (its own test asserts it), so DroidCommand has nothing authoritative to check who a message goes to. **Owner decision needed:** (a) VictorSMS adds participant numbers to the list reply for conversations you switch on (reverses that privacy rule); (b) keep numbers private and add a send-time destination check inside VictorSMS (my recommendation; a larger write-side change); (c) keep attachment sending off.
- **Auto-send.** Whether VictorSMS may ever send without a tap reverses its documented "never auto-sent" rule. Still an owner decision (see decision 1 above in this report).
- **File-Manager gateway.** Its fleet bridge is still a read-only stub that returns no files, so File-Manager is not yet the storage gateway. It must remain an EXTERNAL_NODE (no AIDL, no DependencyGuard, no shared signing key).
- **Handing a picked file to VictorSMS** needs a per-send `grantUriPermission`; not built. Stopping a share denies at once in the engine, but Android's persisted read grant is not yet released.
- **Bots cannot list or search shared files** (the 27-capability catalogue is unchanged).
- **Media understanding** (photos, video, documents, voice messages), captions, speech, calls, interactive calling.
- **Third-party apps** including TextNow (no official API; automated sending is not claimed), and the VictorBots connector and screens.
- **Not touched in this work:** VictorBots, VictorSMS, File-Manager, VictorLinux, VictorSuite, Victorious Mesh, RustdeskV, Victorious Browser. No README or user manual in those repos was updated.
- **No screenshots, no physical-device testing, no release APK.** CI builds a debug APK on every PR; I never downloaded one, so I have no APK checksum. One CI artifact digest was recorded for reference: the artifact zip of run 45468478159 (PR #344's head `d47b8da`) reported `sha256:8b4689abbf1044340106f35de4e5695a683329b7b4b38cb3055c3f58b9717b3b`; that is the zip, it was never downloaded or verified, and it expires 2026-12-01.

## Decisions recorded — 2026-11-25 (owner said "You choose")

The two decisions listed above as open were delegated to me. I chose the option that keeps every existing VictorSMS rule intact. **The owner can overrule either; nothing here is built on them yet.**

1. **Recipient verification: option (b).** VictorSMS keeps its privacy rule (no phone number or account in the conversation list). DroidCommand never supplies or learns a destination number: it names a *conversation*, and VictorSMS derives the destination itself from its own archive. A grant's recipient restriction, if any, is to be enforced **inside VictorSMS** at send time. That check is part of VictorSMS's write side, which is **not built**; until it is, the gate stays fail-closed (`ShareGatedBackend` still refuses a conversation-addressed attachment send because no contact can be resolved).
2. **Auto-send: not enabled.** VictorSMS's documented "never auto-sent" rule stands. Every send, attachments included, needs the owner's tap on the phone, like VictorSMS's existing remote `sms.send` ("Approve SMS?" screen). `AutomationGate`'s Auto/Autonomous modes stay unwired to a real sender.

### A bigger blocker found while doing this

**VictorSMS cannot send MMS from the SIM at all.** Its manual says so ("Not implemented: sending MMS from the SIM; alias MMS goes through Twilio"), and the only attachment route it has is Twilio alias MMS, which needs a **public `https://` image URL**. That route is not acceptable for a private photo (it would publish it), so it is not used. Native attachment sending therefore needs a new on-device MMS sender in VictorSMS, which requires a real SIM and the default-SMS role to build and verify. Not attempted: it cannot be verified here and an unverifiable MMS sender would be a guess. Plain text SMS with owner approval already exists in VictorSMS and is unaffected.

### Built in this step

When a share stops, the engine now reports the picked references that no active grant covers any more, and the app releases only the Android read grants the share dialog itself took (a ledger, so chat attachments, model import and the other features' grants are never touched). 8 JVM tests, 3 of 3 mutation checks caught; the app part is compiled by CI only and never run. Limits: an expired grant is not released at the moment it expires (only when a later revoke or replace happens); if the owner picked the very same folder or file for another feature, Android keeps one grant per URI and releasing it here releases it there too.

## Update 2026-11-26 — bots can text and respond, as drafts the owner sends (Victorious Framework)
Built: conversation-addressed hand-off to VictorSMS (`message.draft.from_archive` / `message.draft.status`) for the bot capabilities `create_draft`, `send_sms`, `get_delivery_status`. Every send still needs the owner's Send in VictorSMS; no phone number crosses the boundary. The owner's own "review this reply in VictorSMS" button is PR #358's. JVM-tested; the app wiring is compile-checked by CI only. Still not possible: attachment/MMS sending, auto-send (owner decision, unchanged). Never run on a device or against a real VictorSMS. Details in `docs/COMPANION_COMMS.md`.
