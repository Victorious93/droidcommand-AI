# Voice follow-on scope: neural TTS, wake word, voice approvals

**Date:** 2026-10-09. **Status:** SCOPING, then BUILT 2026-10-09e/f — see §6 for what exists and what does not. The sections above are the original plan and are left as written.
**Follows:** Phase 4 (`core-voice`, `core-voice-android`; audit addendum 2026-10-09c), which
deliberately left out ElevenLabs cloud TTS, wake-word detection and voice approvals.
**Gate:** per the Consumer Roadmap rules, nothing here starts until the owner approves a slice.

## 0. Correction to the request's premise

The request asked for an open-source tool *from ElevenLabs* as the replacement. A web search
(2026-10-09) found no evidence that ElevenLabs has released an open-source TTS model or tool; results
covered only its commercial platform and client SDKs. **Do not plan around that.** What the request is
really after — a free, local, more efficient alternative to a paid cloud TTS — is available from
unrelated open-source projects, below.

## 1. Why this changes the earlier exclusion

The audit excluded ElevenLabs because it "would send reply text to a third party". A local neural
engine sends nothing off-device, so the privacy objection disappears and the capability can be offered
in the same `TextToSpeechEngine` seam (`core-voice/VoiceEngines.kt`) with no change to `VoiceController`.
The remaining costs are APK/model size, CPU/battery, and licensing (§2).

## 2. TTS engine options

| Option | What it is | Fit | Unverified / risk |
|---|---|---|---|
| **sherpa-onnx (k2-fsa) hosting Kokoro or Piper voices** — RECOMMENDED | One Apache-2.0 runtime (license confirmed from package-registry listings) that also does STT, VAD and keyword spotting; Kokoro-82M and Piper models run on Android through it (third-party app and 1.10.x Android demo seen in search results) | Single native dependency can cover TTS **and** wake word | Per-voice model licenses are separate from the runtime's and must each be checked. sherpa-onnx's espeak-ng phonemizer is, to my recollection, GPLv3 [Likely, not verified] — this repo has no `LICENSE` file, so confirm before shipping. Real-time factor on mid-range phones is unmeasured. |
| Piper standalone | Small, fast VITS voices | Lowest CPU cost | Upstream project moved/relicensed [Likely GPL-3.0 for the maintained fork — verify]; same espeak-ng question |
| Kokoro-82M | Higher quality, heavier | Best quality per MB | Larger and slower than Piper; verify real-time factor on-device |
| Keep system `TextToSpeech` | Already built | Zero size cost | Quality is whatever the vendor ships |

**Recommendation:** add sherpa-onnx as an *optional* engine behind `TextToSpeechEngine`, defaulting to
the system voice. Start with one Piper voice (smallest) and add Kokoro only if measured speed allows.
Do not call it "more efficient" than system TTS: system TTS will almost certainly use less CPU and
storage; the gain is voice quality and consistency, not efficiency [Likely].

## 3. Build plan (dependency-ordered)

**V1 — Engine seam, JVM only (buildable here).**
Add `TtsEngineChoice` (SYSTEM / NEURAL) and a selector in `core-voice`; extend `VoiceController` tests
for engine fallback (neural unavailable or model missing → system voice → silent, never a crash).
Define a `VoiceModelRepository` contract reusing the SHA-256-verified download pattern from
`core-llm-local.ModelRepository` (do not invent a second download strategy; Phase 2/5 note says model
downloads share one).

**V2 — Android neural TTS (needs Android SDK; ON DISK/UNVERIFIED until built).**
`SherpaOnnxTextToSpeech : TextToSpeechEngine` in a new dynamic-feature or companion-delivered module,
following the Phase 2 distribution decision (on-demand dynamic feature) so users who never enable it
download nothing. Audio via `AudioTrack` with streaming chunks; `stop()` must cancel mid-synthesis.
Settings: engine picker, voice picker, download/delete model.

**V3 — Wake word (own gate; always-on mic).**
`WakeWordDetector` seam in `core-voice` (JVM, fake-tested): emits `Detected` only; never starts an
action itself — it only triggers the existing `VoiceController.startListening`. Android impl via
sherpa-onnx keyword spotting [Android KWS support specifically NOT confirmed — check repo examples
first; fallback candidates are openWakeWord (Apache-2.0 [Likely]) or a commercial SDK needing a key,
which conflicts with the free requirement].
Requires a foreground service with a visible notification (Android 14+ microphone foreground-service
type), explicit opt-in, off by default, a persistent on-screen indicator, no audio retained or sent
anywhere, and pause on screen-off unless the user opts in. Battery cost is unmeasured.

**V4 — Voice approvals (own gate; security-sensitive).**
Design rule, same as OpenDroid's: the voice layer **cannot grant new authorization by itself**. Concretely:
- It may only answer a *currently pending* `ApprovalRequest` (`core-security/ApprovalFlow.kt`), matched
  by `requestId`; it cannot create grants or touch `GrantStore`.
- Allowed for `RiskTier.READ_ONLY` and `REVERSIBLE` only if the owner opts in; **always** deny-able by
  voice; `DESTRUCTIVE` and `IRREVERSIBLE` require on-screen confirmation, no exceptions.
- No speaker verification exists, so anyone in earshot — or audio played by the device's own TTS or
  another app — can speak "yes". Mitigations: never listen for approval while TTS is speaking, read
  back the exact operation and require a second distinct confirmation phrase, short timeout
  (`ApprovalRequest.timeoutMs` already maps to `TimedOut` → deny).
- Every voice decision goes through `AuditLog` tagged as voice-originated.
- Parser is strict (exact phrases), unrecognised input = deny/no-op, never fuzzy-match to "yes".

## 3a. Prebuilt-package rule (owner, 2026-10-09)

V2/V3 need native code this environment can't compile. Per the new standing rule in `CLAUDE.md`, consume
sherpa-onnx's **published prebuilt Android release artifacts** rather than building from source: pin an exact
version, record its SHA-256 and release URL, and check the licenses first (§2). A prebuilt
library still leaves the feature unverified until it runs on a device.

**Looked up 2026-10-09 (V2 start), from the official release page**
`https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8` (marked "Latest"; the page shows "10 Sep",
year not shown). Asset names and digests were read from the release's asset listing; the first row
was then re-verified by downloading it and running `sha256sum` (matched).

| Asset | Size | SHA-256 | Use |
|---|---|---|---|
| `sherpa-onnx-1.13.8.aar` | 47.8 MB | `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96` | **Candidate for V2.** Verified contents: `classes.jar` + `jni/{arm64-v8a,armeabi-v7a,x86,x86_64}/` with `libonnxruntime.so`, `libsherpa-onnx-{c-api,cxx-api,jni}.so` |
| `sherpa-onnx-static-link-onnxruntime-1.13.8.aar` | 36.9 MB | `b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471` | Alternative; not inspected |
| `sherpa-onnx-v1.13.8-android.tar.bz2` | 44 MB | `2ff63469a71cb6009aa2e3ed5f4a670f8abdcbe4bb9ffd23776afc792a6b4f44` | Raw jniLibs tarball; not inspected |
| `sherpa-onnx-1.13.8-rknn.aar` | 25.4 MB | `33489f8d…0fc876` | Rockchip NPU only — not relevant |
| `*-android-*-termux-{shared,static}.tar.bz2` | 15–165 MB | (on release page) | Termux CLI binaries — not relevant to an APK |

Not verified: the digests for every row except the first (read from the page, not re-hashed); the
asset list was truncated in the fetch, so other assets may exist; Maven Central coordinates were not
checked (the docs host was unreachable from this environment) — consuming the release AAR directly
is what was verified. License: the repo's `LICENSE` at tag v1.13.8 is Apache-2.0 (read directly).
Bundled `libonnxruntime.so` (ONNX Runtime is MIT upstream [Likely, not checked]) and every voice
model/espeak-ng data remain unchecked. The AAR is ~48 MB across four ABIs; an APK split to
arm64-v8a would carry roughly 32 MB of native libs (sum of the listed arm64 files) before models.

## 4. Honest limits

- This environment has no Android SDK, device, or model: V1 can be JVM-tested here; V2–V4 cannot be
  compiled or run here and must be recorded as ON DISK/UNVERIFIED, not IMPLEMENTED.
- No latency, battery, APK-size or voice-quality numbers exist yet. Every efficiency claim above is
  an expectation, not a measurement.
- Licenses (espeak-ng, each voice model) are unverified and gate shipping.

## 5. Owner decisions needed

1. Approve V1 (JVM seam) as the first slice? (Recommended — small, testable, no regret.)
2. Is a GPL-licensed component acceptable, or must the engine avoid espeak-ng? That could rule out Piper/Kokoro via sherpa-onnx.
3. Wake word and voice approvals: build at all, or keep out of scope? Voice approvals is the riskiest item and the easiest to drop.

## 6. What was built (2026-10-09e/f) — read this before the plan above

Owner approved V2 and then asked for all remaining Phase 4 voice work. Status per item, honestly:

| Item | State |
|---|---|
| V1 engine seam (`NeuralTextToSpeech`, `FallbackTextToSpeech`, `TtsEngineChoice`, `selectTts`) | **IMPLEMENTED — JVM-tested** (gradle) |
| `VoiceModelRepository` (SHA-256-verified, atomic, resumable; `HttpsFileDownloader`) | **IMPLEMENTED — JVM-tested** with a fake downloader; the real `HttpsFileDownloader` has never touched a server |
| V2 `SherpaOnnxSynthesizer`, `AudioTrackSink` | ON DISK, UNVERIFIED. Synthesizer **compiles against the real v1.13.8 `classes.jar`** (direct `kotlinc`, stub `AssetManager`); `AudioTrackSink` not compiled; nothing has run |
| V3 wake word: `WakeWordController`/`AudioWakeWordDetector` | **IMPLEMENTED — JVM-tested** |
| V3 `SherpaKeywordEngine`, `AudioRecordSource`, `WakeWordService` | ON DISK, UNVERIFIED. Keyword engine compiles against the real `classes.jar` (**answers §3 V3's open question: `KeywordSpotter` is in the Android AAR's Kotlin API**; that it works on a device is unverified). Service/recorder not compiled |
| V4 voice approvals: `VoiceApprovalProvider`, `VoiceApprovalParser`, `EngineVoiceApprovalIo` | **IMPLEMENTED — JVM-tested; NOT connected to the app's approval gate** (see below) |
| `VoiceSettings`, prefs store, Settings "Voice" section | ON DISK, UNBUILT (`:app` not compiled here); settings model is JVM-tested |

**Deliberately not done, with reasons**
- **`:app` is not linked to `core-voice-neural-android`.** That would add a ~48 MB native AAR (via a `flatDir` repo that `:app` would also need) to an app whose `:app:assembleDebug` last passed in an earlier session; I cannot build here to prove it still passes. `VoiceFeatures()` is all-false, so the Settings screen shows neural voice and wake word as unavailable and `normalized()` keeps them off. Linking is: add the dependency + `flatDir` in `:app`, pass the real engines, set the two flags.
- **Voice approvals are not wired into `SecureToolExecutor`.** `:app` approves through `ComposeApprovalPrompt` (a boolean `ApprovalPrompt`); `VoiceApprovalProvider` is an `ApprovalProvider`. Bridging them changes the security gate and needs a device to test. The Settings switches for it are persisted but inert and say so.
- **No voice/wake-word model catalog is registered.** Each entry needs a pinned URL + SHA-256 and a license check (espeak-ng/GPL question in §5 is still open). `VoiceModel.license` is mandatory so it cannot be skipped.
- **Kokoro / other model types** and **dynamic-feature packaging** are not started; the AAR ships as an ordinary library.
- Wake phrase is not user-typed: sherpa's keywords file must be pre-tokenized, so it ships with the keyword model.
- Android 12+ blocks starting a microphone foreground service from the background; `WakeWordService.start` only catches the failure.

**Bugs the tests found:** `NeuralTextToSpeech` reported a truncated utterance as completed when the sink refused audio (fixed; regression test `a_sink_that_refuses_audio_stops_synthesis`).


## 7. Update 2026-10-09g — the "left undone" items

Owner decisions: **no GPL** in the model catalog; voice approvals are **deny-by-voice, approve on screen**.
This session had a real Android SDK (installed from dl.google.com) but no device or emulator.

| Item | State now |
|---|---|
| `:app` linked to `core-voice-neural-android` | **DONE — `:app:assembleDebug` and `:app:lintDebug` pass.** Never run on a device. `VoiceRuntime` (cached SHA-256 check) feeds `VoiceFeatures`; neural voice and wake word are offered only when their model is installed |
| Voice approvals → app gate | **DONE, deny-only.** `VoiceApprovalPrompt` + a per-request, cancellable `ComposeApprovalPrompt`. 12 JVM tests. No real-microphone run |
| Model catalog | **DONE (no GPL):** wake word `kws-zipformer-gigaspeech-3.3m` and voice `tts-vits-ljs` (below) |
| Archive installs | **DONE, JVM-tested** (7 tests) + opt-in live test `VOICE_LIVE_CATALOG=1` passed (~19 s, both archives, real `HttpsFileDownloader`) |
| Kokoro | **DEFERRED.** Needs espeak-ng data or a lexicon route whose license is not cleared; one voice should be proven on a device first |
| Dynamic-feature packaging | **NOT STARTED, by decision.** Measured: universal debug APK 20.0 -> 149.8 MB; arm64 native libs ~31.6 MB. Needs a bundle build + device to design properly |

**Catalog provenance** (official sherpa-onnx GitHub release, fetched 2026-10-09; licenses are what each README declares, not audited, training-data terms not checked):

| Model | Archive | Size | Archive SHA-256 |
|---|---|---|---|
| KWS zipformer gigaspeech 3.3M (int8 used) | `releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2` | 17.6 MB | `f170013b...6561a` |
| VITS LJ Speech | `releases/download/tts-models/vits-ljs.tar.bz2` | 109 MB | `78f7df44...b31a` |

Per-file digests are in `VoiceModelCatalog.kt`. **Off-device check** (sherpa-onnx 1.13.8 Python wheel, same version as the AAR): the voice synthesized audio; the wake word detected 3 of 3 synthesized keyword phrases and 0 of 1 non-keyword phrase. Synthetic speech only — no real microphone, no real-world false-accept/reject rate. sherpa-onnx logged "Unknown token" for some lexicon characters, so a few sounds may be dropped.

**Still unverified:** everything on a device — TTS playback through `AudioTrack`, `AudioRecord` capture, the wake-word foreground service on Android 12+/14, `POST_NOTIFICATIONS` (not requested at runtime, so the notification may be hidden on 13+), SpeechRecognizer from a worker thread, latency, battery, voice quality. The wake phrases are the nine that ship with the model (e.g. "hello world", "hey siri"); a custom phrase needs its own tokenization.
