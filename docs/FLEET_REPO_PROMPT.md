# Prompt for fleet repos that are not in the DCA session

Paste the **master prompt** into a Claude Code session opened on the target repo, then append that repo's
**block**. Written 2026-11-21 from DCA's own docs (`docs/FLEET_CONTROL.md`, `COMPANION_PROTOCOL.md`); DCA could
not read these repos, so every "DCA assumes" line is something the repo session must verify, not a fact.

## Master prompt

```
You are working in ONE repo of the Victorious fleet. DroidCommand AI (DCA, Victorious93/droidcommand-AI) is the
hub. Its new "Connected apps" screen lists each fleet app with one state: Not installed / Installed, not paired /
Paired / Active. This repo is an EXTERNAL_NODE: independent product, own signing key, works with DCA removed.

Rules (non-negotiable):
- Read this repo first. Report what is actually built, what is stub, and what was never run on a device. Never
  claim LIVE/verified without a real run; say what you did and did not run.
- Never add DCA as a dependency, never share a signing key, never "install DCA first".
- External control is OFF by default. Caller identity = package + SHA-256 signing cert (Message.sendingUid, not
  Binder.getCallingUid). Allow only ai.droidcommand.app (and .debug). Errors: external_control_disabled /
  unauthorized_caller.
- Messenger bridge: action <package>.action.BRIDGE; request = Message what=1, Bundle key "request"; reply what=2,
  key "response"; JSON envelope protocol_major 1; reply fields include live (bool), mode, fleet_class
  "external_node", dca_required false.
- Destructive ops stay confirmation_required with an on-device confirm. Nothing auto-approves.
- Work on a branch, never push to main, no PR unless asked, no secrets committed, tests for every change.

Deliver, in order:
1. A short STATE report (built / stub / unverified) with file paths.
2. `<pkg>.capabilities.get` and `<pkg>.status` returning honest data (installed version, enabled, what ops are
   really implemented, last error). DCA's Connected apps row is built from these two ops only.
3. The default-off external-control switch + caller allowlist, if missing.
4. The repo-specific ops below, each marked live:true only when it really ran on a device.
5. docs/DROIDCOMMAND_INTEGRATION.md in this repo: action, ops, error categories, cert pins, what is verified.
Stop and ask before anything outside this list.
```

## Repo blocks

**File-Manager** (`ai.victorious.filemanager`, owner has run it on a device) — the identity/default-off PR if still
open; replace `filemanager.list` stub with a real, scoped listing; add `filemanager.transfer.*` (cloud upload /
download queue, status poll) so DCA's "Cloud file transfer" toggle becomes this app. Scope to owner-chosen roots; no
whole-storage access over the bridge; every transfer needs an on-device approve.

**VictorSMS** — decide and state whether it will own email as well as SMS (DCA assumes one "Messages" app).
Existing SMS remote-command source and bot-send path stay as they are. If email is added: IMAP/SMTP with OAuth or
app passwords stored in Keystore, read/send ops separate, send always asks on the phone, bots never read mail.

**victorious-mesh** (`com.victorious.mesh`) — implement `mesh.capabilities.get/status/up/down/ip.get`. This is the
planned transport for the PC agent: also report whether the node can reach a Tailscale/Headscale peer by name.

**Victorious-Browser** (`com.victorious.browser`) — add `browser.url.check{url}` returning a verdict
(safe/suspicious/blocked + reasons) by calling the security app if present, else local heuristics only. Never send
URLs to a third party without an owner switch.

**Victorious-Keyboard** — catalog only; never forward typed text or keystrokes. Only capabilities/status.

**VictorSuite** (already in the DCA session; skip).

**New repo: security monitor ("VictorShield", name TBD)** — Android first, PC agent second. Phone: installed-app and
permission audit (accessibility / device-admin / overlay abuse), APK hash reputation lookup, optional local DNS
filter via VpnService, URL checker. PC: orchestrate Defender/ClamAV and OS telemetry, no homemade AV. URL checks:
local heuristics (punycode, lookalike, redirect chain, domain age) + Google Safe Browsing; VirusTotal only behind an
owner switch because it shares the URL. Be explicit in the UI that it flags known-bad and suspicious items and
cannot guarantee detection. Expose ops `shield.capabilities.get`, `shield.status`, `shield.scan.apps`,
`shield.url.check`, `shield.findings.list`.

**rootforge-os, EDL-Commander, VictorLaunch, Flash** — only deliver steps 1, 2, 3 and 5 of the master prompt.
