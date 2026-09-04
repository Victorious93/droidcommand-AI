# DroidForge AI

An Android AI-agent platform in early development. See
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the target architecture
and an honest, per-component status (IMPLEMENTED / PLANNED /
CONFIGURATION-DEPENDENT) — this README will be rewritten to describe the
finished product only once there is a finished product to describe.

## Current status

Two pure-Kotlin/JVM modules are implemented and tested:

- `core-agent` — agent state machine, tool interface/registry/bounded-retry
  executor, conversation context, the `Planner` contract, and a bounded
  Forge objective loop (`ObjectiveEngine`).
- `core-llm` — provider-independent LLM request/response/error types, the
  `LlmProvider` interface, and `LlmPlanner` (a `Planner` implementation
  backed by an `LlmProvider`). No concrete provider (Anthropic, an
  OpenAI-compatible endpoint, a local model) is implemented yet — every test
  runs against a scripted fake provider, not a live model.

Everything else described in the architecture doc — the Android app shell,
device control tools, root execution, a real LLM provider, the Forge
build/APK pipeline — is not yet built.

```
./gradlew test
```
