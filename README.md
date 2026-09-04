# DroidForge AI

An Android AI-agent platform in early development. See
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the target architecture
and an honest, per-component status (IMPLEMENTED / PARTIAL / PLANNED /
CONFIGURATION-DEPENDENT) — this README will be rewritten to describe the
finished product only once there is a finished product to describe.

## Current status

Four pure-Kotlin/JVM modules are implemented and tested:

- `core-agent` — agent state machine, tool interface/registry/bounded-retry
  executor, conversation context, the `Planner` contract, a bounded Forge
  objective loop (`ObjectiveEngine`), and `DroidForgeSession`, which
  coordinates Pilot Mode and Forge Mode over that shared infrastructure and
  rejects a mode switch attempted while a task is active.
- `core-llm` — provider-independent LLM request/response/error types, the
  `LlmProvider` interface, and `LlmPlanner` (a `Planner` implementation
  backed by an `LlmProvider`). No concrete provider (Anthropic, an
  OpenAI-compatible endpoint, a local model) is implemented yet — every test
  runs against a scripted fake provider, not a live model.
- `core-security` — `SecurityPolicy`/`SecurityPolicyEnforcer` decide whether
  a tool invocation is allowed, needs explicit approval, or is denied
  (root/permission requirements), and `SecureToolExecutor` enforces that
  decision before a tool ever runs: a denied tool is never invoked, no
  matter what an LLM or planner requested. Root/permission checks are
  injected functions, tested with fixtures — real on-device root detection
  and Android permission grants remain PLANNED (need `core-root` /
  `core-tools-android` and a real device).
- `core-config` — `LlmConfigLoader`/`SecurityPolicyLoader` build `core-llm`'s
  and `core-security`'s config objects from a `ConfigSource` (environment
  variables by default). No secret is ever held as a plain field: an LLM
  API key is read from the source fresh on every call, not captured at load
  time — verified by a test that changes the underlying value between two
  calls and checks each call sees the current one. Only in-process sources
  exist; a real Android-backed source (e.g. `EncryptedSharedPreferences`)
  remains PLANNED.

Everything else described in the architecture doc — the Android app shell,
device control tools, root execution, a real LLM provider, the Forge
build/APK pipeline — is not yet built.

```
./gradlew test
```
