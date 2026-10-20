# Remote Control — user manual

This is the user-facing copy also shown in-app (Help). The engineering
contract is `docs/specs/REMOTE_CONTROL.md`.

DroidCommand AI is the controller. It authorizes remote actions on fleet
apps. Live wipe, power-off, lock, and RustDesk are **not implemented** in
this skeleton.

## Triggers

- **SMS:** VictorSMS (`com.victorious.sms`) on the paired device.
  VictorSMS owns the sender whitelist, secret code word, and the
  external-control switch (OFF by default). After those gates, it
  forwards a frozen envelope over Messenger.
- **Voice:** an exact phrase you configure in Settings → Remote Control.
- **Macros:** an ordered list of fleet steps, bound to voice, SMS,
  schedule text, or Run.

## Frozen envelope

Fields are **top-level**. There is no nested `auth` object:

`requestId`, `targetNodeRef`, `actionRef`, `args`, `nonce`,
`timestampMs`, `ttlMs`, `destructive` (advisory only).

`requestId` is the existing BridgeRequest `request_id`. **Authorization
is package+cert (`sendingUid`), never a payload field.**
`trigger.sourceNumber` / `matchedKeyword` / `rawExtras` are
informational only. A self-reported number cannot authorize anything.

## Destructive actions

WIPE, POWER_OFF, and LOCK always prompt on this device
(`SecureToolExecutor` → `ApprovalPrompt`). A forged `destructive:false`
does not bypass that. YOLO may waive non-destructive remote commands the
same way it waives shell; it cannot waive destructive remote actions or
Kali/pentest authorization.

## VictorSpoof

Spoof actionRefs (`spoof.profile.apply` / `spoof.profile.clear` /
`spoof.status`) are listed in a registry **isolated from destructive
classification**. They never become wipe/power-off/lock. Package and
certs are not pinned yet.

## Local backup

Settings can export mappings, targets, macros, and the last status tray
to a file on this device. API keys and VictorSMS secrets are not
included.

## Status tray

Last consumed node status rows, published for display. No live bind.
