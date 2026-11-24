# DroidCommand phone-worker mode (`core-fleet-worker`)

The phone side of the VictorLinux fleet coordinator (contract: `VictorLinux/fleet/docs/FLEET.md`, protocol_major 1). Pure JVM module; the
Android pieces (Keystore identity, capability probe, foreground service, pairing UI, the two switches) live in `:app` and are described under
"Android half" below: **they compile and their logic is JVM-tested, but none of it has run on a phone or emulator.**

## What is implemented and tested
* `FleetProtocol` — Kotlin twin of the coordinator's validator, run against the **same golden fixtures** (`src/test/resources/fleet-fixtures/v1`,
  byte-identical copies, `MANIFEST.sha256` checked).
* `WorkerIdentity` / `SoftwareWorkerIdentity` — per-phone EC P-256 identity; enrollment signs `victorfleet-enroll-v1 | coordinator cert fingerprint | sha256(secret) | nonce`
  (proof of possession bound to the coordinator the owner confirmed). The software key is for tests/non-Keystore hosts and says so.
* `HttpsFleetTransport` — HTTPS pinned to the coordinator's SPKI via the existing `CertificatePinner`; TLS verification is not disabled.
* `FleetWorker` — enroll, hello/heartbeat with **live** capabilities, assignments, durable at-least-once result queue with coordinator-side de-duplication, bounded backoff, cancel,
  revoke (wipes credential, cancels work, stops), pause/disable, node messages. It never schedules.
* Execution always goes through the device's `ToolRunner` (in the app: `SecureToolExecutor`) as `Initiator.REMOTE`, so phone policy, approvals, grants and audit stay authoritative.
  Before each run it re-probes permissions and re-checks root and the time-limited root grant (60 s slack). Typed failures (`permission_denied`, `capability_missing`, `executor_unavailable`,
  `device_busy`, `deadline_exceeded`) report `side_effects: none` when nothing ran.
* `FleetApprovalPrompt` — a job waiting on the owner reports `approval_needed` (never success) then `progress` after the decision.
* `ForegroundGate` — one semaphore for local Pilot, fleet UI jobs and the screen adapter.
* `hash_files` operation (no root): safe tar extraction (no traversal/links/devices), SHA-256 manifest uploaded as an artifact.
* **Optional batch approval (OFF by default, an added feature)** — see the section below.
* Tests: 7 fixture tests, 3 tar tests, 18 worker tests and 11 batch-approval worker tests (scripted coordinator), and 3 real cross-process tests that launch the Python coordinator (`VICTOR_FLEET_LIB=…/VictorLinux/fleet`) and run Kotlin workers over HTTPS (hash workflow, manifest on, manifest off).

## Optional: approve a reviewed batch once (off by default)

An *added feature*, never required. Two independent switches on the phone, **both off by default**, each read again at the moment of use:
**Fleet worker mode** (whether this phone takes fleet jobs at all; the Android service is not written yet) and **Batch approval** (whether one
approval may cover a reviewed plan). The coordinator has its own switch, `config --share-plan on|off` (default off), without which no
assignment carries the plan manifest.

What one approval is: the owner reviews the plan once (every job, its tool and arguments, which ones are covered and which will **still
ask, with the reason**) and chooses a duration (default 15 min, never over 60). It then replaces *only the owner's tap* for exactly those
jobs. It does not change policy, mode, initiator, grant, audit or root checks (all still run in `SecureToolExecutor`).

Hard limits, all enforced in `core-security` (`BatchApprovals`, `ToolBatchEligibility`) and tested:
* bound to job id + tool + a SHA-256 of the canonical arguments the phone *received*; any difference means not covered;
* each job is covered once; a retry asks again; at most 25 jobs and 8 live batches; expires; revocable at any moment; memory only (a restart ends it);
* never covers root, a tool behind its own grant, a root-level permission category, or shell / remote-control / pentest / destructive tooling by name;
* an operation must opt in (`FleetOperation.batchable`, default **false**; `hash_files` does not, it never asks);
* a job the coordinator flags `requires_root` never runs in a batch context at all;
* switching **Batch approval** off, pausing, disabling, unpairing or being revoked ends every live batch immediately;
* issuing, using and refusing coverage is audited (`GRANT_ISSUED` / `GRANT_CONSUMED` / `GRANT_DENIED` / `GRANT_REVOKED`); if the audit log cannot record, nothing is covered;
* the owner is asked once per plan, at the first job that would have asked for a tap anyway (a plan whose jobs never ask never shows a card); jobs of one plan running in parallel wait on that one question.

Honest status: `hash_files` is the only operation and it is `NORMAL` level, so **nothing can be covered by a batch today**; the engine, the
manifest and the worker wiring are tested, and the first operation that asks for a tap is what makes the feature observable. Nothing here has run on a phone.

## Android half (2026-11-22) — an optional feature, off by default

Everything below is in `:app` (`ai.droidcommand.app.fleetworker`, Settings → "Fleet worker (optional)"). **Both switches are off until the owner turns them on**, are
independent, and are re-read at the moment they matter.

| Piece | What it is | Verified |
|---|---|---|
| **Fleet worker mode** switch | Whether this phone talks to the coordinator and takes jobs. It cannot be turned on until the phone is paired. Off: the service stops, running jobs are cancelled, every batch approval ends. | engine JVM tests (switch off means nothing runs, no contact with the coordinator) |
| **Batch approval** switch | See the batch section above. Off: no offer is ever made, nothing is covered, live batches end at once. | engine JVM tests |
| Pairing | Paste the coordinator's `victor-fleet pair` output, strict parse (https address, two 64-hex fingerprints, secret), the owner compares the fingerprint on screen, then enrollment signs the coordinator identity with the phone key. QR scanning is not built. | parser + engine JVM tests, scripted coordinator |
| Identity | EC P-256 key in the AndroidKeyStore (`KeystoreWorkerIdentity`, StrongBox first, then the platform default; reports hardware- or software-backed). | compiles only |
| State | `WorkerPersisted` (credential, pairing, unsent results) in the Keystore-backed secrets vault, never plain storage; an unreadable record means "not paired". | JVM tests with the in-memory vault |
| Capabilities | Live battery, thermal and free storage; one operation (`hash_files`), "granted" is the owner's switch; no root is ever offered. | mapping JVM-tested; the platform reads compile only |
| Service | `FleetWorkerService`: a visible foreground service with a **Switch off** notification action. Started only by the owner's switch (or when the app is opened with the switch still on), stops when the switch goes off, the phone is unpaired or revoked, or the coordinator rejects its credential. **Not started at boot.** | manifest policy test; lifecycle not run |
| Own executor | Fleet jobs run through a dedicated `SecureToolExecutor` holding only the worker's operations, as `Initiator.REMOTE` only, with the app's `SecurityPolicy`, approval prompt and audit log. Local planners and helper bots cannot reach them. | engine JVM tests |
| Activity feed | Each job is a run under "Jobs from your fleet coordinator": started, waiting for you, your answer, covered by a batch, done / failed / stopped. No arguments are recorded. | engine JVM tests |
| Offer dialog | `BatchOfferHost`: what one approval covers, what will still ask (with the reason), 5 min / 15 min / 1 hour or "No, ask me each time". Closing it counts as no. | compiles only; the blocking prompt is JVM-tested at the core-security level |

**Why `specialUse`, not `dataSync`.** Android documents that a `dataSync` foreground service may run 6 hours in a 24-hour period once the app targets Android 15 (then
`Service.onTimeout` is called and a service that does not stop crashes the app), and that such a service cannot be started from a `BOOT_COMPLETED` receiver. A connection that stays up until the
owner switches it off does not fit that, so the service declares `foregroundServiceType="specialUse"` with the required `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` text. The same page says specialUse
values are reviewed in Google Play Console if the app is published there; this project has no stated Play distribution. (Source: developer.android.com foreground service types and timeout pages, read 2026-11-22.)

**After a reboot** the worker does not restart itself; the owner's switch stays on and the service comes back the next time the app is opened.

## Not implemented (do not assume)
Anything that has run on a phone or emulator (none of the Android half has); QR pairing; a restart at boot or after the system kills the app (only an app open brings it back);
operations other than `hash_files` (`apk.install`, `apk.launch`, `apk.a11y_walkthrough`, `apk.instrumentation`, `apk.diagnostics`, file/private-log reads, Shizuku/ADB/root executors);
Android storage-permission file access; the Python coordinator's inbound device messages UI. Unrooted phones can therefore only do `hash_files` on coordinator-supplied inputs today, and it
never asks for a confirmation, so **no batch approval can be observed yet**. Installation confirmation must be reported as `approval_needed`.
