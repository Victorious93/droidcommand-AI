<p align="center">
  <img src="docs/images/logo.jpg" alt="DroidCommand AI logo" width="200">
</p>

# DroidCommand AI

DroidCommand AI (DCA) is an Android AI-agent platform: a flagship chat UI and command center for a small fleet of independent companion apps. The on-device agent can plan work, call tools under a strict security policy, drive build/deploy (“Forge”) workflows, and reach companion capabilities over local IPC / network boundaries — without folding those apps into this codebase.

The project is a multi-module Gradle build. Most of the engine is **pure Kotlin/JVM** (buildable and tested without an Android SDK). Android UI and native/on-device pieces are **opt-in** (`-PincludeAndroid=true`).

> **Status in one line:** the JVM engine is implemented and tested; the Android debug APK builds and has been launched on an API 28 x86_64 emulator (launch smoke + primary-screen captures); it has **not** been run on a physical device. Live LLM calls, real device/root/Termux/Metasploit/SET backends, and companion-APK bindings stay UNVERIFIED.

Authoritative status: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), [`docs/AUDIT_2026-09-05.md`](docs/AUDIT_2026-09-05.md). Session orientation / roadmap: [`CLAUDE.md`](CLAUDE.md).

## The fleet

DCA is the hub. Members are reached through the class they actually are — bound
companion APK, independent node, Termux IPC, or host process — not a single
AIDL bind. Honest status: **none of these is LIVE control**. Full map:
[`docs/FLEET_CONTROL.md`](docs/FLEET_CONTROL.md).

| Member | Repo | Class | How DCA reaches it | Status |
|---|---|---|---|---|
| **HackerAI** | [Victorious93/hackeraiETC](https://github.com/Victorious93/hackeraiETC) | Bound companion | AIDL `IHackerAIService`; `core-hackerai` | PARTIAL (not one of the six room-fleet items) |
| **Pentest-Swarm-AI** | [Victorious93/Pentest-Swarm-AI](https://github.com/Victorious93/Pentest-Swarm-AI) | Bound companion | One command-envelope AIDL (planned). Older REST/AIDL copies in this repo are not LIVE | PLANNED |
| **VictorSuite** | `Victorious93/VictorSuite` (documented; not publicly resolvable 2026-10-14) | Termux IPC | `com.termux` + `RUN_COMMAND` / plugin IPC; gated on their PR #15 | PARTIAL |
| **RootForge OS** | [Victorious93/rootforge-os](https://github.com/Victorious93/rootforge-os) | External node | Their main already has Messenger + JSON (`ai.rootforge.os.action.BRIDGE`). This repo speaks SSH fallback only. No DependencyGuard, no shared signing key | PARTIAL |
| **KYC-AuditLab** | unpublished as of 2026-10-14 | Host process | `kyc_auditlab_status` stub — localhost simulator; hub must not call `/scope` admin | PLANNED |
| **EDL-Commander** | `Victorious93/EDL-Commander` (APK in progress) | Bound companion | `edl_commander_status` stub. AIDL + same-signing when the APK lands. Owns Sahara/Firehose | PLANNED |
| **Victorious File Manager** | [Victorious93/File-Manager](https://github.com/Victorious93/File-Manager) | External node | `filemanager_status` stub. Their main already has Messenger `ai.victorious.filemanager.action.BRIDGE`. This repo does not bind it. No DependencyGuard, no shared signing key | PLANNED |

AIDL + `DependencyGuard` apply **only** to bound companions (HackerAI, Pentest, EDL). RootForge / File Manager / VictorSuite / KYC must not get them. See [`COMPANION_PROTOCOL.md`](COMPANION_PROTOCOL.md). Companion binding glue requires the Android SDK and is excluded from the default build.

## Features (what exists in-repo)

Honesty grades: IMPLEMENTED / PARTIAL / STUB / PLANNED — see audit docs. Highlights of modules that exist:

- **Agent core** — state machine, tool registry/executor, Planner, Forge objective loop, Pilot/Forge session (`core-agent`)
- **LLM providers** — Anthropic, OpenAI, Google Gemini, Groq, local seam; factory composition; mock-server tests (**no live API calls in CI**)
- **Security** — fail-closed `SecurityPolicyEnforcer` / `SecureToolExecutor` (allow / require-approval / deny; root & permission gating)
- **Build & deploy (Forge)** — workspace/pipeline, local process executor (refuses Android project type), remote build delegation, APK lifecycle pipeline (real device executor PLANNED)
- **Execution targets** — Android device-control tools (`DeviceController` seam; growing `AdbDeviceController` implementations), shell allow-list, root-gated tools (`NullRootExecutor` default), Termux `RUN_COMMAND`
- **Security tooling clients** — HackerAI, Pentest-Swarm, Metasploit/SET toolkit modules (route through secure executor)
- **Product features** — templates/skills, RAG, web search, voice STT/TTS logic, billing entitlements, prompt regen, conversations (Room), knowledge graph (Android), **on-device markdown notes vault** (`core-notes`)
- **CLI** — device-free Pilot/Forge entrypoint (`:cli`)

## Screenshots

Captured 2026-10-14 on an API 28 x86_64 emulator (AVD `dca_ui`, TCG — this host’s KVM hits `kvm_arch_vcpu_create`). Sources: instrumented `PrimaryScreensScreenshotTest` (production `MainActivity`) and `adb exec-out screencap` after tapping the real bottom bar. Reproduce with `./scripts/capture-app-screenshots.sh`. Tool backends are the existing `Null*` stubs — nothing here executed real root, Termux, Metasploit, or SET.

| Chat (Pilot) | Tool approval | Forge build run | Memory / knowledge | Settings / providers | Notes vault |
|---|---|---|---|---|---|
| <img src="docs/images/chat.png" alt="Chat screen with Mode 1 selected" width="160"> | *UNVERIFIED* — Confirm action dialog not captured. Pilot `run_root_command` was denied (`root is disabled for this session`) before a prompt. The Metasploit form did not reach Run Module via coordinate taps on TCG. | <img src="docs/images/forge.png" alt="Forge engine empty objective" width="160"><br>*Empty Forge UI.* A finished build run needs a live LLM — UNVERIFIED. | <img src="docs/images/memory.png" alt="Persistent Memory empty state" width="160"> | <img src="docs/images/settings.png" alt="Settings mode names and API keys" width="160"> | <img src="docs/images/notes.png" alt="Notes vault" width="160"> |

Additional screens from the same emulator session (not in the table above):

| Tools | Metasploit (Null backend) | Pilot engine | System Logs |
|---|---|---|---|
| <img src="docs/images/tools.png" alt="Tools launcher" width="160"> | <img src="docs/images/tools-metasploit.png" alt="Metasploit form, no program connected" width="160"> | <img src="docs/images/pilot.png" alt="Pilot engine" width="160"> | <img src="docs/images/logs.png" alt="System Logs empty state" width="160"> |

Still UNVERIFIED on emulator and device: live chat against a real provider, a finished Forge run, the Confirm-action dialog, Social-Engineer Toolkit, clipboards / automation / coding, voice, GGUF load, and any `Null*` backend executing for real.

## Requirements

**JVM engine**

- JDK **21** (CI: Temurin 21)

**Android APK** (`-PincludeAndroid=true`)

- Android SDK: `platforms;android-36`, `build-tools;36.0.0`, `platform-tools`
- NDK `ndk;28.2.13676358` (r28c) and CMake `cmake;3.22.1` (llama.cpp + sherpa-onnx native builds, four ABIs)

## Build

```sh
# JVM modules + tests (default CI)
./gradlew test --continue
./gradlew ktlintCheck --continue

# Android debug APK (opt-in)
./gradlew :app:assembleDebug -PincludeAndroid=true
# → app/build/outputs/apk/debug/app-debug.apk
#   package ai.droidcommand.app · versionName 0.1.0-phase0 · minSdk 28 · compile/target 36

# CLI
./gradlew :cli:run --args="..."
```

The debug APK bundles native llama.cpp and sherpa-onnx libraries and is large (hundreds of MB, all 4 ABIs). For a phone-sized build, dispatch `.github/workflows/android-apk.yml` with `abi=arm64-v8a` (or `./gradlew :app:assembleDebug -PincludeAndroid=true -PabiFilter=arm64-v8a`); that artifact is `app-arm64-debug-apk`. Both are **debug-signed**. An x86_64 build has been launched on an API 28 emulator (see Screenshots); it has not been installed on a physical device.

## Project structure

```text
core-agent/          Agent session, tools, planner, Forge/Pilot
core-llm*/           Provider-independent LLM stack + vendor modules
core-security/       Policy enforcement, secure tool execution
core-build*/         Forge build/deploy pipeline
core-tools-android/  Device-control tools (ADB seam)
core-termux/         Termux RUN_COMMAND protocol
core-companion/      AIDL contracts (Android opt-in)
core-*/              RAG, voice, templates, notes, root, rootforge, …
cli/                 Device-free entrypoint
app/                 Jetpack Compose UI + Hilt (Android opt-in)
docs/                Architecture, audit, security, integration plans
```

Default build = JVM modules. Android modules join with `-PincludeAndroid=true`.

## Status conventions

Every intentional non-real implementation is named as such (`Mock*`, `Null*`). A component is IMPLEMENTED only when `./gradlew test` passes for it. Device, root, live LLM credentials, and on-device execution stay PLANNED / UNVERIFIED rather than faked. Read `docs/AUDIT_2026-09-05.md` (addenda first) before assuming any capability is complete.

## Contributing

1. Keep status honesty: do not mark device-only work as IMPLEMENTED without evidence.
2. Prefer pure-Kotlin cores with tests; thin Android shells.
3. Security-sensitive paths (tool execution, secrets, logging) must stay fail-closed.
4. Companion protocol changes need coordinated copies in companion repos — see `COMPANION_PROTOCOL.md`.

## License

**This repository does not currently ship a `LICENSE` file.** Companion apps are independently licensed (notably Pentest-Swarm-AI from AGPL-3.0 upstream; VictorSuite from Termux/GPLv3). DCA integrates across process boundaries and does not vendor their source.

Until a license is chosen and added, treat redistribution/contribution terms as undefined — add an SPDX license file before a public release.
