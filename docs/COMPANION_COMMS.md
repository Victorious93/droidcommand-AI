# COMPANION_COMMS v1 — Victorious Framework communications contract

Added 2026-11-24. **Status: broker IMPLEMENTED, BUILT, TESTED on the JVM (42 tests, 11 security/durability mutations verified caught).
The Android shell is BUILT but NEVER RUN: a bound Messenger service, Hilt wiring, durable stores and the owner screen compile
(`:app:compileDebugKotlin`, `ManifestPolicyTest`, ktlint) but nothing has run on a device, an emulator, a carrier or a real model. The VictorSMS and
VictorBots connector is NOT BUILT (its capabilities report "not connected"). The VictorSMS connector is BUILT for three read capabilities plus reply/summary context
(section "VictorSMS connector"), also never run on a device; every other VictorSMS capability reports "unavailable".**
Cross-repo status: `docs/COMMUNICATIONS_INTEGRATION_REPORT.md`.

## Why a third contract

| Contract | Transport | Human tap per request | Capabilities | Can serve an unattended bot? |
|---|---|---|---|---|
| `COMPANION_ASSIST` | exported Activity | yes | text only | no |
| `COMPANION_EXEC` v1 | exported Activity | yes | one read-only (`device.apps.list`) | no |
| **`COMPANION_COMMS` v1** | **transport-neutral broker** (Android: bound service, planned) | **only where the owner's policy requires it** | 27 `communications.*` | **yes, but only with owner-created grants** |

`COMPANION_ASSIST` and `COMPANION_EXEC` are unchanged. This module (`core-communications`) is pure JVM; the transport
supplies a caller identity it has already verified (same catalogue and pairing as `COMPANION_ASSIST`; there is no second
certificate list) and hands the request JSON to `CommsBroker.handle(caller, json)`.

## Ownership

| Concern | Owner | DroidCommand's role |
|---|---|---|
| Models, inference routing, STT/TTS, tool execution, approvals, audit | **droidcommand-AI** | everything |
| Conversation archive, import, delivery, providers, contacts, calls, final send authority | **VictorSMS** (`comms-core`) | asks, never sends |
| Bots, personalities, style profiles, corrections, assignments | **VictorBots** | asks for AI work, serves profiles |

droidcommand-AI never holds a copy of an archive, never sends a message itself, and never lets model output decide
anything. VictorSMS re-checks authority independently of anything DroidCommand says (`AutomationGate`, `Outbox`).

## Android transport (built, never run)

`ai.droidcommand.action.COMPANION_COMMS`, exported bound **Messenger** service `.communications.CompanionCommsService`. A client binds, sends
`Message.what = 1` with a bundle string `request_json` (the JSON below) and `replyTo`, and receives `what = 2` with `result_json`.
Identity is **only** `Message.sendingUid`: it must map to exactly one package (shared uids are refused), the package must be in the inbound
catalogue and not excluded, and its signing certificate must pass `CompanionAssist.verifyCaller` including an **existing owner pairing**. The
service never starts pairing; an unpaired app gets `not_paired` and nothing runs. Pair VictorBots/VictorSMS first through the existing
COMPANION_ASSIST password-pairing flow (both are `pending-refused` in the catalogue until then). `ManifestPolicyTest` allowlists exactly this
component and its single action.

Owner screen: Advanced → *Communications*: emergency stop, requests waiting for approval (Allow once / Deny), apps allowed (per-app permissions, optional
bot id, lifetime, one use), Remove. The transport never receives the admin object.

State (`files/communications`): `grants.json`, `approvals.json`, `receipts.json`, `emergency-stop`, written atomically. An unreadable grants or approvals
file is quarantined (`*.corrupt-<time>`) and reads as **no authority**; an unreadable receipts file makes sends refuse (`receipts_unavailable`) because a
repeat could no longer be detected. Nonces are memory-only, so a request stamped before the last restart is answered `stale_timestamp` and must be re-sent.

## Wire

Request (JSON object, at most 64,000 characters):

```json
{"v":1,"min_v":1,"task":"call","request_id":"8-64 [A-Za-z0-9_-]","nonce":"32 lowercase hex",
 "ts":1800000000000,"expires_at":1800000060000,"capability":"communications.send_sms",
 "args":{"to":"+15550100101","body":"running late"},
 "actor":{"bot_id":"<bot uid>","mode":"approval|auto"},"reason":"short text"}
```

`task` is `discover`, `call` or `receipt`. `expires_at` is at most 5 minutes ahead; `ts` must be within 120 s of the
broker's clock. A **nonce is single-use, including on a re-send of the same `request_id`**. Arguments are strings, numbers or
booleans only; unknown argument names are rejected, never ignored.

Reply: `{"v":1,"ok":bool,"state":"completed|running|pending_approval|failed|refused|unknown|error","request_id":…,
"code":…,"detail":…,"result":{…},"job_id":…,"approval_id":…,"audit_ref":…}`. `ok` is true only for `completed`, `running`
and `pending_approval`.

| `state` | Meaning | Client may |
|---|---|---|
| `completed` | the owning app reported success | record it as done |
| `running` | accepted; `job_id` given | poll with `receipt` |
| `pending_approval` | waiting for the owner in droidcommand-AI; **nothing was done** | re-send the same `request_id` and arguments with a fresh nonce to collect the outcome |
| `refused` | not allowed (`not_granted`, `denied`, `kill_switch`, `rate_limited`, `unavailable`, …) | not retry unchanged |
| `failed` | definitely did not happen | decide whether to ask again |
| `unknown` | **may or may not have happened** (hand-off then silence, crash) | check delivery status; **never resend** |
| `error` | protocol error (`invalid_request`, `incompatible_version`, `replayed_nonce`, `stale_timestamp`, `request_id_reused`, …) | fix the request |

### Discovery and versions

`discover` returns every capability with `status: available|unavailable` and, when unavailable, a plain `reason`
("The app that provides this (victorsms) is not connected."). It also returns `versions {min,max}`, limits and the
emergency-stop state. A client sends its preferred `v` and `min_v`; the broker negotiates down, or answers
`incompatible_version` with its own range so the client can tell the owner exactly what to update. Capabilities never
report success for work no connected app can do.

## Capabilities

`owner` = who performs it. Permission names are the owner-facing grant names. "Approval" is per call: `per_call` means the
owner approves in droidcommand-AI unless an auto-reply grant applies; `owner_every_time` can never be satisfied by a
standing grant.

| Capability | Owner | Needs | Approval |
|---|---|---|---|
| list_contacts · search_contacts | VictorSMS | read_contacts | never |
| list_conversations · get_unread_messages · get_delivery_status · list_providers | VictorSMS | read_messages | never |
| read_conversation · search_messages | VictorSMS | read_messages + read_imported | never |
| generate_reply | droidcommand-AI | read_messages + read_imported + access_memory + use_ai | never (a draft, never a send) |
| summarize_conversation | droidcommand-AI | same | never |
| create_draft | VictorSMS | create_drafts | never (a draft, never a send) |
| send_sms · send_email · schedule_message | VictorSMS | send_messages | **per_call** |
| send_mms | VictorSMS | send_messages + send_attachments | **per_call** |
| send_attachment | VictorSMS | send_attachments | **per_call** |
| send_voice_message | VictorSMS | send_voice | **per_call** |
| place_call | VictorSMS | place_calls | **per_call** |
| get_call_status · get_call_history | VictorSMS | read_call_history | never |
| import_conversation · update_conversation | VictorSMS | import_conversations | never (file chosen by the owner; rollbackable) |
| get_style_profile · update_style_profile | VictorBots | access_profiles | never |
| set_auto_reply | VictorSMS | enable_auto_reply | **owner_every_time** when enabling; none when disabling |
| pause_agent · cancel_job | VictorSMS | none | never. Pausing and cancelling are always allowed to a paired app; `cancel_job` only for jobs that same app started |

There is deliberately **no** capability that creates, widens or extends a grant or approves an approval.

## Security properties (each has a test in `BrokerTest`/`ReplyTest`)

* **Caller identity comes from the transport**, never the payload; a blank caller is refused (`not_paired`).
* **Grants are owner-created only** (`GrantStore.add` is called from the owner's UI). Scope: app, optionally bot,
  optionally contacts, optional expiry, optional single use, revocable. A grant for bot A is unusable by bot B and by
  another app.
* **Approvals are bound** to caller, request id, capability and the exact arguments by hash. Changing the text after
  approval is `request_id_reused`. An approval authorises one execution (consumed atomically) and expires with the request.
* **`mode: "auto"` is a claim.** It is honoured only if the owner granted `enable_auto_reply` for that bot and contact; otherwise the
  call is treated as per-call approval. VictorSMS re-evaluates its own rule regardless.
* **Idempotency**: receipts are keyed by (caller, request id). A resend returns the stored receipt and never executes again.
  Receipts are per caller.
* **Replay**: nonce single-use, timestamp skew ≤ 120 s, expiry ≤ 5 min.
* **Rate limits** per caller and risk class (reads 120/min, sends and calls 10/min).
* **Emergency stop** (`KillSwitch`): sends, calls and enabling automation are refused; reading, pausing, cancelling and
  disabling auto-reply still work.
* **Audit before action**: anything that sends, calls or changes automation is audited first; a full audit log fails closed
  (`audit_unavailable`).
* **Uncertain outcomes** are `unknown`, stored, and never retried. A crash during a read is `failed`; during a send/call it is `unknown`.
* **Prompt injection**: conversation text reaches a model only as JSON-quoted data between `<<<DATA` and `DATA>>>`; look-alike
  delimiters in messages are defused. Model output is validated: no tool-call or code text, no leaked instructions, no link,
  email or phone number the **owner** did not supply (links appearing only in incoming messages are not trusted), bounded
  length and count. `generate_reply` has no path to execution; it returns `sent:false, requires_review:true`.

## Inference policy (required wiring)

`ReplyModel.generate(feature, prompt)` is the only way this module reaches a model. The app **must** implement it with
`PolicyInference.ask(feature, prompt)` so `docs/INFERENCE_POLICY.md` (this device only / my PC only / confirmed cloud
fallback) decides where private conversation text may go. Features used: `"Communications reply"`, `"Communications summary"`.
Not yet wired.

## Backend contract (what VictorSMS and VictorBots must implement)

`CommsBackend.availability(cap)`, `call(cap, args, ctx)` → `Ok | Running | Refused | Failed | Uncertain`, and
`context(conversation, query, maxChars)` (blank query means "use the latest incoming message"). `ctx.autonomy` is `none`,
`approved` or `auto`; the backend must still apply its own gate. VictorSMS's reference implementation of the data side is
`comms-core` (`ContextRetriever`, `Archive`, `AutomationGate`, `Outbox`). The Android binding is not built.

## TextNow

TextNow's help centre documents **Export Conversations on Android only**; the file format is not stated there, and no
official public API was found. Community libraries log in with browser cookies and users report possible account action.
This contract therefore treats TextNow as an **import source** (generic CSV/JSON/text with mapping) and a user-directed
handoff, never as a live provider. Do not add unofficial TextNow access.

## VictorSMS connector (read-only archive)

Added 2026-11-24c. **Status: Implemented, Built, Tested on the JVM and under Robolectric. NEVER RUN against a real phone, a real Messenger bind or an
installed VictorSMS.** The two sides were built and tested separately; their agreement rests on reply frames pinned as literal copies in both
repositories (`VictorSmsFramesContractTest` here, `ArchiveBridgeTest`/`DcaFramesTest` in VictorSMS), not on a run of one against the other.

**What it serves:** `list_conversations`, `read_conversation`, `search_messages`, and the context behind `generate_reply` / `summarize_conversation`.
Everything else VictorSMS owns (contacts, sending, calls, drafts, imports, auto-reply, delivery status, providers) is reported **unavailable** in discovery with
the reason "VictorSMS does not provide this through the assistant connector." and a call is refused. Nothing is simulated. VictorBots-owned capabilities
still say "not connected".

**Path:** caller app -> `CommsBroker` -> `CompositeBackend` -> `VictorSmsCommsBackend` -> `ArchiveChannel` (seam in `core-communications`) ->
`ControllerArchiveChannel` (`:app`) -> `FleetMessengerController.callOwnerGrantedRead` (paired, package and signing certificate re-checked on every call;
only the five listed operations) -> Messenger -> VictorSMS `BridgeService`.

**Gates, all of which must pass and none of which this app can widen:**
1. This app's broker: the owner granted the caller `READ_MESSAGES` + `READ_IMPORTED` (plus `ACCESS_MEMORY` + `USE_AI` for reply/summary).
2. The owner paired VictorSMS here (single signer, certificate pinned).
3. In VictorSMS: External control is ON, this app is approved under *Connected apps*, and the owner granted it the separate **Archive read** group
   (it is not part of *Read-only*, because message text crosses the bridge).
4. In VictorSMS: the owner switched **Let my assistant read this conversation** on for that conversation. Off by default for every existing and new
   conversation; an upgrade, and a restore of a pre-connector backup, leave everything off. A conversation that is not switched on answers exactly like one that
   does not exist (`not_found`), so existence cannot be probed.

**Wire (protocol major 1, op group `ARCHIVE_READ`; strict parameters, unknown ones are refused):**

| Operation | Parameters | Answer |
|---|---|---|
| `comms.capabilities` | none | `version`, `ops`, `access:"per_conversation"`, `shared_conversations`, `max_limit`, `max_body_chars` |
| `comms.conversations.list` | `limit`, `provider` | shared conversations: `id` (opaque uid), `title`, `provider`, `is_group`, `message_count`, `last_ts` (no number or account) |
| `comms.conversation.read` | `conversation`, `limit`, `before_ts`, `direction` | messages oldest first, `has_more`, `next_before_ts`; runs of one timestamp are never split across pages |
| `comms.messages.search` | `query`, `conversation`, `limit` | hits across shared conversations; every word first, then any word, with `match:"all"|"any"` |
| `comms.context.retrieve` | `conversation`, `query`, `max_chars` | recent and relevant turns, approved facts, summary, the owner's own earlier messages, `truncated` |

**Limits:** 200 rows, 4,000 characters per body (clipped and flagged `truncated`), 45,000 serialized characters of messages per answer. This broker refuses any
result over 60,000 characters with `result_too_large` instead of cutting it into broken JSON (the earlier code truncated silently; fixed here).

**Failure mapping:** an unreachable, unpaired or certificate-changed VictorSMS is `backend_unavailable`; VictorSMS saying no keeps its own code
(`permission_denied`, `not_found`, `external_control_disabled`, ...) with a plain explanation; a reply the validator rejects is a failure. Reads are idempotent,
so a read is never reported as "outcome unknown", and the broker never retries.

**Transparency in VictorSMS:** every archive request (except `comms.capabilities`) writes one access-log row (app, operation, conversation id, count, outcome, time;
never message text). The conversation screen shows the latest five; the log keeps 500 rows and is removed with the conversation.

**Two defects found and fixed while building this (both pre-existing):**
- `CompositeBackend.availability` returned "not connected" for every capability that *was* available (it treated `null`, which means "can be served", as "no backend").
  It only went unnoticed because the app wired `UnconnectedBackend` and the tests used a fake directly.
- VictorSMS's replies on the droidcommand-AI wire carried no `node_id` and its errors only a `code`. This app's validator rejects the first as an identity mismatch and
  cannot decode the second, so **even the existing `sms.send` path could not have validated a VictorSMS reply**. VictorSMS now echoes `target_node_id` as `node_id`
  and sends `{category, message, code}`; `VictorSmsFramesContractTest` runs the real validator on both the old and the new shapes.

**Known limits, stated plainly:**
- `node_id` is a *correlation echo*, not an identity proof. VictorSMS's own node id is `victorsms-<hex>`, while this app pairs it as `victorsms`; real node-id
  binding is not done. Identity on this transport is the OS: `Message.sendingUid` -> package + signing certificate on VictorSMS's side, and a pinned certificate
  before binding on this side.
- `discover` probes VictorSMS (`comms.capabilities`, cached 30 s when it works, 5 s when it does not). A paired but unresponsive VictorSMS can make one discovery wait for
  the bridge timeout (10 s plus grace).
- Reads only. No sending, drafting, import or auto-reply goes through this connector.
- Nothing here has bound a service, received a Messenger reply or read a real conversation on a device.

## droidcommand-AI's own agent: the default-off switch (2026-11-24, Victorious Framework)

droidcommand-AI's Chat, Plan, Pilot and Forge get **no** `communications_*` tools by default. Communications screen → *Let the assistant use these tools* turns them on; the choice is stored in
`AdvancedPrefs` (`communications.agent.tools`, default **false**) and applied at app start and whenever the owner flips it.

Why a switch: what these tools return is conversation text, and it goes into whichever model the owner's inference policy routes to (`docs/INFERENCE_POLICY.md`: this device, the owner's PC, or a confirmed
cloud fallback). The owner decides that once, here, not implicitly.

| Property | How it is enforced (`AgentToolSwitch.kt`, each has a test in `AgentToolSwitchTest`/`CommunicationsViewModelTest`) |
|---|---|
| Off means absent | While off, the five tools are not in the `ToolRegistry`, so a planner is never even shown them. `apply()` removes exactly ours, never a same-named tool someone else registered. |
| Off means refused, even mid-plan | Each tool is wrapped in `GatedCommunicationsTool`, which reads the switch on **every call**. A plan that already holds a tool is refused the moment the owner turns it off, before the registry is updated. |
| Reads and drafts only | The registrar's constructor refuses any capability whose risk is not READ or DRAFT. It cannot be built with `send_sms`, `place_call`, `set_auto_reply`, imports or contacts. |
| Only what can work | Offered: `list_conversations`, `read_conversation`, `search_messages` (VictorSMS archive) and `generate_reply`, `summarize_conversation` (this app's own model). The other 22 are not offered, so the owner is never asked to approve a send that could only be refused. |
| Choice saved before it takes effect | `setEnabled` persists, then registers, so a crash between the two cannot leave tools on that the owner turned off. |
| VictorSMS's gates are untouched | The agent path still needs VictorSMS's *Archive read* permission for droidcommand-AI and the per-conversation assistant switch. This switch widens neither. |

What this does **not** do: it does not stop the agent from using other, existing tools after it has read a message (for example the separately gated `victorsms_send` tools); those have their own switch and
approval. It does not make `generate_reply` auto-send; there is no auto-reply path.

**Tested:** the registrar and gate on the JVM (12 tests), mutation-checked (7 of 7 mutants killed); the view model in plain JVM tests (4 tests, 4 of 4 mutants killed); the Hilt graph compiles (JDK 21).
**NOT verified:** the Application start-up call and the Hilt provider are not exercised by any test (only compiled); the screen toggle was not rendered or tapped; no real agent loop has called one of these tools;
nothing ran on a device.

## Not built

- **The VictorBots backend.** It needs a service to answer `get_style_profile` / `update_style_profile`; until then its capabilities report "not connected".
- **VictorSMS write-side capabilities** (contacts, sends, calls, drafts, imports, auto-reply, delivery status, providers). Sends and calls need VictorSMS's real
  sender behind its own `AutomationGate`/`Outbox`, which has no caller yet.
- Live SMS are not yet recorded into the VictorSMS archive, so there is nothing for an automatic reply to answer.
- Voice-message and interactive-call capabilities beyond the catalogue entry; a scheduler for `schedule_message`; semantic (embedding) retrieval.
- Anything on a device. The first device step is binding the service from a paired app and calling `discover`.

## File sharing, attachments and multi-conversation orchestration (2026-11-24, Victorious Framework)

Added to `core-communications` (pure JVM; no Android, no new dependency on File-Manager). **Tested on the JVM only. Never run on a device, a carrier or a real model.**

| Piece | File | What it guarantees (each has a test) |
|---|---|---|
| `FileShareEngine` | `FileShare.kt` | Default deny. A grant is bound to **one conversation** (mandatory) and optionally a bot, contact and account; there is no inheritance and no "any conversation". Scopes: folder (direct children, or with subfolders), file, or an owner-picked collection. Independent operations: browse, search, preview, read, AI-analyse, download, upload, save, modify, send. Read-only grants cannot carry upload/save/modify. Expiry, per-grant approval requirement, recipient restriction (default: only that conversation's contact), immediate revocation per grant or per conversation, JSON-file persistence that fails closed on a corrupt file, access history for every allow **and** deny, emergency stop. |
| Incoming messages | `FileShare.kt` | A request whose origin is a received message is always denied. It can only queue an `ExpansionRequest` (flagged `triggeredByIncomingMessage`); only the owner-side `approveRequest` creates a grant, and only for the asking conversation. |
| Paths | `SharePaths` | Lexical normalisation; `..`, backslashes, NUL/control characters and percent-encoded dots/separators are **denied, not repaired**. It cannot see symlinks: a `MediaGateway` must `realpath` before reading. |
| `AttachmentValidator`, `ProviderMedia` | `Attachments.kt` | Content sniffing (JPEG, PNG, GIF, WebP, MP4/3GP/M4A, MP3, OGG, AMR, WAV, PDF, plain text) must agree with the declared type; ELF/PE/DEX/script content and blocked extensions (including double extensions) are rejected; size is checked; names are sanitised. **SMS and TextNow routes have no automated attachment path and are refused.** The MMS 1 MB limit is a cautious assumption, not a verified carrier limit. |
| `ShareGatedBackend` | `Attachments.kt` | Wraps any `CommsBackend`. `send_attachment`/`send_mms` run only for a file shared with *that conversation*, to a recipient the grant allows, after validation; a grant that requires approval cannot be used by an `auto` call. A refusal queues an expansion request for the owner. Everything else passes through. The broker, catalogue (still 27 capabilities) and their tests are unchanged. |
| `ConversationOrchestrator` | `Orchestrator.kt` | One ordered lane per conversation, one shared bounded inference pool, per-conversation memory handed to the model as a snapshot, duplicate message ids absorbed, fair scheduling (a flooding conversation cannot starve others), per-conversation pause, emergency stop, queue back-pressure, snapshot/restore without answering a handled message twice. |

### Synthetic load results (fake model, 2 ms per turn, 10 messages per conversation, this container's JVM)

| Conversations | Messages | Inference slots | Wall time | Max concurrent model calls |
|---|---|---|---|---|
| 20 | 200 | 4 | 112 ms | 4 |
| 50 | 500 | 6 | 196 ms | 6 |
| 100 | 1000 | 8 | 292 ms | 8 |

Asserted for each run: every message handled once, zero failures, strict per-conversation order, concurrency never above the slot count, and no conversation's model call ever saw another conversation's memory (canary check). **These numbers prove scheduler behaviour only.** They say nothing about real model latency, memory, battery or Android scheduling.

### Not built (still Requested)
- **Wiring:** `ShareGatedBackend` and `ConversationOrchestrator` are not yet constructed in `:app` or the Hilt graph, and the owner screens for grants, requests and history do not exist.
- **File-Manager:** its bridge is still a read-only stub that returns no files, so no `MediaGateway` implementation exists. Until it does, nothing can actually be sent. It must stay an EXTERNAL_NODE (no AIDL, no DependencyGuard, no shared signing key).
- **Media understanding** (photo, video, document, voice-message analysis), captions, and the Photo Picker / SAF / MediaStore / secure-content-URI plumbing. Encrypted-at-rest share state (the JSON file is plain).
- **Third-party apps** (TextNow and others) and calls: unchanged. TextNow has no official API; automated sending through it is not claimed.
- **VictorLinux, VictorSuite, Victorious Mesh, RustdeskV, Victorious Browser** integrations: not touched in this change.

### App wiring (2026-11-24, second slice) — compiled by CI only, never run
- `CommunicationsModule` now provides `FileShareEngine` (state in `files/communications/file-shares.json`, emergency stop shared with the broker's `KillSwitch`), a `MediaGateway`, and wraps the backend in `ShareGatedBackend`.
- **The gateway is `UnconnectedMediaGateway`: it returns nothing.** File-Manager's bridge is still a stub, and a conversation's contact cannot yet be resolved for the recipient check, so **every attachment send is refused** (fail closed). This is deliberate, not a bug; sending attachments needs both pieces.
- Advanced → Communications gained "Files requested by a conversation" (approve = the narrowest grant: that one file and operation, read-only, 24 hours, still asks per send; or deny) and "Files you shared" (stop sharing). There is still no screen to create a broader grant (folder, subfolders, collection, other operations), to view access history, or to see the conversation scheduler.
- `ConversationOrchestrator` is **not** wired yet: nothing in the app feeds it incoming messages.
- No local Android SDK in this environment: `:app` was not compiled or tested here. CI on the PR is the only evidence.

### Share form, access history, and a decision needed (2026-11-24, third slice)
- `ShareForms.build` (JVM-tested, 6 tests) turns the owner's form into a validated grant: read-only is **derived** from the operations, never typed; relative or `..` paths, the storage root, empty choices, over-long expiry and more than 20 recipients are refused with a reason. Advanced → Communications gained "Share files with a conversation…" and a "File access history" list (newest 30; allow and deny). The screens are compiled by CI only and have never been run.
- **Paths are typed, not picked.** Android's Storage Access Framework and Photo Picker return `content://` URIs, not paths, and the engine is path-based. A picker needs a URI-based scope type and a gateway that resolves it; that is not built.
- **Decision needed (blocks attachment sending): recipient verification.** The gate must confirm who a conversation's messages actually go to. VictorSMS's archive deliberately exposes **no phone number or account** in the conversation list (`AssistantAccessTest` asserts it), so DroidCommand has nothing authoritative to check against, and guessing from display names would be unsafe. Options: (a) let VictorSMS add each shared conversation's non-self participant numbers to the list reply (additive, still only for conversations you switch on; reverses that privacy rule); (b) keep numbers private and add a VictorSMS send-time check that the destination matches what the grant names (a write-side op, larger); (c) keep attachment sending off. I did not change VictorSMS.

### Reply drafts from live messages (2026-11-24, fourth slice) — drafts only, nothing is sent
`Drafts.kt` connects the archive to the scheduler: `ArchivePoller` reads VictorSMS's shared conversations, `ConversationOrchestrator` queues new messages (ordered per conversation, two worker threads, one model), `DraftTurnEngine` asks `ReplyService` for a suggested reply, and `DraftInbox` holds it for you. **The scheduler's delivery hook is empty and no send capability is called.** 9 JVM tests plus 3 mutation checks (drop the live-origin rule, drop the enable-time cutoff, drop the already-handled check: all 3 caught).
- Off by default. Nothing is polled until you switch a conversation on (Advanced → Communications → Reply drafts).
- Switching on never answers what is already there: only **live** incoming messages newer than that moment count, and imported history never does (the same rule VictorSMS's own gate enforces).
- A message is drafted once, also after a restart (state in `files/communications/drafts.json`). A model failure is shown as a "No draft" entry with the reason, not hidden. The emergency stop halts polling and drafting.
- **Manual, not scheduled.** It checks only when you press "Check now". There is no background worker, so nothing runs while the screen is closed. A periodic worker needs a real device to tune battery and timing, and I have none here.
- Not verified: the Android screen has never been displayed; the VictorSMS read path has never run against the real app; reply quality and model latency are unmeasured; the draft's model call goes through the owner's inference policy only as far as `ReplyModel` does (`PolicyReplyModel`).
- **Review in VictorSMS** hands a waiting reply draft to the paired phone app through `message.draft.from_archive`.
  VictorSMS requires **Drafts** and **Archive read**, plus the conversation's assistant access switch. It resolves the
  native SMS recipient locally, then presents the draft for editing and approval. **Check review status** queries the
  same draft ID. The existing broker's `communications.create_draft` capability is independent of this owner-screen action.

### Automatic checking for reply drafts (2026-11-25) — drafts only, unmeasured on a device
- `DraftCheckWorker` (WorkManager, unique periodic work, 15-minute period, battery-not-low) runs the same poll as "Check now" and waits up to 4 minutes for the drafts it queued (`ConversationOrchestrator.awaitProcessed`, 2 JVM tests), because the process may be frozen once a worker returns. Unfinished drafts are not lost: a message counts as handled only after its draft is stored, so the next period picks it up.
- **Off by default**, one switch on the Communications screen ("Check automatically"). The emergency stop and the per-conversation switches apply unchanged. It can only draft; it has no send path.
- Honest limits: WorkManager's minimum period is 15 minutes and Android may delay it (Doze, battery saver, app standby), so this is "every so often", not instant. There is **no notification** when a draft is ready (that needs a notification permission and a design for what to show). **Battery cost, Doze behaviour and reliability are unmeasured**; it compiles and has never run on a device.

### Android folder and file pickers (2026-11-25) — compiled by CI only once pushed; never run
The engine was path-only; Android's pickers return `content://` references, not paths. Added, all pure JVM except where noted:
- `ContentRefs`: strict parsing of the three SAF shapes (`/tree/<id>`, `/tree/<id>/document/<id>`, `/document/<id>`) plus opaque media URIs, rebuilt into **one canonical spelling** (so `%3a` and `%3A` are the same file), a provider allow-list (an app's own provider, `file://` and `http://` are refused), and containment checked on decoded ids at a `/` boundary. Refused outright: queries/fragments/userinfo, control characters, NUL, backslashes, double-encoding, `.` / `..` components, and provider roots (`primary:`) as a share.
- New scopes `ShareScope.Tree` (a picked folder, with or without subfolders), `ShareScope.Item` (one picked file); `Collection` may now hold content references (a picked album). A plain-path grant never covers a content reference and the reverse. Old stored grants still load.
- `ShareForms` gained TREE / TREE_WITH_SUBFOLDERS / PICKED. **Picked scopes are read-only**: the system picker gives this app read access only, so the form refuses upload, save and modify for them rather than promise a write Android will refuse.
- `:app`: "Pick a folder…" (`OpenDocumentTree`) and "Pick files…" (`OpenMultipleDocuments`) in the share dialog, each asking for a persistable read grant; `ContentResolverMediaGateway` replaces the placeholder gateway and reads only what the system lets this app read, failing closed (null) on any error or unknown size. **No storage permission is requested.**
- Verified: 14 new JVM tests incl. hostile-URI cases; 4 of 4 mutation checks caught (folder-boundary check, other-tree check, provider allow-list, direct-child rule); two real parser holes were found by the first run and fixed (`/tree/<id>/document` with no id was accepted as "opaque"; `primary:../x` slipped past the `..` check).
- **Not done / not verified:** nothing here has run on a device (the picker contracts, persisted grants, provider behaviour and reading through a tree URI are untested). The Photo Picker is not used because its grants are not persistable. A grant stays in Android's list of persisted URI permissions after you stop sharing (the engine denies at once, but the OS-level read grant is not released yet). A document is matched by its exact canonical spelling: `/document/<id>` and `/tree/<t>/document/<id>` are different references. **Handing a picked file to VictorSMS to actually send** needs `grantUriPermission` to its package at send time: not built. Bots still have no tool to *list or search* shared files (the 27-capability catalogue is unchanged). Attachment sending is still blocked on recipient verification (open owner decision).
