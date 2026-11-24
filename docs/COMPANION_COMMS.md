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
