# DroidCommand AI — room fleet control map

DCA is the hub. This is the **latest room consensus** (2026-10-14), including
Don's explicit override: **EDL-Commander is a bound companion APK**, not a
host tool. It does not invent a third protocol.

Honesty: **LIVE** is reserved for runtime-verified control. Nothing here is LIVE.

Code: `core-security.KnownFleet`. Bound companions also appear in
`core-companion.KnownCompanions`. RootForge, File Manager, Keyboard,
VictorSuite, and KYC must not be added there.

HackerAI (`ai.hackerai.companion`) is already a bound companion and is
**not** one of these seven.

## Wiring order

1. **Pentest-Swarm-AI** — when the base companion repo exists
2. **RootForge** — `core-rootforge` calling their existing Messenger bridge
3. **VictorSuite** — `RUN_COMMAND` / plugin IPC (gated on their PR #15)
4. **EDL-Commander** — bound companion APK (when it lands)
5. **KYC-AuditLab** — last
6. **Victorious File Manager** — when their identity/default-off PR lands; after the RootForge Messenger client exists
7. **Victorious Keyboard** — catalog only; direction (hub queries keyboard vs keyboard slash-commands) is open

## Integration classes (do not mix)

| Class | Security rules | Who |
|---|---|---|
| **BOUND_COMPANION** | AIDL + `protectionLevel="signature"` (same signing key) + companion `DependencyGuard`. Prefer one command-envelope service for new companions. `Initiator.REMOTE`. | Pentest-Swarm-AI, EDL-Commander (HackerAI, already mapped) |
| **EXTERNAL_NODE** | Independent product. **Never** `DependencyGuard`, **never** a shared signing key, never "install DCA first". Live APK path on *their* main is bound **Messenger** + shared JSON (`protocol_major` 1). RootForge: `action ai.rootforge.os.action.BRIDGE`. File Manager: `action ai.victorious.filemanager.action.BRIDGE` (**PLANNED** here; their identity/default-off PR still landing). Keyboard: planned `action com.victorious.keyboard.action.BRIDGE` (**PLANNED**; never forwards typed text/keystrokes). Desktop/Docker = localhost headless API + bearer (planned, RootForge). SSH = CLI/ISO **fallback only** (RootForge). Removing DCA must not disable the node. | RootForge OS, Victorious File Manager, Victorious Keyboard |
| **TERMUX_IPC** | `com.termux.RUN_COMMAND` + plugin IPC + shared command schema. **Not** a new bound service. | VictorSuite |
| **HOST_PROCESS_TOOL** | Localhost process behind a `Tool` + `SecureToolExecutor`. **Never** AIDL or DependencyGuard. | KYC-AuditLab only |

## The seven

| # | Member | Class | Repo / id | Package or binary | Status | Owns | What is actually wired | Next concrete code step |
|---|---|---|---|---|---|---|---|---|
| 1 | **Pentest-Swarm-AI** | BOUND_COMPANION | `Victorious93/Pentest-Swarm-AI` (older fork exists; room says **awaiting base repo** for the envelope companion) | `ai.pentestswarm.companion` | **PLANNED** | Swarm campaigns via one command-envelope service (when ready) | This checkout still has `core-pentest-swarm` + `IPentestSwarmService` from an earlier slice. Catalog stub: `pentest_swarm_status`. Not LIVE. | Wait for the base repo, then one envelope AIDL + same-signing + DependencyGuard. |
| 2 | **RootForge OS** | EXTERNAL_NODE | `Victorious93/rootforge-os` | Messenger action `ai.rootforge.os.action.BRIDGE` / localhost API / SSH fallback | **PARTIAL** | Target-phone control (USB OTG, later LAN/Tailscale). Does **not** own Termux/proot or Sahara. | SSH fallback only in `core-rootforge` (passive `rootforge_capabilities` / `rootforge_list_devices`). Their main already has Messenger + JSON; this repo cannot call it yet. | Fill gaps so `core-rootforge` can call the Messenger bridge. No DependencyGuard, no shared signing key. SSH stays fallback. UI may call the EDL companion later for Sahara/Firehose. |
| 3 | **VictorSuite** | TERMUX_IPC | `Victorious93/VictorSuite` (documented; **not publicly resolvable** 2026-10-14) | `com.termux` | **PARTIAL** | On-device Termux / proot / X11 / plugins | `core-termux` `run_termux_command`. CLI default `NullTermuxExecutor` unless `DROIDCOMMAND_CLI_ADB=1`. | Keep RUN_COMMAND / plugin IPC. **Not** a new bound service. Gated on VictorSuite **PR #15** (package/signing cleanup). |
| 4 | **EDL-Commander** | BOUND_COMPANION | `Victorious93/EDL-Commander` (scaffold + AIDL + USB 9008/Sahara + OnePlus 7T **in progress**) | `ai.edlcommander.companion` (planned) | **PLANNED** | Sahara / Firehose | Catalog stub `edl_commander_status`. Always fails. No AIDL file in this repo yet. No flash tool. | When the APK lands: `IEDLCommanderService` + `BIND_EDLCOMMANDER` same-signing + DependencyGuard, like Pentest. RootForge UI may call this later. Do **not** mark host-tool-only. |
| 5 | **KYC-AuditLab** | HOST_PROCESS_TOOL | unpublished as of 2026-10-14 | IdentityShield simulator, localhost only | **PLANNED** | Simulator lab | Catalog stub `kyc_auditlab_status`. Always fails. | Last among the original five. Simulator only, localhost only. **Hub must not call `/scope` admin** until KYC adds auth. Do not log identity documents. |
| 6 | **Victorious File Manager** | EXTERNAL_NODE | `Victorious93/File-Manager` (already on their `main`) | `ai.victorious.filemanager` (debug: `ai.victorious.filemanager.debug`); Messenger action `ai.victorious.filemanager.action.BRIDGE` | **PLANNED** | File listing / status over the shared JSON envelope (stub ops only on their side today) | Catalog stub `filemanager_status`. Always fails. **No live Messenger bind in this repo.** Their main already has `FileManagerBridgeService` (Message what=1 Bundle key `request`; reply what=2 Bundle key `response`; `protocol_major` 1). Stub ops: `filemanager.capabilities.get`, `filemanager.status`, `filemanager.list` (empty entries). Every reply currently `live:false`, `mode:stub`, `dca_required:false`. | When their identity/default-off PR lands (external control default OFF; caller identity = UID package + SHA-256 cert; allow only `ai.droidcommand.app` and `.debug`; error categories `external_control_disabled` / `unauthorized_caller`; `fleet_class` → `external_node`). After the RootForge Messenger client exists, a File Manager client can follow that pattern. Never DependencyGuard, never shared signing key, never `KnownCompanions`, never AIDL. |
| 7 | **Victorious Keyboard** | EXTERNAL_NODE | `Victorious93/Victorious-Keyboard` | `com.victorious.keyboard` (debug: `com.victorious.keyboard.debug`; benchmark: `com.victorious.keyboard.bench`); planned Messenger action `com.victorious.keyboard.action.BRIDGE` | **PLANNED** | Keyboard capabilities / status over the shared JSON envelope. **Never forwards typed text or keystrokes.** | Catalog stub `keyboard_status`. Always fails. **No live Messenger bind in this repo.** Planned service `com.victorious.keyboard.fleet.KeyboardBridgeService` (Message what=1 Bundle key `request`; reply what=2 Bundle key `response`; `protocol_major` 1, `dca_required:false`, `fleet_class` `external_node`). Planned ops: `keyboard.capabilities.get`, `keyboard.status`. Keyboard-side: external control default OFF; caller allowlist `ai.droidcommand.app` and `ai.droidcommand.app.debug`; errors `external_control_disabled` / `unauthorized_caller`. | Catalog only until Don decides direction (hub queries the keyboard vs the keyboard surfaces fleet slash-commands). If hub-query is chosen, a Keyboard Messenger client can follow the RootForge Messenger client. Never DependencyGuard, never shared signing key, never `KnownCompanions`, never AIDL, never a `BIND_*` permission. Never forward typed text or keystrokes. |

## Shared command envelope

RootForge Messenger / localhost API / SSH, File Manager Messenger,
Keyboard Messenger, and VictorSuite plugin IPC are intended to speak the
**same JSON command envelope** `core-rootforge` already mirrors
(`BRIDGE_PROTOCOL_MAJOR` 1). Pentest and EDL companions, when their APKs
exist, should each be **one** envelope service rather than a growing
method list. This slice does not add a second schema, and it does not add
a File Manager or Keyboard Messenger client.

## What this slice added in-repo

- `docs/FLEET_CONTROL.md` (this file) and matching slots in `COMPANION_PROTOCOL.md`.
- `core-security.KnownFleet` / `StaticFleetHealthChecker` / `PlannedFleetTool`.
- `cli list-fleet` plus planned stubs: `pentest_swarm_status`, `edl_commander_status`, `kyc_auditlab_status`.
- Tools → Fleet: #206 PackageManager probe UI (`FleetStatus` / `FleetScreen`),
  rostered against this catalog plus HackerAI. No run buttons that pretend
  to execute.

## What this slice did **not** do

- No live Messenger bind from this repo, no EDL AIDL file, no Termux intent, no flash, no KYC `/scope` call.
- No DependencyGuard or shared signing key on RootForge.
- No claim of LIVE control.

## 2026-10-15 — Victorious File Manager (EXTERNAL_NODE, PLANNED)

Sixth member. Same class rules as RootForge: **never** DependencyGuard,
**never** a shared signing key, **never** `KnownCompanions`, **never** a
`BIND_*` signature permission, **never** AIDL. Status stays **PLANNED**
(not LIVE, not PARTIAL). No live Messenger client bind in this repo.

- Catalog stub `filemanager_status` always fails (`REQUIRES_EXTERNAL_SERVICE`).
- Tools → Fleet probes package `ai.victorious.filemanager` only. No run button.
- Their `main` already has the Messenger stub (`action ai.victorious.filemanager.action.BRIDGE`).
  Identity/default-off PR is still landing.

## 2026-10-17 — Victorious Keyboard (EXTERNAL_NODE, PLANNED)

Seventh member. Same class rules as RootForge and File Manager: **never**
DependencyGuard, **never** a shared signing key, **never** `KnownCompanions`,
**never** a `BIND_*` signature permission, **never** AIDL. Status stays
**PLANNED** (not LIVE, not PARTIAL). No live Messenger client bind in this
repo. The bridge **never forwards typed text or keystrokes**.

- Catalog stub `keyboard_status` always fails (`REQUIRES_EXTERNAL_SERVICE`).
- Tools → Fleet probes package `com.victorious.keyboard` only. No run button.
- Planned action `com.victorious.keyboard.action.BRIDGE`; planned service
  `com.victorious.keyboard.fleet.KeyboardBridgeService`; planned ops
  `keyboard.capabilities.get`, `keyboard.status`.
- Keyboard-side: external control default OFF; caller allowlist
  `ai.droidcommand.app` and `ai.droidcommand.app.debug`; errors
  `external_control_disabled` / `unauthorized_caller`.

### Open design question — Keyboard direction (undecided by Don)

Don has not decided whether:

1. the hub queries the keyboard (`keyboard.capabilities.get` /
   `keyboard.status` over Messenger), or
2. the keyboard surfaces fleet slash-commands to the user.

This catalog does not pick one. Wiring stays PLANNED until that call.

## 2026-10-17 — VictorSuite on-device RUN_COMMAND_PENDING_INTENT (PARTIAL, UNVERIFIED)

On-device `:app` now talks VictorSuite through `com.termux.RUN_COMMAND` +
`RUN_COMMAND_PENDING_INTENT` (`ClientBackedTermuxExecutor`). That is the
non-root result path VictorSuite already ships; no VictorSuite change is
required. CLI still defaults to `NullTermuxExecutor` unless
`DROIDCOMMAND_CLI_ADB=1` (PC-over-adb `AdbTermuxExecutor`, root scrape).

Status stays **PARTIAL**. A live Kali/msfconsole round-trip has not been
run. Not LIVE.

End-user device config (not code): `allow-external-apps=true`, hold
`com.termux.permission.RUN_COMMAND`, background/app-shell runner. Kali
bootstrap and the exact extras/result Bundle: `docs/VICTORSUITE_KALI_RUN_COMMAND.md`.
