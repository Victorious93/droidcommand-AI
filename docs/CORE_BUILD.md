# core-build — Workspace & Build Pipeline

> **`core-build` currently provides the build/workspace abstraction and
> pipeline scaffolding. It does not claim to compile Android applications
> unless a real build executor and Android build environment are installed
> and configured.**

Everything in this module is pure Kotlin/JVM. It has no Android dependency
and does not spawn external processes to perform a build — see
[Executor abstraction](#executor-abstraction) for why, and Section 7 of
`docs/ARCHITECTURE.md` for the verified environment facts (no Android SDK,
no `adb`, no device/emulator) that make that constraint necessary rather
than merely cautious.

## Architecture

```
BuildRequest
      |
      v
BuildPipeline.execute()
      |
      +-- VALIDATE            (validateBuildRequest — shape only)
      |
      +-- CREATE_WORKSPACE    (WorkspaceManager.create, inside an
      |                        authorized root — SECURITY_DENIED if the
      |                        request's claimed roots don't intersect
      |                        the manager's actual authorized roots)
      |
      +-- PREPARE_SOURCE      (WorkspaceManager.importSource)
      |
      +-- RESOLVE_STRATEGY    (BuildEnvironmentDetector.checkAll against
      |                        request.environmentRequirements)
      |
      +-- EXECUTE             (BuildExecutor.execute — the only stage
      |                        that "does" anything; MockBuildExecutor
      |                        never performs a real build)
      |
      +-- COLLECT_ARTIFACTS   (emit ARTIFACT_FOUND for each artifact the
      |                        executor reported)
      |
      +-- VALIDATE_RESULT     (each artifact must: resolve inside the
      |                        workspace root, exist on disk, have its
      |                        declared size match its real size, and
      |                        stay under maxArtifactBytes)
      |
      v
BuildResult (Success | Failure)
```

Every stage has an explicit `BuildStage` value and, on failure, a
structured `BuildError` — a caller (today: tests; eventually: the Forge
agent) can always tell exactly where a build stopped and why, never just
"an exception happened somewhere."

### Domain model

- **`BuildRequest`** — `sourceLocation` (a `SourceLocation`, sealed;
  `LocalDirectory` is the only variant so far), `projectType`
  (`ProjectType`: `ANDROID`/`JVM`/`NATIVE`/`GENERIC`/`UNKNOWN`), `target`
  (`BuildTarget`: `DEBUG`/`RELEASE`/`TEST`/`PACKAGE`/`ARTIFACT` — a generic
  classification; a future Android executor maps `(ANDROID, RELEASE)` to
  the Gradle task `assembleRelease` without `BuildTarget` itself growing
  an Android-specific value), `requestedArtifactTypes`, `signingRequired`,
  `testRequired`, `environmentRequirements` (a `Set<EnvironmentTool>`),
  `securityConstraints` (`BuildSecurityPolicy`), `timeout`, `metadata`.
  `validateBuildRequest` checks the request's *shape* only (blank source
  path, non-positive timeout, non-positive `maxArtifactBytes`) — whether
  it's *authorized* to run at all, or to use a particular workspace root,
  is a distinct concern with its own error code (`SECURITY_DENIED`, not
  `INVALID_REQUEST`).
- **`Workspace`** (`WorkspaceHandle` + `WorkspaceState`) — a real
  filesystem directory with an explicit lifecycle:
  `CREATED → PREPARING → READY → BUILDING → COMPLETED/FAILED → CLEANING →
  CLEANED`. `WorkspaceHandle.transition` rejects any transition outside
  that map — the same "an illegal transition throws rather than silently
  succeeding" mechanism `core-agent.AgentStateMachine` already established
  for agent task lifecycle, applied here to workspace lifecycle instead.
- **`BuildPipeline`** — the orchestrator described above.
- **`BuildExecutor`** — see [below](#executor-abstraction).
- **`BuildEnvironmentDetector`** — see
  [Environment model](#environment-model).
- **`BuildResult`** — `Success` (buildId, workspaceId, projectType,
  target, durationMillis, artifacts, logs, warnings, metadata) or
  `Failure` (buildId, failedStage, structured `BuildError`, logs,
  diagnostics, exitStatus). Never a bare exception.
- **`Artifact`** — artifactId, buildId, `ArtifactType`
  (`APK`/`AAB`/`JAR`/`ZIP`/`TAR`/`BINARY`/`REPORT`/`LOG`), path, fileName,
  sizeBytes, checksumSha256, mimeType, createdAt, metadata. Android is one
  possible artifact type among several — nothing in the core layer
  requires APK support.
- **`BuildEvent`** / **`BuildEventSink`** — structured, typed log events
  (`BUILD_CREATED` … `WORKSPACE_CLEANED`). No persistence layer exists
  anywhere in this repository, so this is intentionally just a sink
  interface: `InMemoryBuildEventSink` (tests, or any in-process consumer)
  is the only implementation. A future UI, remote client, or build-history
  store consumes events by implementing `BuildEventSink`, not by this
  module growing a database.

## Workspace security

`core-build` never trusts a caller-supplied path directly. Every path
operation goes through `WorkspacePathValidator.resolve(root, relativePath)`,
which:

1. Requires `root` to be inside one of the constructor-supplied
   `authorizedRoots` (a hard `require`, not a soft check).
2. Resolves `relativePath` against `root` and normalizes the result.
3. Rejects the result with `PathSecurityViolation` if it does not start
   with the normalized `root` — this catches both `../` traversal and an
   absolute-path escape (`Path.resolve` returns an absolute argument
   unchanged, ignoring the base, so `root.resolve("/etc/passwd")` produces
   `/etc/passwd`, which then fails the `startsWith` check).

`WorkspaceManager.clean` re-derives this guarantee independently at
delete time: every path visited during recursive cleanup is re-checked
against the workspace root immediately before deletion, so a symlink or
any other filesystem trick that might have appeared after workspace
creation still cannot cause a delete outside the workspace.

**Artifact path containment** — this was found and fixed during this
phase's own testing, not merely designed defensively: the first version
of `BuildPipeline`'s `VALIDATE_RESULT` stage checked that a claimed
artifact existed and matched its declared size, but never checked the
path was actually *inside* the workspace. A test that placed a
real, correctly-sized file directly under the top-level authorized root
(outside the workspace subdirectory the pipeline itself created) exposed
that a buggy or malicious `BuildExecutor` could report an artifact
pointing anywhere on disk — `/etc/passwd`, given a matching size — and it
would have been accepted. `VALIDATE_RESULT` now requires every artifact
path to resolve inside the workspace root before any other check runs;
`BuildPipelineTest`'s "an artifact outside the workspace root is
rejected..." test is the regression test for this.

Real filesystem behavior is exercised directly, not mocked:
`WorkspacePathValidatorTest` and `WorkspaceManagerTest` run against real
`java.nio.file` temporary directories, including a "clean never touches
files outside the workspace root" test that creates a sibling file next
to a workspace, cleans the workspace, and asserts the sibling survives.

### Tool-level authorization (integration with core-security)

`core-build` does not reimplement approval logic. `BuildTool` (a
`core-agent.Tool`) exposes the pipeline to the agent with
`SecurityLevel.SENSITIVE`, and is meant to be run through
`core-security`'s existing `SecureToolExecutor`/`SecurityPolicyEnforcer` —
exactly like any other sensitive tool. `BuildToolSecureExecutorIntegrationTest`
proves this for real: with an `ApprovalPrompt` that denies, `BuildPipeline`
never runs and no workspace directory is created (checked on disk, not
inferred); with one that approves, the pipeline runs through
`MockBuildExecutor` and a workspace really is created. This is the
"Agent → Tool → Build Service → Pipeline → Executor" chain the design
calls for, not "Agent directly executes Gradle."

`BuildSecurityPolicy` (per-request: `allowedWorkspaceRoots`,
`networkAccessAllowed`, `maxExecutionTimeMillis`, `maxOutputBytes`,
`maxArtifactBytes`, `maxWorkspaceBytes`, `allowedEnvironmentVariables`) is
a distinct, narrower concern from `core-security.SecurityPolicy`: the
latter decides whether running a build *at all* is authorized (tool-level
approval); the former constrains what an already-authorized build may do.
`BuildPipeline` enforces `allowedWorkspaceRoots` (intersected against
`WorkspaceManager`'s actual authorized roots — `SECURITY_DENIED` if empty)
and `maxArtifactBytes` (checked against each artifact's real, on-disk
size, not merely its self-reported one).

## Executor abstraction

```kotlin
interface BuildExecutor {
    fun execute(context: BuildContext, isCancelled: () -> Boolean = { false }): BuildExecutionResult
}
```

`core-build` itself never spawns a process or invokes a compiler — every
line of `BuildPipeline` operates on `WorkspaceManager`, `BuildEnvironmentDetector`,
and this interface, never on `ProcessBuilder` or a shell. That is what
keeps the core layer usable in a headless environment with no Android SDK,
JDK build toolchain, or device: nothing here needs one to compile, test,
or run.

`MockBuildExecutor` is the only implementation in this phase. Its default
outcome is `Success` with **zero artifacts** and an output string that
says explicitly `"MockBuildExecutor: no real build was performed. This is
a scaffold result."` — this was a deliberate choice to make "do not fake a
successful build" true in the default case, not just in the class doc
comment. Tests script a specific `outcome` lambda to exercise pipeline
behavior (a failure, a set of fake artifacts placed inside the real
workspace for validation testing) without that constituting a claim that
compilation happened.

Future implementations (none exist in this repository):

- **`AndroidGradleBuildExecutor`** — invokes the Android Gradle Plugin.
  Needs: Android SDK, Android Build Tools, JDK, Gradle. None are
  available in this environment (see [Environment](#environment-model)).
- **`LocalProcessBuildExecutor`** — runs an arbitrary local build command
  under `BuildSecurityPolicy`'s constraints (allowed commands, allowed
  environment variables, output size cap, timeout).
- **`RemoteBuildExecutor`** — delegates to a build server over
  `core-remote`'s `RemoteClient`, consuming the *same* `BuildContext` /
  `BuildExecutionResult` contract every other executor does. This module
  does not depend on `core-remote` yet — nothing in it needs to — but the
  contract is deliberately shaped so a future `RemoteBuildExecutor` slots
  in without changing `BuildPipeline`, `BuildRequest`, or `BuildTarget`.
  The agent, and the pipeline itself, never need to know whether a build
  ran locally or remotely.

## Environment model

`EnvironmentTool` enumerates `JDK`, `GRADLE`, `ANDROID_SDK`,
`ANDROID_BUILD_TOOLS`, `ADB`, `NDK`, `GIT`, `SIGNING_TOOLS`.
`ToolAvailability` is `AVAILABLE` / `UNAVAILABLE` / `NOT_TESTED` — a tool
this repository has no way to check is reported as `NOT_TESTED`, never
silently treated as available.

`SystemBuildEnvironmentDetector` is a real, working implementation, not a
stub:

- **JDK** — always `AVAILABLE`; the detail is `System.getProperty("java.version")`,
  since this code is already running on a JVM.
- **`ANDROID_SDK` / `ANDROID_BUILD_TOOLS` / `ADB` / `NDK`** — environment
  variable (`ANDROID_HOME` / `ANDROID_SDK_ROOT` / `ANDROID_NDK_HOME`) plus
  a real filesystem existence check. No process is spawned.
- **`SIGNING_TOOLS`** — checks for `keytool` under `java.home` (ships with
  every JDK). `apksigner`/`zipalign` — Android-build-tools-specific
  signing utilities — are covered separately by `ANDROID_BUILD_TOOLS`.
- **`GRADLE` / `GIT`** — `PathExecutableDetector` scans every directory in
  `PATH` for a file with that name that is both a regular file and
  executable. This is a pure filesystem check (`File.isFile`/
  `File.canExecute()`) — **no process is spawned to detect a tool**,
  deliberately, per the master prompt's "do not introduce unrestricted
  shell execution": there is no `which`, no `command -v`, no
  `ProcessBuilder` anywhere in this detector.

`env`, `fileExists`, `javaHome`, `javaVersion`, and the `PathExecutableDetector`'s
`pathEnv` are all injectable, so every test in this module runs against a
fixture — none of `SystemBuildEnvironmentDetectorTest` or
`PathExecutableDetectorTest` depends on or reads this sandbox's real
environment. See [Environment (this phase)](#environment-this-phase) below
for what this detector actually reports when run for real, in this
sandbox, with its default (production) constructor arguments.

## Dry-run mode

`DryRunPlanner.plan(request)` produces a `BuildPlan` — planned workspace
root, the seven pipeline steps, environment check results, expected
artifact types, and the security policy in effect — **without creating a
workspace, importing source, or invoking a `BuildExecutor`**. The planned
workspace root is computed by pure path arithmetic
(`authorizedRoot.resolve("<dry-run-not-created>")`) and is never passed to
`Files.createDirectories` or anything else that touches disk.
`DryRunPlannerTest`'s "performs no filesystem mutation" test asserts this
directly: it counts entries under the authorized root before and after
calling `plan()` and requires them to be equal. `plan()` still runs
`validateBuildRequest` and throws `InvalidBuildRequest` for a shape-invalid
request, rather than silently producing a plan for something that could
never actually build.

## Cancellation & timeout

`BuildPipeline.execute` takes `isCancelled: () -> Boolean` (the same
injected-predicate pattern `core-agent.ToolExecutor` and `ObjectiveEngine`
already use) and composes it with a wall-clock deadline computed from
`request.timeout` and an injectable `clock: () -> Instant`:

```kotlin
val deadline = startedAt.plus(request.timeout)
fun timedOut() = clock().isAfter(deadline)
fun effectivelyCancelled() = isCancelled() || timedOut()
```

`effectivelyCancelled()` is checked before every stage transition, and the
same combined check is threaded into `BuildExecutor.execute` as its own
`isCancelled`. Which of `TIMEOUT` or `CANCELLED` gets reported is
disambiguated at the point of termination, not conflated: `timedOut()` is
re-checked independently, so a caller-requested cancellation and a
wall-clock deadline are always distinguishable in the resulting
`BuildError`. A workspace already created by the time cancellation or
timeout is detected is always cleaned up (best-effort) before the pipeline
returns — `BuildPipelineTest` covers cancellation before workspace
creation (no workspace ever exists), cancellation mid-pipeline (the
workspace that was created is removed), and a simulated timeout via a fake
clock (reported as `TIMEOUT`, distinct from `CANCELLED`) without needing
any real `Thread.sleep`.

## Testing

- **`core-build` tests: 72**, all passing (`./gradlew :core-build:test`).
- **Integration tests: 3** — `BuildToolSecureExecutorIntegrationTest`
  (`core-build` + `core-security` + `core-agent`'s `Tool` contract) plus
  `BuildConfigLoaderTest`'s coverage of `core-build` + `core-config`.
- **Full repository: 162 tests, 0 failures, 0 errors**
  (`./gradlew test`, all seven modules).

Two real defects were caught during this phase's own test-writing, not
merely avoided by design:

1. **Workspace state-machine gap** — a workspace cancelled immediately
   after creation (state `CREATED`) had no legal transition to `CLEANING`
   in the original transition map, so cleaning it up would have thrown
   `IllegalWorkspaceTransition` instead of actually cleaning up. Fixed by
   adding `CREATED → CLEANING` to the allowed-transitions map;
   `WorkspaceManagerTest`'s "a workspace cancelled right after creation
   can be cleaned directly" test is the regression test.
2. **Artifact path containment** — described above, under
   [Workspace security](#workspace-security).

## Environment (this phase)

Verified in this sandbox, using the same real filesystem/environment
checks `SystemBuildEnvironmentDetector` performs (not `SystemBuildEnvironmentDetector`
itself run against production defaults inside a committed test — those
stay fixture-based — but the equivalent direct shell checks, for this
report):

| Tool | Status | Detail |
|---|---|---|
| JDK | **AVAILABLE** | OpenJDK 21.0.10 |
| Gradle | **AVAILABLE** | `gradle` 8.14.3 on `PATH` |
| Git | **AVAILABLE** | git 2.43.0 |
| Signing tools (`keytool`) | **AVAILABLE** | ships with the JDK |
| Android SDK | **UNAVAILABLE** | `ANDROID_HOME`/`ANDROID_SDK_ROOT` unset |
| Android Build Tools | **UNAVAILABLE** | depends on Android SDK |
| ADB | **UNAVAILABLE** | depends on Android SDK |
| NDK | **UNAVAILABLE** | depends on Android SDK |
| Physical/emulated Android device | **UNAVAILABLE** | none attached; no emulator installed |

This is a session fact, not a permanent project constraint — a developer
machine or CI runner with the Android SDK and a connected/emulated device
removes most of these rows. It's recorded here, as it was after Phase 1,
so "UNAVAILABLE" above is auditable rather than asserted.

## Future Android integration

What becomes possible once an Android SDK, JDK, Gradle, Android Build
Tools, and a device/emulator exist in the environment, **without
redesigning anything in this module**:

- An `AndroidGradleBuildExecutor : BuildExecutor` — maps
  `(ProjectType.ANDROID, BuildTarget.RELEASE)` to `assembleRelease`,
  `(ANDROID, PACKAGE)` to `bundleRelease`, `(ANDROID, TEST)` to
  `connectedAndroidTest`, etc. `BuildPipeline`, `BuildRequest`, and
  `BuildTarget` do not change.
- A `checkAll`-driven pre-flight using the real (production) constructor
  of `SystemBuildEnvironmentDetector` — already implemented and correct,
  simply unexercised against a real SDK in this sandbox.
- Real artifact types (`ArtifactType.APK`/`AAB`) flowing through the
  existing `Artifact` model with no schema change.
- Real signing, once `EnvironmentTool.SIGNING_TOOLS`/`ANDROID_BUILD_TOOLS`
  report `AVAILABLE` and a keystore configuration is supplied — the
  `signingRequired` flag on `BuildRequest` already exists for this.
- Real `adb install`/launch — belongs to `core-apk-lifecycle`
  (still PLANNED), which would consume a completed `BuildResult`'s
  artifacts rather than being part of `core-build` itself.

## Future remote-build integration

`core-remote`'s `RemoteClient`/`RemoteEndpoint`/`HttpTransport` already
exist and are tested for real (see `docs/ARCHITECTURE.md` §5d). A future
`RemoteBuildExecutor : BuildExecutor` would wrap a `RemoteClient`,
serialize a `BuildContext` to a remote build server, and translate its
response back into a `BuildExecutionResult` — consuming the exact same
`BuildPipeline` this document describes. No remote build server exists to
connect to in this environment, so this executor is not implemented here;
implementing it without a real server to test against would mean either
fabricating success or writing an untestable stub, both of which this
phase's rules explicitly rule out.
