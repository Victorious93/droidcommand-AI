# DroidCommand AI — room fleet control map

DCA is the hub. This document maps Don's five room-fleet members onto
**existing** integration classes from `COMPANION_PROTOCOL.md` and
`docs/ROOTFORGE_INTEGRATION.md`. It does not invent a third protocol.

Honesty grades match the rest of this repo: **LIVE** is reserved for
runtime-verified control. Nothing in this table is LIVE.

Source of the catalog in code: `core-security.KnownFleet` /
`FleetMemberDescriptor`. Companion APKs that *do* use AIDL stay in
`core-companion.KnownCompanions`. Host/node/Termux members are **not**
added there.

HackerAI (`Victorious93/hackeraiETC`, package `ai.hackerai.companion`) is
already a bound companion and is **not** one of these five. It stays in
`COMPANION_PROTOCOL.md` / `KnownCompanions`.

## Integration classes (do not mix)

| Class | Security rules | Who |
|---|---|---|
| **BOUND_COMPANION** | AIDL bind + `protectionLevel="signature"` + companion `DependencyGuard` (companion stops without DCA). `Initiator.REMOTE`. | Pentest-Swarm-AI (HackerAI, already mapped) |
| **EXTERNAL_NODE** | Authenticated SSH-carried JSON (`core-rootforge`). **Never** DependencyGuard, AIDL, or "install DCA first". Removing DCA must not disable the node. | RootForge OS |
| **TERMUX_IPC** | Public `com.termux.RUN_COMMAND` via `core-termux`. Same Android package as Termux (`com.termux`). **Never** AIDL or DependencyGuard. | VictorSuite |
| **HOST_PROCESS_TOOL** | Local process / CLI behind a `Tool` + `SecureToolExecutor`. **Never** AIDL or DependencyGuard. Fail closed / dry-run until a real executor is pinned. | KYC-AuditLab, EDL-Commander |

## The five

| Member | Class | Repo / id | Package or binary | Status | What is actually wired | Next concrete code step |
|---|---|---|---|---|---|---|
| **RootForge OS** | EXTERNAL_NODE | `Victorious93/rootforge-os` (public) | SSH JSON bridge; no APK | **PARTIAL** | `core-rootforge`: passive `rootforge_capabilities` / `rootforge_list_devices`. CLI registers them only when `DROIDCOMMAND_CLI_ROOTFORGE_NODES_FILE` is set. | Keep the SSH path. Android `RootForgeTransport` is unbuilt. Do not add AIDL. Write/flash/build stay gated on RootForge's own document. |
| **VictorSuite** | TERMUX_IPC | `Victorious93/VictorSuite` (documented 2026-09-29b; **not publicly resolvable** on GitHub as of 2026-10-14) | `com.termux` | **PARTIAL** | `core-termux.AdbTermuxExecutor` + `run_termux_command` + `TerminalSelector`. CLI default is `NullTermuxExecutor` unless `DROIDCOMMAND_CLI_ADB=1`. App stays on the Null executor. | Do not add `core-victorsuite` or an AIDL companion. Next device-side work is exercising `RUN_COMMAND` on a real phone. VictorSuite-only UI (multi-distro, backup) needs new surface in that repo. |
| **KYC-AuditLab** | HOST_PROCESS_TOOL | unpublished — no public `Victorious93/KYC-AuditLab` as of 2026-10-14 | Python IdentityShield lab; no APK | **PLANNED** | Catalog + `kyc_auditlab_status` stub. Always `ToolResult.Failure`. Capability state `REQUIRES_EXTERNAL_SERVICE`. | Pin the repo/binary, then a process executor behind this status tool. Never AIDL. Do not log identity-document contents. |
| **EDL-Commander** | HOST_PROCESS_TOOL | unpublished — no public `Victorious93/EDL-Commander` as of 2026-10-14 | Rust Sahara/EDL CLI; no APK | **PLANNED** | Catalog + `edl_commander_status` stub. Status/dry-run only. Always `ToolResult.Failure`. No flash/write/restore tool is registered. | Pin the CLI, then a **dry-run-only** probe. Flash/write stay unexposed (would be `RiskTier.IRREVERSIBLE`) until an explicit owner decision. Never AIDL. |
| **Pentest-Swarm-AI** | BOUND_COMPANION | `Victorious93/Pentest-Swarm-AI` (public; AGPL-3.0 upstream lineage) | `ai.pentestswarm.companion` | **PARTIAL** | `core-pentest-swarm` REST client + `SwarmTool.*`. `core-companion` AIDL + `KnownCompanions.PENTESTSWARM`. Bind never runtime-verified. Not registered in `cli` today. | Same-certificate signed bind on a device. Do not move this to SSH or a host-process stub. |

## What this slice added in-repo

- `docs/FLEET_CONTROL.md` (this file) and a matching section in `COMPANION_PROTOCOL.md`.
- `core-security.KnownFleet` / `StaticFleetHealthChecker` / `PlannedHostProcessTool`.
- `cli list-fleet` plus the two planned status tools on `list-tools`.
- Tools screen: a read-only fleet card. No run buttons that pretend to execute.

## What this slice did **not** do

- No live SSH, AIDL bind, Termux intent, Python lab, or EDL binary was invoked.
- No new Gradle module, no new AIDL, no DependencyGuard on RootForge / VictorSuite / KYC / EDL.
- No claim that the `:app` fleet card has been shown on a device in this session.
