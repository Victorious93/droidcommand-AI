# P0 / P1 hardware checklist (proof pending)

Copy-paste checklist for Don to run on a **real phone**.

This file is readiness copy only. **No device run is claimed here.** Every
box below is **pending hardware** until Don reports the result. Do not mark
any path LIVE from this document.

P0 = three live paths on one phone (BYOK AI, one device action, one fleet
round-trip). P1 first live Messenger edge = **RootForge OS**
(`ai.rootforge.os` / `ai.rootforge.os.debug`). **File Manager stays
PLANNED** — do not use it as the P1 proof.

Hub code already on `main` (not proven on a phone):

- BYOK Chat can call HTTPS when a vault key exists.
- Confirm-action dialog exists (`ApprovalHost`).
- Fleet Messenger path is live in code: `FleetModule` →
  `MessengerFleetTransport` → `PairedNodeGateway` (not
  `UnwiredMessengerTransport`). Hardware proof is still pending.

---

## (a) BYOK AI — pending hardware

1. Open **Settings**.
2. Enter a cloud API key (Anthropic, OpenAI, Google/Gemini, or Groq) into
   the vault.
3. Open **Chat**.
4. Send one message.
5. Expect a **real model reply** (not a local stub or an empty error).

Pass: a real reply arrives. Fail: missing key, network error, or no reply.

_Status: pending hardware. This repo has not run this on a phone._

---

## (b) One device action — pending hardware

Prefer the cheapest safe action.

1. Open **Settings → Advanced** (Automation keys / allowed executables).
2. Add `echo` to the shell allowlist (the list starts empty).
3. Add a shell key that runs `echo` (for example `echo hello`).
4. Tap **Run**. Confirm the approval dialog.
5. Expect the echo output.

Alternatives if `echo` is awkward on that build:

- **Termux / VictorSuite** `RUN_COMMAND` if `com.termux` is installed.
- **Shizuku** `shizuku_read_device_state` if Shizuku or Sui is installed
  and granted.

Do **not** use wipe, flash, lock, Kali, Metasploit, SET, or pentest for
this proof.

Pass: the approved action returns real device output. Fail: allowlist
empty, denied, missing APK, or crash.

_Status: pending hardware. This repo has not run this on a phone._

---

## (c) Fleet round-trip — RootForge (first P1) — pending hardware

1. Install RootForge OS **debug** (`ai.rootforge.os.debug`) or **release**
   (`ai.rootforge.os`).
2. Open **Tools → Fleet**.
3. Find **RootForge OS**. The screen says `P1 target: RootForge — Pair →
   Capabilities`.
4. Confirm the observed signing-cert SHA-256. Tap **Pair**.
5. Tap **Capabilities** (hub sends `rootforge.capabilities.get`).
6. Tap **Status** (hub sends `rootforge.status`).

Pass: Capabilities and Status return a real reply from the installed
RootForge APK. Fail: not installed, pairing refused, bind failed, or
timeout.

File Manager is **not** this step. It stays PLANNED.

_Status: pending hardware. This repo has not run this on a phone._

---

## After you run it

Tell the hub which of (a) / (b) / (c) passed or failed, with the exact
error if any. Do not mark any path LIVE in docs until that report exists.
