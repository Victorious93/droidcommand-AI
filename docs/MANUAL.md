# DroidCommand AI — user manual

This is the user-facing copy also shown in-app (Help → Manual). The
engineering contract for remote control is `docs/specs/REMOTE_CONTROL.md`.

DroidCommand AI (DCA) is the **controller / brain** of the room fleet.
It chats, plans, runs tools, and authorizes remote actions on fleet
apps. Live wipe, power-off, lock, flash, bootloader, and RustDesk are
**not implemented** in this skeleton: those seams still prompt, then
return an honest "not implemented".

Honesty rule: if a screen says something is planned, stubbed, or not
wired, that is the real status.

---

## Chat and providers

Settings holds **BYOK** API keys (Anthropic, OpenAI, Google/Gemini, Groq)
in the on-device vault. Pick a provider and model on the chat screen.
Local GGUF models are a separate path (`ProviderType.LOCAL`) and are not
required for cloud chat.

Chat history, skills, and templates live in Library. A skill injects
system text; a template fills the input.

Document Q&A (RAG) attaches a text/PDF file to the conversation. The
embedding model is a separate download. Answers are grounded in attached
chunks when retrieval is enabled.

Web search is an optional, off-by-default toolbar toggle. It injects
search snippets as context; it is not a live browse of arbitrary sites
unless you configured a search key.

---

## Voice

Mic input transcribes into the chat box (on-device STT when available).
TTS can speak replies (system voices only — no voice cloning). Both
degrade to silent / no-crash if permission is denied.

**Local voice remote-control phrases** are configured under Settings →
Remote Control. Match is exact after normalize. Voice remote control is
a **local** trigger, not an inbound node command.

---

## Pilot, Forge, and tools

- **Pilot** runs one named tool with arguments.
- **Forge** runs a multi-step objective through the planner.

Tools include shell, root, Termux `RUN_COMMAND`, Shizuku, Metasploit,
Social-Engineer Toolkit, web search, and catalog stubs for fleet
members. Every non-read tool goes through `SecureToolExecutor` → an
on-device **ApprovalPrompt**.

### Shizuku

Shizuku / Sui privileged ADB-shell ops (`run_shizuku_command`,
`shizuku_read_device_state`, `shizuku_pm_grant`) never auto-run. Missing
or not-running Shizuku degrades to unprivileged use; it does not crash
or skip confirmation.

### YOLO

YOLO is an explicit opt-in. It may waive **non-destructive** shell /
Termux / Shizuku / root / remote_command the same way it waives ordinary
shell. It **cannot** waive:

- WIPE / POWER_OFF / LOCK / flash / bootloader (`remote_wipe`,
  `remote_power_off`, `remote_lock`, `remote_destructive`)
- Kali / Metasploit / SET / Pentest-Swarm authorization (the phrase
  **I confirm these targets are authorized** and per-step ACTIVE/EXPLOIT
  prompts)

A remote SMS or node trigger **never auto-approves**, even with YOLO.

---

## Fleet control

Tools → Fleet lists HackerAI plus the **eleven** `KnownFleet` members
(Pentest, RootForge, VictorSuite, EDL-Commander, KYC-AuditLab, File
Manager, Keyboard, Mesh, Browser, VictorSMS, VictorSpoof). None is LIVE.

EXTERNAL_NODE rows pair by **package + observed signing cert**. Pairing
is an explicit confirm. A trusted pairing can run read-only Messenger
`capabilities.get` / `status` on some nodes. This app does not auto-bind
companions and never uses DependencyGuard / a shared signing key on
EXTERNAL_NODE members.

Tools → Fleet Orchestrator is a stub overview of node reachability and
last command. Send command still asks first.

---

## Remote control (controller)

DCA is the **sole authorizer**. An inbound command from a fleet node is
an **UNTRUSTED REQUEST**.

VictorSMS (`com.victorious.sms`) is a known `remote.command`
EXTERNAL_NODE ([planned/pending-merge]). Its inbound op is
`sms.remote_command.forward`. It may check a sender whitelist **and** a
secret code word on **its** side. That is **not authorization** — it
only **delivers** the request. DCA owns only the commandId→action
mapping. `trigger.sourceNumber` / `matchedKeyword` / `rawExtras` are
informational. Catalogued SMS ops are isolated from destructive paths
(a forwarded wipe commandId is still classified independently). Debug
cert is documented (alias `androiddebugkey`) but **not pinned yet**
(fail-open); release cert TBD.

The controller then re-applies, in order:

1. Caller-id = package + cert pairing of the source node
2. External-control switch (**OFF by default**) on **both** node ops and
   the controller inbound surface
3. Envelope / replay check (`auth` object)
4. The controller's own policy gates (mapping exists, independent
   classification, ApprovalPrompt)

Destructive ops (wipe, power-off, lock, flash, bootloader) always stay
behind device-admin / root / Shizuku **and** explicit on-device
approval. A remote trigger **never auto-approves** and **never
auto-sends** on behalf of a remote caller.

Controller inbound action (named, not exported in this skeleton):
`ai.droidcommand.app.action.BRIDGE`, op `remote.command`.

### Ratified envelope

Anti-replay fields are nested under `auth` (not top-level):

`auth.nonce` (128-bit unique), `auth.issuedAt` (epoch ms), `auth.ttlMs`.

Plus `requestId` (the existing BridgeRequest `request_id`),
`targetNodeRef`, `actionRef`, `args`, advisory `destructive`.

`trigger.sourceNumber` / `matchedKeyword` / `rawExtras` are
informational only. A self-reported number cannot authorize anything.

Replay: seen-nonce cache. Duplicate nonce → `replay_detected`. Past
`issuedAt + ttlMs` or clock skew > 120s → `expired`.

### Generic command surface

Every fleet APK advertises:

- `capabilities.get` — protocol minor 1 + per-op descriptors
  `{ name, kind: read|action|destructive, remoteAllowed, requiresConfirm }`
- `macro.step` `{ op, params }`
- `macro.run` `{ steps: [{ op, params, onError, timeoutMs }], dryRun }`

The node runner dispatches **only** to its own advertised ops, sequentially.
`onError: abort` (atomic) stops on first error and reports **partial**
results — it does **not** roll back. `dryRun` validates params + gates
without executing.

### Four trigger types

1. **SMS** — VictorSMS delivers `sms.remote_command.forward` after its own gates.
2. **Voice** — exact phrase in Settings → Remote Control.
3. **Schedule** — stored expression only; live scheduler is a stub.
4. **Manual** — Run on a mapping or macro (still asks first).

### Macros bot

Settings → Remote Control → Macros names an ordered list of fleet
steps and binds one of the four triggers. Dry-run validates. Run still
opens the approval dialog, then the stub fails. Any independently
classified destructive step requires approval at run.

---

## Status tray

Settings → Remote Control → Status tray consumes **and** publishes last
health rows per node (mesh / VPN / browser / SMS / node health). Shared
with VictorLaunch. Exact schema/transport is still pending the Fleet
Orchestrator; this app uses a generic in-memory model. No live bind.

---

## Backup / restore

Settings → Remote Control → Local backup exports a **versioned JSON** of
this controller's **macros, mappings, and device pairings** (plus
targets and last status-tray rows) to a file on this device. API keys
and VictorSMS secrets are **not** included. The unified cross-app
backup schema is still pending the Fleet Orchestrator.

---

## VictorSpoof

VictorSpoof (`com.victorious.spoof`) is a known EXTERNAL_NODE (novelty /
prank caller-ID). It is a remote.command **target**, isolated from
wipe / power-off / lock / flash / bootloader. Signing certs are not
pinned yet (fail-open). TODO: pin later.

---

## Approval gates (summary)

| Action | Gate |
|---|---|
| Cloud chat | BYOK key in the vault |
| Shell / Termux / Shizuku / root | ApprovalPrompt; YOLO may waive |
| Kali / pentest / Metasploit / SET | Phrase + per-step prompts; YOLO cannot waive |
| Remote wipe / power-off / lock / flash / bootloader | Device-admin/root/Shizuku **and** ApprovalPrompt; remote never auto-approves; YOLO cannot waive |
| Remote non-destructive command | ApprovalPrompt; YOLO may waive like shell |
| Inbound node command | Pairing, then external-control OFF-default, then replay, then policy |

Help in the app is this manual. The live privileged buttons still fail
closed after they ask.
