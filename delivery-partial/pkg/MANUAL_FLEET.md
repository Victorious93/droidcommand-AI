# Fleet user manual (VictorLinux coordinator + DroidCommand workers)

Read `fleet/docs/FLEET.md` for the contract. This is the owner's how-to. What is **not** done yet is stated at the end — read it before relying on phones.

## 1. Set up the coordinator (VictorLinux)
1. Boot VictorLinux with its data partition (VICTORDATA/TORIDATA) so state is durable. `victor-ctl fleet status` shows `persistence.durable`; if `false`, work is lost at reboot.
2. `victor-ctl fleet start` (Control Center → Fleet → Start). It listens on `127.0.0.1` only.
3. To let phones reach it on your authorised LAN or private network: `victor-ctl fleet config --enable-network 192.168.1.20` (the PC's own address), then stop/start.
   Mesh is optional; any reachable private path works. Nothing opens a router port.

## 2. Pair a phone (rooted or not — root is optional)
1. `victor-ctl fleet pair` → shows URL, **certificate fingerprint**, and a single-use secret (10 min).
2. In DroidCommand on the phone, open the worker pairing screen **(not built yet — see §10)**, enter URL and secret, and confirm the fingerprint matches the one on the PC screen.
3. A rooted phone only gains the *ability* to run root jobs on itself; root on one phone grants nothing on another. Root grants are per device, per operation and time-limited.
Pause/resume/disable/revoke any time: Control Center buttons or `victor-ctl fleet pause|resume|disable|enable|revoke <device>`. Revoke is immediate.

## 3. Submit work
* Batch hashing (no root): `victor-ctl fleet workflow batch-hash --root /path/to/files --shards 3` → review → `victor-ctl fleet approve <plan>`.
* Your own plan: JSON `{ "title":..., "jobs":[{ "key","op","args","deps":[],"priority":0-9,"deadline_s","device","requires_root","ui_exclusive","idempotent","max_attempts","inputs":[{"job","name"}] }] }`
  then `victor-ctl fleet submit plan.json`, `review <plan>`, `approve <plan>`. Cycles and unknown fields are rejected.
* Dependent hand-off: `workflow handoff --op-a … --op-b …` — B receives A's `output` artifact via the coordinator.
* APK test: `workflow apk-test` plans only what the chosen phone can really do; **installing/launching alone ends INCONCLUSIVE**, a PASS needs a behavioural check.
* Watch: `jobs`, `report <plan>`, `logs`. Cancel: `cancel <job|plan>` (a running job shows `cancelling` until the phone confirms it stopped).

## 4. Permissions and approvals
The phone decides. Anything its own policy requires approval for waits on the phone (`awaiting_approval`) — it is never counted as success. Unrooted phones can only do what ordinary Android
APIs and granted access allow; the coordinator never assumes private file/log access.

## 5. Recovery
Lost or rebooted phone: unstarted work is re-queued; running idempotent jobs retry; **non-idempotent running jobs go to `needs_review`** for you to decide. Results from an older attempt are rejected.
Coordinator restart: all state is in the database; phones reconnect with backoff and reconcile.

## 6. Stop, revoke, uninstall
`fleet stop` stops scheduling (local chat/inference unaffected; the service is off by default). `revoke` kills a phone's credential. Remove a phone locally in DroidCommand to wipe its credential.
Data lives in `<data partition>/fleet/` (`fleet.db`, `artifacts/`, `tls/`, `config.json`); delete the folder to reset. Rollback = stop the service; conversations, models and other data are separate paths and untouched.

## 7. Build cache controls
Off by default. `VICTOR_SCCACHE=1` on the image build enables the pinned build-only compiler cache; `VICTOR_SCCACHE_DIR`, `VICTOR_SCCACHE_MAX_MB` (default 2048) bound it;
`scripts/sccache-stage.sh purge` deletes it; it is never in the ISO (see `docs/caching.md`). Server prompt-cache RAM: Debian `cache_ram_mb` (`auto` or MiB, 0 = off).

## 8. Benchmarks
Method, raw files and numbers: `docs/caching.md`, `docs/evidence-cache/`, and in victorious-flash `docs/BUILD_CACHE_EVALUATION.md`. Headline: llama.cpp CPU build 225–237 s uncached, 232 s cold, **13.5 s warm**; Flash: sccache ≈ noise-level gain, not adopted.

## 9. PC desktop (rustdeskV) and screen control
Desktop: DroidCommand's hand-off logic (`core-remote-pc/DesktopHandoff.kt`) builds `rustdesk://<peer-id>` to the installed rustdeskV (`com.carriez.flutter_hbb`) after checking version and a **owner-trusted signing certificate**;
no password, key or server setting is ever put in the link; rustdeskV keeps your self-hosted settings. The Android UI for it is not built yet. Screen control between phones is **off, research-only** (`docs/SCREEN_CONTROL_RESEARCH.md`).

## 10. What is NOT done (honest list)
No phone has run any of this. Not built: Android pairing/worker UI, Keystore identity, real capability probes, foreground-service/WorkManager lifecycle, APK-test and file/log operations on phones, model-assisted planning,
the screen-control I/O, the desktop-handoff Android glue, Arch-edition prompt-cache RAM setting. No APK or ISO was built here (no Android SDK / container daemon / live-build).
