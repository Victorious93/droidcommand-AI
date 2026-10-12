<p align="center">
  <img src="docs/images/logo.jpg" alt="DroidCommand AI logo" width="200">
</p>

# DroidCommand AI

DroidCommand AI (DCA) is an Android AI-agent platform built as the
flagship app and command center for a small fleet of independent
companion apps. It pairs an on-device chat UI with a conversational agent
that can plan work, call tools under a strict security policy, build and
deploy software, and orchestrate the capabilities of the other fleet apps
over well-defined local IPC / network boundaries — without folding those
apps into this codebase.

The project is organised as a multi-module Gradle build. Most of the
engine is **pure Kotlin/JVM** so it compiles, runs, and is tested without
an Android SDK or a device; the Android UI and the native/on-device pieces
are **opt-in modules** that require the Android toolchain. The sections
below describe what exists in the repository today, with an honest status
for anything not yet verified on real hardware.

> **Status in one line:** the JVM engine is implemented and tested; the
> Android debug APK builds (in CI and in configured build environments) but
> has **not** been launched on a device or emulator; live LLM calls, real
> device/root control, and the companion-APK bindings are gated on
> environments this repository's CI does not have.

For the authoritative, per-component status read
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) and the dated addenda in
[`docs/AUDIT_2026-09-05.md`](docs/AUDIT_2026-09-05.md). Session orientation
and the Consumer Product Roadmap live in [`CLAUDE.md`](CLAUDE.md).

## The fleet

DCA is the hub. Each companion is a **separate, independently licensed and
versioned** application with its own codebase and APK; DCA integrates their
capabilities rather than reimplementing them.

| App | Repo | How DCA reaches it |
|---|---|---|
| **HackerAI** | [`Victorious93/hackeraiETC`](https://github.com/Victorious93/hackeraiETC) | Agent orchestration / skill catalog / finding validation via an AIDL bound service (`IHackerAIService`). `core-hackerai` is a Kotlin port of its subagent-orchestration logic. |
| **Pentest-Swarm-AI** | [`Victorious93/Pentest-Swarm-AI`](https://github.com/Victorious93/Pentest-Swarm-AI) | REST client (`core-pentest-swarm`) to its Go server on loopback, and an AIDL bound service (`IPentestSwarmService`). Fork of the AGPL-3.0 `Armur-Ai/Pentest-Swarm-AI`. |
| **VictorSuite** | [`Victorious93/VictorSuite`](https://github.com/Victorious93/VictorSuite) | A Termux fork (keeps `applicationId com.termux` and the `RUN_COMMAND` contract); reached through `core-termux`'s public `com.termux.RUN_COMMAND` intent protocol — no DCA-specific module needed. |
| **RootForge OS** | [`Victorious93/rootforge-os`](https://github.com/Victorious93/rootforge-os) | An independent host OS queried over an SSH-carried JSON protocol (`core-rootforge`). **Passive reads only** today (capabilities, attached adb/fastboot devices); flashing/builds/jobs are deliberately absent. Not a companion APK and exempt from the AIDL model. |

The AIDL contracts are defined once in `core-companion/src/main/aidl/` and
copied into each companion repo; see
[`COMPANION_PROTOCOL.md`](COMPANION_PROTOCOL.md). `core-companion` and the
companion binding glue require the Android SDK and are excluded from the
default build.

## Repository layout

The default build includes the JVM modules. The Android modules are opt-in
behind `-PincludeAndroid=true` (they need an Android SDK, and some need the
NDK/CMake).

### JVM engine (default build)

- **Agent core** — `core-agent` (agent state machine, tool
  registry/executor, the `Planner` contract, the Forge objective loop, and
  `DroidCommandSession` coordinating Pilot and Forge modes), `core-llm`
  (provider-independent request/response/error types and the `LlmProvider`
  interface), `core-config` (config loading from a `ConfigSource`; secrets
  read fresh per call, never cached), `core-security`
  (`SecurityPolicyEnforcer` + `SecureToolExecutor` — allow / require-approval
  / deny with root and permission gating, fail-closed), `core-remote`
  (`JdkHttpTransport`: a real HTTP client with optional certificate pinning
  and mutual TLS), and `core-mcp` (Model Context Protocol tool server).
- **LLM providers** — `core-llm-anthropic`, `core-llm-openai`,
  `core-llm-google` (Gemini), `core-llm-groq`, and `core-llm-local` (a
  local-inference backend seam), composed config-first by
  `core-llm-factory`. Every provider is tested against a local mock server;
  **none has been exercised against a live API** in this repository's CI.
- **Build & deploy (Forge)** — `core-build` (workspace + pipeline),
  `core-build-local` (`LocalProcessBuildExecutor` for JVM/native/generic
  builds — refuses `ProjectType.ANDROID`), `core-build-remote` (delegates to
  a remote build server), `core-apk-lifecycle` (install → launch → logs →
  test pipeline; real executor is PLANNED, needs a device).
- **Execution targets** — `core-tools-android` (device-control tools over a
  `DeviceController` seam; only `NullDeviceController` exists), `core-shell`
  (real argument-vector subprocess execution, fail-closed allow-list),
  `core-root` (root-gated tool execution; `NullRootExecutor` only), and
  `core-termux` (the `com.termux.RUN_COMMAND` intent protocol).
- **Security tooling** — `core-hackerai`, `core-pentest-swarm`,
  `core-tools-metasploit`, `core-tools-setoolkit` (JVM clients/ports that
  route through `SecureToolExecutor`), and `core-rootforge`.
- **Product features** — `core-templates` (prompt templates / skills),
  `core-rag` (document retrieval), `core-websearch`, `core-voice`
  (STT/TTS logic), `core-billing` (subscription entitlement logic), and
  `core-prompt-regen` (a deterministic prompt optimizer).
- **Entrypoints & tests** — `cli` (a device-free entrypoint that dispatches
  Pilot instructions and Forge objectives against a real
  `DroidCommandSession`) and `core-integration-tests` (cross-module tests).

### Android modules (opt-in, `-PincludeAndroid=true`)

`app` (Jetpack Compose UI + Hilt), `core-conversations` (Room chat storage),
`core-knowledge-android` (Room knowledge graph), `core-templates-android`,
`core-rag-android`, `core-llm-local-android` (llama.cpp via JNI/CMake),
`core-voice-android`, and `core-voice-neural-android` (sherpa-onnx). These
hold complete source but are **gated on the Android toolchain**; the APK
has never run on a device.

## Building

### Prerequisites

- **JDK 21** (CI uses Temurin 21).
- For the Android APK only: an **Android SDK** (`platforms;android-36`,
  `build-tools;36.0.0`, `platform-tools`), the **NDK** (`ndk;28.2.13676358`,
  r28c) and **CMake** (`cmake;3.22.1`) — the NDK and CMake are needed
  because `core-llm-local-android` (llama.cpp) and
  `core-voice-neural-android` (sherpa-onnx) build native libraries for four
  ABIs.

A Gradle wrapper is checked in, so no separate Gradle install is required.

### JVM engine (no Android SDK needed)

```sh
# Build and run every JVM module's tests
./gradlew test --continue

# Kotlin lint
./gradlew ktlintCheck --continue
```

This is what CI runs on every push and pull request (`.github/workflows/ci.yml`).

### Android debug APK

The Android modules are off by default. Enable them with
`-PincludeAndroid=true` (or set `includeAndroid=true` in your user-level
`~/.gradle/gradle.properties`) and point Gradle at your SDK via
`ANDROID_HOME` / `local.properties`:

```sh
./gradlew :app:assembleDebug -PincludeAndroid=true
```

The output is `app/build/outputs/apk/debug/app-debug.apk` — package
`ai.droidcommand.app`, `versionName 0.1.0-phase0`, `minSdk 28`,
`compileSdk`/`targetSdk 36`. Because it bundles native llama.cpp and
sherpa-onnx libraries for four ABIs, the debug APK is large (on the order
of several hundred MB). The `.github/workflows/android-apk.yml` workflow
builds and uploads this APK on manual dispatch, but it is **debug-signed and
has never been launched on a device or emulator** — see the `app` status in
`docs/AUDIT_2026-09-05.md`.

### Running the CLI

```sh
./gradlew :cli:run --args="..."
```

The `cli` module drives Pilot instructions and Forge objectives against a
real `DroidCommandSession` without any device or Android SDK.

## Status and honesty conventions

This codebase grades every component honestly — IMPLEMENTED / PARTIAL /
STUB / PLANNED / CONFIGURATION-DEPENDENT — and every intentional non-real
implementation is self-documented as such in its own code
(`Mock*Executor`, `Null*Controller`, `Null*Executor`). A component is only
marked IMPLEMENTED once `./gradlew test` passes for it. Anything requiring a
real device, root, a live LLM credential, or on-device/emulator execution is
marked PLANNED or UNVERIFIED rather than faked.

`docs/AUDIT_2026-09-05.md` is the dated, append-only source of truth for
status; `docs/ARCHITECTURE.md` tracks the per-component design and status
table. Read those before assuming any capability above is complete.

## License

This repository does not currently ship a `LICENSE` file. The companion
apps are independently licensed (notably, Pentest-Swarm-AI derives from the
AGPL-3.0 `Armur-Ai/Pentest-Swarm-AI` and VictorSuite from Termux); DCA
integrates with them across process boundaries and does not vendor their
source.
