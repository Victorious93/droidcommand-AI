# Unified fleet SETTINGS/BACKUP bundle

Status: **[CONTRACT] FROZEN** (this document). DroidCommand AI defines
the cross-app envelope. Each node fills in **one entry**. The
controller is the only place that stitches entries into a file the
user can copy.

Honesty: **ON DISK / UNVERIFIED**. Spec + JVM encode/decode/import
semantics only. **No live network bind.** No cloud upload.

This freeze closes `docs/specs/REMOTE_CONTROL.md` §7 item 9. It does
**not** replace the #233 controller-local JSON
(`UnifiedLocalBackup` — macros + mappings + pairings). That file is
**one entry** inside this envelope.

---

## Terms (once)

- **Node** — one fleet APK or Termux slot.
- **Controller** — DroidCommand AI. It owns the on-device file, the
  wrapping key, and the import prompt.
- **Bundle** — one JSON object that holds every node's settings
  snapshot. Frozen type: `UnifiedFleetBackup`.
- **Entry** — one node's contribution (`packageName` + versioned
  payload). Frozen type: `UnifiedBackupEntry`.
- **Clear payload** — the node's own JSON, **unencrypted**. Allowed
  **only** for local-dev / unit tests. Real backups use ciphertext.
- **Ciphertext** — AES-GCM of that JSON. The wrapping key never lives
  in the bundle.
- **Conflict policy** — what to do when an imported id already exists
  (`skip` / `overwrite` / `merge-by-id`).

---

## 1. Ops every node implements

| Op | `kind` | `remoteAllowed` (default) | `requiresConfirm` |
|---|---|---|---|
| `config.export` | `action` | **false** | false |
| `config.import` | `action` | **false** | **true** |

Advertised in `capabilities.get`. A node may raise `remoteAllowed` only
with an explicit owner decision — the default is **off** so a remote
caller cannot dump or restore settings. `config.import` always needs
an on-device confirm. A remote trigger **never** auto-approves and
**never** auto-enables `remote.command`.

These are **not** destructive in the wipe/power-off sense. They do
**not** change Kali / pentest / YOLO gates.

### Request

Reuse BridgeRequest / BridgeResponse (`protocol_major` 1). Minor stays
`1` via `capabilities.get`. Same frozen envelope fields as remote
control (`nonce` / `timestampMs` / `ttlMs` / `destructive` advisory).

`config.export` args (all optional):

| Arg | Meaning |
|---|---|
| `clearPayload` | `"true"` only in local-dev. Production omits this and encrypts. |
| `packageName` | Restrict to one app. Empty = this node. |

`config.import` args:

| Arg | Meaning |
|---|---|
| `bundle` | The bundle JSON (string). Required unless the controller is importing a local file. |
| `dryRun` | `"true"` = validate only, do not write. |
| `conflictPolicy` | `skip` \| `overwrite` \| `merge-by-id` (default `skip`). |

`dryRun` may also sit at the envelope top level (`FleetRemoteEnvelope.dryRun`), same as `macro.run`.

---

## 2. Bundle envelope  `[CONTRACT]`

```json
{
  "version": 1,
  "createdAtMs": 1760920000000,
  "sourceNodeRef": "controller",
  "wrapping": "raw-aes-256-gcm",
  "entries": [
    {
      "packageName": "ai.droidcommand.app",
      "schemaVersion": 2,
      "ciphertext": "v1.<iv>.<ciphertext>"
    }
  ]
}
```

| Field | Type | Notes |
|---|---|---|
| `version` | number | Bundle envelope version. **1** is this freeze. Unknown versions fail closed. |
| `createdAtMs` | number | Epoch ms when the controller (or exporting node) built the file. |
| `sourceNodeRef` | string | Who exported. Controller uses `controller` or its node id. |
| `wrapping` | string | `raw-aes-256-gcm` (JVM / tests) \| `android-keystore` (on-device) \| `none` (**local-dev only**, every entry must then use `clearPayload`). |
| `entries` | array | One object per `packageName`. Duplicate package names are invalid. |

### Entry

| Field | Type | Notes |
|---|---|---|
| `packageName` | string | Android `applicationId`. Required. |
| `schemaVersion` | number | **That node's** payload schema, not the bundle version. Controller local JSON is `2` (`UnifiedLocalBackup.VERSION`). |
| `ciphertext` | string \| omit | `v1.<base64url-iv>.<base64url-ciphertext+tag>` |
| `clearPayload` | string \| omit | UTF-8 JSON. **Local-dev / tests only.** |

Exactly one of `ciphertext` or `clearPayload` must be present.
`wrapping=none` requires `clearPayload`. Any other wrapping requires
`ciphertext`.

The controller's own entry (`ai.droidcommand.app`) is the existing
#233 local JSON: macros, mappings, pairings, targets, last status-tray
rows. Nodes ship their own schema behind `schemaVersion`.

---

## 3. Encryption  `[CONTRACT]`

**Algorithm:** AES-256-GCM, 12-byte IV, 128-bit tag, no padding.
Transformation: `AES/GCM/NoPadding`.

**Record format:** `v1.<iv>.<ciphertext>` where both trailing parts are
URL-safe Base64 **without** padding. Same shape as
`core-config.EncryptedSecretsVault`.

**AAD (bound into GCM):**

```
packageName | schemaVersion | sourceNodeRef
```

(`|` is a literal ASCII pipe.) Decrypting an entry under the wrong
package / version / exporter fails closed.

**Key wrapping:**

1. Generate a random 256-bit **data key** (DEK) per bundle (or reuse
   one DEK for every entry in that file — either is allowed; this JVM
   slice uses one DEK per bundle).
2. Wrap the DEK:
   - **On device:** Android Keystore AES wrap (`android-keystore`).
     The Keystore alias is **not** in the bundle.
   - **JVM / tests:** caller-supplied `SecretKey` (`raw-aes-256-gcm`).
3. The wrapping key, the DEK, Android Keystore aliases, and any
   keystore PIN/password **never** appear in the file.

This PR's JVM code encrypts directly with the supplied AES-256 key
(the DEK and the wrapping key are the same in tests). On-device
Keystore wrapping is documented here and **not** implemented (no
Android SDK in this slice).

### Never in a payload  `[CONTRACT]`

Refuse to export or import if the JSON contains these names or obvious
variants (`api_key`, `apiKey`, …):

- signing keys, keystore passwords, private keys, cert PEMs
- API tokens / API keys / bearer tokens
- VictorSMS secrets, code word, sender whitelist
- VictorSpoof Twilio credentials and owned-number pool (`twilio`,
  `authToken`, `numberPool` — case-insensitive substrings; a spoof
  node must **never** export these)
- plaintext passwords, cookies

Refusal is `invalid_parameters`, not a silent strip. The #233 local
JSON already omits those fields; this guard is defense in depth.

---

## 4. Import semantics  `[CONTRACT]`

Order:

1. Parse envelope. Unknown `version` → fail.
2. Duplicate `packageName` → fail.
3. Decrypt (or read `clearPayload` in local-dev). GCM/AAD failure → fail.
4. Secret-name scan → fail.
5. **Dry-run** (`dryRun=true`): report what would change; **do not
   write**.
6. Apply per `conflictPolicy` (default **`skip`**).
7. **Force `externalControlEnabled=false`** on the controller config
   after import. Never auto-enable `remote.command` / external control,
   even if the payload said `true` and the policy is `overwrite`.
8. On-device confirm is still required when `requiresConfirm=true`
   (always for `config.import` except dry-run).

### Conflict policy

Applies to **lists keyed by id** inside a node payload. For the
controller local JSON those ids are:

| List | Id |
|---|---|
| mappings | `id` |
| macros | `id` |
| pairings | `sourceNodeRef` |
| targets | `nodeId` |

| Policy | Behavior |
|---|---|
| `skip` (default) | Keep the live row; drop the imported id. |
| `overwrite` | Replace the live row with the imported one. |
| `merge-by-id` | Union: imported ids fill gaps; live ids win on collision (same as skip for collisions, plus add missing). |

Unknown keys inside a node payload are ignored (`ignoreUnknownKeys`).
Status-tray rows in the controller JSON are **not** restored as live
health (they are a snapshot of the past). This slice may load them
into the in-memory tray; a node must still answer `node.status.get`.

---

## 5. Who does what

```
node  --config.export-->  one UnifiedBackupEntry
controller                wraps entries + encrypts with DEK
user                      copies the file (no cloud)
controller --config.import--> dry-run → confirm → merge
```

VictorLaunch may **ask the controller** to export/import. It does not
hold the wrapping key. Nodes do not restore each other's payloads.

**No live bind in this PR.** Local file + JVM crypto / test double
only.

---

## 6. Error mapping

Existing `RemoteErrorCategory` names:

| Category | When |
|---|---|
| `invalid_parameters` | Bad JSON, both/neither ciphertext+clear, secret names, unknown wrapping, duplicate package |
| `not_paired` | Caller not paired (if this ever goes over the bridge) |
| `not_found` | Named `packageName` has no entry |
| `capability_off` | Op not advertised, or `remoteAllowed=false` for a remote caller |
| `external_control_disabled` | Switch OFF (default). Local Settings still export/import. |
| `confirmation_required` | `config.import` without dry-run / without on-device confirm |
| `unsupported_op` | Node has no config surface |
| `internal` | Decrypt failure after a well-formed envelope (treat as tamper) |

---

## 7. Relation to the #233 local JSON

| #233 (`UnifiedLocalBackup`) | This freeze (`UnifiedFleetBackup`) |
|---|---|
| Controller-only file | Cross-app envelope |
| Version 2 inner schema | Envelope version 1 + per-entry `schemaVersion` |
| Clear JSON on disk | Ciphertext by default; clear only in local-dev |
| Macros + mappings + pairings | That JSON becomes the `ai.droidcommand.app` entry |
| Schema note said TBD | **This document** is the frozen contract |

---

## 8. Out of scope

- Live Messenger / AIDL / Termux send
- Android Keystore implementation (documented, not coded here)
- Cloud sync
- Restoring signing keys (forbidden forever)
- Changing Kali / pentest / YOLO gates
- Auto-enabling `remote.command`

---

## 9. JVM types (this repo)

| Type | Role |
|---|---|
| `UnifiedFleetBackup` / `UnifiedBackupEntry` | Frozen envelope |
| `BackupWrapping` / `BackupConflictPolicy` | Frozen enums |
| `FleetBackupAead` / `JceFleetBackupAead` | AES-GCM seam (JVM key or test double) |
| `UnifiedFleetBackupCodec` | JSON encode/decode + secret scan |
| `UnifiedFleetBackupImporter` | Dry-run / skip / overwrite / merge-by-id; never enables external control |
| `UnifiedLocalBackup` | Unchanged inner controller JSON |

Spec: this file. Envelope: `docs/specs/REMOTE_CONTROL.md`.
