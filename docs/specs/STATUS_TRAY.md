# Fleet STATUS-TRAY schema (`node.status.get`)

Status: **[CONTRACT] FROZEN** (this document). DroidCommand AI defines
and aggregates. VictorLaunch and the DCA UI consume. Nodes implement the
op; they do not invent a second schema.

Honesty: **ON DISK / UNVERIFIED**. Spec + JVM encode/decode/aggregate
only. **No live network bind.** No Messenger / AIDL / Termux send.

This freeze closes `docs/specs/REMOTE_CONTROL.md` §7 item 8. It aligns
with the in-tree skeleton from PR #233 (`NodeStatusSnapshot` /
`StatusTrayDomain` in `core-remotecontrol`) rather than replacing those
names.

---

## Terms (once)

- **Node** — one fleet APK or Termux slot (Mesh, Browser, VictorSMS, …).
- **Controller** — DroidCommand AI. It asks nodes for status, stores the
  latest picture, and hands that picture to UI (DCA Settings and
  VictorLaunch).
- **Status tray** — the shared health strip: one row per node, several
  **domains** on that row (mesh, VPN, browser, SMS, node health, …).
- **Snapshot** — one node's status at one moment. The frozen type is
  `NodeStatusSnapshot`.
- **Domain** — one slice of that snapshot (`StatusTrayDomain`). The
  starter list is mesh / VPN / browser / SMS / node health; unknown
  names stay `other` so the list can grow without a major bump.
- **Connection class** — how the controller talks to the node. Same
  enum as the rest of the fleet: `EXTERNAL_NODE`, `BOUND_COMPANION`,
  `TERMUX_IPC`, `HOST_PROCESS_TOOL`. Wire name is `connectionClass`.
- **Op** — a named action on the existing Bridge request/response. The
  status op is `node.status.get`.

---

## 1. What every node implements

Every fleet node advertises **`node.status.get`** in `capabilities.get`:

```json
{
  "name": "node.status.get",
  "kind": "read",
  "remoteAllowed": true,
  "requiresConfirm": false
}
```

Typical: `kind=read`, `remoteAllowed=true`, `requiresConfirm=false`. A
node may set `remoteAllowed=false` if it never answers a remote caller;
the controller still reads locally. This is **not** a destructive op
and it does not change Kali / pentest / YOLO gates.

The controller also advertises the same descriptor on its inbound
surface so a peer (VictorLaunch) can pull the **aggregated** tray.

### Request

Reuse the frozen Bridge envelope (`protocol_major` 1). Minor stays `1`
and is advertised in `capabilities.get` — do **not** add a required
`protocol_minor` field on `BridgeRequest`.

| Field | Where | Notes |
|---|---|---|
| `operation` | BridgeRequest | `node.status.get` |
| `target_node_id` | BridgeRequest | The node being asked, or `controller` for the aggregated tray |
| `parameters` | BridgeRequest | Frozen top-level envelope (`nonce` / `timestampMs` / `ttlMs` / `destructive`). Optional `args.nodeId` to ask for one node. Empty args = "this node" (or "all", on the controller). Unknown `args` keys are **ignored, not rejected**. |

`node.status.get` is a **standalone** BridgeRequest `operation` (outer
`operation` field), **not** an `actionRef` under `remote.command`. See
`docs/specs/REMOTE_CONTROL.md` §1. It is **never** confirmation-gated
(`requiresConfirm=false`); ignore advisory `destructive` for gating.

`requestId` **is** `BridgeRequest.request_id`. Identity is package+cert
(`Message.sendingUid` / Binder uid / Termux permission). Never a
payload field.

### Response

`BridgeResponse` (`ok`, `result`, or `error`). On success `result` is
**one** `NodeStatusSnapshot` object (a node answering for itself) or:

```json
{ "snapshots": [ /* NodeStatusSnapshot, … */ ] }
```

when the controller returns the aggregated tray. An empty tray is
`{ "snapshots": [] }`, not an error.

---

## 2. Snapshot model  `[CONTRACT]`

Wire object (camelCase). JVM type: `NodeStatusSnapshot`.

| Field | Type | Notes |
|---|---|---|
| `nodeId` | string | Frozen identity. Same value as controller `sourceNodeRef` for this node. Required, non-blank. |
| `packageName` | string \| null | Android `applicationId` when known. Never a signing cert. |
| `connectionClass` | string | `EXTERNAL_NODE` \| `BOUND_COMPANION` \| `TERMUX_IPC` \| `HOST_PROCESS_TOOL`. Same names as `FleetIntegrationClass`. |
| `updatedAtMs` | number | Epoch ms when **this node** last built the snapshot. |
| `domains` | array | One or more `{ name, state, detail? }`. Empty array means "alive, nothing to say". |

`domains[]` entries (`StatusTrayDomainStatus`):

| Field | Type | Notes |
|---|---|---|
| `name` | string | Wire: `mesh` \| `vpn` \| `browser` \| `sms` \| `node_health` \| `other`. Unknown names decode as `other` (extensible). |
| `state` | string | `ok` \| `degraded` \| `down` \| `unknown` \| `off`. |
| `detail` | string \| optional | Short human text. **No secrets, no PII dumps, no signing material.** Max 200 characters. Omit rather than pad. |

Example (Mesh node):

```json
{
  "nodeId": "mesh-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "packageName": "com.victorious.mesh",
  "connectionClass": "EXTERNAL_NODE",
  "updatedAtMs": 1760920000000,
  "domains": [
    { "name": "mesh", "state": "ok", "detail": "headscale up" },
    { "name": "vpn", "state": "degraded", "detail": "exit node lag" },
    { "name": "node_health", "state": "ok" }
  ]
}
```

### Alignment with the #233 skeleton

PR #233 shipped a generic in-memory tray keyed by `sourceNodeRef` with
a **single** `domain` / `healthy` / `receivedAtMs`. This freeze keeps
those names as **aliases** so Settings UI and local backup do not
break:

| Frozen wire (# this PR) | #233 alias (still valid on the JVM model) |
|---|---|
| `nodeId` | `sourceNodeRef` (same string) |
| `updatedAtMs` | `receivedAtMs` (controller ingest time; equals `updatedAtMs` when the controller just pulled) |
| `domains[0].name` | `domain` (`StatusTrayDomain`) |
| `domains` any `ok` / not | `healthy` (`true` if any domain is `ok` and none is `down`; `false` if any is `down`; otherwise `null`) |
| — | `capability` / `actionRef` / `payload` stay as telemetry on the JVM object; they are **not** on the frozen wire |

`StatusTrayDomain` enum names (`MESH`, `VPN`, `BROWSER`, `SMS`,
`NODE_HEALTH`, `OTHER`) are unchanged. Wire strings are the lowercase
forms above (`node_health`, not `NODE_HEALTH`).

The in-memory tray still keys **one snapshot per `nodeId`**. A second
consume for the same node **merges** `domains` by `name` (latest wins
for that name) and takes the later `updatedAtMs`. That is the
controller aggregate.

---

## 3. Payload domains (extensible)

Starter list — nodes implement the rows that apply; skip the rest:

| `name` | Typical owner | What `state` means |
|---|---|---|
| `mesh` | Victorious Mesh | Overlay / Headscale reachability |
| `vpn` | Mesh or a VPN helper | Tunnel up / lag / down |
| `browser` | Victorious Browser | Browser process / proxy |
| `sms` | VictorSMS | SMS app ready to **deliver** (not authorize) |
| `node_health` | every node | Process alive, storage, generic pulse |
| `other` | future slices | Catch-all; do not overload this if a real name exists |

Adding a new name is a **minor** additive change: unknown `name` values
decode as `other` and round-trip the original string in `detail` only
when the sender put it there. Do **not** bump `protocol_major` for a
new domain name.

---

## 4. Who does what

```
node.status.get  →  node builds NodeStatusSnapshot
                 →  BridgeResponse.result
controller       →  consumes into InMemoryRemoteStatusTray (merge by nodeId)
                 →  publishes the list
VictorLaunch UI  →  reads the published list
DCA Settings     →  reads the published list
```

The controller is the only aggregator. VictorLaunch does not scrape
nodes itself in this contract. Nodes do not talk to each other for
status.

Pull and a best-effort push are both allowed:

- **Pull (canonical):** controller (or VictorLaunch via the controller)
  sends `node.status.get`.
- **Push (optional):** a node may send `node.status.get` inbound with
  its snapshot in `args.snapshot` (JSON string). The controller
  consumes it the same way. Push is still an **untrusted request** and
  still walks pairing → external-control → replay.

**No live bind in this PR.** Encode/decode/aggregate are unit-tested
only.

---

## 5. Transport

Reuse `BridgeRequest` / `BridgeResponse`. `protocol_major` 1. Minor 1
via `capabilities.get`. Same three encodings as remote control
(EXTERNAL_NODE JSON, BOUND_COMPANION logical field map, TERMUX_IPC
JSON blob). Do **not** invent a fourth envelope. Do **not** send this
op as `remote.command` + `actionRef=node.status.get`.

`destructive` on the request is advisory and must be `false` for this
read. Receivers ignore it for gating.

---

## 6. Privacy  `[CONTRACT]`

A snapshot **must not** contain:

- API keys, tokens, cookies, passwords, code words
- Signing keys, keystore passwords, cert private material, full cert
  PEMs (a SHA-256 fingerprint is also out of scope here — pairing
  already holds that)
- Address books, SMS bodies, phone numbers, message text, URLs with
  secrets, clipboard contents
- VictorSMS whitelist / matched sender lists

`detail` is a short machine/human hint (`"headscale up"`, `"exit node
lag"`), not a dump. The JVM codec **refuses** to encode a snapshot
whose `detail` or leftover `payload` keys match the forbidden-name
list (`password`, `apiKey`, `api_token`, `token`, `secret`,
`codeWord`, `whitelist`, `signingKey`, `privateKey`, …). Refusal is
`invalid_parameters`, not a silent strip.

---

## 7. Refresh / TTL

These are **guidance** for producers and the tray, not a new envelope
field.

| Clock | Value | Meaning |
|---|---|---|
| Recommended pull interval | 15 s | UI refresh. Nodes may rate-limit faster pulls. |
| Fresh | `now - updatedAtMs ≤ 30_000` | Show as current. |
| Stale | `30_000 < age ≤ 300_000` | Show with a stale mark; keep the row. |
| Expire from tray | `age > 300_000` (5 min) | Controller **may** drop the row. Default in-memory tray keeps the last 64 nodes and expires rows older than 5 min on publish. |

Request `ttlMs` is the **envelope** TTL (default 120 s, max 300 s) —
how long the *request* is valid — not how long the snapshot stays
fresh. Do not mix the two.

---

## 8. Error mapping

Wire categories are the existing `RemoteErrorCategory` snake_case
names. Status-tray specific meaning:

| Category | When |
|---|---|
| `not_paired` | Caller package+cert is not in the pairing table. |
| `not_found` | `args.nodeId` does not match this node (or, on the controller, is not in the tray). |
| `capability_off` | `node.status.get` is not advertised, or the node turned the capability off. |
| `external_control_disabled` | External-control switch is OFF (default). Remote pulls fail closed. Local UI publish still works. |
| `unauthorized` | Paired node id exists but package/cert does not match. |
| `replay_detected` / `expired` | Envelope replay / TTL / skew (same as remote control). |
| `invalid_parameters` | Missing `nodeId`, forbidden privacy content, bad JSON. |
| `unsupported_op` | Node has no status surface at all. |
| `internal` | Catch-all after the above. |

A node that is simply `down` still answers `ok: true` with
`domains[].state = "down"`. That is data, not an error.

---

## 9. Out of scope (this freeze)

- Live Messenger / AIDL / RUN_COMMAND send
- VictorLaunch's own UI layout
- Per-domain extra metrics (bytes, GPS, SMS bodies)
- Changing Kali / pentest / YOLO gates
- A new Gradle module — the model stays in `core-remotecontrol`

---

## 10. JVM types (this repo)

| Type | Role |
|---|---|
| `StatusTrayDomain` | Frozen domain enum (unchanged names from #233) |
| `StatusTrayState` | Frozen `ok` / `degraded` / `down` / `unknown` / `off` |
| `StatusTrayDomainStatus` | `{ name, state, detail? }` |
| `NodeStatusSnapshot` | Frozen snapshot + #233 aliases |
| `NodeStatusSnapshotCodec` | JSON encode/decode (Bridge `result`) |
| `InMemoryRemoteStatusTray` | Consume (merge) + publish + TTL expire |

Spec: this file. Envelope: `docs/specs/REMOTE_CONTROL.md`.
