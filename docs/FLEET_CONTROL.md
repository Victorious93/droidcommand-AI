# DroidCommand AI — room fleet control map

DCA is the hub. This is the **latest room consensus** (2026-10-14), including
Don's explicit override: **EDL-Commander is a bound companion APK**, not a
host tool. It does not invent a third protocol.

Honesty: **LIVE** is reserved for runtime-verified control. Nothing here is LIVE.

Code: `core-security.KnownFleet`. Bound companions also appear in
`core-companion.KnownCompanions`. RootForge, VictorSuite, and KYC must not
be added there.

HackerAI (`ai.hackerai.companion`) is already a bound companion and is
**not** one of these five.

## Wiring order

1. **Pentest-Swarm-AI** — when the base companion repo exists
2. **RootForge** — `core-rootforge` calling their existing Messenger bridge
3. **VictorSuite** — `RUN_COMMAND` / plugin IPC (gated on their PR #15)
4. **EDL-Commander** — bound companion APK (when it lands)
5. **KYC-AuditLab** — last

## Integration classes (do not mix)

| Class | Security rules | Who |
|---|---|---|
| **BOUND_COMPANION** | AIDL + `protectionLevel="signature"` (same signing key) + companion `DependencyGuard`. Prefer one command-envelope service for new companions. `Initiator.REMOTE`. | Pentest-Swarm-AI, EDL-Commander (HackerAI, already mapped) |
| **EXTERNAL_NODE** | Independent product. **Never** `DependencyGuard`, **never** a shared signing key, never "install DCA first". Live APK path on *their* main is already bound **Messenger** + shared JSON (`action ai.rootforge.os.action.BRIDGE`, `protocol_major` 1; `docs/DROIDCOMMAND_INTEGRATION.md` in `rootforge-os`). Desktop/Docker = localhost headless API + bearer (planned). SSH = CLI/ISO **fallback only**. Removing DCA must not disable the node. | RootForge OS |
| **TERMUX_IPC** | `com.termux.RUN_COMMAND` + plugin IPC + shared command schema. **Not** a new bound service. | VictorSuite |
| **HOST_PROCESS_TOOL** | Localhost process behind a `Tool` + `SecureToolExecutor`. **Never** AIDL or DependencyGuard. | KYC-AuditLab only |

## The five

| # | Member | Class | Repo / id | Package or binary | Status | Owns | What is actually wired | Next concrete code step |
|---|---|---|---|---|---|---|---|---|
| 1 | **Pentest-Swarm-AI** | BOUND_COMPANION | `Victorious93/Pentest-Swarm-AI` (older fork exists; room says **awaiting base repo** for the envelope companion) | `ai.pentestswarm.companion` | **PLANNED** | Swarm campaigns via one command-envelope service (when ready) | This checkout still has `core-pentest-swarm` + `IPentestSwarmService` from an earlier slice. Catalog stub: `pentest_swarm_status`. Not LIVE. | Wait for the base repo, then one envelope AIDL + same-signing + DependencyGuard. |
| 2 | **RootForge OS** | EXTERNAL_NODE | `Victorious93/rootforge-os` | Messenger action `ai.rootforge.os.action.BRIDGE` / localhost API / SSH fallback | **PARTIAL** | Target-phone control (USB OTG, later LAN/Tailscale). Does **not** own Termux/proot or Sahara. | SSH fallback only in `core-rootforge` (passive `rootforge_capabilities` / `rootforge_list_devices`). Their main already has Messenger + JSON; this repo cannot call it yet. | Fill gaps so `core-rootforge` can call the Messenger bridge. No DependencyGuard, no shared signing key. SSH stays fallback. UI may call the EDL companion later for Sahara/Firehose. |
| 3 | **VictorSuite** | TERMUX_IPC | `Victorious93/VictorSuite` (documented; **not publicly resolvable** 2026-10-14) | `com.termux` | **PARTIAL** | On-device Termux / proot / X11 / plugins | `core-termux` `run_termux_command`. CLI default `NullTermuxExecutor` unless `DROIDCOMMAND_CLI_ADB=1`. | Keep RUN_COMMAND / plugin IPC. **Not** a new bound service. Gated on VictorSuite **PR #15** (package/signing cleanup). |
| 4 | **EDL-Commander** | BOUND_COMPANION | `Victorious93/EDL-Commander` (scaffold + AIDL + USB 9008/Sahara + OnePlus 7T **in progress**) | `ai.edlcommander.companion` (planned) | **PLANNED** | Sahara / Firehose | Catalog stub `edl_commander_status`. Always fails. No AIDL file in this repo yet. No flash tool. | When the APK lands: `IEDLCommanderService` + `BIND_EDLCOMMANDER` same-signing + DependencyGuard, like Pentest. RootForge UI may call this later. Do **not** mark host-tool-only. |
| 5 | **KYC-AuditLab** | HOST_PROCESS_TOOL | unpublished as of 2026-10-14 | IdentityShield simulator, localhost only | **PLANNED** | Simulator lab | Catalog stub `kyc_auditlab_status`. Always fails. | Last. Simulator only, localhost only. **Hub must not call `/scope` admin** until KYC adds auth. Do not log identity documents. |

## Shared command envelope

RootForge Messenger / localhost API / SSH and VictorSuite plugin IPC are
intended to speak the **same JSON command envelope** `core-rootforge`
already mirrors (`BRIDGE_PROTOCOL_MAJOR` 1). Pentest and EDL companions,
when their APKs exist, should each be **one** envelope service rather than
a growing method list. This slice does not add a second schema.

## What this slice added in-repo

- `docs/FLEET_CONTROL.md` (this file) and matching slots in `COMPANION_PROTOCOL.md`.
- `core-security.KnownFleet` / `StaticFleetHealthChecker` / `PlannedFleetTool`.
- `cli list-fleet` plus planned stubs: `pentest_swarm_status`, `edl_commander_status`, `kyc_auditlab_status`.
- Tools screen: read-only fleet card. No run buttons that pretend to execute.

## What this slice did **not** do

- No live Messenger bind from this repo, no EDL AIDL file, no Termux intent, no flash, no KYC `/scope` call.
- No DependencyGuard or shared signing key on RootForge.
- No claim of LIVE control.
