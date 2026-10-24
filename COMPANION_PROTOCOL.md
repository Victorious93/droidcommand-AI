# DroidCommand AI — Companion APK Integration Protocol

This document describes how DroidCommand AI integrates with external companion
APKs (currently HackerAI and Pentest-Swarm) via Android Bound Services. Read it
before modifying AIDL contracts, adding new companion capabilities, or working
on `core-companion` / `core-hackerai` / `core-pentest-swarm`.

## Overview

DCA's built-in modules (`core-agent`, `core-security`, etc.) run on the JVM and
do not require an Android device. The companion APKs extend DCA with capabilities
that either need a bundled binary (Pentest-Swarm) or a full LLM agent loop
(HackerAI), which would bloat DCA's own APK if bundled directly.

```
DroidCommand AI (ai.droidcommand.app)
        │
        ├── bindService() + ai.droidcommand.permission.BIND_HACKERAI
        │       └── HackerAI companion APK (ai.hackerai.companion)
        │               └── IHackerAIService.Stub
        │
        ├── bindService() + ai.droidcommand.permission.BIND_PENTESTSWARM
        │       └── Pentest-Swarm companion APK (ai.pentestswarm.companion)
        │               └── IPentestSwarmService.Stub
        │                       └── SwarmProcess → pentestswarm binary (Go)
        │
        └── bindService() + uses-permission ai.edlcommander.companion.permission.BIND_COMPANION
                └── EDL-Commander companion APK (ai.edlcommander.companion)
                        └── IEdlCompanion.Stub  (action ai.edlcommander.companion.BIND)
```

Both companions are **optional and independently installable**. If a companion
is absent, its `CapabilityHealthChecker` returns `REQUIRES_EXTERNAL_SERVICE`
and DCA's tools (`HackerAiTool`, `SwarmTool.*`) return `ToolResult.Failure`.

## External nodes are not companions (RootForge)

Everything in this document — AIDL binding, signature permissions, and the
`DependencyGuard` below — describes **dependent companion APKs**, which exist to
extend DCA and are meant to stop working without it.

**RootForge OS is the opposite kind of integration and is exempt from all of it.**
It is an independent product. Live APK path on *their* `main` is already a bound
**Messenger** + shared JSON envelope (`action ai.rootforge.os.action.BRIDGE`,
`protocol_major` 1; canonical doc `docs/DROIDCOMMAND_INTEGRATION.md` in
`Victorious93/rootforge-os`). Desktop/Docker is a localhost headless API + bearer
(planned). SSH is the CLI/ISO **fallback only** — that is what `core-rootforge`
implements today. The on-device Messenger client is `core-fleet-messenger` +
`:app` (`AndroidMessengerChannel`); it is **off until a node is paired** (package
+ signing-cert SHA-256). Do not treat SSH as the live APK path.

Never add a `DependencyGuard`, a shared signing key, or an "install DCA first"
check to RootForge. A node that DCA cannot reach loses nothing; removing DCA
must not disable any RootForge feature. RootForge owns target-phone control
(USB OTG, later LAN/Tailscale). It does **not** own Termux/proot (VictorSuite)
or Sahara/Firehose (EDL companion).

The room-fleet map lives in `docs/FLEET_CONTROL.md` and `core-security.KnownFleet`.
Add a member to `KnownCompanions` only when it is a genuine dependent companion
APK (HackerAI, Pentest-Swarm, EDL-Commander). File Manager, Keyboard, Mesh,
and Browser are EXTERNAL_NODE like RootForge — never `KnownCompanions`.

## Fleet slots that are not companions

These use the **same security rules as their class**, not a copy of the AIDL
block above. Forcing AIDL + `DependencyGuard` onto them would invert the
architecture.

### VictorSuite — Termux IPC (`com.termux`)

VictorSuite is a Termux/ZeroTermux-derived Android Linux suite. It ships under
Termux's own `applicationId` (`com.termux`) with the public `com.termux.RUN_COMMAND`
contract. DCA already reaches that contract through `core-termux` /
`AdbTermuxExecutor` (`run_termux_command`).

- **Not** a bound companion. Do not add `IVictorSuiteService`, a signature
  permission, or a `DependencyGuard`.
- DCA cannot tell a VictorSuite install from genuine Termux by package name;
  only one can be installed. `TerminalSelector` uses a `versionName` heuristic.
- VictorSuite owns on-device Termux / proot / X11 / plugins. Not a new
  bound service. Gated on their package/signing cleanup (PR #15).
- Status: **PARTIAL**. App/CLI default to `NullTermuxExecutor` unless adb is
  opted in. Never runtime-verified on a device in this environment.

### KYC-AuditLab — host process tool (Python IdentityShield) + companion EXTERNAL_NODE

A host-side identity **simulator** (localhost) **and** a companion APK
`com.identityshield.companion` catalogued as a `remote.command`
EXTERNAL_NODE. Catalog id `kyc-auditlab`; tool `kyc_auditlab_status`
(`PlannedFleetTool`).

- **Not** a bound companion. Never AIDL, never `DependencyGuard`, never
  `KnownCompanions`.
- Simulator only, localhost only. **No auth yet** — the hub must not call
  `/scope` admin until they add auth.
- Companion APK: planned action `com.identityshield.companion.action.BRIDGE`.
  Status **[provisional/pending-stable-cert]**. Active fail-open debug
  SHA-256
  `6F:46:77:2A:B4:C1:E0:2F:12:F3:ED:9D:7A:65:FD:CD:61:32:B2:71:D9:07:75:FF:5A:15:9A:68:55:C9:2D:4D`
  is from a per-machine default debug.keystore and is **not stable across
  machines** — documented, **not enforced**. Pending permanent
  `22:4F:C1:64:D6:8A:28:88:B9:FE:8F:10:C7:B6:B5:E5:78:67:2B:D2:F8:97:05:06:68:4B:23:A9:43:FA:44:1D`
  (committed debug keystore, reproducible) is in **KYC PR #29** (unmerged)
  — recorded, **not enforced**; swap the active pin once #29 merges. Do
  not enforce either. `remote.command` OFF-by-default. All PII ops
  `remoteAllowed:false`; the controller never remote-allows them either.
- Do not log identity-document contents.
- Status: **PLANNED**. Wiring order: last.

### VictorFiles — EXTERNAL_NODE (Messenger)

VictorFiles (`Victorious93/File-Manager`) is an independent
product, the same class as RootForge. It is **not** a companion.

- Package: `ai.victorious.victorfiles` (debug: `ai.victorious.victorfiles.debug`).
- Service: `ai.victorious.victorfiles.fleet.FileManagerBridgeService` (Messenger).
- Action: `ai.victorious.victorfiles.action.BRIDGE`, `protocol_major` 1.
- Transport: Message what=1 Bundle key `request` (JSON); reply what=2 Bundle
  key `response`. Envelope fields match RootForge (`protocol_major`,
  `request_id`, `target_node_id` / `node_id`, `operation`, `timeout_ms`,
  `parameters` / `ok`, `result`, `error {category, message}`).
- Stub ops already on their `main`: `filemanager.capabilities.get` (read),
  `filemanager.status` (read), `filemanager.list` (read, empty entries).
  Every reply currently carries `live:false`, `mode:stub`, `dca_required:false`.
- Their identity/default-off PR is still landing: external control default
  OFF; caller identity = UID package + SHA-256 cert; allow only
  `ai.droidcommand.app` and its `.debug` build; error categories
  `external_control_disabled` and `unauthorized_caller`; `fleet_class` →
  `external_node`.
- **Never** `DependencyGuard`, **never** a shared signing key, **never**
  `KnownCompanions`, **never** a `BIND_*` signature permission, **never**
  AIDL. Removing DCA must not disable File Manager.
- Catalog stub: `filemanager_status` (`PlannedFleetTool`). Always
  `ToolResult.Failure` / `REQUIRES_EXTERNAL_SERVICE`. The stub still does
  **not** bind; the generic EXTERNAL_NODE Messenger client
  (`core-fleet-messenger` + `:app`) exists but is not started unless this
  node is paired (package + signing-cert SHA-256). No File Manager is paired
  by default.
- Status: **PLANNED** (not LIVE). Wiring: when their identity/default-off PR
  lands; pair the node to call the existing client.

### Victorious Keyboard — EXTERNAL_NODE (Messenger)

Victorious Keyboard (`Victorious93/Victorious-Keyboard`) is an independent
product, the same class as RootForge and File Manager. It is **not** a
companion.

- Package: `com.victorious.keyboard` (debug: `com.victorious.keyboard.debug`;
  benchmark: `com.victorious.keyboard.bench`).
- Planned service: `com.victorious.keyboard.fleet.KeyboardBridgeService`
  (Messenger).
- Planned action: `com.victorious.keyboard.action.BRIDGE`, `protocol_major` 1,
  `dca_required:false`, `fleet_class` `external_node`.
- Transport: Message what=1 Bundle key `request` (JSON); reply what=2 Bundle
  key `response`. Envelope fields match RootForge (`protocol_major`,
  `request_id`, `target_node_id` / `node_id`, `operation`, `timeout_ms`,
  `parameters` / `ok`, `result`, `error {category, message}`).
- Planned ops: `keyboard.capabilities.get` (read), `keyboard.status` (read).
  The bridge **never forwards typed text or keystrokes**.
- Keyboard-side: external control default OFF; caller allowlist
  `ai.droidcommand.app` and `ai.droidcommand.app.debug`; error categories
  `external_control_disabled` and `unauthorized_caller`.
- **Never** `DependencyGuard`, **never** a shared signing key, **never**
  `KnownCompanions`, **never** a `BIND_*` signature permission, **never**
  AIDL. Removing DCA must not disable Keyboard.
- Catalog stub: `keyboard_status` (`PlannedFleetTool`). Always
  `ToolResult.Failure` / `REQUIRES_EXTERNAL_SERVICE`. The stub still does
  **not** bind; the generic EXTERNAL_NODE Messenger client exists but is
  not started unless this node is paired. No Keyboard is paired by default.
- Status: **PLANNED** (not LIVE). Wiring: catalog only until Don decides
  direction (hub queries the keyboard vs the keyboard surfaces fleet
  slash-commands). See `docs/FLEET_CONTROL.md`.

### Victorious Mesh — EXTERNAL_NODE (Messenger)

Victorious Mesh (`Victorious93/victorious-mesh`) is an independent
product, the same class as RootForge, File Manager, and Keyboard. It is
**not** a companion.

- Package: `com.victorious.mesh` (debug==release; **no**
  `applicationIdSuffix`).
- Planned action: `com.victorious.mesh.action.BRIDGE`, `protocol_major` 1,
  `dca_required:false`, `fleet_class` `external_node`.
- Transport: Message what=1 Bundle key `request` (JSON); reply what=2 Bundle
  key `response`. Envelope fields match RootForge (`protocol_major`,
  `request_id`, `target_node_id` / `node_id`, `operation`, `timeout_ms`,
  `parameters` / `ok`, `result`, `error {category, message}`).
- Node-id prefix: `^mesh-[0-9a-f]{32}$`.
- Planned ops: `mesh.capabilities.get`, `mesh.status`, `mesh.up`,
  `mesh.down`, `mesh.ip.get`. LLM tools: `mesh_capabilities`,
  `mesh_status`, `mesh_up`, `mesh_down`, `mesh_get_ip`.
- Mesh-side: external control default OFF; caller allowlist
  `ai.droidcommand.app` and `ai.droidcommand.app.debug`.
- Mesh debug cert SHA-256:
  `F4:C9:25:B8:E8:0B:55:AB:83:E6:04:13:EE:49:86:57:DF:85:03:BB:30:D8:1F:9F:17:43:F2:2B:F5:36:B9:F4`.
  Mesh **own** RELEASE SHA-256:
  `76:E9:15:83:31:40:DA:96:69:D3:CC:5D:54:F6:66:A9:01:51:51:FC:F6:4C:59:B6:3D:DA:6E:D2:8F:83:A4:0D`
  (RSA 4096, alias `victoriousmeshrelease`, CI-secrets only — **not** the
  shared fleet key).
- **Never** `DependencyGuard`, **never** a shared signing key, **never**
  `KnownCompanions`, **never** a `BIND_*` signature permission, **never**
  AIDL. Removing DCA must not disable Mesh.
- Catalog stub: `mesh_status` (`PlannedFleetTool`). Always
  `ToolResult.Failure` / `REQUIRES_EXTERNAL_SERVICE`. This repo does **not**
  implement a live Messenger client bind.
- Status: **PLANNED** (not LIVE, not PARTIAL). Wiring: catalog only until
  a Mesh Messenger client can follow the RootForge pattern.

### Victorious Browser — EXTERNAL_NODE (Messenger)

Victorious Browser is an independent product, the same class as RootForge,
File Manager, and Keyboard. It is **not** a companion. Repo was not in the
ratified facts (unpublished as of 2026-10-18).

- Package: `com.victorious.browser`.
- Planned action: `com.victorious.browser.action.BRIDGE`.
- Transport: Message what=1 request; reply what=2 response. Envelope
  fields match the shared EXTERNAL_NODE JSON (`protocol_major`,
  `request_id`, `target_node_id` / `node_id`, `operation`, `timeout_ms`,
  `parameters` / `ok`, `result`, `error {category, message}`).
- Node-id prefix: `^browser-[0-9a-f]{32}$`.
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
  Browser keeps its **own** release key (CI-only; **not** the shared fleet
  key). Release is **[planned/unverified]** — do not invent a fingerprint.
- Controller-side DEBUG cert is catalogued
  (`KnownNodeSigningCatalog.VICTORIOUS_BROWSER_DEBUG_SHA256`, enforceable).
  Release stays **[planned/unverified]**.
- **Never** `DependencyGuard`, **never** a shared signing key, **never**
  `KnownCompanions`, **never** a `BIND_*` signature permission, **never**
  AIDL. Removing DCA must not disable Browser.
- Catalog stub: `browser_status` (`PlannedFleetTool`). Always
  `ToolResult.Failure` / `REQUIRES_EXTERNAL_SERVICE`. This repo does **not**
  implement a live Messenger client bind. No extra tool UI was added —
  same probe-only Fleet screen as Keyboard.
- Status: **PLANNED** (not LIVE, not PARTIAL). Wiring: catalog only until
  a Browser Messenger client can follow the RootForge pattern.

### EDL-Commander — bound companion (not a host tool)

Don explicitly chose EDL-Commander as a **bound companion APK**, same model
as Pentest-Swarm: AIDL + same-signing + `DependencyGuard`. Do not mark it
host-tool-only.

- Package: `ai.edlcommander.companion` (`KnownCompanions.EDLCOMMANDER`,
  `EdlCompanionIds.PACKAGE`; EDL PR #8 renamed the applicationId).
- Bind action: `ai.edlcommander.companion.BIND`.
- Signature permission: `ai.edlcommander.companion.permission.BIND_COMPANION`
  (defined by EDL; this hub declares `<uses-permission>` — it does **not**
  invent `ai.droidcommand.permission.BIND_EDLCOMMANDER`).
- AIDL: `IEdlCompanion` / `CommandEnvelope` / `ResultEnvelope` in
  `core-companion/src/main/aidl/ai/edlcommander/companion/aidl/` (package
  `ai.edlcommander.companion.aidl`), copied verbatim from EDL main. Do not
  invent `IEDLCommanderService`.
- Repo: `Victorious93/EDL-Commander`. USB 9008/Sahara + OnePlus 7T path is
  **in progress there**. This repo has never bound a real EDL APK.
- Catalog stub: `edl_commander_status` (always `ToolResult.Failure` /
  `REQUIRES_EXTERNAL_SERVICE`). No flash/write/restore tool. The Fleet
  orchestrator does not auto-bind EDL.
- EDL owns Sahara/Firehose. RootForge is the target-phone UI front end and
  may call this companion later.
- DEBUG cert SHA-256 is unchanged: `E5:60:B6:92:…:58:C8`.
- Status: **PLANNED** (AIDL on disk, never device-verified).

### Adding a fleet slot

1. Add a `FleetMemberDescriptor` to `KnownFleet` with the correct
   `FleetIntegrationClass`. The constructor requires AIDL + `DependencyGuard`
   for `BOUND_COMPANION` and forbids them otherwise. Fill
   `androidPackages` and `expectedSigningCertSha256` when the fingerprint is
   documented; leave the cert list empty (`[Unknown]`) rather than guessing.
2. New dependent APK: follow "Adding a new companion capability" (AIDL sync +
   `KnownCompanions`).
3. Planned members get a `PlannedFleetTool` that fails honestly. Do not spawn
   a guessed binary or bind a missing APK.
4. Update `docs/FLEET_CONTROL.md` and append an audit addendum. Do not mark
   the member LIVE.

## EXTERNAL_NODE Messenger transport (implemented)

Reusable client for **any** EXTERNAL_NODE that speaks the shared envelope over
a bound Messenger (`RootForge-on-device`, VictorFiles, Victorious
Keyboard, Victorious Mesh, …). It is **not** AIDL, **not** `DependencyGuard`,
and does **not** assume a shared signing key.

| Layer | Where | What |
|---|---|---|
| Envelope, validation, pairing, package+cert trust | `core-fleet-messenger` (pure JVM) | `BridgeRequest` / `BridgeResponse` (protocol major 1, `core-rootforge`), `NodeTrustVerifier`, `MessengerBridgeClient`, `PairedNodeGateway` |
| Messenger plumbing | `:app` `AndroidMessengerChannel` | bind `<pkg>.action.BRIDGE`; send `Message.what=1` Bundle key `request`; await `what=2` key `response`; pin reply UID via `Message.sendingUid` |

**Controller enforcement order** (nothing is bound or sent until this passes):

1. **Caller/node identity.** The target package must be paired, installed, and
   signed by **exactly** the paired certificate SHA-256 (`NodeTrustVerifier`).
   Certs are never hardcoded; they come from the pairing document.
2. **Enable switch.** Pairing is the controller-side switch (empty store by
   default = nothing is contacted). The node's own external-control switch is
   independent; if it is off, the reply is `ok:false` with
   `external_control_disabled`.
3. **Envelope validation**, same order as `RootForgeClient`: decode →
   `protocol_major==1` → `node_id` matches the paired id → `request_id` matches
   → `ok:false` (`REMOTE_ERROR` with `error.category`) → `ok:true` without a
   result (`MALFORMED_RESPONSE`, except fire-and-forget `*.up`).

**Messenger identity quirk:** both ends identify the other process via
`Message.sendingUid`, **not** `Binder.getCallingUid()`. A Messenger Handler
runs in the receiving process, so `getCallingUid()` would be that process.

**Fire-and-forget:** `*.up` acks immediately (`ReplyExpectation.ACK`; empty
result is success). Poll the matching `*.status` for the eventual outcome.
Parameterless ops reject a non-empty `parameters` object with
`invalid_parameters`; the client sends `{}` by default.

**Default-off:** the pairing store starts empty. Installing File Manager /
Keyboard / RootForge / Mesh does not enable live control. Tools → Fleet can
pair a node (package + observed cert, explicit confirm) and then run
read-only `*.capabilities.get` / `*.status` (and fire-and-forget `*.up` +
poll where the catalog has an up op). `:app` still registers no tool against
`PairedNodeGateway`. Catalog stubs (`filemanager_status`, `keyboard_status`)
still always fail. Write/control names other than documented `*.up` are
refused by `FleetMessengerController` without opening the channel.

## Security Model

### OS-level enforcement (signature permission)

DCA declares two custom permissions in its `AndroidManifest.xml`:

```xml
<permission android:name="ai.droidcommand.permission.BIND_HACKERAI"
    android:protectionLevel="signature" />
<permission android:name="ai.droidcommand.permission.BIND_PENTESTSWARM"
    android:protectionLevel="signature" />
```

`protectionLevel="signature"` means Android will only grant these permissions
to APKs signed with the same certificate as DCA. No other app — not even a
rooted one — can bind to the companion services.

Each companion service declares the matching permission:

```xml
<!-- HackerAI companion -->
<service android:name=".service.HackerAIBoundService"
    android:exported="true"
    android:permission="ai.droidcommand.permission.BIND_HACKERAI" />

<!-- Pentest-Swarm companion -->
<service android:name=".service.PentestSwarmBoundService"
    android:exported="true"
    android:permission="ai.droidcommand.permission.BIND_PENTESTSWARM" />
```

### App-level enforcement (DependencyGuard)

Every companion APK has a `DependencyGuard` that checks `ai.droidcommand.app`
is installed at every AIDL call. If DCA is absent:

- All methods return `{"ok":false,"error":"DroidCommand AI is not installed"}`
- The UI shows a "DCA required" message and disables all functionality

This is a defence-in-depth measure — the signature permission already prevents
an attacker from binding, but `DependencyGuard` also prevents a compromised
companion from operating if DCA is later removed.

### Controller-side node signature verification

The checks above are **node-side** (the companion/node verifies the controller)
or **same-key** (`protectionLevel="signature"`). They do not, by themselves,
stop the controller from binding or commanding a substituted APK that happens
to occupy a catalogued package name.

`core-security.NodeSignatureVerifier` is the **controller-side** pin: before
DCA binds or commands a node, it resolves the target package's installed
signing-certificate SHA-256 and compares it to the catalogued expected
value(s).

| Piece | Where | Android? |
|---|---|---|
| Comparison, catalog, typed allow/refuse | `core-security.NodeSignatureVerifier` / `NodeSignatureGuard` / `KnownNodeSigningCatalog` | No. JVM-tested. |
| PackageManager `GET_SIGNING_CERTIFICATES` | `:app` `AndroidSigningCertObserver`; duplicate in `core-companion` (that module is still excluded from the default build) | Thin adapter only. Returns cert **bytes**; SHA-256 stays in `core-security`. |
| Bind gate | `AndroidCompanionRegistry.bind` refuses to bind on `NodeSignatureDecision.Refused` | Yes. |

**Catalog.** `FleetMemberDescriptor.expectedSigningCertSha256` holds zero or
more fingerprints (debug + release is the intended shape). Empty means
`[Unknown]` — **do not invent a fingerprint**. The catalogue is **partial**;
fail-closed should be enabled only once it is complete. Victorious Flash
debug is catalogued and enforced (flash PR #18 merged). VictorLaunch
debug is catalogued and enforced (node PR #6 merged; previous
`1B:06:A8:…` pin revoked). Flash and VictorLaunch RELEASE certs are
TBD — do not invent.

Enforceable today (mismatch / missing-observed refuse):

- DroidCommand-AI debug `ai.droidcommand.app` `88:00:51:A6:…:C8:4D`
- Victorious Mesh debug `com.victorious.mesh` `F4:C9:25:B8:…:B9:F4` (8th wiring-order member, PLANNED catalog)
- Victorious Mesh **own** RELEASE `com.victorious.mesh` `76:E9:15:83:…:A4:0D` (not the shared fleet key; RSA 4096, alias `victoriousmeshrelease`, CI-secrets only)
- Victorious Browser debug `com.victorious.browser` `2D:5D:9E:8B:…:C3:E1` (9th wiring-order member, PLANNED catalog)
- Victorious Flash debug `com.victorious.flash.android` `2C:52:0F:7A:…:F6:ED` (12th wiring-order member, PLANNED catalog; flash PR #18 merged, enforced)
- VictorLaunch debug `com.victorious.launch` `B9:9A:AF:E2:…:4A:69` (13th wiring-order member, PLANNED catalog; node PR #6 merged, enforced; previous `1B:06:A8:…` pin revoked)
- Pentest-Swarm-AI debug `ai.pentestswarm.companion` `5E:3A:B1:23:…:30:C7`
- EDL-Commander debug `ai.edlcommander.companion` `E5:60:B6:92:…:58:C8`
- HackerAI-Commander debug `ai.hackerai.companion` `AF:1C:8F:C5:…:23:55`
- HackerAI-Commander RELEASE `9E:A7:B3:14:…:2D:8D` — firsthand verified (v0.1.0 APK signer in CI). **Only** member whose shared-fleet release cert is enforced.
- RootForge debug `ai.rootforge.os.debug` `77:7A:C7:00:…:64:6F` (no release key yet)
- Victorious Keyboard debug `com.victorious.keyboard` / `.debug` `47:1E:AD:6B:…:AF:4F` (`.bench` uncatalogued)
- VictorSuite / Termux debug `com.termux` `67:17:C5:5A:…:31:D3`
- VictorSuite **own** RELEASE `com.termux` `8C:BF:09:AA:…:19:80` (not the shared fleet key)

Planned / unverified (**not** enforced): DroidCommand-AI / Pentest / EDL
release `9E:A7:…` (`SHARED_FLEET_RELEASE_SHA256`); Victorious Browser
RELEASE (own key, NOT shared fleet, CI-only, cert TBD); Victorious Flash
RELEASE (TBD — do not invent); VictorLaunch RELEASE (TBD — do not invent).

Caveated / not enforced: File-Manager DEBUG `8C:1F:E3:C9:…:85:F2` is
`[unstable/CI-secrets]` (CI injects from secrets, else generic Android
debug key). Packages stay fail-open.

Pending / out of scope: RootForge release, Keyboard release
and `.bench`, File Manager release, KYC companion RELEASE (debug cert is
`[provisional/pending-stable-cert]` unenforced), Victorious Flash
RELEASE (debug pin is enforced), VictorLaunch RELEASE (debug pin is
enforced), BakerPi.

**Default for `[Unknown]` members: ALLOW.** Fail-closed
(`MissingExpectedCertPolicy.REFUSE`) is available
(`DROIDCOMMAND_NODE_CERT_MISSING_EXPECTED=refuse`) but must not be the
default until the catalogue is complete. When expected certs **are**
catalogued, mismatch and missing-observed always refuse — that path is
not configurable.

This **adds** a check. It does not replace DependencyGuard, signature
permissions, Kali/pentest authorization, or any other existing gate.

### Initiator mapping

All AIDL calls that arrive at DCA's tool executor are dispatched with
`Initiator.REMOTE`, triggering the full `SecureToolExecutor` policy/approval/
grant/audit path. Companion tools are never silently bypassed.

## AIDL Interface Specifications

### `IHackerAIService.aidl`

```aidl
package ai.droidcommand.companion;

interface IHackerAIService {
    // Start an agent task. Input: CreateAgentInput JSON.
    // Returns: {"ok":true,"taskId":"..."} or {"ok":false,"error":"..."}
    String runAgentTask(String inputJson);

    // Returns the full Strix skill catalog as a JSON array.
    // Each entry: {id, name, description, category, tags}
    String getSkillCatalog();

    // Validate a security finding candidate.
    // Input: SecurityFindingCandidate JSON.
    // Returns: SecurityValidationResult JSON.
    String validateFinding(String candidateJson);

    // Cancel a running agent task. Fire-and-forget.
    oneway void cancelTask(String taskId);

    // Returns "ok" | "busy" | "no_llm_provider" | "dca_not_installed"
    String healthCheck();
}
```

### `IPentestSwarmService.aidl`

```aidl
package ai.droidcommand.companion;

interface IPentestSwarmService {
    // Create and start a campaign. Input: CreateCampaignRequest JSON.
    // Returns: {"ok":true,"campaignId":"...","status":"..."} or error JSON.
    String startCampaign(String createCampaignRequestJson);

    // Stop a running campaign.
    // Returns: {"ok":true,"campaignId":"...","status":"..."} or error JSON.
    String stopCampaign(String campaignId);

    // Get current status of a campaign.
    // Returns: {"ok":true,"campaign":{...CampaignResponse}} or error JSON.
    String getCampaignStatus(String campaignId);

    // Get all findings for a campaign.
    // Returns: {"ok":true,"findings":[...FindingResponse]} or error JSON.
    String getFindings(String campaignId);

    // Run an exploit chain against a target.
    // scopeJson: JSON array of allowed host strings.
    // Returns: {"ok":true,"executionId":"...","status":"..."} or error JSON.
    String runChain(String chainId, String target, String scopeJson);

    // Run a playbook against a target.
    // variablesJson: JSON object {"varName":"value"}.
    // Returns: {"ok":true,"executionId":"...","status":"..."} or error JSON.
    String runPlaybook(String playbookId, String target, String scopeJson,
                       String variablesJson);

    // List available exploit chains.
    // Returns: {"ok":true,"chains":[...ChainSummary]} or error JSON.
    // Falls back to embedded ChainCatalog if swarm process is not running.
    String listChains();

    // List available playbooks.
    // Returns: {"ok":true,"playbooks":[...PlaybookSummary]} or error JSON.
    // Falls back to embedded PlaybookCatalog if swarm process is not running.
    String listPlaybooks();

    // Returns true if the Go pentestswarm process is running.
    boolean isSwarmReady();

    // Restart the swarm process. Fire-and-forget.
    oneway void restartSwarm();
}
```

### `IEdlCompanion.aidl` (package `ai.edlcommander.companion.aidl`)

Copied verbatim from EDL-Commander. Envelope parcelables, not JSON strings.
Bind with action `ai.edlcommander.companion.BIND`.

```aidl
package ai.edlcommander.companion.aidl;

interface IEdlCompanion {
    const int PROTOCOL_VERSION = 1;
    int getProtocolVersion();
    ResultEnvelope execute(in CommandEnvelope command);
}
```

### JSON transport rationale

All methods pass and return JSON strings rather than typed Parcelables. This
means schema changes (adding a field to `CampaignResponse`, renaming a key in
`FindingResponse`) do not require changes to the AIDL file, which would trigger
a Binder interface mismatch on the device. Both sides share the same
`@Serializable` data class definitions from `core-hackerai`/`core-pentest-swarm`,
so a schema change is a coordinated library update, not an AIDL renegotiation.

### Error JSON contract

All string-returning methods return valid JSON in one of two shapes:

```json
{"ok": true, ...method-specific fields...}
{"ok": false, "error": "human-readable message"}
```

Callers must check `ok` before accessing other fields. The `error` field is
always present when `ok` is false. Special characters in `error` values are
escaped (backslash and double-quote).

## Build and Publish Workflow

### Step 1: Build the JVM library modules

```bash
cd droidcommand-AI/
./gradlew :core-hackerai:build :core-pentest-swarm:build
```

Both are in `settings.gradle.kts` and build as part of the normal JVM build.

### Step 2: Publish to Maven Local

The companion APKs resolve these libraries from `mavenLocal()`:

```bash
cd droidcommand-AI/
./gradlew :core-hackerai:publishToMavenLocal :core-pentest-swarm:publishToMavenLocal
```

Versions: `0.1.0-SNAPSHOT` (see each module's `build.gradle.kts`).

### Step 3 (Pentest-Swarm only): Build the Go binary

```bash
cd Pentest-Swarm-AI/
make build-android-arm64     # → bin/pentestswarm-android-arm64
make build-android-x86_64    # → bin/pentestswarm-android-x86_64
cp bin/pentestswarm-android-arm64 android/app/src/main/res/raw/pentestswarm_arm64
cp bin/pentestswarm-android-x86_64 android/app/src/main/res/raw/pentestswarm_x86_64
```

These files are in `.gitignore` and must be present before the APK assembles.

### Step 4: Assemble the companion APK

```bash
# HackerAI companion
cd hackeraiETC/android/
./gradlew :app:assembleDebug

# Pentest-Swarm companion
cd Pentest-Swarm-AI/android/
./gradlew :app:assembleDebug
```

### Step 5: Sign and install both APKs with the same key

Both APKs and DCA itself must be signed with the same certificate for
`protectionLevel="signature"` to work. In development, use the same debug
keystore for all three. In production, use the same release keystore.

## AIDL Contract Sync Rule

The AIDL files in each companion repo are **copies** of the canonical files in
`core-companion/src/main/aidl/`. The canonical files are:

- `core-companion/src/main/aidl/ai/droidcommand/companion/ICompanionService.aidl`
- `core-companion/src/main/aidl/ai/droidcommand/companion/IHackerAIService.aidl`
- `core-companion/src/main/aidl/ai/droidcommand/companion/IPentestSwarmService.aidl`
- `core-companion/src/main/aidl/ai/droidcommand/companion/CompanionCapabilityParcel.aidl`
- `core-companion/src/main/aidl/ai/edlcommander/companion/aidl/IEdlCompanion.aidl`
- `core-companion/src/main/aidl/ai/edlcommander/companion/aidl/CommandEnvelope.aidl`
- `core-companion/src/main/aidl/ai/edlcommander/companion/aidl/ResultEnvelope.aidl`

EDL's AIDL package `ai.edlcommander.companion.aidl` is part of the wire
contract. Copy that directory from `Victorious93/EDL-Commander` verbatim;
do not re-home it under `ai.droidcommand.companion`.

When modifying any AIDL file, update all three locations atomically:

1. Edit `core-companion/src/main/aidl/...` (source of truth)
2. Copy to `hackeraiETC/android/app/src/main/aidl/...`
3. Copy to `Pentest-Swarm-AI/android/app/src/main/aidl/...`

A diverged AIDL file causes a `RemoteException: Binder interface mismatch`
at bind time, which is not caught at compile time.

## Capability Lifecycle

### Adding a new companion capability

1. Add the method to the relevant `.aidl` file (sync all three copies).
2. Add the `@Serializable` input/output types to `core-hackerai` or
   `core-pentest-swarm`.
3. Implement the method in the companion's bound service (`HackerAIBoundService`
   or `PentestSwarmBoundService`) — public delegate method + binder delegation.
4. Implement the DCA-side `Tool` in `core-hackerai.HackerAiTool` or add a new
   `SwarmTool.*` in `core-pentest-swarm`.
5. Register the tool in `cli/Main.kt`'s tool registry.
6. Add JVM tests (mockk) for the bound service method and the DCA tool.

### Detecting companion presence at runtime (DCA side)

`CompanionCapabilityHealthChecker` (in `core-companion`) calls `ping()` via
the bound service interface. It returns:
- `AVAILABLE` — companion installed + `ping()` succeeds
- `REQUIRES_EXTERNAL_SERVICE` — companion not installed
- `ERROR` — companion installed but not responding

`suggestedReverifyIntervalMs()` returns `30_000L`. The `CapabilityManager` in
`core-security` re-runs the check on this schedule.

## Testing Strategy

### JVM-only (runs without device)

All `core-hackerai` and `core-pentest-swarm` tests. Run with:

```bash
./gradlew :core-hackerai:test :core-pentest-swarm:test
```

These cover: JSON schema validation, catalog loading, doom loop detection,
runtime recovery, step budget gating, HTTP client URL construction and JSON
parsing (via `MockHttpTransport`).

Each companion APK's `src/test/` directory also has JVM tests (mockk +
Robolectric) that run without a device:

```bash
# In hackeraiETC/android/
./gradlew :app:test

# In Pentest-Swarm-AI/android/
./gradlew :app:test
```

### Requires Android device (deferred — NOT RUNTIME VERIFIED)

The following have never been tested on a real device and are correctly marked
as such in the audit doc:

- AIDL binding between DCA and a companion APK
- `protectionLevel="signature"` rejection of a third-party bind attempt
- Go binary extraction and execution on ARM64 hardware
- Per-session API key injection and validation end-to-end
- Termux `RUN_COMMAND` intent delivery
- Full end-to-end: DCA dispatches a `SwarmTool` → AIDL → companion → Go HTTP →
  findings returned → `SecureToolExecutor` audit log entry

All of these require two APKs signed with the same key installed on a real
(or emulated) Android device. This environment has no Android SDK and no device.

## Module Dependency Map

```
app (Android)
  ├── core-companion (Android, excluded from JVM build)
  │     └── core-agent, core-security
  ├── core-hackerai (JVM)
  │     └── core-agent, core-security, kotlinx-serialization
  └── core-pentest-swarm (JVM)
        └── core-agent, core-security, core-remote, kotlinx-serialization, yamlkt

hackeraiETC/android/ (Android, standalone)
  └── core-hackerai via mavenLocal()

Pentest-Swarm-AI/android/ (Android, standalone)
  └── core-pentest-swarm via mavenLocal()
```
