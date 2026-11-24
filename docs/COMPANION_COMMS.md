# COMPANION_COMMS v1 — Victorious Framework communications contract

Added 2026-11-24. **Status: broker IMPLEMENTED, BUILT, TESTED on the JVM (34 tests, 7 security mutations verified caught).
The Android transport, the VictorSMS/VictorBots connectors and every UI are NOT BUILT. Nothing here has run on a device,
against a carrier, or with a real model.** Cross-repo status: `docs/COMMUNICATIONS_INTEGRATION_REPORT.md`.

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

## Not built

Android bound-service transport and certificate verification wiring · VictorSMS and VictorBots backend connectors ·
`PolicyInference` wiring · owner-side grant/approval screens · Communications screen · voice-message and interactive-call
capabilities beyond the catalogue entry · scheduling engine · semantic (embedding) retrieval. See the integration report.
