# Android-to-Android screen control — research and prototype status (2026-11-14)

**Status: EXPERIMENTAL, DISABLED BY DEFAULT, adapter I/O NOT IMPLEMENTED, no physical device validation (UNVERIFIED).**
What exists: `core-screen-adapter` — policy, limits and session-lifecycle logic with JVM tests. What does not: ADB client, server launch,
video decode, input injection, Android UI. Nothing here claims compatibility with any of the owner's phones.

## References compared (read from source, shallow clones of the default branches)
| | sasha0552/EasyControlNext | shiyunjin/EasyControlNext | Genymobile/scrcpy |
|---|---|---|---|
| Relationship | hard fork of daitj/Easycontrol ← mingzhixian/Easycontrol | same lineage; **newer** (1.1.2, 2026-03-30) | upstream of the scrcpy server idea |
| Licence | **GPL-3.0** (LICENSE file) | **GPL-3.0** | Apache-2.0 (not cloned here; from my knowledge, **unverified in this session**) |
| Shape | Android client app + `server` module run on the target through `app_process` over an in-app ADB client | identical layout; differs in `app/build.gradle` and `DisplayManager.java` only | desktop client + `scrcpy-server` |
| ADB | own implementation (`app/adb/*`, TCP + USB channels), SPAKE2 pairing-code (`spake2-android`), BouncyCastle, Conscrypt | same | adb binary |
| SDK | minSdk 21, targetSdk 34, compileSdk 36 (shiyunjin) | | |
| Behaviour the README discloses | sets `screen_off_timeout`, can change `wm size`, uses `dumpsys`, `monkey`, `am display move-stack` | same | |

## Decision
* **Do not copy or link EasyControlNext code into DroidCommand.** Both forks are GPL-3.0 and DroidCommand has no `LICENSE` file; `CLAUDE.md` already says
  GPL code needs an explicit owner decision. That decision has not been made, so nothing GPL was vendored.
* Smallest maintainable route, in order of preference (all need an owner call + a real device): (1) hand off to the separately installed EasyControlNext
  app as its own process, like the Termux/RustDesk hand-offs (no code shared; but it has no documented intent API — its `MyBroadcastReceiver` was not
  analysed); (2) a clean-room client against the scrcpy server protocol, if scrcpy's Apache-2.0 licence is confirmed; (3) accessibility/Shizuku paths DroidCommand already has.
* Behaviours deliberately excluded because they are what "can break the device" in the forks' README: no `wm size` change, no keep-awake change, no clipboard sync (all fixed `false`
  in `ScreenAdapterSettings`, no setter), no automatic grant of shell/root/files/settings.

## What `core-screen-adapter` guarantees (tested)
* Off by default; view and input are separate grants; input requires view; disabling clears both; grants cannot be set while disabled.
* `AdbDisclosure.TEXT`: states plainly that an ADB key is far broader than "view"/"touch" and that the app switches do **not** limit it.
* Stream limits derived from what the **target reported** (SDK ≥ 30 for pairing-code, encoder present, native size, refresh rate) plus hard ceilings; never asks the target to change resolution.
* Session lifecycle: every end reason (owner stop, disconnect, network loss, revoke, cancel, backgrounding, policy change) owes the full cleanup list (stop target server process, release decoder,
  release all input pointers, close connections, delete target temp files, forget session keys); the session is `Closed` only after each step is acknowledged; errors are `Failed`, never success;
  rotation does not end the session; live policy is rechecked per input event; the start requires the shared foreground gate (serialises with Pilot and fleet UI jobs).
* Key storage contract (`AdbKeyStore`): Keystore-wrapped, deleted on revoke — **interface only; the implementation is not written**.

## To finish (needs owner decisions and hardware)
Pick the route above; implement ADB pairing + launch + decode in `:app`; wire the foreground gate to the one used by Pilot and `core-fleet-worker`; test on two real phones (Android 11+) incl. rotation, backgrounding,
network loss, revoke mid-stream; only then consider enabling.
