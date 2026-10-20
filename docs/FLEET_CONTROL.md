# DroidCommand AI — room fleet control map

DCA is the hub. This is the **latest room consensus** (2026-10-14), including
Don's explicit override: **EDL-Commander is a bound companion APK**, not a
host tool. It does not invent a third protocol.

Honesty: **LIVE** is reserved for runtime-verified control. Nothing here is LIVE.

Code: `core-security.KnownFleet`. Bound companions also appear in
`core-companion.KnownCompanions`. RootForge, File Manager, Keyboard,
Mesh, Browser, VictorSuite, and KYC must not be added there.

HackerAI (`ai.hackerai.companion`) is already a bound companion and is
**not** one of these nine.

## Wiring order

1. **Pentest-Swarm-AI** — when the base companion repo exists
2. **RootForge** — `core-rootforge` calling their existing Messenger bridge
3. **VictorSuite** — `RUN_COMMAND` / plugin IPC (gated on their PR #15)
4. **EDL-Commander** — bound companion APK (when it lands)
5. **KYC-AuditLab** — last
6. **Victorious File Manager** — when their identity/default-off PR lands; after the RootForge Messenger client exists
7. **Victorious Keyboard** — catalog only; direction (hub queries keyboard vs keyboard slash-commands) is open
8. **Victorious Mesh** — catalog only; EXTERNAL_NODE with its own signing keys (debug==release package)
9. **Victorious Browser** — catalog only; EXTERNAL_NODE with its own signing keys (release CI-only, not the shared fleet key)
10. **VictorSMS** — EXTERNAL_NODE, SMS remote-command source; debug cert documented unenforced
11. **VictorSpoof** — EXTERNAL_NODE, novelty/prank caller-ID; certs TBD / unpinned
12. **Victorious Flash** — EXTERNAL_NODE, Messenger `FLEET_BRIDGE`; debug cert **enforced**; `gadget.expose` destructive always confirmation_required + on-phone ERASE

## Integration classes (do not mix)

| Class | Security rules | Who |
|---|---|---|
| **BOUND_COMPANION** | AIDL + `protectionLevel="signature"` (same signing key) + companion `DependencyGuard`. Prefer one command-envelope service for new companions. `Initiator.REMOTE`. | Pentest-Swarm-AI, EDL-Commander (HackerAI, already mapped) |
| **EXTERNAL_NODE** | Independent product. **Never** `DependencyGuard`, **never** a shared signing key, never "install DCA first". Live APK path on *their* main is bound **Messenger** + shared JSON (`protocol_major` 1). RootForge: `action ai.rootforge.os.action.BRIDGE`. File Manager: `action ai.victorious.filemanager.action.BRIDGE` (**PLANNED** as a product slot; generic client exists, off until paired). Keyboard: planned `action com.victorious.keyboard.action.BRIDGE` (**PLANNED**; never forwards typed text/keystrokes). Mesh: planned `action com.victorious.mesh.action.BRIDGE` (**PLANNED**; debug==release package `com.victorious.mesh`; Mesh keeps its own release key; generic client exists, off until paired). Browser: planned `action com.victorious.browser.action.BRIDGE` (**PLANNED**; package `com.victorious.browser`; caller identity = `Message.sendingUid`, not `Binder.getCallingUid`; Browser keeps its own release key, CI-only). KYC companion: planned `action com.identityshield.companion.action.BRIDGE` (**[provisional/pending-stable-cert]**; `remote.command` OFF-by-default; all PII ops `remoteAllowed:false`; fail-open cert). Desktop/Docker = localhost headless API + bearer (planned, RootForge). SSH = CLI/ISO **fallback only** (RootForge). Removing DCA must not disable the node. | RootForge OS, Victorious File Manager, Victorious Keyboard, Victorious Mesh, Victorious Browser, VictorSMS, VictorSpoof, Victorious Flash, KYC-AuditLab companion APK (remote.command surface; KnownFleet class stays HOST_PROCESS_TOOL for the localhost simulator) |
| **TERMUX_IPC** | `com.termux.RUN_COMMAND` + plugin IPC + shared command schema. **Not** a new bound service. | VictorSuite |
| **HOST_PROCESS_TOOL** | Localhost process behind a `Tool` + `SecureToolExecutor`. **Never** AIDL or DependencyGuard. | KYC-AuditLab only |

## The nine

| # | Member | Class | Repo / id | Package or binary | Status | Owns | What is actually wired | Next concrete code step |
|---|---|---|---|---|---|---|---|---|
| 1 | **Pentest-Swarm-AI** | BOUND_COMPANION | `Victorious93/Pentest-Swarm-AI` (older fork exists; room says **awaiting base repo** for the envelope companion) | `ai.pentestswarm.companion` | **PLANNED** | Swarm campaigns via one command-envelope service (when ready) | This checkout still has `core-pentest-swarm` + `IPentestSwarmService` from an earlier slice. Catalog stub: `pentest_swarm_status`. Not LIVE. | Wait for the base repo, then one envelope AIDL + same-signing + DependencyGuard. |
| 2 | **RootForge OS** | EXTERNAL_NODE | `Victorious93/rootforge-os` | Messenger action `ai.rootforge.os.action.BRIDGE` / localhost API / SSH fallback | **PARTIAL** | Target-phone control (USB OTG, later LAN/Tailscale). Does **not** own Termux/proot or Sahara. | SSH fallback in `core-rootforge` (passive `rootforge_capabilities` / `rootforge_list_devices`). On-device Messenger client + pairing UI is `core-fleet-messenger` + `:app`, **off until paired**. | Pair package + cert on Tools → Fleet for read-only Messenger ops. No DependencyGuard, no shared signing key. SSH stays CLI fallback. Device-unverified. |
| 3 | **VictorSuite** | TERMUX_IPC | `Victorious93/VictorSuite` (documented; **not publicly resolvable** 2026-10-14) | `com.termux` | **PARTIAL** | On-device Termux / proot / X11 / plugins | `core-termux` `run_termux_command`. CLI default `NullTermuxExecutor` unless `DROIDCOMMAND_CLI_ADB=1`. | Keep RUN_COMMAND / plugin IPC. **Not** a new bound service. Gated on VictorSuite **PR #15** (package/signing cleanup). |
| 4 | **EDL-Commander** | BOUND_COMPANION | `Victorious93/EDL-Commander` (scaffold + AIDL + USB 9008/Sahara + OnePlus 7T **in progress**) | `ai.edlcommander.companion` | **PLANNED** | Sahara / Firehose | Catalog stub `edl_commander_status`. Always fails. AIDL `IEdlCompanion` copied under `ai.edlcommander.companion.aidl`. Bind action `ai.edlcommander.companion.BIND`. Hub `uses-permission` `ai.edlcommander.companion.permission.BIND_COMPANION`. Orchestrator does not auto-bind. No flash tool. | Bind from the Fleet orchestrator once device-verified. Same-signing + DependencyGuard, like Pentest. RootForge UI may call this later. Do **not** mark host-tool-only. |
| 5 | **KYC-AuditLab** | HOST_PROCESS_TOOL (simulator) + companion EXTERNAL_NODE (`remote.command`) | unpublished as of 2026-10-14 | IdentityShield simulator (localhost) + companion APK `com.identityshield.companion` | **PLANNED** | Simulator lab; companion is a remote.command target | Catalog stub `kyc_auditlab_status`. Always fails. Companion is in `RemoteCommandTargetRegistry` / `ExternalNodeCatalog` as `[provisional/pending-stable-cert]` (fail-open). | Last among the original five. Simulator only, localhost only. **Hub must not call `/scope` admin** until KYC adds auth. Do not log identity documents. Companion: `remote.command` OFF-by-default; all PII ops `remoteAllowed:false`; never remote-allow PII from the controller. Debug cert documented, not enforced (per-machine debug.keystore). |
| 6 | **Victorious File Manager** | EXTERNAL_NODE | `Victorious93/File-Manager` (already on their `main`) | `ai.victorious.filemanager` (debug: `ai.victorious.filemanager.debug`); Messenger action `ai.victorious.filemanager.action.BRIDGE` | **PLANNED** | File listing / status over the shared JSON envelope (stub ops only on their side today) | Catalog stub `filemanager_status`. Always fails. Generic Messenger client exists (`core-fleet-messenger` + `:app`) but **is not started unless this node is paired**. Their main already has `FileManagerBridgeService` (Message what=1 Bundle key `request`; reply what=2 Bundle key `response`; `protocol_major` 1). Stub ops: `filemanager.capabilities.get`, `filemanager.status`, `filemanager.list` (empty entries). Every reply currently `live:false`, `mode:stub`, `dca_required:false`. | When their identity/default-off PR lands (external control default OFF; caller identity = UID package + SHA-256 cert; allow only `ai.droidcommand.app` and `.debug`; error categories `external_control_disabled` / `unauthorized_caller`; `fleet_class` → `external_node`), pair package + cert to call the existing client. Never DependencyGuard, never shared signing key, never `KnownCompanions`, never AIDL. |
| 7 | **Victorious Keyboard** | EXTERNAL_NODE | `Victorious93/Victorious-Keyboard` | `com.victorious.keyboard` (debug: `com.victorious.keyboard.debug`; benchmark: `com.victorious.keyboard.bench`); planned Messenger action `com.victorious.keyboard.action.BRIDGE` | **PLANNED** | Keyboard capabilities / status over the shared JSON envelope. **Never forwards typed text or keystrokes.** | Catalog stub `keyboard_status`. Always fails. Generic Messenger client exists (`core-fleet-messenger` + `:app`) but **is not started unless this node is paired**. Planned service `com.victorious.keyboard.fleet.KeyboardBridgeService` (Message what=1 Bundle key `request`; reply what=2 Bundle key `response`; `protocol_major` 1, `dca_required:false`, `fleet_class` `external_node`). Planned ops: `keyboard.capabilities.get`, `keyboard.status`. Keyboard-side: external control default OFF; caller allowlist `ai.droidcommand.app` and `ai.droidcommand.app.debug`; errors `external_control_disabled` / `unauthorized_caller`. | Catalog only until Don decides direction (hub queries the keyboard vs the keyboard surfaces fleet slash-commands). Never DependencyGuard, never shared signing key, never `KnownCompanions`, never AIDL, never a `BIND_*` permission. Never forward typed text or keystrokes. |
| 8 | **Victorious Mesh** | EXTERNAL_NODE | `Victorious93/victorious-mesh` | `com.victorious.mesh` (debug==release; **no** `applicationIdSuffix`); planned Messenger action `com.victorious.mesh.action.BRIDGE` | **PLANNED** | Mesh VPN / Headscale node capabilities, status, up, down, and IP over the shared JSON envelope | Catalog stub `mesh_status`. Always fails. Generic Messenger client exists (`core-fleet-messenger` + `:app`) but **is not started unless this node is paired**. Planned Messenger (Message what=1 Bundle key `request`; reply what=2 Bundle key `response`; `protocol_major` 1, `dca_required:false`, `fleet_class` `external_node`). Planned ops: `mesh.capabilities.get`, `mesh.status`, `mesh.up`, `mesh.down`, `mesh.ip.get`. LLM tools: `mesh_capabilities` / `mesh_status` / `mesh_up` / `mesh_down` / `mesh_get_ip`. Node-id prefix `^mesh-[0-9a-f]{32}$`. Mesh-side: external control default OFF; caller allowlist `ai.droidcommand.app` and `ai.droidcommand.app.debug`. Mesh debug cert SHA-256 `F4:C9:25:B8:E8:0B:55:AB:83:E6:04:13:EE:49:86:57:DF:85:03:BB:30:D8:1F:9F:17:43:F2:2B:F5:36:B9:F4`. Mesh **own** RELEASE SHA-256 `76:E9:15:83:31:40:DA:96:69:D3:CC:5D:54:F6:66:A9:01:51:51:FC:F6:4C:59:B6:3D:DA:6E:D2:8F:83:A4:0D` (RSA 4096, alias `victoriousmeshrelease`, CI-secrets only; **not** the shared fleet key). | Catalog only. Pair package + cert to call the existing generic client. Never DependencyGuard, never shared signing key, never `KnownCompanions`, never AIDL, never a `BIND_*` permission. |
| 9 | **Victorious Browser** | EXTERNAL_NODE | unpublished as of 2026-10-18 (repo not in ratified facts) | `com.victorious.browser`; planned Messenger action `com.victorious.browser.action.BRIDGE` | **PLANNED** | Browser capabilities, URL open, panel list/open, recon summary, and proxy set/status over the shared JSON envelope | Catalog stub `browser_status`. Always fails. Generic Messenger client exists (`core-fleet-messenger` + `:app`) but **is not started unless this node is paired**. Planned Messenger (Message what=1 request; reply what=2 response). Documented ops: `browser.capabilities.get`, `browser.open_url{url}`, `browser.panel.list`, `browser.panel.open{panel_id}`, `browser.recon.summary{limit?,since?}`, `browser.proxy.set{enabled}` (fire-and-forget) + pollable `browser.proxy.status`. Error categories: `invalid_parameters`, `not_paired`, `not_found`, `proxy_error`, `internal`. Node-id prefix `^browser-[0-9a-f]{32}$`. Browser-side: external control default OFF; caller identity = `Message.sendingUid` (**not** `Binder.getCallingUid`). Browser debug cert SHA-256 `2D:5D:9E:8B:53:8E:78:51:3D:B5:41:F4:DF:FD:67:BE:27:FF:EC:3F:37:EB:1A:D4:8D:77:83:AE:59:27:C3:E1`. Browser keeps its **own** release key (CI-only; **not** the shared fleet key) — release is **[planned/unverified]**. | Catalog only. Pair package + cert to call the existing generic client. Never DependencyGuard, never shared signing key, never `KnownCompanions`, never AIDL, never a `BIND_*` permission. |

## Shared command envelope

RootForge Messenger / localhost API / SSH, File Manager Messenger,
Keyboard Messenger, Mesh Messenger, Browser Messenger, and VictorSuite
plugin IPC are intended to speak the **same JSON command envelope**
`core-rootforge` already mirrors (`BRIDGE_PROTOCOL_MAJOR` 1). Pentest and
EDL companions, when their APKs exist, should each be **one** envelope
service rather than a growing method list. This slice does not add a
second schema. The generic Messenger client (`core-fleet-messenger`)
speaks that same envelope for any EXTERNAL_NODE; nothing is contacted
until a node is paired.

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

## 2026-10-18 — Victorious Browser (EXTERNAL_NODE, PLANNED)

Ninth member (Mesh already took wiring order 8). Same class rules as RootForge, File Manager, Keyboard, and Mesh:
**never** DependencyGuard, **never** a shared signing key, **never**
`KnownCompanions`, **never** a `BIND_*` signature permission, **never**
AIDL. Status stays **PLANNED** (not LIVE, not PARTIAL). No live Messenger
client bind in this repo. Browser keeps its **own** release key (CI-only;
not the shared fleet key).

- Package `com.victorious.browser` (the only package in the ratified facts).
- Catalog stub `browser_status` always fails (`REQUIRES_EXTERNAL_SERVICE`).
- Tools → Fleet probes package `com.victorious.browser` only. No run button.
- Planned action `com.victorious.browser.action.BRIDGE`; Messenger what=1
  request / what=2 response; node-id prefix `^browser-[0-9a-f]{32}$`.
- Documented ops: `browser.capabilities.get`, `browser.open_url{url}`,
  `browser.panel.list`, `browser.panel.open{panel_id}`,
  `browser.recon.summary{limit?,since?}`, `browser.proxy.set{enabled}`
  (fire-and-forget) + pollable `browser.proxy.status`.
- Error categories: `invalid_parameters`, `not_paired`, `not_found`,
  `proxy_error`, `internal`.
- Browser-side: external control default OFF; caller identity =
  `Message.sendingUid` (**not** `Binder.getCallingUid`).
- Browser debug cert SHA-256 (committed, enforceable):
  `2D:5D:9E:8B:53:8E:78:51:3D:B5:41:F4:DF:FD:67:BE:27:FF:EC:3F:37:EB:1A:D4:8D:77:83:AE:59:27:C3:E1`.
- Browser release: own key, CI-only — **[planned/unverified]**. Do not invent
  a release fingerprint.
- Controller-side DEBUG cert is catalogued
  (`KnownNodeSigningCatalog.VICTORIOUS_BROWSER_DEBUG_SHA256`, enforceable).
  Release stays **[planned/unverified]**.

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

## 2026-10-18 — Victorious Mesh (EXTERNAL_NODE, PLANNED)

Eighth member. Same class rules as RootForge, File Manager, and Keyboard:
**never** DependencyGuard, **never** a shared signing key, **never**
`KnownCompanions`, **never** a `BIND_*` signature permission, **never**
AIDL. Status stays **PLANNED** (not LIVE, not PARTIAL). No live Messenger
client bind in this repo. Mesh keeps its **own** release key.

- Package `com.victorious.mesh` is debug==release (no `applicationIdSuffix`).
- Catalog stub `mesh_status` always fails (`REQUIRES_EXTERNAL_SERVICE`).
- Tools → Fleet probes package `com.victorious.mesh` only. No run button.
- Planned action `com.victorious.mesh.action.BRIDGE`; Messenger what=1
  request / what=2 response; `protocol_major` 1; node-id prefix
  `^mesh-[0-9a-f]{32}$`.
- Planned ops: `mesh.capabilities.get`, `mesh.status`, `mesh.up`,
  `mesh.down`, `mesh.ip.get`. LLM tools: `mesh_capabilities` /
  `mesh_status` / `mesh_up` / `mesh_down` / `mesh_get_ip`.
- Mesh-side: external control default OFF; caller allowlist
  `ai.droidcommand.app` and `ai.droidcommand.app.debug`.
- Mesh debug cert SHA-256
  `F4:C9:25:B8:E8:0B:55:AB:83:E6:04:13:EE:49:86:57:DF:85:03:BB:30:D8:1F:9F:17:43:F2:2B:F5:36:B9:F4`.

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

## 2026-10-18 — Controller-side node signature verification

The (package → cert SHA-256) trust check used to live only on the **node**
(the node verifies the controller). The controller now pins each fleet
NODE's app signing certificate before bind/command.

Code: `core-security.NodeSignatureVerifier` / `NodeSignatureGuard` /
`KnownNodeSigningCatalog`. Android PackageManager is a thin observer
(`:app.AndroidSigningCertObserver`, `core-companion` duplicate).
`AndroidCompanionRegistry.bind` refuses on `NodeSignatureDecision.Refused`.

This **adds** a check. DependencyGuard, signature permissions, and
Kali/pentest authorization are unchanged.

### Expected certs

The catalogue is **partial**. Do **not** invent fingerprints. Victorious
Flash debug is catalogued and **enforced** (flash PR #18 merged; debug
cert is stable). Flash RELEASE is TBD — do not invent. Fail-closed should
be enabled only once remaining members and verified release pins are
catalogued.

**Enforceable** (in `expectedSigningCertSha256` / `expectedCerts`; mismatch
and missing-observed refuse):

| Member | Type | Package | SHA-256 |
|---|---|---|---|
| DroidCommand-AI | DEBUG | `ai.droidcommand.app` | `88:00:51:A6:D9:5D:38:DD:8D:81:E1:93:1E:17:05:48:03:87:D5:93:C3:09:43:53:99:68:E6:EF:11:A0:C8:4D` |
| Victorious Mesh (8th wiring-order member, PLANNED catalog) | DEBUG | `com.victorious.mesh` | `F4:C9:25:B8:E8:0B:55:AB:83:E6:04:13:EE:49:86:57:DF:85:03:BB:30:D8:1F:9F:17:43:F2:2B:F5:36:B9:F4` |
| Victorious Mesh (8th wiring-order member, PLANNED catalog) | RELEASE (own key, **not** the shared fleet key) | `com.victorious.mesh` | `76:E9:15:83:31:40:DA:96:69:D3:CC:5D:54:F6:66:A9:01:51:51:FC:F6:4C:59:B6:3D:DA:6E:D2:8F:83:A4:0D` |
| Victorious Browser (9th wiring-order member, PLANNED catalog) | DEBUG | `com.victorious.browser` | `2D:5D:9E:8B:53:8E:78:51:3D:B5:41:F4:DF:FD:67:BE:27:FF:EC:3F:37:EB:1A:D4:8D:77:83:AE:59:27:C3:E1` |
| Victorious Flash (12th wiring-order member, PLANNED catalog) | DEBUG | `com.victorious.flash.android` | `2C:52:0F:7A:A4:BE:72:6D:EB:F9:18:3D:04:3D:56:C5:58:E5:67:F7:77:BD:10:BE:3C:98:86:38:1C:6A:F6:ED` |
| Pentest-Swarm-AI | DEBUG | `ai.pentestswarm.companion` | `5E:3A:B1:23:1F:7E:DA:18:78:B8:FF:D8:9E:AC:B1:F7:2E:EB:66:AF:3D:BA:2E:01:33:51:24:68:42:23:30:C7` |
| EDL-Commander | DEBUG | `ai.edlcommander.companion` | `E5:60:B6:92:BD:E6:51:9E:B7:CB:B5:2C:E4:DC:91:5A:19:E4:41:A0:1A:5D:03:68:82:71:AC:99:78:8A:58:C8` |
| HackerAI-Commander | DEBUG | `ai.hackerai.companion` | `AF:1C:8F:C5:44:9F:58:4A:46:C8:96:CC:C9:7F:35:B0:7B:35:7E:96:EE:66:5F:3A:BF:CB:0D:28:4D:F5:23:55` |
| HackerAI-Commander | RELEASE | `ai.hackerai.companion` | `9E:A7:B3:14:94:5B:0A:62:97:15:6F:F7:06:E7:0C:0A:B1:B7:24:DE:06:D5:09:9C:A5:12:8A:B4:43:F7:2D:8D` (firsthand verified, v0.1.0 APK signer in CI — **only** member whose shared-fleet release cert is enforced) |
| RootForge OS | DEBUG | `ai.rootforge.os.debug` | `77:7A:C7:00:87:05:A8:B4:B8:93:AC:EF:0E:03:E9:1C:7F:49:52:C9:52:FE:B2:6E:06:D7:90:B9:14:86:64:6F` |
| Victorious Keyboard | DEBUG | `com.victorious.keyboard`, `.debug` | `47:1E:AD:6B:3F:96:7E:F3:C8:63:EF:49:08:74:8B:3B:E3:63:F9:1D:75:0B:FD:B1:E9:28:05:BC:F1:6B:AF:4F` |
| VictorSuite / Termux | DEBUG | `com.termux` | `67:17:C5:5A:BE:9E:0D:F8:C6:03:C9:A0:F3:F3:67:B6:CA:BD:B0:1F:3E:58:3A:4F:F6:44:E5:DF:3D:4C:31:D3` |
| VictorSuite / Termux | RELEASE (own key, **not** the shared fleet key) | `com.termux` | `8C:BF:09:AA:5D:0D:24:6B:5A:97:C9:5D:2E:70:DC:0C:5F:5B:D3:33:8C:39:9A:97:88:91:09:A1:0D:CE:19:80` |

**Planned / unverified** (documented constant `SHARED_FLEET_RELEASE_SHA256` =
`9E:A7:…`; **not** in those members' expected lists):

| Member | Type | Status |
|---|---|---|
| DroidCommand-AI | RELEASE | [planned/unverified] — same shared-fleet fingerprint, no verified in-repo release `signingConfig` |
| Pentest-Swarm-AI | RELEASE | [planned/unverified] |
| EDL-Commander | RELEASE | [planned/unverified] |
| Victorious Browser | RELEASE | [planned/unverified] — **own** key (NOT shared fleet `9E:A7`), CI-only, cert TBD — do not invent |
| Victorious Flash | RELEASE | TBD — do not invent |

**Caveated** (documented, **not** enforced — packages stay fail-open):

| Member | Type | SHA-256 | Why unenforced |
|---|---|---|---|
| Victorious File Manager | DEBUG | `8C:1F:E3:C9:E5:34:F2:71:05:C0:68:8F:45:28:D7:7E:47:11:AE:0A:79:DD:08:D1:E8:59:30:B9:E9:48:85:F2` | **[unstable/CI-secrets]**. Not committed; CI injects from secrets and falls back to the generic Android debug key without them. Enforcing it would refuse the fallback. No release key yet. |
| VictorSMS | DEBUG | `BA:81:F5:C7:6E:2C:59:04:E8:A0:E5:0C:80:67:A2:A1:6C:23:14:86:2F:13:DA:7F:49:67:94:8A:53:E7:D1:7A` (alias `androiddebugkey`) | **[planned/pending-merge]**. Scaffold PR still open/unmerged. Documented, not enforced — `expectedSigningCertSha256` stays empty (fail-open). Release cert TBD. |
| KYC-AuditLab companion | DEBUG (active fail-open) | `6F:46:77:2A:B4:C1:E0:2F:12:F3:ED:9D:7A:65:FD:CD:61:32:B2:71:D9:07:75:FF:5A:15:9A:68:55:C9:2D:4D` | **[provisional/pending-stable-cert]**. Per-machine default debug.keystore — **not stable across machines**. Documented, not enforced — `expectedSigningCertSha256` stays empty (fail-open). Keep as the active pin until KYC PR #29 merges. |
| KYC-AuditLab companion | DEBUG (pending permanent) | `22:4F:C1:64:D6:8A:28:88:B9:FE:8F:10:C7:B6:B5:E5:78:67:2B:D2:F8:97:05:06:68:4B:23:A9:43:FA:44:1D` | Committed debug keystore, reproducible. Lives in **KYC PR #29** (unmerged). Documented, **not enforced**. Swap this in as the active pin once #29 merges. Do not enforce either until then. |

**Pending / out of scope** (fail-open; do not invent):

| Member | Why |
|---|---|
| RootForge RELEASE | No release key yet |
| Keyboard RELEASE and `.bench` | No release key; `.bench` not in the debug pin |
| File Manager RELEASE | No release key yet |
| VictorSMS RELEASE | Cert TBD — do not invent |
| KYC-AuditLab companion RELEASE | Cert TBD — do not invent. Active debug pin stays the provisional 6F:46:… fail-open value until KYC PR #29 merges, then swap to 22:4F:…. |
| Victorious Flash RELEASE | Cert TBD — do not invent. Debug pin is enforced (flash PR #18 merged). |
| BakerPi | Out of scope |

### Default for `[Unknown]`: ALLOW (documented)

Fail-closed (`MissingExpectedCertPolicy.REFUSE`) is the stronger posture and
is available (`DROIDCOMMAND_NODE_CERT_MISSING_EXPECTED=refuse`). The
**default is ALLOW** so existing flows for members with no catalogued cert
are not blocked. When a cert **is** catalogued, mismatch and
missing-observed always refuse.

## 2026-10-18 — EXTERNAL_NODE Messenger transport (implemented, default-off)

On-device Messenger client for any EXTERNAL_NODE (RootForge-on-device,
Victorious Mesh, File Manager, Keyboard, …). Envelope + validation +
package+cert trust live in pure-JVM `core-fleet-messenger`. Android bind /
`Message.what=1` `request` / `what=2` `response` plumbing lives in `:app`
(`AndroidMessengerChannel`).

- **Trust:** on connect, resolve the target package and require its
  installed signing-cert SHA-256 to be exactly the paired one. Certs come
  from pairing/config, never from code. Identity on the wire uses
  `Message.sendingUid`, not `Binder.getCallingUid()`.
- **Default-off:** pairing store starts empty; Hilt exposes
  `PairedNodeGateway` but no tool calls it. Catalog stubs still always fail.
  Installing a node APK does not enable live control. Not LIVE.
- **Fire-and-forget:** `*.up` acks immediately; poll `*.status`. Parameterless
  ops send `{}` (`invalid_parameters` otherwise).
- Never AIDL, never DependencyGuard, never a shared signing key.

## 2026-10-18 — pairing UI + live read-only ops (default-off until paired)

Tools → Fleet now pairs EXTERNAL_NODE members and can run live **read-only**
ops against a trusted pairing. State: `SharedPreferencesPairedNodeStore`
(`fleet_paired_nodes` / `nodes_v1`). Logic: `FleetMessengerController`
(JVM). UI is thin Compose (`FleetScreen` / `FleetViewModel`).

- **Pairing:** list RootForge / File Manager / Keyboard; show installed
  package and observed cert SHA-256; owner confirms to store package + cert.
  Install does not pair. Re-sign → cert-mismatch until explicit re-pair.
  Unpair clears the store entry.
- **Live ops after TRUSTED pairing only:**
  - RootForge: `rootforge.capabilities.get`, `rootforge.status`;
    `rootforge.up` is fire-and-forget then poll `rootforge.status`.
  - File Manager: `filemanager.capabilities.get`, `filemanager.status`
    (no `*.up`).
  - Keyboard: `keyboard.capabilities.get`, `keyboard.status` (no `*.up`;
    never typed text / keystrokes).
- **Still gated / not on this screen:** any other write/control op (refused
  as gated, channel never opened); Kali / pentest authorization;
  DependencyGuard / AIDL companions; catalog stubs
  (`filemanager_status`, `keyboard_status`) still always fail; no Pilot/Forge
  tool registered against `PairedNodeGateway`.
- Default-safe: empty store, nothing contacted. Not LIVE. Device-unverified.

## 2026-10-19 — EDL-Commander applicationId rename

EDL-Commander's app-ID rename merged on their `main` (PR #8). The previous
GitHub-style applicationId placeholder is gone.

| Piece | Value |
|---|---|
| Package / `applicationId` | `ai.edlcommander.companion` |
| Bind action | `ai.edlcommander.companion.BIND` |
| Signature permission (defined by EDL; hub `uses-permission`) | `ai.edlcommander.companion.permission.BIND_COMPANION` |
| AIDL package (copied verbatim) | `ai.edlcommander.companion.aidl` (`IEdlCompanion`, `CommandEnvelope`, `ResultEnvelope`) |
| DEBUG cert SHA-256 | **unchanged** `E5:60:B6:92:…:58:C8` |

Status stays **PLANNED**: AIDL is on disk here, the Fleet orchestrator
does not auto-bind, and nothing has run against a real EDL APK. No flash
tool. Kali/pentest gates and YOLO exclusions were not touched.

## 2026-10-20 — Remote Control (VictorSMS trigger + voice) skeleton

Approved fleet work. Spec: `docs/specs/REMOTE_CONTROL.md`. Code:
`core-remotecontrol` + Settings skeleton. **Not LIVE.**

VictorSMS (`com.victorious.sms`) is the SMS trigger source on the
target/paired device: whitelist **and** secret code word, then an
EXTERNAL_NODE Messenger forward (`protocol_major` 1, proposed op
`remote.command`). It is **not** added to `KnownFleet.ALL` in this
slice — whether it is a 10th member or a trigger app that reuses the
generic Messenger client is **[CONTRACT TBD]**. Never DependencyGuard,
never a shared signing key, never AIDL, never a `BIND_*` permission.

Voice trigger is local to DCA (exact phrase match). Delivery mode is
per mapping: `FULL_SCREEN_CONTROL` (RustDesk, stub) or
`DISCRETE_COMMAND`. Destructive actions (WIPE, POWER_OFF, LOCK) always
prompt; YOLO cannot waive them. Kali/pentest gates are untouched.

No live wipe / power-off / lock / RustDesk in this slice.

## 2026-10-20b — Frozen envelope + VictorSMS is the 10th member

The Fleet Orchestrator froze the remote envelope (top-level fields, no
nested `auth`). Spec: `docs/specs/REMOTE_CONTROL.md`. VictorSMS
(`com.victorious.sms`, node-id `victorsms-<32 hex>`, action
`com.victorious.sms.action.BRIDGE`) **is** `KnownFleet.VICTOR_SMS`
(`wiringOrder` 10, empty `expectedSigningCertSha256`, fail-open).
Inbound op is `remote.command` with informational `trigger{}`.
Authorization is `Message.sendingUid` (package+cert), never a payload
field. Replay is `(sourceNodeRef, nonce)`. `destructive` is advisory.

VictorSpoof is a **remote-control actionRef registry** isolated from
destructive classification, not an 11th `KnownFleet` member (package and
certs [CONTRACT TBD]). Never DependencyGuard, never a shared signing
key, never AIDL.

Status tray consume+publish and unified local backup are controller-side
skeletons. Live privileged execution is still absent.

## 2026-10-20c — Ratified nested-auth protocol + VictorSpoof is the 11th member

Fleet Orchestrator **ratified** the remote protocol. This **overrides**
the 2026-10-20b top-level-field freeze. Spec:
`docs/specs/REMOTE_CONTROL.md`.

- Nested `auth { nonce, issuedAt, ttlMs }` (not top-level).
- `BRIDGE_PROTOCOL_MINOR = 1` advertised in `capabilities.get`.
- Generic node surface: `OpDescriptor`, `macro.run` / `macro.step`
  (sequential, partial, no rollback; dryRun validates without executing).
- Controller inbound action `ai.droidcommand.app.action.BRIDGE` (constant
  only; no exported receiver in this skeleton).
- Trust: inbound is an untrusted request. VictorSMS whitelist + code word
  is delivery, not authorization. Order: pairing → external-control OFF →
  replay → policy. Remote never auto-approves / auto-sends.
- Error categories extended with unauthorized / replay_detected / expired /
  external_control_disabled / confirmation_required / unsupported_op /
  capability_off.

**VictorSpoof** (`com.victorious.spoof`) **is** `KnownFleet.VICTOR_SPOOF`
(`wiringOrder` 11, empty certs / fail-open). Isolated from
wipe/power-off/lock/flash/bootloader. Never DependencyGuard, never a
shared signing key, never AIDL.

Status tray (mesh/VPN/browser/SMS/health) consume+publish and versioned
local backup of macros+mappings+pairings are controller-side skeletons.
Shared tray transport and unified fleet backup schema remain
[CONTRACT TBD pending Fleet Orchestrator]. Live privileged execution is
still absent.

## 2026-10-20d — VictorSMS remote.command target registry row

VictorSMS (`com.victorious.sms`) is now a `remote.command` EXTERNAL_NODE
**target registry / pairing-table** row alongside VictorSpoof
(`RemoteCommandTargetRegistry`). Inbound op `sms.remote_command.forward`.
Status **[planned/pending-merge]** (scaffold PR still open). Debug
SHA-256 `BA:81:F5:C7:6E:2C:59:04:E8:A0:E5:0C:80:67:A2:A1:6C:23:14:86:2F:13:DA:7F:49:67:94:8A:53:E7:D1:7A`
(alias `androiddebugkey`) is **documented, not enforced** —
`expectedSigningCertSha256` stays empty (fail-open). Release cert TBD.
Isolated from wipe/power-off/lock/flash/bootloader the same way
VictorSpoof is; a forwarded commandId is classified independently.
VictorSMS owns whitelist + code word; DCA owns commandId→action.
`trigger{}` remains informational. Never DependencyGuard.

## 2026-10-20e — KYC-AuditLab companion remote.command target registry row

KYC-AuditLab companion (`com.identityshield.companion`) is now a
`remote.command` EXTERNAL_NODE **target registry / pairing-table** row
alongside VictorSMS and VictorSpoof (`RemoteCommandTargetRegistry` +
`KycAuditLabRegistry`). Status **[provisional/pending-stable-cert]**.
Debug SHA-256
`6F:46:77:2A:B4:C1:E0:2F:12:F3:ED:9D:7A:65:FD:CD:61:32:B2:71:D9:07:75:FF:5A:15:9A:68:55:C9:2D:4D`
is from a **per-machine default debug.keystore** and is **not stable
across machines** — **documented, not enforced**
(`expectedSigningCertSha256` empty, fail-open). Do **not** enforce this
fingerprint. A stable value will replace it later.
`remote.command` is **OFF by default**. All PII ops are
`remoteAllowed:false`; the controller never remote-allows them either.
Isolated from wipe/power-off/lock/flash/bootloader the same way
VictorSpoof is. KnownFleet.KYC_AUDITLAB stays `HOST_PROCESS_TOOL` for the
localhost simulator (hub must not call `/scope` admin). Never
DependencyGuard, never AIDL.

## 2026-10-20f — KYC companion pending permanent debug cert (KYC PR #29)

The permanent stable debug cert for `com.identityshield.companion` is
SHA-256
`22:4F:C1:64:D6:8A:28:88:B9:FE:8F:10:C7:B6:B5:E5:78:67:2B:D2:F8:97:05:06:68:4B:23:A9:43:FA:44:1D`
(committed debug keystore, reproducible). It is in **KYC PR #29**, not
merged yet. The registry **keeps** the provisional `6F:46:77:…:2D:4D`
entry as the **active fail-open pin** and **records** `22:4F:C1:…:44:1D`
as `pendingPermanentDebugCertSha256`. **Do not enforce either.** Swap
the active pin once KYC #29 merges. PII ops stay `remoteAllowed:false`.

## 2026-10-20g — Frozen top-level envelope (nested `auth` superseded)

Fleet Orchestrator **froze** the remote envelope as **top-level** fields.
This **overrides** the 2026-10-20c nested-`auth` sketch. Spec:
`docs/specs/REMOTE_CONTROL.md`.

- Top-level `nonce`, `timestampMs` (not `issuedAt`), `ttlMs`,
  `destructive`. No nested `auth` object.
- Replay is `(sourceNodeRef, nonce)`, retained ≥ 10 min.
- One contract, three bindings: EXTERNAL_NODE JSON `parameters`,
  BOUND_COMPANION AIDL parcel fields, TERMUX_IPC JSON-blob — same
  top-level names.
- VictorSMS `trigger{}` stays informational. Source identity =
  package+cert (`sendingUid`).
- `destructive` is advisory. Kali/pentest/YOLO pentest gates untouched.

## 2026-10-20h — Frozen STATUS-TRAY schema (`node.status.get`)

Fleet Orchestrator assigned DCA ownership of the shared status-tray
schema. **[CONTRACT] FROZEN** in `docs/specs/STATUS_TRAY.md`.

- Every node implements `node.status.get` (`kind=read`,
  `remoteAllowed=true` typically). Transport is the existing
  BridgeRequest / BridgeResponse (`protocol_major` 1, minor 1 via
  `capabilities.get`).
- Snapshot: `{ nodeId, packageName, connectionClass, updatedAtMs,
  domains[{name, state, detail?}] }`. Connection class is
  `EXTERNAL_NODE` | `BOUND_COMPANION` | `TERMUX_IPC` |
  `HOST_PROCESS_TOOL`. Domains start at mesh / VPN / browser / SMS /
  node health; unknown names decode as `other`.
- Aligns with the #233 `NodeStatusSnapshot` / `StatusTrayDomain`
  skeleton (those names stay as JVM aliases).
- Controller aggregates (merge domains per `nodeId`). VictorLaunch +
  DCA UI consume. No live bind in this slice.
- Privacy: no secrets, no PII dumps, no signing material.
- Kali/pentest/YOLO pentest gates untouched.

## 2026-10-20j — Frozen unified SETTINGS/BACKUP bundle

Fleet Orchestrator assigned DCA ownership of the cross-app settings
backup contract. **[CONTRACT] FROZEN** in `docs/specs/UNIFIED_BACKUP.md`.

- Envelope: `{ version, createdAtMs, sourceNodeRef, wrapping,
  entries[{packageName, schemaVersion, ciphertext or clearPayload}] }`.
- Ops: `config.export` / `config.import` (`kind=action`,
  `remoteAllowed=false` by default, `requiresConfirm=true` on import).
- AES-256-GCM (`v1.<iv>.<ciphertext>`). Never signing keys, API tokens,
  VictorSMS secrets, or plaintext passwords.
- Import: dry-run validate, conflict policy skip | overwrite |
  merge-by-id, **never** auto-enable `remote.command`.
- The #233 controller-local JSON is the `ai.droidcommand.app` entry.
  No live bind. Kali/pentest/YOLO pentest gates untouched.

## 2026-10-20m — Victorious Flash is the 12th member (debug cert ENFORCED)

Victorious Flash (`com.victorious.flash.android`,
`Victorious93/victorious-flash`) **is** `KnownFleet.VICTORIOUS_FLASH`
(`wiringOrder` 12, EXTERNAL_NODE, PLANNED). Never DependencyGuard, never
a shared signing key, never AIDL, never `KnownCompanions`.

- Messenger: exported service, action
  `com.victorious.flash.android.FLEET_BRIDGE`, Message what=1 JSON
  envelope + `replyTo`, reply what=2.
- Node-id prefix `^vfa-[0-9a-f]{32}$` (`targetNodeRef` = phone id).
- Ops: `status.get` / `capabilities.get` (read);
  `netboot.serve.start` / `netboot.serve.stop` (action);
  `otg.write{deviceName}`; `gadget.expose` (**DESTRUCTIVE**, always
  `confirmation_required` plus on-phone ERASE confirmation). Isolated
  like other destructive gating. This catalog stub does not bind and
  never exposes a gadget.
- DEBUG signing cert SHA-256 **enforced** (flash PR #18 merged to main;
  CI green `7a4c32c`):
  `2C:52:0F:7A:A4:BE:72:6D:EB:F9:18:3D:04:3D:56:C5:58:E5:67:F7:77:BD:10:BE:3C:98:86:38:1C:6A:F6:ED`
  (`KnownNodeSigningCatalog.VICTORIOUS_FLASH_DEBUG_SHA256` on
  `expectedSigningCertSha256`). Release cert TBD — do not invent.
- Distinct from the Linux SSH `core-flash` node (`vf-` prefix,
  `docs/FLASH_INTEGRATION.md`).
- Catalog stub `victorious_flash_status` always fails
  (`REQUIRES_EXTERNAL_SERVICE`). Tools → Fleet probes
  `com.victorious.flash.android` only.