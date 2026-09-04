# DroidForge AI

An Android AI-agent platform in early development. See
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the target architecture
and an honest, per-component status (IMPLEMENTED / PLANNED /
CONFIGURATION-DEPENDENT) — this README will be rewritten to describe the
finished product only once there is a finished product to describe.

## Current status

Only `core-agent` (the pure-Kotlin agent state machine, tool interface,
registry, and bounded-retry executor) is implemented and tested. Everything
else described in the architecture doc — the Android app shell, device
control tools, root execution, LLM integration, the Forge build/APK
pipeline — is not yet built.

```
./gradlew :core-agent:test
```
