# Victorious Bridge (ShizukuV) integration

`Victorious93/ShizukuV` ships **Victorious Bridge** (`ai.victorious.bridge`), the local privileged-access service for
Victorious apps. DroidCommand AI keeps what it already owns: AI planning, approvals, audit, backend selection, remote
commands and fleet scheduling. The Bridge only executes approved, scoped operations on this device.

`core-shizuku/.../bridge` adds the DCA side. The existing upstream-Shizuku code in the same module is unchanged
(different package id, permission and wire protocol; see ShizukuV `docs/COMPATIBILITY.md`).

## What was added

- `BridgeContract` - a *copy* of the contract v1 (identity, operations, capabilities, error codes, backends). DCA does
  not depend on a ShizukuV checkout. `BridgeContractFixtureTest` checks it against `bridge-contract-v1.json`, the file
  ShizukuV's tests check its service against.
- `BridgeTransport` / `BridgeCall` - platform seam: `negotiate()` and `submit()` with cancel. The Android app implements
  it over ShizukuV's `:bridge-sdk`; JVM tests/CLI use fakes or `UnavailableBridgeTransport`.
- `BridgeTool` - one DCA `Tool` per operation (`victorious_bridge_<op>`). Register them behind `SecureToolExecutor`.

## Behaviour

- **Two independent gates.** DCA's owner approval / risk classification / audit run first; then the Bridge checks its
  own grant, expiry, identity and backend policy. Neither substitutes for the other.
- **Negotiation before submit.** Not running, wrong contract version, unsupported operation, or capability not held
  each fail with a specific message and send nothing.
- **Structured results.** Success carries `request_id`, `status`, `duration_ms`, `exec_mode` and `data`; failures keep the
  Bridge error category and request id; cancelled results say so (`REVOKED`, `EXPIRED`, `CANCELLED`...).
- **Cancellation.** A local timeout requests remote cancellation. Revocation in the Bridge cancels queued and running
  work and returns `CANCELLED/REVOKED`.
- **Remote tasks.** A remote task id travels as `remote_task_id` for correlation in the Bridge's activity log. It is
  never an authorization: a request with a task id and no local grant is refused (tested). The bridge stays local;
  remote requests reach it only through DCA's existing authenticated pipeline.
- **Backend.** `BridgeBackend` is a *requirement*, not a preference. If the service policy or actual mode does not
  match, the call fails; DCA never asks for "something stronger".
- **Shell is separate.** `victorious_bridge_shell_exec` needs the `shell.exec` capability, which no other grant implies.

## Status

| Item | State |
|---|---|
| Contract copy + fixture check, tool mapping, negotiation, errors, cancel, backend, correlation | **Implemented, JVM-tested (17 tests)** |
| Android `BridgeTransport` over the SDK, tool registration in `:app`, approval-UI wording | **Not written** (handoff below) |
| Any run against a real Bridge, device or emulator | **Not done** |

## Handoff for `:app`

1. Add ShizukuV `:bridge-sdk` (AAR) and its provider to the manifest (authority `${applicationId}.victoriousbridge`).
2. Implement `BridgeTransport`: `negotiate()` -> `VictoriousBridge.negotiate()` (map `heldCapabilities`,
   `supportedOperations`, `servicePolicy`); `submit()` -> `VictoriousBridge.submit(...)` returning a `BridgeCall`.
3. `VictoriousBridge.requestAccess(code, caps, roots)` from a settings screen; show the negotiated mode.
4. Register `BridgeTool.all(transport)` in the tool registry used by `SecureToolExecutor`.
