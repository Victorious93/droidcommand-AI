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
* Tests: 4 fixture tests, 3 tar tests, 17 worker tests (scripted coordinator), and a real cross-process test that launches the Python coordinator and runs two Kotlin workers over HTTPS.

## Not implemented (do not assume)
Android Keystore identity; `CapabilityProbe` against real Android state; foreground service / WorkManager and reconnect after process death; pairing screen with fingerprint confirmation;
operations other than `hash_files` (`apk.install`, `apk.launch`, `apk.a11y_walkthrough`, `apk.instrumentation`, `apk.diagnostics`, file/private-log reads, Shizuku/ADB/root executors);
Android storage-permission file access. Unrooted phones can therefore only do `hash_files` on coordinator-supplied inputs today. Installation confirmation must be reported as `approval_needed`.
Nothing was run on a phone or emulator (no Android SDK in this environment).
