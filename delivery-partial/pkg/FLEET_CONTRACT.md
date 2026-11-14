# Victorious fleet coordinator (VictorLinux)

One active task-assignment authority for **three or four** DroidCommand phones. It runs on the VictorLinux machine, as an
**unprivileged** process, and is shared by both editions (Arch `airootfs/` and Debian `debian-edition/`) through one
implementation in `fleet/` (`fleet/install.sh DEST` copies it into each edition's image tree at build time; nothing is
duplicated and nothing is downloaded at boot). Pure Python standard library plus the `openssl` CLI, both already in both images.

Status vocabulary used below: **VERIFIED** = exercised by an automated test in this repository, run on the build host;
**UNVERIFIED** = implemented or designed but not exercised on the target (physical phones, a booted ISO, a real LAN).

## Ownership

| Concern | Owner |
|---|---|
| Who runs which job, when (queue, leases, retries, reports) | **VictorLinux coordinator (only)** |
| Whether a phone may run an operation, approvals, grants, root, audit | **DroidCommand on that phone (only)** |
| Workers' schedules | none — workers never schedule |
| Mesh VPN, RustDesk, Flash, File-Manager, VictorSuite | unchanged; the fleet works on an authorised LAN without Mesh |

## Run it

```sh
victor-ctl fleet start            # or: victor-fleet start   (background, current user, local-only 127.0.0.1)
victor-ctl fleet status           # persistence durability, devices, capabilities, job counts
victor-ctl fleet pair             # single-use pairing payload + coordinator fingerprint (read it out to the phone)
victor-ctl fleet config --enable-network 192.168.1.20   # explicit LAN/VPN address; wildcard binds are refused
victor-ctl fleet stop
```

Everything the Control Center's **Fleet** section (Debian: Tk tab; Arch: web tab) does is one of these commands.
Implemented verbs: `serve start stop status logs [--file] pair devices pause resume enable disable revoke unpair config
submit plans review approve jobs cancel report workflow batch-hash|apk-test|handoff`.

* Networking is **local-only until you enable it**. `--enable-network ADDR` binds that one address; `0.0.0.0` needs a
  deliberate `allow_wildcard` in `config.json`. Opening a router port is out of scope and never done.
* Plans are **reviewed before first dispatch** by default (`config --plan-approval on|off`). `review` shows, per job, which phones
  could take it *right now*, and flags jobs nobody can run.

## Persistence (one authoritative store)

SQLite (WAL, `synchronous=FULL`) in `<data root>/fleet/fleet.db`, mode 0600, plus `artifacts/`, `tls/`, `config.json`.

* Debian: `/run/victor/root` → `VICTORDATA`. Arch: `data_root` from `/run/tori/persist.json` when `mode == persistent`.
* **The live OS root does not survive reboot and is never used as durable storage.** If the data partition is missing, read-only,
  `tmpfs`/`overlay`, or the user chose a temporary session, the coordinator still runs but `status` reports
  `durable: false` with the reason and the Control Center shows a warning. `VICTOR_FLEET_DIR` overrides the path for tests and is
  durable only if `VICTOR_FLEET_DIR_DURABLE=1` is also set by the owner.
* Local chat/inference never depends on the fleet service: it is off by default and not started at boot.

## Protocol (versioned: `protocol_major` 1)

Transport: HTTPS, TLS ≥ 1.2, coordinator self-signed EC P-256 certificate. Phones **pin the coordinator public key** (SPKI SHA-256,
the same pin DroidCommand's `CertificatePinner` already uses); the owner confirms the full-certificate fingerprint shown on the
coordinator against the phone during pairing. TLS verification is never disabled.

| Endpoint | Auth | Purpose |
|---|---|---|
| `POST /v1/enroll` | single-use pairing secret + device-key signature | create the device credential |
| `POST /v1/exchange` | device bearer credential | send ≤64 envelopes, ack inbound, long-poll ≤25 s for outbound |
| `PUT /v1/artifacts/<job>/<attempt>/<name>?sha256=` | device credential, current attempt only | upload (size/SHA-256 verified, quotas) |
| `GET /v1/artifacts/<id>` | device credential, producer **or** active assignment listing it as an input | download |
| `POST /v1/rotate` | device credential | rotate the credential (old one dies at once) |

Envelope: `{protocol:{major,minor}, msg_id, type, sender, target, sent_at, payload, in_reply_to?}`. Message types — worker→coordinator:
`enroll_request hello heartbeat ack progress complete fail cancel_ack approval_needed artifact_offer node_message`; coordinator→worker:
`enroll_response assignment cancel revoke node_message`. Payload schemas are fixed by the golden fixtures in `fleet/fixtures/v1/` (valid
and invalid, with expected error codes). The DroidCommand worker repo carries byte-identical copies guarded by `MANIFEST.sha256`; both
sides run the same fixtures. Rules: unknown optional fields are ignored; unknown types and other majors are rejected with a structured
error `{code, message}`; payloads are bounded (256 KiB/message, 64 KiB job args, 64 jobs/plan, 16 KiB node-message body).
This is **not** the local Android Messenger envelope reused as a network protocol; it is its own authenticated, versioned contract.

Capabilities are **live** and typed per operation: `available` (an executor really works), `granted` (permission/consent held now),
`owner_interaction_required`; root is separate (`available/granted/grant_expires_ms`); device state carries battery, charging,
thermal, free storage, foreground-busy. A configured flag or installed package is not an executable capability.
Inference location, model tool support, prompt-cache support, phone-worker operations and desktop-session availability are separate
capabilities and never share a flag.

### Identity and pairing

1. `victor-fleet pair` creates a secret that is single-use and expires (default 10 min), stored only as a hash.
2. The phone generates (Android Keystore on the real app) its own EC P-256 key and signs
   `victorfleet-enroll-v1 \n <coordinator cert fingerprint> \n sha256(secret) \n nonce`; the coordinator verifies proof-of-possession and
   binding to *its own* identity (a man-in-the-middle's cert makes the signature invalid).
3. The coordinator issues a random 256-bit **per-device** credential (stored as a hash). There is no fleet-wide secret.
4. `revoke`/`unpair` kill the credential immediately; `/v1/rotate` rotates it. Inference credentials are unrelated to this one.

## Scheduling and recovery semantics

* Plans are DAGs; submission rejects cycles, unknown dependencies, unknown fields, >64 jobs, over-large args.
* A job is dispatched only when the plan is approved, dependencies **succeeded**, and a phone is eligible *by its latest live capability
  report*: online, enabled, not paused, operation available **and** granted, root (valid > 60 s ahead) if required, thermal not
  severe+, battery above floor (15 %) or charging, free storage, below its concurrency limit, and — for `ui_exclusive` jobs — no other
  foreground UI job on that phone. Pinned jobs never move to another phone. Least-loaded eligible phone wins.
* **Leases and fencing:** each dispatch creates attempt *n* with a lease refreshed by that phone's heartbeats/progress. Results are
  accepted only for the *current* attempt from the *same* phone; anything else is rejected `stale_attempt` and the old worker is told to stop.
* **No exactly-once claim.** Delivery is at-least-once with persisted de-duplication by `(sender, msg_id)`.
* Lost phone: *assigned but never acked* → requeued, retry not consumed (nothing ran). *Running* and `idempotent` with attempts left →
  requeued. *Running* and not idempotent → **`needs_review`**, never replayed automatically. Revocation behaves the same.
* Cancellation: queued → `cancelled`; running → `cancelling` until the phone confirms `stopped`; lease lapse → `cancelled_unconfirmed`.
  A job is never reported cancelled while it may still be running.
* A job waiting on the phone owner is `awaiting_approval` (never success) until the phone reports progress after the decision.
* Deadlines expire queued jobs and cancel running ones. Dependents of a non-successful job become `dep_failed`.
* Coordinator restart: all of the above is in SQLite; un-acked outbound messages are redelivered.

## Node messages and artifact hand-off

Coordinator-relayed only: phone A → coordinator → phone B as `node_message` (≤16 KiB, topic + JSON body, data only). A message cannot
cause execution or widen B's permissions; only an `assignment` from the coordinator can. Artifacts move phone → coordinator →
phone: a phone may download an artifact only if it produced it or holds an active assignment listing it as an input. Integrity is checked on
upload (SHA-256 + size) and again by the receiving phone. There are no direct phone-to-phone transfers.

## Built-in workflows

* `workflow batch-hash --root DIR [--shards N] [--device ID]…` — partitions owner-provided files (size-balanced), phones hash their shard
  with no root, the coordinator merges and **verifies every file appears exactly once with the expected size** → `combined-manifest.json`.
* `workflow apk-test --root DIR --apk REL --package PKG --device ID` — step list is built from the phone's *live* operations
  (`apk.install`, `apk.launch`, `apk.a11y_walkthrough`, `apk.instrumentation`, `apk.diagnostics`); refused if the phone cannot install.
  Verdict: **FAIL** if any step failed, **PASS only if a behavioural check (accessibility walkthrough or instrumentation) ran and passed**,
  else **INCONCLUSIVE** (installed/launched only). Evidence artifacts are kept.
* `workflow handoff --op-a X --op-b Y` — job B consumes job A's `output` artifact through the coordinator.

## What is and is not verified

VERIFIED (automated, build host): enrollment (single-use/expiry/binding/pinning/limits/rotation), auth and revocation, schema and
fixture validation, capability-aware assignment incl. root/battery/thermal/storage/UI-exclusivity/pinning, cycle detection,
leases + fencing + stale results, lost-phone semantics, reconnect reconciliation, restart recovery, de-duplication, cancellation states,
approvals, artifact integrity/quotas/traversal, three-phone batch-hash with real SHA-256, APK verdict rules, node-message relay,
persistence-durability reporting, Arch web Control Center tab driven in headless Chromium.
Workers in these tests are **simulated** (a Python worker and the real Kotlin `core-fleet-worker` over real HTTPS) — they prove the
coordinator and protocol, not Android.

UNVERIFIED: any physical phone; Android Keystore identity; foreground-service/WorkManager lifecycle; real LAN/VPN; root grants;
battery/thermal readings on hardware; the Debian Tk tab rendering (syntax-checked only); a booted ISO containing the coordinator.
Model-assisted planning is **not implemented**: submission is deterministic/manual and validated; nothing needs a model or tool calling.
