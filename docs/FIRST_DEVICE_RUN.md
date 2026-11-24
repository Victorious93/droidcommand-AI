# First device run: VictorBots → droidcommand-AI → VictorSMS (Victorious Framework)

**Status: written by reading the code. Nothing here has been run.** Every label below was taken from the source of the three apps on
2026-11-24, but no phone, emulator or second app was available, so expect a step to differ. If it does, that difference is the most useful
thing you can send back (see "If a step fails").

**What a pass proves:** the three apps can be paired, droidcommand-AI can reach VictorSMS's archive through the owner's two gates, and a real
read goes all the way back to VictorBots. **What it does not prove:** sending, drafting, automatic replies, or live SMS recording. None of
those are part of this run, and **nothing in it sends a message**.

Builds needed (all debug-signed): droidcommand-AI `main`, VictorSMS `main` **including PR #28** (without it VictorSMS answers droidcommand-AI's
status check with "not implemented"), VictorBots `main` **including PR #14** (without it the screen has discovery but no "list" button).
APKs come from each repository's latest green run of its Android workflow (artifact names: droidcommand-AI `android-apk`, VictorSMS
`victorsms-debug-<abi>`, VictorBots `VictorBots-debug-apk`). Pick the ABI of the phone. VictorSMS does **not** need to be the default SMS app for this run.

## Steps (in this order; the order matters)

1. **VictorSMS: turn on External control.** Settings → *External control* (switch). Until it is on, VictorSMS refuses everything from droidcommand-AI
   and does not even list it as a pending app.
2. **VictorSMS: have one conversation to share.** Settings → *Saved conversations (import)* → *Import a conversation* → choose a small export (a CSV with
   Date, Sender, Body columns is enough; use a redacted or invented one). Save it. Open it and switch on **Let my assistant (droidcommand-AI) read this
   conversation**. (Off by default. It is the owner's second gate.)
3. **droidcommand-AI: pair VictorSMS.** Fleet tab → the VictorSMS row → *Pair* → confirm "Pair VictorSMS?" with **Pair**.
4. **droidcommand-AI: first call.** Advanced → *Connected apps* → **Check paired apps**. VictorSMS will refuse it (it does not know droidcommand-AI yet), which is expected,
   and it is what makes droidcommand-AI appear in VictorSMS.
5. **VictorSMS: approve droidcommand-AI.** Settings → *Connected apps* → the entry for `ai.droidcommand.app` → switch it **Approved**, then tick
   **Read-only: status and capabilities** and **Archive read: message text from saved conversations you switch on for the assistant (read-only)**.
   (Do not tick Send or Drafts for this run.)
6. **droidcommand-AI: check again.** Advanced → *Connected apps* → **Check paired apps**. VictorSMS should now be healthy (needs VictorSMS PR #28).
7. **droidcommand-AI: allow password pairing, then link VictorBots.** Settings → *Link by password* → switch **Allow password pairing** on → type a **New pairing password** →
   **Save password**. Then **Package name** `com.victorious.bots`, **Pairing password** (the same one) → **Link installed package**. The message under the form should say it was linked.
8. **droidcommand-AI: allow VictorBots to read.** Advanced → *Communications (VictorBots and VictorSMS)* → *Apps you allowed* → **Allow an app…** → VictorBots. The
   dialog starts with *read messages* and *read imported* ticked: leave it that way, tick nothing else, and allow it.
9. **VictorBots: check the link.** More → DroidCommand. It should say **Installed · signing certificate matches the pin**. If it says *Certificate not trusted*, tap
   **Show installed fingerprint**, confirm it is the droidcommand-AI you just installed, paste it into **Trusted DroidCommand certificate SHA-256**, and **Save pin**.
10. **VictorBots: discover.** **Check messaging connection**. Expected: "Connected. N of 27 messaging capabilities are available right now", with
    `list_conversations`, `read_conversation` and `search_messages` available, the send/call/draft ones listed as "VictorSMS does not provide this through the assistant
    connector.", and the VictorBots-owned ones as "not connected". Nothing is sent.
11. **VictorBots: the real read.** **List conversations shared with the assistant**. Expected: the conversation you switched on in step 2, with its message count.
    That answer travelled VictorBots → droidcommand-AI → VictorSMS and back.
12. **Prove the gates.** In VictorSMS switch the conversation **off** and tap the same button again: the list must be empty. Then untick **Archive read** and tap
    again: VictorBots must show a refusal that names the Archive read permission. Finally, in VictorSMS open the conversation: the latest requests list shows
    `ai.droidcommand.app`, the operation and the count, never the text.

## What each message means (and which step to fix)

| You see | Meaning | Fix |
|---|---|---|
| VictorBots: "Not connected: … certificate does not match" | the pin does not match the installed droidcommand-AI | step 9 |
| VictorBots: "droidcommand-AI has not allowed VictorBots yet … Connected apps" | VictorBots is not linked | step 7 |
| VictorBots: "has not allowed VictorBots to read messages … Allow an app" | no read grant | step 8 |
| discovery lists the read capabilities as "VictorSMS has External control switched off." | | step 1 |
| … "has not approved droidcommand-AI. Approve it in VictorSMS under Connected apps." | | steps 4 and 5 |
| … "has not granted droidcommand-AI the Archive read permission." | | step 5 |
| … "This VictorSMS version has no archive connector. Update VictorSMS." | an old VictorSMS build | install a build from `main` |
| the list is empty | nothing is switched on | step 2 |
| VictorBots: "did not reply in time … may have run" | droidcommand-AI accepted the request and nothing came back (a read, so safe to repeat) | check droidcommand-AI is running; retry |

## If a step fails, send back
The exact words on screen (a screenshot is best), the step number, and the phone's Android version. If you can attach a computer:
`adb logcat -d | grep -iE "droidcommand|victorsms|victorbots"`. None of the three is meant to log message text; if you see message text in a log, that is itself a bug to report.

## Undo, any time
VictorSMS: switch the conversation off, untick Archive read, switch External control off. droidcommand-AI: Communications → *Emergency stop*, or revoke VictorBots under *Apps you allowed*;
Settings → *Link by password* → **Revoke** next to `com.victorious.bots`. Nothing in this run writes to your SMS database or sends anything.

## Found while writing this (not fixed here)
- **The 27 `communications_*` agent tools were not registered (fixed 2026-11-24e, default OFF).** `CommunicationsTools.all(broker)` had no caller, so droidcommand-AI's own Chat/Plan/Pilot/Forge could not use
  them. Now Communications → *Let the assistant use these tools* offers five of them (the three archive reads, `generate_reply`, `summarize_conversation`) while it is on, and none while it is off; the other
  22 are never offered (see `COMPANION_COMMS.md`). Extra first-device check: with the switch **off**, ask Chat to list your conversations and confirm it has no such tool; turn it on, repeat, and confirm it
  can only read what VictorSMS shared. Not run on a device.
- `sms.list_threads` and `sms.read_thread` are authorized in VictorSMS and answer "not implemented" (reading text threads over the bridge is not built).
- `node_id` in VictorSMS's replies is an echo for matching a reply to its request, not an identity proof; identity is the signing certificate and the sender uid.
