# COMPANION_EXEC v1 — inbound execution contract for paired fleet apps

Added 2026-11-14 on branch `feature/companion-exec-contract`. **Status: ON DISK, UNVERIFIED — written with unit tests (`CompanionExecTest`) but NOT compiled or run (dependency
resolution was blocked in the authoring session). The Android shell (`CompanionExecActivity`) is likewise unbuilt.**

`COMPANION_ASSIST` stays text-only: it never runs a tool. This is a **separate, versioned contract** for the
one case a fleet app needs real work done on the phone (first consumer: VictorBots 0.2.0, see
`Victorious93/VictorBots` `ARCHITECTURE.md`). It adds no root, accessibility or shell path: it calls the
existing `ToolRunner` (`SecureToolExecutor`: policy enforcer, approval prompt, grants, audit log).

## Wire contract

Action `ai.droidcommand.action.COMPANION_EXEC`, exported Activity, `startActivityForResult`, request in
`ai.droidcommand.extra.REQUEST_JSON`, result in `ai.droidcommand.extra.RESULT_JSON` with `RESULT_OK`.
`RESULT_CANCELED` only when the caller is not allowed at all (missing identity, excluded app, unpaired,
wrong cert).

| `task` | Request fields (all with `"v":1`) | UI | Result |
|---|---|---|---|
| `discover` | `request_id` | none | `{v,ok:true,contract,capabilities:[{id,description,risk,args}],expires_max_ms}` |
| `execute` | `request_id`, `capability`, `args` (strings/numbers, ≤8), `expires_at` (epoch ms, ≤5 min ahead), `reason` | **Allow / Deny screen** | a receipt (below) |
| `receipt` | `request_id` | none | the stored receipt, or `{ok:true,known:false}` |

Receipt: `{v:1, ok:true, known:true, request_id, capability, outcome, detail, output, finished_at, audit_note}`,
`outcome` ∈ `executed | denied | failed | expired | refused`. `executed` is produced only from a
`ToolResult.Success` of the real tool; `output` is capped at 8,000 characters.

## Security properties (each has a unit test)

* **Caller identity**: `CompanionAssist.verifyCaller` (same catalogue, same password-pairing trust). The
  caller must already be `Allowed` (paired); this contract never starts pairing. No second cert list.
* **Capability discovery**: only `ExecCatalog.DEFAULT`; v1 offers **one read-only capability**,
  `device.apps.list` → tool `list_installed_apps`. The caller cannot name a tool, pass a command or add args
  (`allowedArgs` is empty).
* **Request ids and idempotency**: receipts are keyed by (caller, `request_id`). A repeated `execute` returns
  the stored receipt and never runs again; a stale consent object cannot trigger a second run. Receipts are
  per caller (another caller cannot read them).
* **Scoped authorization / approval**: the owner's Allow tap in *this* app, for *that* capability and
  arguments, for *that* request. No standing grants. The caller has no way to approve. The `ToolRunner`
  may additionally require its own approval for riskier tools.
* **Expiry**: `expires_at` is mandatory and capped at 5 minutes. Expiry while waiting records `expired` and
  nothing runs; a late Allow cannot revive it.
* **Cancellation semantics**: before the tap, Deny/back records `denied`. Once a tool has started it is
  short and bounded and is not cancellable by the caller; the receipt states what really happened. A caller
  that lost the reply (process death) asks `receipt` and must **not** resend as a new request id unless
  the receipt says `known:false`.
* **Audit**: approvals, denials, expiries and refusals are written to the existing `AuditLog`
  (`APPROVAL_APPROVED/DENIED/TIMED_OUT`, `ACCESS_DENIED`). Receipts persist in `exec-receipts.jsonl`
  (bounded; when full it refuses rather than dropping evidence).
* **Excluded apps** stay excluded (`InboundEntryPointCatalog.isExcluded` is checked first).

## Dependency note for consumers

VictorBots 0.2.0 sends this contract only after its own pinned-signer check, never treats a reply as
authority, and maps only `executed` to "completed". A DroidCommand build without this contract answers with
`RESULT_CANCELED`/unknown action; VictorBots then records the job as *outcome unknown* and does not retry.

## What was and was not run

See the audit addendum `2026-11-14 COMPANION_EXEC` in `docs/AUDIT_2026-09-05.md`.
