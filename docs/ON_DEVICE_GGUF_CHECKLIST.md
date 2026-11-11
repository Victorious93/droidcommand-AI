# On-device GGUF chat — device checklist (proof pending)

Copy-paste checklist for Don to run on a **real phone**. **No device run is
claimed here.** Every box is **pending hardware** until the result is
reported. What has been run, and where, is in
`docs/AUDIT_2026-09-05.md` (2026-11-11 addendum): the same Kotlin and native
code ran on a Linux host with the real llama.cpp, never on Android.

What a phone adds that the host run cannot show: the system file picker and
its `content://` streams, real storage and RAM limits, the low-memory
killer, phone CPU speed, the Compose screens, and airplane mode.

## Before you start

- Install the **arm64-v8a** debug APK (or the 4-ABI one). A build made for a
  different processor type has no on-device engine and Models says so.
- Have one small instruct model on the PC, single file, e.g. a 0.5–2 GB
  `Q4_K_M` `.gguf`. Not a `-00001-of-0000N` split, not an `mmproj` file.
- Copy it to the phone over USB (**wait for the copy to finish**) into
  `Download/`.

Record for each step: pass / fail, the exact message shown, phone model,
Android version, RAM, model file name and size.

## 1. Import through the file picker — pending hardware

1. Open **Models** → **Pick a .gguf from storage**.
2. Pick the file in the system picker.
3. Expect `Importing… N of M MB copied`, a **Cancel import** button, then
   `Imported <name>. Chat now runs on this device.`
4. Expect the model at the top of Models as the current model.

Pass: listed and selected. Note any RAM line ("tight", "may be killed").

## 2. Select, generate, stream — pending hardware

1. Open **Chat**. The header must read `On device · <name>` **without
   restarting the app**.
2. Send `Say hello in one sentence.`
3. Expect text to appear piece by piece, then stay in the chat.
4. Send three or four more messages, at least one of them several
   paragraphs long.

Pass: every message is answered and the app never closes. (A prompt over
512 tokens crashed the process before 2026-11-11; this step is the device
proof of that fix.) Note seconds to first text and rough speed.

## 3. Stop — pending hardware

1. Send `Write a 2000 word story.`
2. Tap **Stop** while text is appearing.
3. Expect: text stops at once, `Stopped.`, the message is back in the input
   box, and the half reply is **not** in the chat after reopening it.
4. Send a short message straight away.
5. Repeat, but tap **Stop** *before* any text appears (while the model is
   still reading a long prompt).

Pass: both stops return within about a second and the next message is
answered without a long wait. The phone should cool down / CPU drop after
Stop (the model really stopped).

## 4. Restart — pending hardware

1. Swipe the app away from recents (or `adb shell am force-stop`).
2. Open it again → **Chat**.
3. Expect `On device · <name>` and the earlier conversation.
4. Send a message.

Pass: answered, no re-import needed. The first reply after a restart is
slower: the file is re-checked (SHA-256) before it loads. Note the seconds.

## 5. Networking disabled — pending hardware

1. Remove every cloud API key in **Settings** (or use a fresh install with
   none).
2. Turn on **airplane mode** (Wi-Fi and mobile data off).
3. Force-stop and reopen the app.
4. Chat with the on-device model for several turns.
5. Turn the **web search** toggle on and send a message.
6. If you use them: attach a document, turn Memory on, run a helper bot.

Pass: every turn is answered. With web search on, the reply carries
`Web search is not used for on-device replies.` and nothing fails. A helper
bot answers with `On-device models cannot use tools…`. No "add an API key"
prompt blocks sending.

Optional, stronger: with networking **on**, watch the app's traffic
(`adb shell dumpsys netstats detail`, or a firewall app set to log) during
several on-device turns. Expect no connection from the app.

Not expected to work offline, by design: a `/claude …`-style slash command
(that message is sent to the named cloud provider on purpose), the catalog
and Hugging Face search, downloading the embedding model.

## 6. Errors — pending hardware

Each should show a message that says what to do, and never close the app.

| Do this | Expect |
|---|---|
| Pick a non-model file (a PDF) | `Pick a file whose name ends in .gguf.` |
| Pick an `mmproj-*.gguf` | `…vision projector (mmproj), not a chat model…` |
| Pick one part of a split model | `…one part of a model split into N files…` |
| Start an import, tap **Cancel import** | `Import cancelled. Nothing was kept.` Storage use back to before. |
| Start an import, force-stop the app mid-copy, reopen | Model not listed; storage use back to before. |
| Pick a file while it is still being copied from the PC | `The import was interrupted: only X of Y MB arrived…` or a load error naming an incomplete file — never a crash |
| Pick a model bigger than free storage | `Not enough free storage: the model is about … MB and … MB is free…` (or it is used in place when the path is readable) |
| Pick a model bigger than the phone's RAM | Imports with a RAM warning; sending shows `This model needs about … MB of RAM to run, more than this device's … MB…` and nothing loads |
| Delete the model's file behind the app's back (only possible for an in-place model in shared storage), reopen Chat, send | `The on-device model you selected is no longer on this device… Nothing was sent.` — the message must **not** be answered by a cloud provider |

## 7. Report back

- Phone model, Android version, total RAM (Settings → About).
- For each section: pass / fail and the message text.
- Seconds to first text and tokens-per-second feel for the model used.
- Whether the app was ever killed (it disappears with no error): that is the
  low-memory killer, and means the RAM estimate was too generous for that
  phone and model.

_Status: pending hardware. This repo has not run any of this on a phone._
