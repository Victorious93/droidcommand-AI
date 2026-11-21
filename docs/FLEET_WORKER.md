# DroidCommand phone-worker mode (`core-fleet-worker`)

The phone side of the VictorLinux fleet coordinator (contract: `VictorLinux/fleet/docs/FLEET.md`, protocol_major 1). Pure JVM module; the
Android pieces (Keystore identity, capability probe, foreground service/WorkManager, pairing UI) are **not written** and are listed at the end.

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

## Not implemented (do not assume)
Android Keystore identity; `CapabilityProbe` against real Android state; foreground service / WorkManager and reconnect after process death; pairing screen with fingerprint confirmation;
operations other than `hash_files` (`apk.install`, `apk.launch`, `apk.a11y_walkthrough`, `apk.instrumentation`, `apk.diagnostics`, file/private-log reads, Shizuku/ADB/root executors);
Android storage-permission file access. Unrooted phones can therefore only do `hash_files` on coordinator-supplied inputs today. Installation confirmation must be reported as `approval_needed`.
Nothing was run on a phone or emulator (no Android SDK in this environment).
