# DroidCommand AI — Architecture Blueprint (Phase 2)

Status: DRAFT. Reflects the design adopted for implementation; components are
individually classified IMPLEMENTED / PARTIAL / PLANNED / CONFIGURATION-DEPENDENT
in Section 6 — do not read a component's presence in the diagram below as proof
it is built. Source of truth for what actually exists is the repository
itself. (The Phase 1 repository/provenance/licensing audit that preceded this
document was conducted and reported in the originating chat session; it found
the repository empty of code at the time, with no license or branding
findings to carry forward. It was not committed as a standalone file.)

## 1. Why this shape

Phase 1 found no existing code, no Droid Pilot/OpenDroid source, no license
obligations, and no branding to migrate. There is nothing to preserve or
reconcile — this is a greenfield design, not a migration. That removes an
entire category of risk (accidental license violation, silent behavior
regression) but means every capability below starts from PLANNED.

## 2. Module tree

```
DroidCommand AI
│
├── app                      Android application module (UI shell, DI wiring)
│
├── core-agent               Pure Kotlin/JVM. Agent state machine, tool
│                            registry/executor, bounded retry policy,
│                            conversation context, Planner contract, the
│                            bounded Forge objective loop (ObjectiveEngine),
│                            and DroidCommandSession, which coordinates
│                            Pilot/Forge mode switching. No Android
│                            dependency — testable on any JVM.
│
├── core-llm                 Provider-independent chat/tool-call abstraction
│                            (LlmProvider, LlmRequest/Response, LlmConfig)
│                            plus LlmPlanner, an adapter implementing
│                            core-agent's Planner against an LlmProvider. No
│                            local-model-only provider is implemented yet,
│                            but core-llm-anthropic and core-llm-openai now
│                            supply real ones (PARTIAL — see Section 6).
│
├── core-llm-anthropic       AnthropicLlmProvider — a real LlmProvider
│                            implementation, not a fake, built on
│                            core-remote's RemoteClient/HttpTransport
│                            exactly like core-shell's real subprocess
│                            executor was the only honest choice for a
│                            plain JVM capability. Encodes/decodes the real
│                            Anthropic Messages API JSON shape
│                            (kotlinx.serialization), sends the API key as
│                            an x-api-key header (never Authorization:
│                            Bearer, never cached — read fresh from
│                            LlmConfig.authToken() on every call), and maps
│                            HTTP status codes to LlmError variants
│                            (401/403 -> Authentication, 429/5xx ->
│                            ModelUnavailable, other non-2xx ->
│                            InvalidResponse). Tested against a real local
│                            HttpServer speaking Anthropic's JSON shape —
│                            never a live call to api.anthropic.com, never
│                            a real credential. Two documented, honest
│                            simplifications: tool parameter schemas are
│                            advertised as an open `{"type":"object"}`
│                            because ToolSpec models no parameter schema
│                            yet, and a TOOL-role Message maps to a plain
│                            user message because Message carries no
│                            tool_use_id for Anthropic's native tool_result
│                            block.
│
├── core-llm-openai          OpenAiLlmProvider — a second real LlmProvider,
│                            speaking the OpenAI Chat Completions API
│                            shape that OpenAI itself serves and that most
│                            self-hosted "OpenAI-compatible" servers
│                            (Ollama, vLLM, LM Studio, llama.cpp's server)
│                            implement too, so config.endpoint pointing at
│                            a local server is the expected case, not an
│                            edge case. Built on the same core-remote
│                            RemoteClient/HttpTransport as
│                            core-llm-anthropic. Unlike Anthropic's API,
│                            this one genuinely does use RemoteClient's
│                            built-in bearer-token auth (OpenAI's real API
│                            accepts Authorization: Bearer for real), and a
│                            missing key is not rejected up front — many
│                            self-hosted OpenAI-compatible servers accept
│                            requests with no key at all, so failing closed
│                            here would misrepresent what this shape
│                            actually requires. Same two documented
│                            simplifications as core-llm-anthropic (open
│                            tool parameter schema; TOOL-role Message maps
│                            to `user`, not OpenAI's native tool role,
│                            since Message carries no tool_call_id).
│
├── core-tools-android       DeviceController (the extension point for
│                            actual device control — mirrors
│                            core-build.BuildExecutor), a device-agnostic
│                            UiNode/UiTree/Selector domain model, and Tool
│                            wrappers (tap/swipe/type/pressKey/launchApp/
│                            findElement/tapElement/getUiTree/
│                            listInstalledApps/takeScreenshot) gated by
│                            core-security exactly like core-build's
│                            BuildTool. NullDeviceController is the only
│                            implementation — every method fails
│                            explicitly ("no real device is connected"),
│                            never fabricating a successful tap or UI
│                            read. A real Android-backed controller
│                            (PLANNED) needs the Android SDK and a
│                            connected/emulated device this environment
│                            does not have.
│
├── core-shell               Unlike core-tools-android/core-build, a real,
│                            working ShellExecutor genuinely belongs here:
│                            spawning a subprocess is a plain JVM
│                            capability, not an Android-only one.
│                            ProcessBuilderShellExecutor runs a command as
│                            a plain argv vector (never `sh -c "..."`, so
│                            shell-metacharacter injection is impossible
│                            by construction), fail-closed by default
│                            (empty executable allow-list — nothing runs
│                            until explicitly permitted), with a real
│                            enforced timeout and real cancellation.
│                            Tested against real subprocesses (echo, true,
│                            false, sleep, pwd, env, seq), not mocked.
│
├── core-root                RootTool declares requiresRoot=true and
│                            SecurityLevel.ROOT, closing the loop on
│                            core-security's rootEnabled/rootAvailable
│                            gate (built in an earlier phase, never
│                            exercised end-to-end until now).
│                            PolicyEnforcingRootExecutor adds a second,
│                            narrower fail-closed allow-list layer beneath
│                            that session-level gate. NullRootExecutor is
│                            the only RootExecutor — isRootAvailable()
│                            truthfully returns false, and execute() fails
│                            explicitly rather than fabricating a
│                            successful elevated command. A real
│                            rooted-device executor (PLANNED) needs an
│                            actual rooted device this environment does
│                            not have. RootProvider (added 2026-09-10)
│                            extends RootExecutor with provider identity/
│                            health/capability reporting; MagiskProvider is
│                            a real implementation (real process-based
│                            detection, cached root-shell probing, real
│                            su-backed execution with injection-safe
│                            quoting) — real on-device verification stays
│                            IMPLEMENTED — NOT RUNTIME VERIFIED, same as
│                            every other real executor in this module.
│
├── core-build               BuildRequest -> WorkspaceManager -> BuildPipeline
│                            -> BuildExecutor -> BuildResult -> Artifact.
│                            WorkspaceManager, WorkspacePathValidator,
│                            BuildPipeline, SystemBuildEnvironmentDetector,
│                            and DryRunPlanner are real, working
│                            implementations against real java.nio.file
│                            operations — not fakes. MockBuildExecutor
│                            never performs a real build (see
│                            docs/CORE_BUILD.md). No AndroidGradleBuildExecutor/
│                            RemoteBuildExecutor exists yet (PLANNED — the
│                            former needs the Android SDK/AGP this
│                            environment does not have; the latter needs a
│                            real build server). core-build-local now
│                            supplies the third: a real
│                            LocalProcessBuildExecutor for
│                            ProjectType.JVM/NATIVE/GENERIC builds.
│
├── core-build-local          LocalProcessBuildExecutor — a real
│                            BuildExecutor, not a fake, for
│                            ProjectType.JVM/NATIVE/GENERIC: spawning a
│                            build command is a plain JVM/OS capability,
│                            not an Android-only one, so a real
│                            implementation was the honest choice here too
│                            (same reasoning as core-shell/
│                            core-llm-anthropic/core-llm-openai).
│                            ProjectType.ANDROID is refused outright — that
│                            needs the Android Gradle Plugin/SDK this
│                            executor does not provide. Delegates the
│                            actual process spawn to core-shell's
│                            ShellExecutor rather than reimplementing
│                            ProcessBuilder handling, and never invents a
│                            build command from ProjectType — the command
│                            and any expected artifact paths come entirely
│                            from BuildRequest.metadata, so it never
│                            guesses at "likely" build outputs. Tested
│                            against a real `javac` invocation (and, via
│                            BuildPipeline, a real end-to-end workspace →
│                            compile → checksummed-artifact run) — the JDK
│                            tools present in this sandbox even without an
│                            Android SDK.
│
├── core-build-remote        RemoteBuildExecutor — a second real
│                            BuildExecutor, delegating the actual build to
│                            a remote build server over core-remote's
│                            RemoteClient/HttpTransport. Sending an HTTP
│                            request with the workspace's source archived
│                            inside it is a plain JVM/OS capability, the
│                            same reasoning that made core-build-local,
│                            core-shell, and both LLM providers real rather
│                            than fake. Unlike core-build-local, it never
│                            refuses ProjectType.ANDROID — the whole point
│                            of a remote build server is that *it*, not
│                            this sandbox, is expected to carry the Android
│                            SDK/AGP. Speaks a single synchronous
│                            request/response protocol this repository
│                            defines itself (there is no vendor API to
│                            conform to, unlike Anthropic/OpenAI): the
│                            workspace's source directory is zipped and
│                            base64-encoded into the request body, and a
│                            returned artifact's bytes are decoded, written
│                            to disk, and checksummed locally rather than
│                            trusting anything the server claims about its
│                            own output. Tested against a real local
│                            HttpServer, never a real build service.
│
├── core-apk-lifecycle        ApkLifecyclePipeline consumes an already-
│                            completed core-build.BuildResult (building
│                            and deploying are separate concerns) and
│                            orchestrates select-artifact → install →
│                            launch → (best-effort) collect logs →
│                            (optional) test → result through an
│                            ApkLifecycleExecutor extension point — same
│                            pattern as BuildExecutor/DeviceController.
│                            NullApkLifecycleExecutor is the only
│                            implementation; every method fails explicitly
│                            rather than fabricating a successful install
│                            or launch. A real adb-backed executor
│                            (PLANNED) needs a connected/emulated device
│                            this environment does not have.
│
├── core-remote               RemoteEndpoint (HTTPS-by-default, path-only
│                            resolution so a request can never be aimed at
│                            an unintended host), HttpTransport +
│                            JdkHttpTransport (a real java.net.http-backed
│                            implementation, tested against a real local
│                            HTTP server on loopback), and RemoteClient
│                            (bearer-token auth, bounded retry reusing
│                            core-agent's RetryPolicy, 5xx retried / 4xx
│                            never retried). The one module so far with a
│                            genuinely working implementation, not only an
│                            interface plus fakes — see Section 5d.
│                            MutualTlsConfig adds opt-in mutual TLS
│                            (client certificates) to JdkHttpTransport,
│                            tested against a real TLS handshake with a
│                            real keytool-generated private CA and
│                            server/client certificate chain.
│
├── core-security             SecurityPolicy, SecurityPolicyEnforcer, and
│                            SecureToolExecutor: authorizes a tool
│                            invocation (root/permission/confirmation)
│                            before it ever reaches ToolExecutor — a
│                            PolicyDecision.Deny means the tool is never
│                            invoked at all, regardless of what an LLM or
│                            planner requested. Root/permission checks are
│                            injected functions (rootAvailable,
│                            grantedPermissions), so the policy itself
│                            stays device-agnostic and fully testable.
│
├── core-config               ConfigSource/ConfigReader plus LlmConfigLoader
│                            and SecurityPolicyLoader, which build
│                            core-llm's LlmConfig and core-security's
│                            SecurityPolicy from a key/value source. No
│                            secret is ever held as a plain field — an API
│                            key is read from the source fresh on every
│                            authToken() call, not captured at load time.
│
└── core-mcp                  McpToolServer — exposes core-agent's
                             ToolRegistry over the real, official Kotlin MCP
                             SDK (io.modelcontextprotocol:kotlin-sdk-server),
                             closing ROADMAP-123/DP-001. Introducing this
                             module is why every module's Kotlin version
                             moved from 2.0.21 to 2.4.10 project-wide as of
                             2026-09-09 — the SDK's published metadata isn't
                             readable by 2.0.21, and Gradle resolves one
                             Kotlin plugin version for the whole build, not
                             one per module (see the addendum in
                             docs/AUDIT_2026-09-05.md for the full story).
│
└── core-integration-tests    Test-only module, no src/main: holds the
                             cross-module integration tests that need two
                             sibling modules together (core-shell,
                             core-root) which neither depends on the other.
                             core-security (the module that actually owns
                             DefaultExecutionRouter) was deliberately not
                             made to test-depend on either, to avoid its
                             test classpath growing to know about every
                             future concrete ExecutionTarget-providing
                             module (Termux, Docker, WireGuard, Proxmox,
                             VNC/X11, SSH, ...) as each one ships.
```

`core-agent`, `core-llm`, `core-llm-anthropic`, `core-llm-openai`,
`core-security`, `core-config`, `core-remote`, `core-build`,
`core-build-local`, `core-build-remote`, `core-tools-android`, `core-shell`,
`core-apk-lifecycle`, and `core-root` are implemented so far because none
has a *compile-time* Android dependency (none of this code needs
`android.jar`): `core-llm-anthropic`'s and `core-llm-openai`'s tests hit a
real local HTTP server, never a live provider endpoint or a real
credential, `core-build-local` compiles real Java source with the JDK's
own `javac` rather than the Android Gradle Plugin, `core-build-remote`
archives a real source directory into a real ZIP and sends it to a real
local HTTP server rather than any real build service (none exists for it
to call), `core-security`'s
root/permission checks are injected functions rather than real device
queries, `core-config`'s `EnvConfigSource` wraps `System.getenv` behind an
injectable function so its tests never read or depend on real process
environment, `core-remote`'s network tests talk only to a real HTTP server
bound to loopback (127.0.0.1) that the test itself starts and stops,
`core-build`'s workspace/pipeline tests run against real `java.nio.file`
temporary directories with a real (mock, not fabricated) executor (see
`docs/CORE_BUILD.md`, including two real bugs its own tests caught before
they shipped), `core-tools-android`'s `DeviceController` is only
implemented by `NullDeviceController`, which fails every method
explicitly rather than fabricating a successful tap, swipe, or UI-tree
read, `core-shell` is the one exception to the "nothing real" pattern:
spawning a subprocess doesn't need Android, so `ProcessBuilderShellExecutor`
is a real, working, fail-closed-by-default shell executor, tested against
real subprocesses (`echo`, `sleep`, `pwd`, `env`, ...) rather than mocked,
and `core-apk-lifecycle` follows `core-tools-android`'s pattern again:
`ApkLifecyclePipeline` is real orchestration logic (consuming an
already-completed `core-build.BuildResult`), but `NullApkLifecycleExecutor`
is the only `ApkLifecycleExecutor`, and it fails every install/launch/log/
test call explicitly rather than fabricating a successful deployment, and
`core-root` closes a loop left open since Phase 6/7: `core-security`'s
`rootEnabled`/`rootAvailable` gate has existed for several increments but
was never exercised end to end against a real `Tool` until `RootTool`
existed to test it with — `NullRootExecutor` truthfully reports root as
unavailable and fails every command explicitly, and
`PolicyEnforcingRootExecutor` adds its own fail-closed executable
allow-list beneath that session-level gate. Every other module is
scaffolding-only or not yet created — see Section 6.

## 3. Two-mode architecture

```
                    DROIDCOMMAND AI
                         │
              ┌──────────┴──────────┐
              │                     │
          PILOT MODE            FORGE MODE
              │                     │
       core-agent.Tool         core-agent.ObjectiveEngine, driven by
       Executor (direct,       a core-agent.Planner — core-llm.LlmPlanner
       single-step)            is the only implementation so far, and it
              │                talks to LlmProvider (no concrete provider
              │                implemented — PARTIAL, Section 6)
              │                     │
              └──────────┬──────────┘
                         │
              core-agent.ToolRegistry
                         │
              core-tools-android (Tool wrappers implemented; only
              NullDeviceController exists — see Section 6) / core-shell
              (implemented and real) / core-root (Tool + gate wiring
              implemented; only NullRootExecutor exists)
```

Both modes route through the same `ToolRegistry` and `ToolExecutor` so a tool
is written once and works in either mode. Pilot Mode invokes a single tool per
user instruction; Forge Mode wraps repeated tool invocations in an objective
loop (UNDERSTAND → PLAN → SELECT TOOLS → EXECUTE → OBSERVE → VALIDATE →
DIAGNOSE → FIX → REBUILD → RETEST → ITERATE → COMPLETE). The active mode must
be surfaced in the UI at all times (UI itself: PLANNED, Phase 15).

`DroidCommandSession` (`core-agent`) is the mode coordinator: it exposes
`mode: AgentMode` (`PILOT`/`FORGE`), `runPilotInstruction(...)`, and
`runForgeObjective(...)`, and it is the single place a mode switch is
rejected while a task is active. A switch attempted mid-task throws
`IllegalModeSwitch` rather than silently queuing or corrupting state, and
this is tested for real concurrent-looking behavior, not just documented
intent: a test drives an active Pilot task whose cancellation callback
itself attempts `switchMode(FORGE)` mid-execution and asserts it is
rejected, then asserts the same switch succeeds once the task has actually
finished. The lock guarding this is held only for the mode/active-flag
check, never across the task's own execution, so a switch attempt from a
genuinely different thread fails fast instead of blocking on a long-running
tool or objective loop.

## 4. Agent state machine (implemented in `core-agent`)

`AgentState` is a sealed hierarchy: `Idle`, `Planning`, `AwaitingApproval`,
`ExecutingTool`, `Observing`, `Recovering`, `Completed`, `Cancelled`,
`Failed`. Transitions are validated — an illegal transition (e.g.
`Completed → ExecutingTool`) throws rather than silently succeeding, which is
the mechanism that prevents an agent from being resumed after it has already
terminated. Retry is bounded by an explicit `RetryPolicy(maxAttempts, backoff)`
passed into the executor; there is no unbounded loop anywhere in this module.

## 4b. Objective engine and LLM abstraction (implemented, partial)

`ConversationContext` (`core-agent`) is an append-only, ordered message
history with an optional system prompt. `Planner` (`core-agent`) is the
contract an objective loop consults for its next step —
`InvokeTool`/`Complete`/`Abort` — and `ObjectiveEngine` (`core-agent`) drives
the Forge loop against it: each iteration re-enters `Planning`, asks the
planner, and either runs a tool through `ToolExecutor` or terminates. Agent
safety is enforced by `maxIterations` (default 25, validated `>= 1`): a
planner that never returns `Complete` or `Abort` cannot loop forever — the
engine gives up and transitions to `Failed` once the bound is hit. This is
verified directly, not just claimed: `ObjectiveEngineTest`'s
"never exceeds maxIterations" case runs a planner that always requests a
tool and asserts the tool was invoked exactly `maxIterations` times, no more.

`core-llm` defines the provider-independent side: `LlmRequest`/`LlmResponse`/
`LlmError`, and `LlmProvider`, an interface with no implementation yet.
`LlmConfig.authToken` is a function (`() -> String?`), not a stored string,
so no code path in this module can hold or serialize a credential.
`LlmPlanner` implements `core-agent.Planner` against an `LlmProvider`: a
tool-call response becomes `InvokeTool`, plain text becomes `Complete`
(the model considers the objective satisfied), and a provider error becomes
`Abort` rather than an uncaught exception. Because no real provider exists,
every test — including the end-to-end `ObjectiveEngineIntegrationTest`,
which drives `ObjectiveEngine` + `LlmPlanner` + a real registered `Tool`
through one full tool-call-then-complete cycle — runs against a scripted
fake `LlmProvider`. That proves the wiring, not a live model; see Section 6.

## 5. Build strategy decision

[Likely — recommendation, not yet validated against real build workloads]
Hybrid: Pilot Mode's device-control tools run on-device (Accessibility, shell,
root are inherently on-device). Forge Mode's compilation step should target a
remote build server rather than running Gradle/AGP on-device — Android has no
supported, reliable on-device Android Gradle Plugin toolchain, and this
sandbox's own environment confirms the pattern: Gradle 8.14.3 and JDK 21 are
available, but there is no Android SDK, adb, or emulator installed, and none
of the standard Android device paths exist. A phone would be worse-equipped
than this sandbox, not better. `core-remote` is the client-side half of that
architecture; the server half is out of scope for this repository.

## 5b. Security policy (implemented, device-agnostic)

`SecurityPolicy` (`core-security`) is the configuration: `rootEnabled`,
`rootAvailable` (a function — a real on-device root check when core-root
exists, a fixture in tests), `grantedPermissions`, and `autoApprove` (which
`SecurityLevel`s bypass interactive confirmation; only `NORMAL` by default).
`SecurityPolicyEnforcer.authorize(spec: ToolSpec)` returns `Allow`,
`RequireApproval(reason)`, or `Deny(reason)` — root and permission checks
are evaluated first and are hard denials, never downgraded to a mere
prompt: a `ROOT` tool with a missing permission is denied outright, not
asked-and-approved-around.

`SecureToolExecutor` wraps `ToolExecutor` with that check: a `Deny` returns
a `ToolResult.Failure` without the underlying `Tool.execute` ever being
called — verified directly by a test that asserts a policy-denied tool's
invocation counter stays at zero. A `RequireApproval` transitions the
shared `AgentStateMachine` to the previously-unused `AgentState.
AwaitingApproval` and blocks on an injected `ApprovalPrompt` — this is the
first code that gives `AwaitingApproval` real behavior; it existed in the
state machine's sealed hierarchy since Phase 3 but nothing produced it
until now.

This closes the device-agnostic slice of Phase 7's "ROOT TEST MATRIX":
root unavailable, root available (then approved), user denies
authorization, and permission failure are all unit-tested. Command
failure/timeout/cancellation/invalid-command are unchanged from
`ToolExecutorTest` — `SecureToolExecutor` delegates to the same
`ToolExecutor` once a decision is `Allow` or an approval is granted, so it
inherits those guarantees rather than re-implementing them. What remains
un-testable here is real root detection and real permission grants on an
actual Android device — that is `core-root`/`core-tools-android`'s job,
not this module's, and both remain PLANNED (Section 6).

## 5c. Configuration loading (implemented, device-agnostic)

`core-config` is the seam between raw configuration (environment variables,
or any other `ConfigSource`) and the typed config objects `core-llm` and
`core-security` already define. `ConfigReader` gives typed access
(`require`/`optional`/`optionalInt`/`optionalDouble`/`optionalBoolean`)
over any `ConfigSource`; a missing required key throws
`MissingConfigException` rather than producing a half-built config object
that fails confusingly later.

`LlmConfigLoader.load(source)` builds an `LlmConfig`: `provider` and
`model` are required, `endpoint`/`temperature`/`maxOutputTokens` are
optional, and `authToken` is a lambda that reads `DROIDCOMMAND_LLM_API_KEY`
from the source fresh on every call — the loader itself never captures the
key into a field. This is verified directly, not just designed that way in
prose: a test changes the underlying source's value between two calls to
the returned config's `authToken()` and asserts each call sees the current
value, proving nothing was cached at load time.

`SecurityPolicyLoader.load(source, rootAvailable)` builds a
`SecurityPolicy` from comma-separated permission and auto-approve-level
lists, defaulting to root disabled, no granted permissions, and only
`SecurityLevel.NORMAL` auto-approved — the same conservative defaults
`SecurityPolicy` itself uses. `rootAvailable` is deliberately never sourced
from configuration (root availability is a device fact, not a setting);
the loader only threads through whatever function the caller supplies.

`EnvConfigSource` wraps `System.getenv` behind an injectable function
(defaulting to `System::getenv`), so production code gets real environment
variables while every test in this module supplies a fake — no test here
reads or depends on this sandbox's actual process environment.

## 5d. Remote client (implemented — the first module with a real, working implementation)

Every module up to this one has been an interface plus fakes: `LlmProvider`
has no concrete implementation, `SecurityPolicy`'s root/permission checks
are injected functions, `core-config`'s sources are env-var/map-backed.
`core-remote` is different — `JdkHttpTransport` is a real HTTP client
(`java.net.http.HttpClient`, part of the JDK, no external dependency), and
it is tested against a real server: `JdkHttpTransportTest` starts an actual
`com.sun.net.httpserver.HttpServer` bound to `127.0.0.1` on an
OS-assigned port, sends a real request over a real socket, and asserts on
the real response — headers included. A second test opens a raw
`ServerSocket` that accepts the TCP connection but never writes a
response, to prove the transport's own request timeout actually fires
rather than hanging.

This caught a real bug before it shipped: the first version compared
response header names case-sensitively, but HTTP header names are
case-insensitive by spec and `java.net.http.HttpHeaders.map()` does not
guarantee a server's exact casing survives into that map. The round-trip
test against the real server failed with the header coming back `null`
under the original casing — not a hypothetical, an actual assertion
failure in this session — and the fix (a case-insensitive
`TreeMap`) is what ships now.

`RemoteEndpoint` only ever resolves a caller-supplied relative path
against its own configured `baseUrl` — there is no code path by which a
caller can aim a request at a different host, so "server identity"
protection here is structural rather than a runtime allow-list check.
`RemoteClient` reuses `core-agent`'s `RetryPolicy` rather than
reimplementing bounded retry, retries a 5xx status or an I/O
error/timeout, and never retries a 4xx (the request itself was wrong;
retrying would just repeat the failure). `RemoteClientIntegrationTest`
proves the retry path for real too: a local server returns 503 twice then
200, and `RemoteClient` + the real `JdkHttpTransport` recover without any
test double standing in for the network layer.

`JdkHttpTransport` also accepts an optional `MutualTlsConfig`: constructed
with a `KeyStore`/password to present a client certificate, an optional
`KeyStore` to validate the server against a private CA (or both), it
replaces the platform's default `SSLContext` for that transport instance.
This models the common real-world mTLS shape — both sides of a private
service (a build or LLM server) presenting certificates issued by the
same private CA rather than a public one — deliberately, not as an
artificial test-only setup. `MutualTlsIntegrationTest` proves this against
a real TLS handshake: a real private CA and a real server/client
certificate chain, all generated with the JDK's own `keytool`, and a real
`com.sun.net.httpserver.HttpsServer` requiring client authentication. A
client certificate signed by the trusted CA lets the handshake complete
for real; presenting no client certificate at all against a server that
requires one fails the handshake for real; and a client with no configured
trust store correctly rejects the server's private-CA-signed certificate
via ordinary platform CA trust, proving mTLS support composes with the
existing default rather than silently replacing it everywhere.

What remains PLANNED: there is still no build-server client built on top
of `RemoteClient` — `core-llm-anthropic` is now the first consumer (see
5e) — and certificate pinning (trusting a peer by a specific public-key
hash rather than by presenting a client certificate or the ordinary CA
chain) is a separate, narrower trust mechanism from mTLS that has not
been built in this module yet.

## 5e. core-llm-anthropic (implemented — the first real LlmProvider)

`core-llm`'s `LlmProvider` interface existed with no implementation since
its own module was built; `AnthropicLlmProvider` closes that the same way
`core-shell`'s `ProcessBuilderShellExecutor` closed the "real vs. Null"
question for subprocess execution — spawning HTTP requests, like spawning
subprocesses, is a plain JVM capability, so a fake here would have been
dishonest where a real implementation was achievable.

It encodes `LlmRequest` into Anthropic's actual Messages API JSON shape
(`kotlinx.serialization`, not hand-rolled string concatenation, to avoid a
class of injection/escaping bugs a bespoke JSON builder invites) and
decodes the actual response shape back into `LlmResponse`. Two mapping
gaps are real, not oversights, and are documented in code and here rather
than silently smoothed over: `ToolSpec` carries no parameter schema
anywhere in this repository yet, so every tool is advertised to the model
with a permissive `{"type":"object"}` schema instead of a fabricated one;
and `Message` carries no `tool_use_id`, so a `TOOL`-role message is mapped
to a plain `user` message rather than Anthropic's native `tool_result`
block, which would need an id this repository does not track. A future
increment that adds a parameter schema to `ToolSpec` or a `toolUseId` to
`Message` would let this provider drop both simplifications without any
redesign — the mapping functions are isolated on purpose.

Authentication sends the API key as an `x-api-key` header, read fresh from
`LlmConfig.authToken()` on every `complete()` call and never cached —
`RemoteClient`'s built-in bearer-token auth header is not used here, since
Anthropic's real API does not accept `Authorization: Bearer`. A missing key
fails closed with `LlmError.Authentication` before any network call is
attempted, proven by a test that asserts zero bytes reach the request
handler when the key is absent.

`AnthropicLlmProviderIntegrationTest` runs the full round trip against a
real local `HttpServer` speaking Anthropic's actual JSON shape: a text
response, a `tool_use` response (including a nested-object input value
flattened to compact JSON text rather than dropped), a 401 mapping to
`LlmError.Authentication`, a 529 "overloaded" response mapping to
`LlmError.ModelUnavailable`, a malformed body mapping to
`LlmError.InvalidResponse` instead of throwing, and the `SYSTEM`/`TOOL`
role-folding behavior described above. What remains PLANNED: this has
never been run against `api.anthropic.com` itself, since this environment
has no LLM credentials — the JSON shape is modeled from Anthropic's
published API, not verified against a live response.

## 5f. core-llm-openai (implemented — a second real LlmProvider)

`OpenAiLlmProvider` mirrors `core-llm-anthropic.AnthropicLlmProvider`
structurally — same `RemoteClient`/`HttpTransport` foundation, same
`kotlinx.serialization` DTOs, same two documented mapping simplifications
(an open `{"type":"object"}` tool schema, and a `TOOL`-role `Message`
mapped to `user`) — but it speaks the OpenAI Chat Completions API shape
instead: `POST /v1/chat/completions`, a `messages` array carrying the
system prompt as a `system`-role message rather than a separate top-level
field, and `tool_calls` whose `function.arguments` is a JSON object
serialized as a *string*, which this provider parses and then flattens the
same way `AnthropicLlmProvider` flattens `tool_use.input`.

This shape matters beyond OpenAI itself: most self-hosted
"OpenAI-compatible" model servers (Ollama, vLLM, LM Studio, llama.cpp's
server, and others) implement exactly this endpoint shape, so
`config.endpoint` pointing at `http://localhost:...` rather than
`DEFAULT_BASE_URL` is the expected, common case for this provider — not an
edge case the way a self-hosted Anthropic-shaped server would be.

One deliberate difference from `AnthropicLlmProvider`'s auth handling:
this provider *does* use `RemoteClient`'s built-in `Authorization: Bearer`
header, since OpenAI's real API (and every OpenAI-compatible server this
was modeled against) genuinely accepts it — unlike Anthropic's `x-api-key`
requirement. And a missing API key is not rejected up front here, unlike
`AnthropicLlmProvider`'s fail-closed check: many self-hosted
OpenAI-compatible servers accept requests with no key at all, so refusing
to send one would misrepresent what this shape actually requires rather
than protect anything.

`OpenAiLlmProviderIntegrationTest` proves the same category of round trip
as `AnthropicLlmProviderIntegrationTest` against a real local `HttpServer`
speaking this shape: a text response, a `tool_calls` response (including a
nested-object argument value flattened to compact JSON text), a 401
mapping to `LlmError.Authentication`, repeated 503s mapping to
`LlmError.ModelUnavailable` after retries are exhausted, a malformed body
mapping to `LlmError.InvalidResponse` instead of throwing, a request with
no API key succeeding rather than being blocked, and the `TOOL`-role
mapping. What remains PLANNED: this has never been run against a real
OpenAI account or a real self-hosted server — the JSON shape is modeled
from OpenAI's published API, not verified against a live response.

## 5g. core-build-local (implemented — the first real BuildExecutor)

`core-build.BuildExecutor` existed with only `MockBuildExecutor` — an
explicitly non-real stand-in — since `core-build` was first built.
`LocalProcessBuildExecutor` closes that for `ProjectType.JVM`/`NATIVE`/
`GENERIC`, the same "a plain JVM/OS capability deserves a real
implementation" reasoning already applied to `core-shell`'s subprocess
executor and both LLM providers. `ProjectType.ANDROID` is refused outright
with `BuildError.ExecutorUnavailable` — that needs the Android Gradle
Plugin and Android SDK, which neither this executor nor this environment
provides, and the refusal happens before any process is spawned.

It never invents a build command from `ProjectType`: the actual command
comes entirely from `BuildRequest.metadata` (`command.executable`,
optionally `command.args`), and any artifact paths to collect afterward
come from `metadata["artifact.paths"]` — this executor does not scan the
workspace guessing at "likely" outputs, since that would be fabricating
structure the caller never declared. The actual process spawn is
delegated to `core-shell`'s `ShellExecutor` rather than reimplementing
`ProcessBuilder` handling — a command outside `ShellSecurityPolicy`'s
fail-closed allow-list never runs, exactly as it wouldn't for
`core-shell`'s own tool.

A produced artifact is validated for real before being reported: resolved
against `BuildContext.sourceDir`, checked for containment inside the
workspace root (failing as `BuildError.ArtifactInvalid` if a declared path
tries to escape it), checked to actually exist (`BuildError.ArtifactNotFound`
if the build didn't produce it), and given a real SHA-256 checksum and
file size — no fabricated metadata. One known imprecision, documented in
code: `BuildContext` carries no pipeline-level `buildId` (only
`BuildPipeline` generates one, after the executor returns), so
`Artifact.buildId` here is the workspace id instead — the closest
correlated id available, not a stand-in for the real build id that ends
up in `BuildResult.Success`.

`LocalProcessBuildExecutorTest` proves all of this against a real `javac`
invocation — not a scripted fake — compiling real Java source into a real
`.class` file with a real, verifiable checksum, a real non-zero exit code
surfacing as `BuildError.BuildFailed` with real stderr, and the containment/
existence checks above triggering for real.
`LocalProcessBuildExecutorPipelineIntegrationTest` goes one level up,
running that same real `javac` build all the way through
`core-build.BuildPipeline` — real workspace creation, real source import,
this executor, and the pipeline's own artifact-containment validation —
proving the two modules actually compose, not just that each works in
isolation. What remains PLANNED: `AndroidGradleBuildExecutor` (needs the
Android SDK/AGP); this executor has never built an actual Android APK,
since `ProjectType.ANDROID` is exactly what it refuses to attempt.
`RemoteBuildExecutor`, `core-build`'s other documented future
implementation, is now real too — see 5h.

## 5h. core-build-remote (implemented — a second real BuildExecutor)

`core-build.BuildExecutor`'s own doc comment named `RemoteBuildExecutor` as
a future implementation since the interface was first written:
"delegates to a build server over `core-remote`'s `RemoteClient`." That
future arrived the same way `core-build-local` did — sending an HTTP
request is a plain JVM/OS capability, so a fake here would have been
dishonest where a real implementation was reachable.

Unlike `core-llm-anthropic`/`core-llm-openai`, there is no published vendor
API for "a build server" to conform to — that's this repository's own
architectural placeholder (Section 5), not a real external service. So
`RemoteBuildExecutor` speaks a protocol this repository defines and owns
(`RemoteBuildRequest`/`RemoteBuildResponse` in `RemoteBuildProtocol.kt`):
a single synchronous `POST /builds` carrying the workspace's source
directory archived into a ZIP and base64-encoded in the JSON body, and a
response reporting `SUCCESS`/`FAILURE`, output, and any produced artifacts
(each as a bare file name plus base64 content). This is deliberately
simpler than a real CI/build-server API (no build-id polling, no
asynchronous job queue) — an honest reflection of what is actually
implemented, not a claim of interoperability with anything real.

Unlike `LocalProcessBuildExecutor`, this executor never refuses
`ProjectType.ANDROID` outright: the whole point of delegating to a remote
build server is that *it*, not this sandbox, is expected to carry the
Android Gradle Plugin and Android SDK. What this executor cannot prove is
that such a server exists — it has only ever been run against a real local
test server this repository starts and stops itself, never a real build
service, since none is reachable from this environment.

A returned artifact's bytes are decoded and written to disk inside the
workspace (`<workspace>/remote-artifacts/<fileName>`) and given a locally
recomputed SHA-256 checksum — the server's own claims about its output are
never trusted blindly, matching `LocalProcessBuildExecutor`'s "no
fabricated metadata" rule. A `fileName` is external input (it arrived over
the network), so one containing a path separator or a `..` segment is
rejected outright as `BuildError.ArtifactInvalid` rather than sanitized —
the same zip-slip-style defense already applied to declared artifact paths
in `core-build-local`. An artifact exceeding
`BuildSecurityPolicy.maxArtifactBytes` is rejected rather than written.
Server-reported failures map their `errorCode` string to the matching
`BuildError` variant (falling back to `BuildError.RemoteBuildError`, which
`core-build`'s error taxonomy already reserved for this), and a malformed
or non-JSON response body maps to `BuildError.RemoteBuildError` instead of
throwing. There is no in-flight cancellation once a request has been sent
— `HttpTransport` exposes no such hook — so `isCancelled` is only checked
before sending.

`RemoteBuildExecutorIntegrationTest` proves all of this against a real
local `HttpServer`: a real source directory zipped and sent, decoded and
verified server-side inside the test's own request handler; a real
artifact written to disk with a verified checksum; a path-traversal
`fileName` rejected; an oversized artifact rejected; a server-reported
`BUILD_FAILED` mapped correctly; a real HTTP 500 mapped to
`BuildError.RemoteBuildError`; a malformed response body handled without
throwing; and a pre-set `isCancelled` short-circuiting before any request
reaches the server. What remains PLANNED: this has never been run against
a real build service, since none exists in this environment to call, and
there is still no asynchronous/polling variant of the protocol.

## 6. Component status (Section 3 naming — "Never fabricate features")

| Component | Status | Evidence |
|---|---|---|
| core-agent: AgentState | IMPLEMENTED | `core-agent/src/main/kotlin/.../AgentState.kt`, compiles, unit-tested |
| core-agent: AgentStateMachine thread safety | IMPLEMENTED | Added 2026-09-10 — `state` is now `@Volatile` and `transition()`'s check-then-set is `synchronized`, closing the concurrency gap the MacroScheduler row below previously flagged as a documented limitation. A read of `state` from a different thread than the one calling `transition` (e.g. a status display polling while work runs on another thread, or `MacroScheduler` firing on its own background thread) is now guaranteed to see the latest value rather than a stale or torn one, and two concurrent `transition` calls can never both pass the terminal-state check before either writes — proven directly by two new tests: one submits a transition on an executor thread and asserts the new state is visible after `.get()` on the test thread, the other races 16 threads to transition to a terminal state simultaneously and asserts exactly one succeeds. This does **not** make it correct for two unrelated logical tasks (a live Pilot instruction and a scheduled macro) to share one `AgentStateMachine` instance — their transitions would still interleave into one meaningless sequence even though no individual read/write is corrupted — so `MacroScheduler`'s recommendation to use a dedicated instance per independent task still stands. |
| core-agent: Tool interface | IMPLEMENTED | `Tool.kt`, compiles, unit-tested |
| core-agent: ToolRegistry | IMPLEMENTED | `ToolRegistry.kt`, compiles, unit-tested |
| core-agent: ToolExecutor + bounded retry | IMPLEMENTED | `ToolExecutor.kt`, compiles, unit-tested |
| core-agent: ConversationContext | IMPLEMENTED | `Conversation.kt`, compiles, unit-tested |
| core-agent: ConversationContext token budgeting (ROADMAP-057) | IMPLEMENTED | Added 2026-09-09 — optional `maxTokens` constructor param (default `null` = unbounded, so every existing caller is unaffected); `estimateTokens` is an honest ~4-chars/token heuristic (core-agent has no LLM dependency to ask for a real count), documented as such rather than passed off as exact. An append that pushes the estimate over budget evicts the oldest messages first, never the system prompt and never the message just appended |
| core-agent: Planner contract | IMPLEMENTED | `Planner.kt` (interface only — see LlmPlanner for the one implementation) |
| core-agent: ObjectiveEngine (bounded Forge loop) | IMPLEMENTED | `ObjectiveEngine.kt`, compiles, unit-tested incl. the maxIterations bound. As of 2026-09-05, a planner naming an unregistered/wrong-mode tool no longer fails the objective outright — it's told what's actually available and replans, still bounded by maxIterations |
| core-agent/core-security: structured logging (ROADMAP-014) | IMPLEMENTED | Added 2026-09-09 — `Logger.kt` (`Logger` interface, `NoOpLogger` default, `ConsoleLogger` real implementation); `ObjectiveEngine` gained an optional `logger` constructor param (default `NoOpLogger`, so every existing caller is unaffected) logging `objective_started`/`objective_completed`/`objective_aborted`/`objective_cancelled`/`objective_exhausted_iterations`/`tool_result`/`unknown_tool` events. `ToolExecutor` — the executor Pilot Mode invokes directly, as well as the one `ObjectiveEngine` drives for Forge Mode — gained the same optional `logger` param, logging `tool_result`/`tool_mode_rejected`/`tool_initiator_rejected`/`tool_cancelled`. `core-security`'s `SecureToolExecutor` also gained an optional `logger` param, logging `secure_tool_denied`/`secure_tool_result` — deliberately distinct from its own `auditLog` (a fail-closed security record) rather than merged into it, since losing a log line is not a reason to deny a call the way an unrecordable audit event is |
| core-agent: ToolSpec.allowedModes (mode genuinely scopes tool availability) | IMPLEMENTED | Added 2026-09-05 — `ToolExecutor`/`ObjectiveEngine` enforce it; default (both modes) leaves existing tools unaffected |
| core-agent: DroidCommandSession (Pilot/Forge mode switching) | IMPLEMENTED | `DroidCommandSession.kt`, unit-tested incl. a rejected mode switch attempted mid-task |
| core-agent: ToolResult evaluation model (success/partial/failure/unexpected) | IMPLEMENTED | Added 2026-09-09 (ROADMAP-022/069) — `Tool.kt` gained `ToolResult.Partial`/`ToolResult.Unexpected` alongside the existing `Success`/`Failure`; `ToolExecutor` retries only a `Failure` (a `Partial`/`Unexpected` result is returned immediately — retrying it isn't guaranteed to help, so that judgment is left to the planner), and `ObjectiveEngine.describe` reports each distinctly (`PARTIAL: ...`/`UNEXPECTED: ...`) into the conversation context the planner sees on its next turn |
| core-agent: MacroExecutor (linear, non-replanning playback for saved routines/automation, ROADMAP-127) | IMPLEMENTED (interface + engine) | Added 2026-09-09 — `MacroExecutor.kt`: `Macro` (name + ordered `MacroStep(toolName, input)` list), `MacroOutcome` (`Completed`/`StoppedOnFailure`/`Cancelled`), and `MacroExecutor.run()`, which dispatches each step through the same `ToolExecutor` Pilot Mode and `ObjectiveEngine` already use — same mode/initiator scoping, same retry handling — rather than a second dispatch implementation. This is OD-002's pattern: an intentionally linear executor with no `Planner` involved, so a step's `ToolResult.Failure` stops the sequence immediately instead of replanning (`Partial`/`Unexpected` do not stop it, since they're real results the tool stands behind, not blank failures). An unknown tool name in a step is reported as an ordinary `MacroOutcome.StoppedOnFailure` rather than throwing `UnknownToolException`, so a caller has one outcome shape to handle. Unit-tested incl. ordering, stop-on-failure, Partial/Unexpected pass-through, cancellation, mode/initiator scoping, and logging. |
| core-agent: MacroStore (persistent macro storage, ROADMAP-126/127) | IMPLEMENTED | Added 2026-09-09 — `MacroStore.kt` (`InMemoryMacroStore`, real but non-persistent, matching `core-security`'s `InMemoryGrantStore`/`InMemoryAuditLog` pattern) and `JsonFileMacroStore.kt`, this codebase's first genuinely persistent (survives-a-process-restart) store: one JSON file per macro under a directory, using `kotlinx.serialization` (core-agent's first dependency — added deliberately rather than hand-rolling JSON, matching the same library's use in `core-llm-anthropic`/`core-llm-openai`) with private wire DTOs kept separate from the public `Macro`/`MacroStep` domain types. A macro name is validated against a strict `[A-Za-z0-9_-]+` allow-list, then the resolved file path is re-checked to stay inside the store's directory (the same normalize-then-`startsWith` fail-closed pattern `core-build.WorkspacePathValidator` uses) before any read/write/delete — a name can never escape the directory. Unit-tested incl. round-tripping through a fresh store instance over the same directory (proving real cross-instance persistence, not just in-process caching) and rejecting `../`-shaped and absolute-path-shaped names. This is a first, narrow slice of ROADMAP-126's broader persistent-memory gap (macro storage specifically, not conversation/knowledge-graph memory) — the rest of ROADMAP-126 remains MISSING. |
| core-agent: ConversationStore (persistent conversation storage, ROADMAP-126) | IMPLEMENTED | Added 2026-09-10 — `ConversationStore.kt` (`InMemoryConversationStore`) and `JsonFileConversationStore.kt`, extending the exact `MacroStore`/`JsonFileMacroStore` pattern (interface, non-persistent in-memory implementation, real file-backed implementation with the same id-allow-list + normalize-then-`startsWith` path-escape defense) to `ConversationContext` instead of `Macro`. The one real difference from `MacroStore`: `ConversationContext` is itself mutable (`append`), unlike the immutable `Macro`/`MacroStep`, so both `save` and `load` return an independent snapshot rather than a live reference — `InMemoryConversationStore` does this explicitly (copies every message into a fresh `ConversationContext`), `JsonFileConversationStore` gets it for free from serialization. Unit-tested incl. that mutating the original context after `save`, or mutating a context returned by `load`, never changes the store's own copy — the aliasing bug this design was written specifically to avoid. This is a second narrow slice of ROADMAP-126 (one conversation's message history) — a knowledge-graph/long-term memory layer across conversations remains MISSING. |
| core-agent: KnowledgeStore (long-term/structured memory, ROADMAP-126, first slice) | IMPLEMENTED (interface + storage + literal retrieval; extraction/indexing/access-control/UI deferred) | Added 2026-09-10 — `KnowledgeStore.kt` (`KnowledgeEntry`, `InMemoryKnowledgeStore`) and `JsonFileKnowledgeStore.kt`, extending the same `MacroStore`/`ConversationStore` pattern to a new, previously-unbuilt memory layer: `docs/REQUIREMENTS_PROMPT.md` §11 names "long-term memory: structured knowledge and historical information" as distinct from conversation memory, and nothing existed for it before this. `KnowledgeEntry` is immutable (unlike `ConversationContext`), so — like `Macro` — neither `save` nor `load` needs the snapshot discipline `ConversationStore` requires. Retrieval is real but deliberately literal, never semantic: `findByTag` (exact, case-sensitive) and `search` (case-insensitive substring against `content` only) — the interface's own doc comment says explicitly that no embedding/LLM call exists on this path, and that both are honest O(n) scans, not backed by any index. `KnowledgeContext.kt`'s `formatKnowledgeContext()` turns query results into one block of text a caller can append to a `ConversationContext`, but is deliberately *not* auto-wired into `ObjectiveEngine`/`DroidCommandSession` — deciding when to retrieve and what to query is an LLM-shaped policy decision belonging to `core-llm`, which `core-agent` has no dependency on. Unit-tested incl. cross-instance persistence, path-escape rejection, tag case-sensitivity, and a negative test proving `search` does not match on meaning. Explicitly out of scope for this slice (see `docs/AUDIT_2026-09-05.md`'s addendum for the full per-criterion table against §11's 9-point audit checklist): automatic extraction of entries from conversations, a real index, access-control/security-policy gating, encryption at rest, UI, and any expiry/pruning lifecycle. |
| core-llm: LlmKnowledgeExtractor (automatic extraction into KnowledgeStore, ROADMAP-126, second slice) | IMPLEMENTED (interface + provider adapter; never exercised against a live provider) | Added 2026-09-10 — `LlmKnowledgeExtractor.kt`, closing the "automatic extraction" gap the `KnowledgeStore` row explicitly deferred. Mirrors `LlmPlanner`'s exact adapter role: bridges `core-agent.KnowledgeStore` (real storage, no LLM dependency) to an `LlmProvider` call, turning a `ConversationContext`'s messages into `KnowledgeEntry` candidates via a system prompt asking the model for a JSON array of `{content, tags}` objects. Returns a typed `KnowledgeExtractionResult` (`Success`/`Malformed`/`ProviderFailed`) rather than throwing, the same pattern `LlmPlanner` uses for `LlmResponse.Error` -> `PlannerDecision.Abort`; a provider hallucinating a tool call despite none being offered is `Malformed`, not a crash. Entry ids come from an injectable generator (default: `UUID.randomUUID()`, whose string form already satisfies `JsonFileKnowledgeStore.ID_PATTERN`) — the model is never trusted to invent a valid, unique id. Deliberately does not call `KnowledgeStore.save` itself and is not auto-invoked from `ObjectiveEngine`/`DroidCommandSession` — same restraint as `formatKnowledgeContext`, since deciding when to extract and whether/how to persist is a caller policy decision. Unit-tested against a scripted `LlmProvider` (matching `LlmPlannerTest`'s convention, not a real HTTP server — that boundary lives one layer down in `core-llm-anthropic`/`core-llm-openai`): valid/empty JSON arrays, malformed text, a provider error, an unexpected tool call, a custom id generator, and that the request carries the conversation's messages with no tools offered. Like `AnthropicLlmProvider`/`OpenAiLlmProvider`, the extraction prompt has never been exercised against a real provider — this environment has no LLM credentials. |
| core-llm: KnowledgeExtractionService (extractor + store wiring, ROADMAP-126, third slice) | IMPLEMENTED | Added 2026-09-10 — `KnowledgeExtractionService.kt`, closing the "wiring `LlmKnowledgeExtractor` + `KnowledgeStore.save` together" gap the prior row's own entry named as the next candidate. `extractAndSave(context, source)` calls `LlmKnowledgeExtractor.extract` and, on `Success`, saves every returned entry via `KnowledgeStore.save`; a `Malformed`/`ProviderFailed` result saves nothing and logs a `WARN` via an optional `Logger` (default `NoOpLogger`) instead of throwing. This is still the "caller policy" wiring named in every prior entry, not automatic end-to-end extraction: a caller still decides *when* to call `extractAndSave` (every turn? end of conversation? on a timer?) — this class only removes the boilerplate of dispatching on `KnowledgeExtractionResult` once that decision is made. Deliberately lives in `core-llm` (which already depends on `core-agent` for `ConversationContext`/`KnowledgeStore`) rather than `core-agent`, preserving `core-agent`'s zero dependency on `core-llm`; not invoked from `ObjectiveEngine`/`DroidCommandSession`. Unit-tested (`KnowledgeExtractionServiceTest`, scripted `LlmProvider` + real `InMemoryKnowledgeStore`): a successful extraction saves every entry with the given source, an empty extraction saves nothing, malformed text and a provider failure both save nothing and log exactly one `WARN` event with the failure detail in its fields, and omitting the logger entirely doesn't throw. |
| core-agent: Task/TaskGraph (domain model + validation, ROADMAP-064/-067) | IMPLEMENTED (first slice) | Added 2026-09-10 — `TaskGraph.kt`: `Task(id, description, dependencies, verificationCriteria)`, and `TaskGraph.from(tasks): TaskGraphResult` (`Valid(graph)`/`Invalid(errors)`), the only way to obtain a `TaskGraph` instance (no public constructor, so an invalid graph can never exist as a value). `from` fails closed and collects *every* structural error in one pass — an empty task list (`EmptyTaskGraph`), repeated ids (`DuplicateTaskId`), and any dependency naming a task that doesn't exist (`UnknownDependency`) are all reported together rather than one at a time; if none of those apply, one DFS pass (white/gray/black) both derives `executionOrder` (a deterministic, dependencies-before-dependents topological order — DFS post-order emission, no reversal needed, since a task's `dependencies` are its prerequisites) and detects cycles (`CyclicDependency`, carrying the actual cycle path; a self-dependency comes out as a length-2 `["a","a"]` path with no special-cased check needed). No LLM dependency — this is pure `core-agent` domain logic; see `core-llm.ObjectiveAnalyzer` below for what actually produces a `Task` list from a real objective. Unit-tested (`TaskGraphTest`): single task, linear chain, a diamond (deterministic ordering when two tasks share one dependency), duplicate ids, unknown dependencies, a self-dependency, a longer 3-node cycle with its real path, duplicate-id and unknown-dependency errors reported together, an empty list, `executionOrder` stability across repeated calls, and `task(id)` lookup. |
| core-agent: TaskGraphExecutor (dependency-ordered execution, ROADMAP-067) | IMPLEMENTED (first slice — sequential, stop-on-first-failure) | Added 2026-09-10 — `TaskGraphExecutor.kt`: `TaskRunner` (a `fun interface` executing one `Task` to an `ObjectiveOutcome`, almost always backed by a real `ObjectiveEngine`), and `TaskGraphExecutor.run(graph, runTask, isCancelled)` -> `TaskGraphOutcome` (`Completed`/`StoppedOnFailure(failedTask, failedOutcome, skippedTasks)`/`Cancelled`), running every task in `TaskGraph.executionOrder`. **First-slice scope, named rather than silently assumed:** a task ending in anything other than `AgentState.Completed`/`AgentState.Cancelled` (almost always `Failed`) stops the *entire* graph — every later task, including ones on an independent branch that didn't actually depend on the failed one, is reported in `skippedTasks` rather than run. This matches `MacroExecutor`'s already-shipped stop-on-first-failure precedent; skipping only a failed task's transitive dependents while continuing independent branches is real, more-correct behavior, deliberately deferred as a named future slice, as is parallel execution of independent tasks (this slice is strictly sequential, mirroring `KnowledgeStore`'s own literal-retrieval-first precedent). **A real constraint this class's own doc comment states explicitly:** `AgentStateMachine.transition` throws once terminal, and `ToolExecutor` itself binds to one `AgentStateMachine` at construction — so a `TaskRunner` backed by a real `ObjectiveEngine` must construct a *fresh* `AgentStateMachine` **and** a fresh `ToolExecutor` per task (only `registry`/`planner` are safe to reuse), the same "dedicated instance per independent task" rule `AgentStateMachine`'s own doc comment already states for `MacroScheduler`. Unit-tested (`TaskGraphExecutorTest`, a `ScriptedTaskRunner` fake matching `MacroExecutorTest`'s convention): full-run `Completed`, a `Failed` task stopping the graph with the correct `skippedTasks` and never invoking later tasks, a `Cancelled` outcome mapping to `TaskGraphOutcome.Cancelled` not `StoppedOnFailure`, the executor's own `isCancelled` stopping before the next task, task identity passed through unchanged, and all four logger events. |
| core-llm: ObjectiveAnalyzer (objective decomposition, ROADMAP-064) | IMPLEMENTED (interface + provider adapter; never exercised against a live provider) | Added 2026-09-10 — `ObjectiveAnalyzer.kt`, mirroring `LlmKnowledgeExtractor`'s exact adapter role: turns a high-level objective string into requirements, constraints, and a `TaskGraph` via a real `LlmProvider` call and a JSON-object system prompt (distinct from `LlmKnowledgeExtractor`'s JSON-array prompt). Returns `ObjectiveAnalysisResult` (`Success`/`InvalidTaskGraph`/`Malformed`/`ProviderFailed`) — a 4th case beyond the usual 3, since a provider returning syntactically valid JSON describing a cyclic or dangling-dependency task structure is a structurally different failure than unparseable text: `TaskGraph.from` is called on every successful parse, so an invalid graph is caught and reported as `InvalidTaskGraph`, never silently accepted as `Success`. Deliberately does not mutate the caller's `ConversationContext` the way `ObjectiveEngine.run` does — builds its own request from a snapshot of `context.messages` plus the objective as a trailing message. Unit-tested against a scripted `LlmProvider` (matching `LlmKnowledgeExtractorTest`'s convention): valid multi-task JSON with dependencies, zero tasks, a cyclic dependency, an unknown dependency id, malformed JSON, a provider error, an unexpected tool call, message/tools shape of the outgoing request, and DTO field defaulting. Like every other `core-llm-*` component, this prompt has never been exercised against a real provider — this environment has no LLM credentials. |
| core-llm: AnalyzedObjectiveRunner (ties ObjectiveAnalyzer to TaskGraphExecutor, ROADMAP-064/-067) | IMPLEMENTED | Added 2026-09-10 — `AnalyzedObjectiveRunner.kt`, the ROADMAP-064/-067 counterpart to `KnowledgeExtractionService`: `run(objective, runTask, context, isCancelled)` calls `ObjectiveAnalyzer.analyze`, and only on `Success` runs the resulting `TaskGraph` via `TaskGraphExecutor`, wrapping both in `AnalyzedObjectiveOutcome.Executed`; any other analysis result logs one `WARN` (`objective_analysis_failed`) and returns `AnalysisFailed` **without ever invoking `runTask`**. Unlike the `KnowledgeStore` -> `LlmKnowledgeExtractor` -> `KnowledgeExtractionService` sequence (three separate sessions to close the wiring loop), this composition class shipped in the same change as `ObjectiveAnalyzer`/`TaskGraphExecutor` since the design was already fully scoped. **Deliberately not auto-wired:** this is a new, separate, caller-chosen entry point a caller picks *instead of* calling `ObjectiveEngine.run` directly with a raw objective string — it touches none of `ObjectiveEngine.kt`/`DroidCommandSession.kt`/`Planner.kt`/`MacroExecutor.kt`, so every existing `ObjectiveEngine.run` caller sees zero behavior change, and `core-agent`'s zero dependency on `core-llm` is preserved. Unit-tested (`AnalyzedObjectiveRunnerTest`): a fully successful run, each of the three analysis-failure cases reporting `AnalysisFailed` with `runTask` invoked zero times, a mid-graph task failure surfacing as `Executed(StoppedOnFailure)`, and the `WARN` log on analysis failure. End-to-end proven by `AnalyzedObjectiveIntegrationTest` — a real `ObjectiveAnalyzer`/`TaskGraph`/`TaskGraphExecutor`/`ObjectiveEngine`/`LlmPlanner`, driven only by a scripted provider, analyzing a two-task objective and running both tasks in dependency order; this is also where the fresh-`AgentStateMachine`/`ToolExecutor`-per-task requirement gets a real, not just documented, test. |
| core-agent: ContextManager / DefaultContextManager (CAP-001, first slice) | IMPLEMENTED (generic provider model; see deviation note) | Added 2026-09-10 — `ContextManager.kt`. `docs/CAPABILITY_ROADMAP_PROMPT.md`'s P0.1 specifies a fixed-field `ContextSnapshot` (`userContext: UserContext`, `projectContext: ProjectContext`, `activePersona: Persona?`, `knowledgeGraphContext: KnowledgeGraphSnapshot`, ...) — none of those types exist anywhere in this repository (CAP-005/006/007, all MISSING per `docs/AUDIT_2026-09-05.md`). Rather than defining always-empty structs to satisfy the interface shape, this ships a generic, kind-tagged provider/contribution model instead: `ContextKind` enum names all 11 spec concepts (task/system-instructions/device/tool/execution/user/project/persona/knowledge/summary/conversation) in P0.2's own stated priority order (enum declaration order *is* the priority order, the same explicit-ordering discipline `TaskGraph.from` already documents for its own determinism), `ContextProvider` is the real extension point (`fun interface`, `provide(task): ContextContribution?`), and `DefaultContextManager.buildContext(task, tokenBudget)` aggregates every registered provider's contribution into one `ContextSnapshot` (`included`/`omitted`, `tokenBudgetUsed`/`Remaining`) — `omitted` makes the P0.1/P0.2 "provide token/context visibility where practical" requirement concrete rather than only a total. `ContextKind.TASK`/`SYSTEM_INSTRUCTIONS` are `mandatory`: always included even over budget, the same "never evict the thing that would silence the current turn" rule `ConversationContext.append` already applies to its own just-appended message. `registerProvider` is genuinely ready for all 11 kinds today; `USER`/`PROJECT`/`PERSONA`/`SUMMARY` simply have zero providers registered until CAP-005/006/007 land theirs, with zero change to this interface needed then. Built-in providers shipped in this file: `ConversationContextProvider` (formats `ConversationContext.messages`), `KnowledgeContextProvider` (wraps `formatKnowledgeContext`, taking a `query: (Task?) -> List<KnowledgeEntry>` function rather than a fixed tag — deciding what to retrieve is the same LLM-shaped policy decision `KnowledgeContext.kt`'s own doc comment already keeps out of `core-agent`), and `StaticContextProvider` (task-invariant content, e.g. system instructions). **Not built in this slice, named rather than silently skipped:** the spec's `TokenBudget` profile enum / `TokenBudgetManager` (`selectBudget` by task complexity, CAP-002) — `buildContext` takes a plain `Int` budget, a caller picks the number today; `TOOL`/`EXECUTION` provider adapters (kinds reserved, nothing real to adapt yet — `ObjectiveEngine`'s `lastObservation` already flows into `ConversationContext` as a `Role.TOOL` message, so a parallel path would be duplication, not a new capability); and wiring `ContextManager` into `ObjectiveEngine`/`DroidCommandSession` (deliberately caller-opt-in, matching `AnalyzedObjectiveRunner`/`KnowledgeExtractionService`'s own precedent). Unit-tested (`ContextManagerTest`/`ConversationContextProviderTest`/`KnowledgeContextProviderTest`): registration, priority ordering, budget truncation with `omitted` populated, mandatory-kind over-budget inclusion, multiple providers under one kind, `inspectContext()` reflecting registered counts and the last snapshot, and determinism across repeated calls. |
| core-tools-android: DeviceContextProvider (real ContextManager adapter for CAP-001's DEVICE kind) | IMPLEMENTED | Added 2026-09-10 — `DeviceContextProvider.kt`, a real `core-agent.ContextProvider` composing `DeviceController.getDeviceInfo()`/`getBatteryStatus()`/`getNetworkState()`/`getStorageInfo()` (all already-implemented, real methods) into one `ContextKind.DEVICE` contribution — the cross-module adapter pattern `core-llm.ObjectiveAnalyzer` already establishes (interface in the dependency-free module, adapter in the module with the real capability). Each of the four is independently `Success`/`Failure`; a `Failure` is rendered as `"<field>: unavailable (<reason>)"`, matching `NullDeviceController`'s explicit-failure convention — against it, every line honestly reads "unavailable", never a fabricated default, proven by `DeviceContextProviderTest`'s dedicated `NullDeviceController` case (plus an all-success and a mixed success/failure case, against the existing `ScriptedDeviceController` test fixture). |
| core-agent: TokenBudgetManager / DefaultTokenBudgetManager (CAP-002, first slice) | IMPLEMENTED (heuristic `selectBudget`; see deviation notes) | Added 2026-09-10 — `TokenBudgetManager.kt`, closing the rest of the gap `ContextManager`'s own row above left open. `TokenBudget` enum (`LIGHTWEIGHT`/`BALANCED`/`COMPREHENSIVE`/`FULL`, 1000/3000/8000/16000 tokens) is verbatim from the spec. `AllocatedContext` (the `allocateTokens` return type) is never actually defined anywhere in the roadmap prompt — only referenced — so it was designed here to mirror `ContextSnapshot` scoped to one `TokenBudget`. **`selectBudget(task)`, the real new capability, is a deliberately honest mechanical heuristic, not a classifier:** a deterministic score — `estimateTokens(task.description) + dependencies.size * 200 + verificationCriteria.size * 100` (reusing `estimateTokens`, the same ~4-chars/token approximation `ConversationContext` already documents as inexact) — mapped through constructor-injectable `ComplexityThresholds` (defaults 50/200/600) to one of the four budgets. No semantic understanding of the task; a genuinely LLM-informed classifier is named future `core-llm` work (a second `TokenBudgetManager`, mirroring `ModelRouter`'s multi-`LlmProvider` composition), not attempted here. **`allocateTokens(snapshot, budget)` reuses `allocateByPriority`** — the exact greedy/priority algorithm `DefaultContextManager.buildContext` already runs, extracted into a shared `internal` function in `ContextManager.kt` (a small, behavior-preserving refactor; the existing `ContextManagerTest` suite passed unchanged as the regression check) — to re-slice `ContextSnapshot.included` down to a smaller (or equal) budget. **A stated, honest limitation:** it can only narrow what `buildContext` already gathered — a contribution `buildContext`'s own original budget already excluded can never be recovered by allocating to a larger budget afterward, since it was never fetched into `included` to begin with; a caller wanting reallocation headroom should build once at `TokenBudget.FULL.tokens` and narrow down from there. `overflowStrategy` is always the literal `"truncate"` (one of the spec's own three named strategies) — a contribution that doesn't fit is dropped whole, never summarized (that needs an LLM call this dependency-free module correctly has none of). **A second stated deviation:** `inspectAllocation(): AllocationReport?` is nullable (the spec's signature is not) — `null` before any `allocateTokens` call, mirroring `ContextInspection.lastSnapshot`'s own honest "nothing built yet" signal rather than fabricating a placeholder report. **Not built in this slice:** `ContextManager.buildContext`'s signature is unchanged (still a plain `Int` budget, not the spec's `TokenBudget` — CAP-001's shipped signature was left alone); wiring into `ObjectiveEngine`/`DroidCommandSession` (deliberately caller-opt-in, same precedent as every prior aggregation-layer slice). Unit-tested (`TokenBudgetManagerTest`, 11 cases): budget escalation independently by description length/dependency count/verification-criteria count, determinism, custom thresholds, re-slicing a real snapshot (built via a real `DefaultContextManager` + a registered provider, not a hand-built fixture) down to a smaller budget, mandatory-contribution survival under a very small re-allocation, the "cannot recover an already-omitted contribution" limitation proven directly (not just documented), and `inspectAllocation()`'s null-before/reflects-after behavior. |
| core-agent: Scheduler / ScheduledExecutorServiceScheduler / MacroScheduler (ROADMAP-127's scheduling half) | IMPLEMENTED | Added 2026-09-09 — `Scheduler.kt` (a `Scheduler` interface plus `ScheduledExecutorServiceScheduler`, a real implementation backed by `java.util.concurrent.ScheduledExecutorService` on a daemon-threaded single-thread executor) and `MacroScheduler.kt`, which ties `MacroStore` to `MacroExecutor` via a `Scheduler`. This closes ROADMAP-127's last open half: a saved macro can now actually be made to run on a recurring cadence, not merely stored with a schedule string nothing reads — explicitly avoiding OD-008's documented anti-pattern (OpenDroid's own routine scheduler persisted a `"cron:<expr>"` string that no `WorkManager`/`AlarmManager` equivalent ever fired). The macro is re-loaded from `MacroStore` on every firing rather than captured once at schedule time, so an edit or delete takes effect on the next run without rescheduling. A firing that throws is caught inside `MacroScheduler` rather than allowed to propagate, since an uncaught exception from a `ScheduledExecutorService` task silently suppresses that schedule's every future firing — verified both by a fake-`Scheduler` unit test (deterministic, synchronous firing control) and by `ScheduledExecutorServiceSchedulerTest`, a real (not mocked) integration test proving actual background-thread firing, cancellation, and executor shutdown over real (short) wall-clock time. **Concurrency note (updated 2026-09-10 — see the AgentStateMachine row above):** `AgentStateMachine` is now safe against cross-thread visibility and race conditions in its own right, so a scheduled firing here and a live Pilot/Forge task sharing the same `ToolExecutor` can no longer corrupt or lose a transition. That is not the same as being correct to share: the two remain unrelated logical tasks whose transitions would still interleave into one meaningless sequence on a single shared instance — a `MacroScheduler` should still be given its own dedicated `ToolExecutor`/`AgentStateMachine`, separate from any actively-used session's. |
| app (Android shell) | PLANNED | No directory, Gradle file, or manifest exists yet — nothing scaffolded, and no Android SDK in this environment either (Section 7). Corrected 2026-09-05: an earlier version of this row implied a manifest/Gradle scaffold already existed on disk; it does not — see `docs/AUDIT_2026-09-05.md`. |
| core-llm: request/response/error types, LlmProvider interface | IMPLEMENTED | `LlmTypes.kt`, `LlmProvider.kt`, compiles |
| core-llm: LlmPlanner (Planner adapter) | IMPLEMENTED | `LlmPlanner.kt`, unit-tested, and exercised end-to-end with `ObjectiveEngine` in `ObjectiveEngineIntegrationTest` |
| core-llm: concrete provider (Anthropic) | IMPLEMENTED | `core-llm-anthropic.AnthropicLlmProvider`, real HTTP + real JSON, tested against a real local `HttpServer`; see Section 5e |
| core-llm: concrete provider (OpenAI / OpenAI-compatible) | IMPLEMENTED | `core-llm-openai.OpenAiLlmProvider`, real HTTP + real JSON, tested against a real local `HttpServer`; see Section 5f. No local-model-specific (non-OpenAI-shaped) provider exists yet |
| core-llm: ModelRouter (routing/fallback across providers) | IMPLEMENTED | Added 2026-09-05, `ModelRouter.kt` — itself an `LlmProvider`, so it composes with `LlmPlanner` and anything else built against the interface with no other change. Falls back to the next provider only on a transient failure (Network/Timeout/ModelUnavailable); never on Authentication/InvalidResponse/Cancelled, since retrying those against a different provider would mask a real config/bug signal. Unit-tested with scripted providers; never exercised against two real live providers together (would need credentials for both) |
| core-llm: ProviderType / AiProviderInfo / RegisteredProvider / LocalFirstOrdering (CAP-003, updated 2026-09-11 as part of CAP-004 — see row below) | IMPLEMENTED | `ProviderType.kt`. Originally shipped 2026-09-11 as `ProviderLocality`/`LocatedProvider` (a two-value `LOCAL`/`REMOTE` tag); consolidated the same day into the richer, spec-literal three-value `ProviderType` (`LOCAL`/`SELF_HOSTED`/`CLOUD`) plus the full `AiProviderInfo` CAP-004 needed anyway, rather than shipping two competing provider-metadata concepts — see the CAP-004 row below and `docs/AUDIT_2026-09-05.md`'s dated addenda for the full history. `AiProviderInfo` (id/name/type/maxContextTokens/available/cost/capabilities) is entirely caller-declared — never inferred from `LlmConfig.endpoint`, since a hostname is not a reliable locality signal, and no other field here has a real, introspectable source anywhere in this codebase either. `RegisteredProvider` pairs one `AiProviderInfo` with its real `LlmProvider`. `LocalFirstOrdering.order(providers): List<LlmProvider>` is unchanged in behavior (a pure stable sort, local-first, ties broken by input order) producing exactly the list `ModelRouter`'s constructor already accepts; it now sorts by `ProviderType.ordinal`, so `SELF_HOSTED` sits between `LOCAL` and `CLOUD`. `ModelRouter`/`LlmPlanner` are both untouched — zero changes to either file since this was first shipped. Unit-tested (`LocalFirstOrderingTest`, scripted `LlmProvider` fakes matching `ModelRouterTest`'s convention, no mocking): all-local and all-cloud inputs preserve relative order, a mixed input orders local before self-hosted before cloud preserving relative order within each tier, a dedicated self-hosted-sits-between case, empty input, determinism across repeated calls, and an integration-style case feeding the ordered list into a real `ModelRouter` proving the local provider is tried first and the cloud one is used as fallback. |
| core-llm: AiProviderSelector / DefaultAiProviderSelector / ProviderPreferences (CAP-004, first slice) | IMPLEMENTED (declared-metadata selection; see deviation notes) | Added 2026-09-11 — `AiProviderSelector.kt`, closing the gap the CAP-003 addendum named: `ModelRouter` only does fixed-order fallback on transient failure, with no `AiProviderInfo`/capability-aware, multi-factor *selection* logic. **Two deliberate, stated deviations from the roadmap prompt's literal P0.4 spec:** (1) `selectProvider` is a plain synchronous `fun`, not `suspend fun` — no module in this repository declares a coroutines dependency, direct or transitive, and every sibling interface (`LlmProvider.complete`, `Planner.decide`, `ContextManager.buildContext`, `TokenBudgetManager.allocateTokens`) is already synchronous; adding a new direct `kotlinx-coroutines-core` dependency for one interface would be inconsistent with the rest of the module. (2) `selectProvider` returns `LlmProvider?`, not a separate `AiProvider?` type — `LlmProvider` already is this codebase's provider abstraction; inventing a second, parallel one would be exactly the "second, competing model manager concept" `ModelRouter`'s own doc comment already says this codebase avoids. `ProviderPreferences` (designed fresh — nothing like it existed) is honestly scoped to what's mechanically checkable against a caller-declared `AiProviderInfo` and a real `Task`: `requiredCapabilities` (subset match), `minContextTokens` (explicit, or derived from `Task` via an optional injected `TokenBudgetManager` — real CAP-002 composition, not a fabricated number), `requireLocal` (excludes `CLOUD`), and `maxCostPerMillionInputTokens` (a provider with `cost == null` cannot be verified to meet it and is excluded, never assumed to pass). **Latency and resource (memory/GPU) requirements are deliberately not implemented** — no real measurement of either exists anywhere in this repository. `DefaultAiProviderSelector.selectProvider` filters to every eligible `RegisteredProvider`, returns `preferredProviderId`'s match if eligible, otherwise the local-first-ranked eligible provider (ties broken by registration order, the same determinism `LocalFirstOrdering` already applies) — falling back to normal ranking rather than `null` when the preferred id is unknown or ineligible, treating it as a hint rather than a hard requirement. **Not built in this slice:** config-driven multi-provider construction (`core-config` stays strictly single-provider) and reading `LlmConfig.provider`'s string to auto-construct a concrete provider class (nothing anywhere pattern-matches on it). Unit-tested (`AiProviderSelectorTest`, 13 cases, scripted `LlmProvider` fakes, no mocking): sole-eligible-provider selection, no-providers-registered returns `null`, unavailable-provider exclusion, missing-capability exclusion, explicit-minimum context exclusion, `Task`-complexity-derived minimum via a real `DefaultTokenBudgetManager` excluding a too-small provider, no filtering when no `TokenBudgetManager` is injected and no explicit minimum given, `requireLocal` excluding cloud, a cost ceiling excluding both an over-priced and an unknown-cost provider, `preferredProviderId` winning when eligible, an unknown/ineligible `preferredProviderId` falling back to normal ranking, tie-breaking by registration order, and `listProviders()` returning declared info unchanged in registration order. |
| core-agent: ConversationImporter / DefaultConversationImporter / ConversationParser (CAP-006, first slice) | IMPLEMENTED (two generic formats only; see deviation notes) | Added 2026-09-11 — `ConversationImport.kt`. P0.6's own diagram (File → Format Detection → Parser → Normalization → Validation → Conversation Records → Analyzer → Storage) gives no required Kotlin types, unlike P0.1/P0.2/P0.4/P0.5. `ConversationParser` folds detection and parsing into one interface (`canParse`/`parse`) rather than two competing concepts for what's always used together; `Message` (already `core-agent`'s record shape) is reused as P0.6's "Conversation Record" rather than inventing a parallel type; "Normalization" is implicit — every parser returns the same `List<Message>` regardless of source format. **Deliberately limited to two generic, vendor-neutral formats this codebase can define, parse, and verify entirely on its own terms:** `GenericJsonConversationParser` (a `[{"role":...,"content":...}]` array — the same shape `JsonFileConversationStore`'s own `MessageDto` already round-trips) and `PlainTextTranscriptParser` (`Role: content` line-prefixed transcripts, multi-line replies grouped until the next prefix). **No vendor-specific export parser (ChatGPT/Claude.ai/etc.) is attempted** — building one from training-data memory alone, with no real sample export in this environment to verify against, would be an unverified claim this codebase's honesty convention exists to prevent; named explicit future work once a real sample file is available. **"Analyzer" is scoped to real, structural, non-semantic stats only** (`ImportedConversationSummary`: message count, per-role counts, `estimatedTokens()`) — tone/vocabulary/style analysis is CAP-005 (Persona)'s own job and needs an LLM call this dependency-free module correctly has none of, the same reasoning `TokenBudgetManager.selectBudget`'s own doc comment already applies to its complexity heuristic. `DefaultConversationImporter` tries `parsers` in registration order, validates the parsed messages (non-empty, no blank content) before ever calling `store.save`, and reuses the existing `ConversationStore`/`ConversationContext` for "Storage" unmodified — a caller supplies whichever store they already use (e.g. `JsonFileConversationStore`), matching every prior CAP slice's caller-opt-in composition rather than forcing wiring into `ObjectiveEngine`/`DroidCommandSession`. Unit-tested (`ConversationImportTest`, 16 cases across three test classes, no mocking): `GenericJsonConversationParser` valid parse/order preservation, malformed-JSON and unknown-role failures, `canParse` true/false; `PlainTextTranscriptParser` multi-turn parsing, multi-line reply grouping, case-insensitive role prefixes, no-recognizable-prefix failure; `DefaultConversationImporter` end-to-end success through a real temp-directory `JsonFileConversationStore` (re-loadable after import), `UnrecognizedFormat`/`ParseFailed`/`ValidationFailed` each proven to never touch the store, correct summary counts, and registration-order parser selection. |
| core-agent: Persona / StyleProfile / PersonaStore / InMemoryPersonaStore / PersonaContextProvider (CAP-005, first slice) | IMPLEMENTED (in-memory store only; see deviation notes) | Added 2026-09-11 — `Persona.kt` plus a `PersonaContextProvider` appended to `ContextManager.kt`. P0.5's `Persona`/`StyleProfile` are shipped close to verbatim; `VocabProfile`/`StructureProfile`/`HumorProfile` are never defined by the roadmap prompt (the same "referenced but never defined" gap `AllocatedContext`/`ProviderPreferences`/`AiProvider` already hit) and were designed as small `String`-field data classes, consistent with the spec's own `String`-typed sibling fields (`tone`/`responseStructure`) rather than inventing rigid enums the spec never asked for; `Formality`/`Verbosity` *are* real enums, since the spec treats them as distinct named types unlike its `String` fields — a real signal, not an invented one. `PersonaStore`/`InMemoryPersonaStore` mirror `KnowledgeStore`/`InMemoryKnowledgeStore` exactly (`save`/`load`/`list`/`delete`, `synchronized` map). `PersonaContextProvider` closes the gap the CAP-001 addendum named ("`PERSONA` kind exists with zero adapters shipped") — mirrors `KnowledgeContextProvider`'s exact shape (a mechanical formatter taking an `activePersona: () -> Persona?` supplier, keeping "which persona is active" policy out of `core-agent`); a disabled or absent persona contributes nothing. **Critical Isolation (the spec's own hard requirement — persona must never override security/policy) holds by construction, not an added check:** `core-security`/`core-root` depend on neither `core-llm` nor this file's new types; a persona's `contextContribution` can only ever reach an `LlmRequest.systemPrompt`-shaped value. **Follow-up (2026-09-12, continuation session): `JsonFilePersonaStore` added, closing the gap this row previously named.** Mirrors `JsonFileKnowledgeStore`/`JsonFileMacroStore`'s exact shape — one JSON file per persona under a caller-supplied directory, `Persona.id` validated against the same `[A-Za-z0-9_-]+` `ID_PATTERN`, the resolved path re-checked to stay inside the directory before any read/write/delete (`InvalidPersonaId`, mirroring `InvalidKnowledgeEntryId`). Kept deliberately decoupled from `kotlinx.serialization` the same way `JsonFileMacroStore` already is: `Persona`/`StyleProfile`/`VocabProfile`/`StructureProfile`/`HumorProfile` carry no serialization annotations of their own — a private DTO tree in `JsonFilePersonaStore.kt` converts to/from them, storing every enum (`PersonaCategory`/`Formality`/`Verbosity`) by name and re-parsing via `valueOf` on load; an unrecognized value throws `IOException` naming the persona id, the same honesty `JsonFileKnowledgeStore`'s own unparseable-timestamp handling already applies to its one JSON-hostile field. **`PersonaActivationStatus` remains not built** (unchanged from this slice — still declared for spec completeness only, no staged/background pipeline exists to report interim status for). Unit-tested (`InMemoryPersonaStoreTest`, `PersonaContextProviderTest` in `ContextManagerTest.kt`, and now `JsonFilePersonaStoreTest`, 10 cases): save/load/list/delete/overwrite round-trip; a persona surviving a freshly reopened store instance over the same directory; every `StyleProfile` field (including nested profiles and lists) round-tripping; a path-separator or absolute-path-shaped id rejected without touching the directory; a hand-corrupted enum value on disk throwing `IOException` naming the persona id. |
| core-llm: LlmPersonaExtractor / PersonaManager / DefaultPersonaManager (CAP-005, first slice) | IMPLEMENTED (declared-metadata extraction, no automated style-match judgment; see deviation notes) | Added 2026-09-11 — `LlmPersonaExtractor.kt`/`PersonaManager.kt`, the third instance of the `LlmKnowledgeExtractor`/`KnowledgeExtractionService` two-file extractor/composition-service pattern (`ObjectiveAnalyzer`/`AnalyzedObjectiveRunner` is the second). `LlmPersonaExtractor.extract` concatenates every source `ConversationContext`'s messages (in order) into one request against a JSON-object system prompt, parses a flat DTO, fail-closed maps `formality`/`verbosity` strings to their enums (an unrecognized value is `Malformed`, the same handling `GenericJsonConversationParser` already applies to an unrecognized role), and rejects blank `tone`/`responseStructure`/`contextContribution` as `Malformed` too — kept at the simpler 3-case `KnowledgeExtractionResult` shape (`Success`/`Malformed`/`ProviderFailed`) rather than `ObjectiveAnalysisResult`'s 4-case one, since no graph-validation-shaped step exists here to warrant a 4th; a 4th case, `NoSourceConversations`, exists but is producible only by `DefaultPersonaManager`, never the extractor itself. **Two deliberate, stated deviations from the roadmap prompt's literal signatures**, the same reasoning `DefaultAiProviderSelector`'s own row already documents: every method is a plain `fun`, not `suspend fun` (still zero coroutines exposure anywhere in this repository); and `createPersonaFromConversation(files: List<File>, ...)` becomes `createPersonaFromConversations(sourceConversationIds: List<String>, ...)`, resolving ids via the existing `ConversationStore` rather than taking a raw `java.io.File` — confirmed by grep to have zero precedent anywhere in `core-agent`/`core-llm`/`core-config`, composing with CAP-006's `ConversationImporter` instead of duplicating file-reading. `DefaultPersonaManager.createPersonaFromConversations` resolves only the ids that exist, calling the extractor with just those — `Persona.sourceConversations` on the result honestly reflects only what was actually analyzed, never the full originally-requested list; zero resolved ids short-circuits to `NoSourceConversations` without ever calling the extractor. `setActivePersona`/`testPersona`/`listPersonas` round out the interface; `testPersona` drives one real request per sample prompt using the persona's `contextContribution` as `LlmRequest.systemPrompt` and returns the raw responses — **it deliberately does not judge whether a response matches the persona's style**, which would need a second, separate LLM-as-judge call this slice does not attempt, the same "stays structural, not semantic-judging" restraint CAP-006's own "Analyzer" step already applies to itself. **Not built in this slice:** automated style-match scoring; a version-bump/update method beyond initial creation (`version` is always `"1"`); wiring into `ObjectiveEngine`/`DroidCommandSession` (caller-opt-in, matching every prior CAP slice). Unit-tested (`LlmPersonaExtractorTest`, 11 cases; `DefaultPersonaManagerTest`, 9 cases; scripted `LlmProvider` + real `InMemoryPersonaStore`/`InMemoryConversationStore`, no mocking): full-field mapping from valid JSON, multi-conversation request concatenation, malformed JSON, unrecognized formality/verbosity, blank required fields, provider error, unexpected tool call, custom id generator; manager-level partial/zero conversation resolution, malformed/provider-failure logging, `setActivePersona` true/false verified by re-loading from the store, `testPersona` not-found and real multi-prompt round trip, `listPersonas`. |
| core-agent: EntityType / Entity / Relationship / KnowledgeGraph / InMemoryKnowledgeGraph / JsonFileKnowledgeGraph / GraphContextProvider (CAP-007, first slice) | IMPLEMENTED | Added 2026-09-11 — `KnowledgeGraph.kt`/`JsonFileKnowledgeGraph.kt`, closing the gap the CAP-### reconciliation named for CAP-007: `KnowledgeStore` is a flat id/tag/content store with no distinct entity types and no relationships between entries — "a knowledge *store* exists; a knowledge *graph* does not." Unlike every other P0.x section, P0.7 gives no concrete Kotlin types, only a bulleted "potential entities" list and two requirement sentences (relationships + retrieval, graph-based rather than indiscriminate); this slice designs the concrete shape itself, following the exact `KnowledgeStore`/`JsonFileKnowledgeStore` interface+`InMemory*`+`JsonFile*` precedent. `EntityType` maps the roadmap's 15-item "potential entities" list 1:1 (`NOTE`/`AUTOMATION`/`DEVICE`/`COMMAND`/`LOG`/`PROJECT`/`CONCEPT`/`VARIABLE`/`PLUGIN`/`AI_CONTEXT`/`DOCUMENT`/`CONVERSATION`/`PERSONA`/`TASK`/`EXECUTION_TARGET`), no additions or omissions. `Relationship.type` is a free string, not a closed enum — the spec defines no relationship taxonomy, and `Relationship`/`Entity` mirror `KnowledgeEntry.tags`/`source` in staying free-text rather than inventing one the spec never asked for. `addRelationship` throws `UnknownEntityException` if either endpoint doesn't resolve to a saved `Entity` — a dangling edge would silently break traversal, so it's rejected at write time; `removeEntity` cascades to remove every relationship touching the removed id, in both implementations. **`traverse(startId, maxDepth, relationshipType)` is this slice's concrete answer to P0.7's "graph-based retrieval... instead of indiscriminately loading all stored information"**: direction-agnostic BFS, cycle-safe (visited-set dedup), optionally type-filtered, excluding the start id, deterministic across calls. `JsonFileKnowledgeGraph` persists each `Entity`/`Relationship` as one JSON file under independent `entities/`/`relationships/` subdirectories, reusing `JsonFileKnowledgeStore`'s exact id-allow-list + normalize-then-`startsWith` path-escape guard for both id spaces independently; `entitiesByType`/`searchEntities`/traversal all genuinely re-read from disk, same "no in-process cache" discipline `JsonFileKnowledgeStore.findByTag`/`search` already document. `GraphContextProvider` (appended to `ContextManager.kt`, formatting via a new `formatGraphContext()` in `KnowledgeContext.kt`) registers under the *same* `ContextKind.KNOWLEDGE` slot `KnowledgeContextProvider` already uses — that kind already means "Local Knowledge Graph" per the CAP-001 row's own mapping — taking a `query: (Task?) -> List<Entity>` function rather than owning retrieval policy, the same boundary `KnowledgeContextProvider`/`PersonaContextProvider` already establish. **Not built in this slice:** no new Gradle module (spec's `core-knowledge-graph` folded into `core-agent`, the same call CAP-004/005/006 already made for their own suggested modules); no semantic/embedding retrieval (`searchEntities` stays literal-substring, same honesty posture `KnowledgeStore.search` states for itself); no automatic linkage to existing `KnowledgeEntry`/`Persona`/`Task`/conversation records (a caller may reference one via `Entity.properties` at their own discretion); no UI/visualization; no wiring into `ObjectiveEngine`/`DroidCommandSession` (caller-opt-in, matching every prior CAP slice). Unit-tested (`InMemoryKnowledgeGraphTest`, 19 cases; `JsonFileKnowledgeGraphTest`, 11 cases; `GraphContextProviderTest`, 2 cases in `ContextManagerTest.kt`): entity/relationship CRUD, `UnknownEntityException` on both missing endpoints, cascading delete (both implementations, the file-backed one proven to re-read from disk not a cache), direction-agnostic `neighbors`, multi-hop `traverse` with depth limit, cycle safety, and relationship-type filtering, path-escape rejection for both entity and relationship ids, and cross-instance persistence. |
| core-llm: LlmGraphExtractor / GraphExtractionService (CAP-007, second slice) | IMPLEMENTED (interface + provider adapter; never exercised against a live provider) | Added 2026-09-11 — `LlmGraphExtractor.kt`/`GraphExtractionService.kt`, the 4th instance of the `LlmKnowledgeExtractor`/`KnowledgeExtractionService` extractor/composition-service pattern (`ObjectiveAnalyzer`/`AnalyzedObjectiveRunner` and `LlmPersonaExtractor`/`PersonaManager` are the 2nd and 3rd), giving the graph a real population path rather than shipping an empty structure nobody fills. The prompt asks for one JSON object (`{"entities": [...], "relationships": [...]}`); each entity carries a `key` the model invents purely as a **local, response-scoped** reference to link relationships to entities within that one response — never a real id, and never stored or returned — `LlmGraphExtractor.parse` resolves every `key`/`fromKey`/`toKey` to a real generated id (default `UUID.randomUUID()`, already `JsonFileKnowledgeGraph.ID_PATTERN`-safe) before returning `Entity`/`Relationship` values, the same "model never invents a real id" restraint `LlmKnowledgeExtractor`'s own doc comment states. `GraphExtractionResult` fails closed on invalid JSON, an entity `type` string outside `EntityType.entries` (the same "unrecognized enum value is Malformed" handling `LlmPersonaExtractor` already applies to `formality`/`verbosity`), and a relationship whose `fromKey`/`toKey` doesn't match any entity `key` declared in the same response. `GraphExtractionService.extractAndSave` saves every entity *before* any relationship — load-bearing ordering, since `KnowledgeGraph.addRelationship` requires both endpoints to already exist — logging `INFO`/`WARN` via an optional `Logger` (default `NoOpLogger`), the exact `KnowledgeExtractionService` shape. `source` is logged but not stored on `Entity`/`Relationship` (neither type carries a `source` field the way `KnowledgeEntry` does) — a named gap, not a silently invented field. Deliberately does not call `KnowledgeGraph.addEntity`/`addRelationship` itself (extractor stays storage-free) and is not auto-invoked from `ObjectiveEngine`/`DroidCommandSession`, same restraint as every prior extractor in this codebase. Unit-tested (`LlmGraphExtractorTest`, 10 cases; `GraphExtractionServiceTest`, 5 cases; scripted `LlmProvider`, no mocking): full entity+relationship parse with key-to-id resolution verified, empty-lists `Success`, generated-id pattern match, custom id generator, malformed JSON, unrecognized entity type, a relationship referencing an unknown key, provider error, unexpected tool call, no-tools request shape; service-level entities-before-relationships save ordering (verified against a real `InMemoryKnowledgeGraph`), empty extraction, malformed/provider-failure logging without throwing, and no-logger-given not throwing. Like every other `core-llm` extraction prompt, this has never been exercised against a real provider — this environment has no LLM credentials. |
| core-tools-android: domain model (UiNode/UiTree/Selector/Rect) + UiTreeRenderer | IMPLEMENTED | Compiles, unit-tested — pure Kotlin, no Android dependency |
| core-tools-android: DeviceController + Tool wrappers (tap/swipe/type/pressKey/launchApp/findElement/tapElement/getUiTree/listInstalledApps/takeScreenshot/getDeviceInfo) | IMPLEMENTED | Unit-tested against a scripted `DeviceController` fake, incl. invalid-input paths that never call the device. `get_device_info` (`GetDeviceInfoTool`) added 2026-09-09 — the interface method and result type existed since the original audit, but no `Tool` wrapper exposed it until now |
| core-tools-android: file operations tools (read_file/write_file/move_file/copy_file/delete_file/list_directory) | IMPLEMENTED (interface + wrapper), PLANNED (real device execution) | Added 2026-09-09 (ROADMAP-038) — `FileTools.kt`, `DeviceController` gained `readFile`/`writeFile`/`moveFile`/`copyFile`/`deleteFile`/`listDirectory`; unit-tested against a scripted `DeviceController` fake, incl. invalid-input paths that never call the device. All six are `SecurityLevel.SENSITIVE`. Only `NullDeviceController` backs them so far, same as every other device tool in this package |
| core-tools-android: system tools (get_battery_status/get_network_state/get_storage_info/get_clipboard/set_clipboard) | IMPLEMENTED (interface + wrapper), PLANNED (real device execution) | Added 2026-09-09 (ROADMAP-037, partial — battery/network/storage/clipboard only; intents and notifications, the other two items the master prompt names in this category, are not covered) — `SystemTools.kt`, `DeviceController` gained `getBatteryStatus`/`getNetworkState`/`getStorageInfo`/`getClipboardText`/`setClipboardText`; unit-tested against a scripted `DeviceController` fake. Reads are `SecurityLevel.NORMAL`; both clipboard directions are `SecurityLevel.SENSITIVE`. Only `NullDeviceController` backs them so far |
| core-tools-android: communications tools (send_sms/make_call/list_contacts) | IMPLEMENTED (interface + wrapper), PLANNED (real device execution) | Added 2026-09-09 (ROADMAP-039) — `CommunicationsTools.kt`, `DeviceController` gained `sendSms`/`makeCall`/`listContacts`; unit-tested against a scripted `DeviceController` fake, incl. invalid-input paths that never call the device. All three are `SecurityLevel.SENSITIVE` — SMS/calls have a real-world side effect outside the device, and contacts are real PII, not metadata. Only `NullDeviceController` backs them so far |
| core-tools-android: productivity tools (create_calendar_event/set_alarm/set_timer/set_reminder) | IMPLEMENTED (interface + wrapper), PLANNED (real device execution) | Added 2026-09-09 (ROADMAP-040) — `ProductivityTools.kt`, `DeviceController` gained `createCalendarEvent`/`setAlarm`/`setTimer`/`setReminder`; unit-tested against a scripted `DeviceController` fake, incl. invalid-input paths that never call the device. All four are `SecurityLevel.SENSITIVE` — each schedules something that surfaces to the device owner later. Only `NullDeviceController` backs them so far |
| core-tools-android: media tools (media_play_pause/media_next/media_previous/set_volume) | IMPLEMENTED (interface + wrapper), PLANNED (real device execution) | Added 2026-09-09 (ROADMAP-041) — `MediaTools.kt`, `DeviceController` gained `mediaPlayPause`/`mediaNext`/`mediaPrevious`/`setVolume`; unit-tested against a scripted `DeviceController` fake, incl. `set_volume`'s missing/invalid/out-of-range input paths that never call the device. All four are `SecurityLevel.SENSITIVE`, consistent with every other mutating tool in this package. Only `NullDeviceController` backs them so far |
| core-tools-android: navigation tool (navigate_to) | IMPLEMENTED (interface + wrapper), PLANNED (real device execution) | Added 2026-09-09 (ROADMAP-042) — `NavigateToTool.kt`, `DeviceController` gained `launchNavigation(destination, mode)` (`NavigationMode`: DRIVING/WALKING/BICYCLING/TRANSIT, defaults to DRIVING when omitted); unit-tested against a scripted `DeviceController` fake, incl. missing-destination and unknown-mode paths that never call the device. `SecurityLevel.SENSITIVE`, matching `launch_app`. This closes the last of the six master-prompt Phase 5 device-tool categories (system/file/communications/productivity/media/navigation) — each at least started. Only `NullDeviceController` backs it so far |
| core-tools-android: core-security integration | IMPLEMENTED | `DeviceToolSecureExecutorIntegrationTest` — a denied tap never reaches the device controller |
| core-tools-android: NullDeviceController | IMPLEMENTED (explicitly non-real) | Every method fails explicitly ("no real device is connected"); never fabricates a successful tap, swipe, UI-tree read, file operation, communications action, scheduled productivity action, media control, or navigation launch |
| core-tools-android: a real Android-backed DeviceController | PLANNED | Needs the Android SDK (to compile against real Accessibility/PackageManager APIs) and a connected/emulated device, neither present in this environment |
| core-shell: ShellCommand / ShellSecurityPolicy / ShellExecutor | IMPLEMENTED | Compiles, unit-tested |
| core-shell: ProcessBuilderShellExecutor | IMPLEMENTED (real, not mocked) | Tested against real subprocesses (`echo`/`true`/`false`/`sleep`/`pwd`/`env`/`seq`) — real timeout, real cancellation, real working-directory containment, real fail-closed executable allow-list, real output truncation |
| core-shell: ShellTool + core-security integration | IMPLEMENTED | `ShellToolSecureExecutorIntegrationTest`, using the real executor — a denied command provably never spawns a process |
| core-shell: LocalProcessExecutionTarget (CAP-011, Execution Target Abstraction, first slice) | IMPLEMENTED (see deviation notes) | Added 2026-09-12 — `LocalProcessExecutionTarget.kt`, the P1.3 "refactor the real `ProcessBuilderShellExecutor` behind an abstraction" instruction done as an additive wrapper: delegates every `execute` call to an injected `ShellExecutor` (typically `ProcessBuilderShellExecutor`) rather than changing `ShellExecutor`/`ShellTool` or any existing caller/test. The new `ExecutionTarget`/`ExecutionContext`/`PrivilegeLevel`/`ExecutionResult` types live in `core-security` (`ExecutionTarget.kt`), next to `CapabilityId`/`ExecutionTargetType`/`ExecutionRequest`/`ExecutionResponse` (CAP-008) they're the direct sibling of — `core-shell` already depended on `core-security`, so no new module dependency was needed. **Two deliberate deviations from the roadmap prompt's literal shape, both precedented elsewhere in this document:** (1) `isHealthy`/`execute` are plain synchronous `fun`, not `suspend fun` — no module in this dependency chain declares a coroutines dependency, the identical gap `ai.droidcommand.llm.AiProviderSelector`'s own doc comment already documents for `selectProvider`; (2) `ExecutionResult.timedOut` is derived by matching `ShellExecutionResult.Failure.reason` against the literal `"Command timed out"` prefix `ProcessBuilderShellExecutor` uses, since `ShellExecutionResult.Failure` carries no distinct timeout signal of its own and widening it would ripple through every existing `ShellExecutor` caller/test — an honest, fragile-but-only-available signal, not invented. `ExecutionResult.verified` is honestly always `false`: no target in this repository re-queries state after running a command to confirm the exit code reflects reality. `isHealthy()` always returns `true` — a local process has no external liveness dependency to check, unlike a future Docker/remote-host/Proxmox target. Unit-tested (`ExecutionContextTest`/`ExecutionResultTest`/`ExecutionTargetTest` in `core-security`, 5 cases; `LocalProcessExecutionTargetTest` in `core-shell`, 10 cases): field carriage for the new `core-security` types; `LOCAL_PC` type/`isHealthy() == true`/id-context-capabilities pass-through/empty-argv-fails-without-calling-the-executor/argv-splitting-and-parameter-forwarding/null-env-becomes-empty-map/`Success`-mapping/non-timeout-`Failure`-mapping/timeout-`Failure`-mapping. |
| core-root: RootCommand / RootSecurityPolicy / RootExecutor | IMPLEMENTED | Compiles, unit-tested. `RootCommand` widened 2026-09-12 to carry `workingDirectory: String?`/`environment: Map<String, String>`, mirroring `core-shell.ShellCommand` — see the `RootExecutionTarget` row below for the full record of what changed and why |
| core-root: PolicyEnforcingRootExecutor | IMPLEMENTED | Unit-tested — rejects a command outside its allow-list without reaching the delegate; fail-closed by default (empty allow-list) |
| core-root: RootTool + core-security integration | IMPLEMENTED | `RootToolSecureExecutorIntegrationTest` exercises the full root test matrix (root disabled, root unavailable, user denies, approved-and-executed, command failure) against real `SecureToolExecutor`/`SecurityPolicyEnforcer` |
| core-root: NullRootExecutor | IMPLEMENTED (explicitly non-real) | `isRootAvailable()` truthfully returns false; `execute()` fails explicitly rather than fabricating a successful elevated command |
| core-root: a real rooted-device RootExecutor | PLANNED | Needs an actual rooted device this environment does not have |
| core-root: RootTool opt-in grant requirement (e.g. distinguishing AI-initiated `ai_root` from device-owner root) | IMPLEMENTED | Added 2026-09-05 — `RootTool(executor, grantCapability = "ai_root")`; `RootToolGrantIntegrationTest` proves a live single-use grant permits exactly one execution then is spent, and that no grant/no store denies without ever reaching the executor. Opt-in (defaults to `null`, so existing `RootTool(executor)` callers are unaffected) |
| core-root: `RootProvider` (generic root abstraction) + `NullRootProvider` | IMPLEMENTED | Added 2026-09-10 — `RootProvider` extends `RootExecutor` (adds `info`/`isAuthorized()`/`getPrivilegeLevel()`/`checkHealth()`/`getCapabilities()` on top of the existing `isRootAvailable()`/`execute()` contract, so every `RootProvider` plugs into `PolicyEnforcingRootExecutor`/`RootTool`/`SecurityPolicy.rootAvailable` with zero adapter code). `NullRootProvider` is the explicit-failure default, matching the `Null*` convention |
| core-root: `MagiskProvider` | IMPLEMENTED (real detection/execution logic; real on-device Magisk verification IMPLEMENTED — NOT RUNTIME VERIFIED, no rooted device/Magisk install in this environment) | Added 2026-09-10 — real `ProcessBuilder`-backed detection: `isMagiskInstalled()` (marker file/dir presence OR a `magisk` executable that actually starts — presence only, never treated as proof root is functional, per this addition's own governing rule), `getMagiskVersion()` (parses real `magisk -v` output), a cached (60s TTL, DP-011's already-recommended cadence) root-shell probe (`su -c "id -u"`, accepts only exact `0`) backing `isRootAvailable()`/`isAuthorized()`/`getPrivilegeLevel()`, `checkHealth(probeShell)` (defaults to a passive, non-probing check — a caller must opt in to actually spawning `su`, since that can trigger a real Magisk authorization prompt on a device), and real privileged command execution via `su -c` with every argument individually shell-quoted (`shellQuote`, proven injection-safe by `MagiskProviderTest`). Module list/inspect/enable/disable/install/remove are deliberately NOT implemented — cannot be verified without a real Magisk install, so none are stubbed. Tested against real, controlled fixtures (temp-dir marker files, real fake `su`/`magisk` shell scripts spawned as real subprocesses) — 26 new tests (`NullRootProviderTest`, `MagiskProviderTest`, `MagiskProviderSecureExecutorIntegrationTest`, the last proving the real `MagiskProvider` — not a scripted double — flows through the existing `SecureToolExecutor`/`SecurityPolicyEnforcer` gate unchanged). Built-in Terminal integration and UI are correctly not built — no UI (`app` module) exists anywhere in this repository yet; `CapabilityRegistry`/`ExecutionRouter` integration is now real, see the `RootCapabilityHealthChecker` row below (see `docs/AUDIT_2026-09-05.md`'s CAP-020 row for the remaining Terminal/UI gap) |
| core-root: `RootCapabilityHealthChecker` (CAP-009 registry integration, first real `CapabilityHealthChecker`) | IMPLEMENTED (see deviation notes) | Added 2026-09-12 — `RootCapabilityHealthChecker.kt`, wrapping any `RootProvider` (Magisk today; a future KernelSU/APatch provider without any change here, per `RootProvider`'s own "core application depends on the abstraction" design) as a real `core-security.CapabilityHealthChecker` — the first non-scripted-fake implementation of that interface, closing the gap the CAP-009 entry above named. `verify` calls `RootProvider.checkHealth(probeShell = false)` by default, matching `RootProvider.checkHealth`'s own "never silently trigger a root-authorization prompt from a routine check" rule (a caller can opt into `probeShell = true` via the constructor for a deliberate on-demand full re-verify). `RootProviderState`'s 8 values map exhaustively (no `else` branch needed) onto `CapabilityState`'s larger 11-value set — a total, lossless mapping. `riskTier` defaults to `RiskTier.DESTRUCTIVE` (real, but arbitrary — a first reasonable default, the same honesty `RiskApprovalPolicy`'s own defaults already claim for themselves) and is caller-overridable; `reverifyIntervalMs` defaults to 60 seconds, matching `MagiskProvider`'s own root-shell probe cache TTL (DP-011's recommended cadence). A `null` `RootProviderInfo.version` becomes the literal string `"unknown"` rather than a fabricated value. **Not built in this slice:** no wiring into `CapabilityRegistry.register`/any default capability set — a caller must still construct and register a `CapabilityMetadata` naming this checker itself; `core-root` does not itself depend on anything new (`CapabilityHealthChecker`/`CapabilityId`/`CapabilityMetadata`/`CapabilityState`/`RiskTier` all come from `core-security`, which `core-root` already depended on). Unit-tested (`RootCapabilityHealthCheckerTest`, 14 cases): the full 8-state mapping table; null-version fallback and real-version passthrough; `lastError` passthrough; a healthy result carrying no `lastError`; `lastVerifiedAt` set to a real, recent timestamp; description naming the provider; `riskTier` default and override; the given `providerId` used verbatim rather than the provider's own `info.providerId`; `probeShell` defaulting to `false` and being forwarded when set; `suggestedReverifyIntervalMs` default and override. Also integration-tested against a real `MagiskProvider` (`RootCapabilityHealthCheckerMagiskIntegrationTest`, 2 cases, matching `MagiskProviderSecureExecutorIntegrationTest`'s "real component, not a scripted double" discipline): no marker present → `UNAVAILABLE`; a marker present but never probed → `REQUIRES_PERMISSION` — both real `MagiskProvider` behavior against temp-dir fixtures, no `su`/`magisk` script needed for either case. |
| core-root: `RootExecutionTarget` (CAP-011, second real ExecutionTarget implementation) | IMPLEMENTED (see deviation notes) | Added 2026-09-12 — `RootExecutionTarget.kt`, the second real `core-security.ExecutionTarget` in this repository (`core-shell.LocalProcessExecutionTarget` was the first) — wraps any `RootExecutor` (typically `MagiskProvider`), giving `DefaultExecutionRouter` (CAP-012) a genuinely different second target type (`ExecutionTargetType.ANDROID`, versus `LocalProcessExecutionTarget`'s `LOCAL_PC`) and privilege level to route between, rather than only ever exercising its least-privilege selection against scripted fakes. `isHealthy()` delegates to `RootExecutor.isRootAvailable()` — genuinely checks, unlike `LocalProcessExecutionTarget`'s unconditional `true` (a local process has no external liveness dependency; root does). `timedOut` is derived the same way `LocalProcessExecutionTarget`'s already is: matching `RootExecutionResult.Failure.reason`'s literal `"Command timed out"` prefix, since `MagiskProvider.execute` reports a timeout via that same string pattern (confirmed by reading `MagiskProvider.kt` directly) and `RootExecutionResult.Failure` carries no distinct timeout field either. `verified` stays honestly `false`. **Follow-up (2026-09-12, continuation session): `RootCommand` widened to carry `workingDirectory`/`environment`, closing the limitation this row originally documented.** `RootCommand` gained `workingDirectory: String? = null` and `environment: Map<String, String> = emptyMap()`, matching `core-shell.ShellCommand`'s shape exactly. `ProcessRunner.runProcess` (internal, shared by every `MagiskProvider` probe and its real `execute`) gained matching optional parameters, applied to the `ProcessBuilder` it starts exactly like `ProcessBuilderShellExecutor` already does for `ShellCommand` — every pre-existing caller (the three detection probes) is unaffected since both new parameters default to `null`/empty. `MagiskProvider.execute` now threads `RootCommand.workingDirectory`/`environment` through to `runProcess`, applied to the outer `su` process it starts. **Honest caveat, stated rather than overclaimed:** whether a given `su` binary actually preserves the calling process's working directory/environment once it elevates to root is a property of that `su` implementation, not of this code — no implementation in this repository guarantees it, and `MagiskProviderTest`'s new cases prove only what this JVM-only environment can prove (a real subprocess, real `ProcessBuilder.directory()`/`environment()`, a real nested shell invocation — not a real rooted device's actual `su`). `RootExecutionTarget.execute` no longer refuses a non-null `workingDir`/`env`: both now flow straight into the `RootCommand` it constructs, exactly like `LocalProcessExecutionTarget` already does for `ShellCommand`. `RootTool` also gained `workingDirectory` input parsing, mirroring `core-shell.ShellTool`'s existing input shape (its own `environment` field stays constructor/`ExecutionTarget`-only, matching `ShellTool`'s identical choice not to expose `ShellCommand.environment` as flat tool input either). **Not built in this follow-up:** a cross-module integration test exercising `LocalProcessExecutionTarget` and `RootExecutionTarget` together through one `DefaultExecutionRouter` — still blocked on the same `core-shell`/`core-root` sibling-module fact this row has named since it was first written, unrelated to this widening (since resolved — see the new `core-integration-tests` module row below). Unit-tested (`RootExecutionTargetTest`, 9 cases, updated: the two former "fails outright" cases replaced by one proving `workingDir`/`env`/`timeoutMs` are all forwarded into the real `RootCommand` and one proving a `null` `env` forwards an empty map, not `null`, matching `LocalProcessExecutionTargetTest`'s identical cases; `MagiskProviderTest` gained 3 cases: a real `pwd` reflecting a supplied `workingDirectory`, a real nested shell invocation seeing an injected environment variable, and the no-argument defaults matching the JVM process's own cwd; `RootToolTest` gained 2 cases: `workingDirectory` parsed from tool input and forwarded, and defaulting to `null` when absent). |
| core-root: `RootCapabilityRegistryIntegrationTest` (CAP-009 registry ↔ RootCapabilityHealthChecker, end-to-end) | IMPLEMENTED | Added 2026-09-12 — no new production code, only a test proving `core-security.InMemoryCapabilityRegistry` and `RootCapabilityHealthChecker` genuinely work together through a real `MagiskProvider` (pointed at fixture paths that guarantee "not installed," since no real Magisk exists in this environment), not just two pieces that happen to compile against each other — matching every `*SecureExecutorIntegrationTest`'s "real component, not a scripted double" discipline. Three cases: registering a `root.shell` `CapabilityMetadata` then `reverify`-ing it through the real checker updates and persists the new state; `invalidate` followed by `CapabilityMetadata.isStale` (using the checker's own `suggestedReverifyIntervalMs`) correctly flags a previously-fresh capability as due for recheck; `listCapabilities(CapabilityFilter(riskTier = ...))` finds the registered capability by its `RiskTier`. |
| core-integration-tests: `RouterCrossModuleIntegrationTest` (`DefaultExecutionRouter` across two real, independently-built `ExecutionTarget`s) | IMPLEMENTED | Added 2026-09-12 — the 16th module, new-created, test-only (no `src/main`). Closes the "cross-module integration test putting `LocalProcessExecutionTarget` and `RootExecutionTarget` through one `DefaultExecutionRouter` together" candidate named in every addendum entry since `RootExecutionTarget` first shipped, blocked purely on the module-boundary fact that `core-shell` and `core-root` are siblings (neither depends on the other) and no existing module depended on both. **Scoped before implementing** (three options weighed: a new dedicated module; a `testImplementation` edge from `core-security` onto both; a `testImplementation` edge from one sibling onto the other) — the dedicated module was chosen because `core-security` is the module every concrete `ExecutionTarget` provider depends on, and P2–P6 plan several more of them (Termux, Docker, WireGuard, Proxmox, VNC/X11, SSH); making `core-security`'s own test classpath grow to know about each one as it ships would invert that foundational-module shape, and a `testImplementation` edge between `core-shell` and `core-root` would make the "neither depends on the other" invariant this document has repeated false, even if only in test scope. Confirmed a `testImplementation` edge does not create a Gradle cycle before choosing between options: module A's test configuration depending on module B's main configuration, while B's main depends on A's main, is a valid DAG (no edge points back into A's own test configuration). **A correction to the framing carried by every prior entry naming this candidate, found by reading `ExecutionRouter.kt` directly before writing the test:** `DefaultExecutionRouter.routeExecution` filters `availableTargets` to `it.type == request.targetType` *before* any least-privilege comparison — and `LocalProcessExecutionTarget`'s type (`LOCAL_PC`) differs from `RootExecutionTarget`'s (`ANDROID`), so a single `routeExecution` call never actually makes these two targets race for least-privilege against each other the way earlier entries' framing implied; whichever type wasn't requested is filtered out immediately. What this test proves instead, and what was genuinely untested before it: the router, given a heterogeneous list of real targets built from two modules that know nothing about each other, correctly isolates the one matching the request and ignores the other — using two genuine implementations (`ProcessBuilderShellExecutor`+`ShellSecurityPolicy` for the shell target, `MagiskProvider` against a real scripted `su` fixture for the root target) rather than `DefaultExecutionRouterTest`'s scripted `RoutableFakeTarget`. Unit-tested (`RouterCrossModuleIntegrationTest`, 3 cases): a `LOCAL_PC` request with both real targets in `availableTargets` routes to the shell target and its real `execute()` genuinely runs `echo`; an `ANDROID` request with the same list routes to the root target and its real `execute()` genuinely runs through the scripted `su`; a request for a type neither target declares (`DOCKER`) yields `NoSuitableTarget`. |
| core-build: domain model (BuildRequest, ProjectType, BuildTarget, ArtifactType, BuildError, BuildResult, BuildEvent) | IMPLEMENTED | Compiles, unit-tested; see docs/CORE_BUILD.md |
| core-build: WorkspaceManager / WorkspacePathValidator (real filesystem, path security) | IMPLEMENTED | Real java.nio.file operations, unit-tested incl. traversal/absolute-escape/symlink-adjacent cleanup containment |
| core-build: BuildPipeline (orchestrator) | IMPLEMENTED | Unit-tested for every stage's success/failure path, cancellation, and a simulated timeout via a fake clock |
| core-build: SystemBuildEnvironmentDetector / PathExecutableDetector | IMPLEMENTED | Real detection (env vars, file existence, PATH scan — no process spawning), unit-tested against fixtures |
| core-build: DryRunPlanner | IMPLEMENTED | Unit-tested incl. "performs no filesystem mutation" |
| core-build: BuildTool + core-security integration | IMPLEMENTED | `BuildToolSecureExecutorIntegrationTest` — a denied build never creates a workspace, verified on disk |
| core-build: MockBuildExecutor | IMPLEMENTED (explicitly non-real) | Never performs a real build; default outcome is zero artifacts with an output message saying so |
| core-build: a real BuildExecutor for JVM/NATIVE/GENERIC projects | IMPLEMENTED | `core-build-local.LocalProcessBuildExecutor`, real subprocess execution via `core-shell.ShellExecutor`, tested against a real `javac` invocation; see Section 5g |
| core-build: a real BuildExecutor delegating to a remote build server (RemoteBuildExecutor) | IMPLEMENTED | `core-build-remote.RemoteBuildExecutor`, real HTTP over `core-remote.RemoteClient`, tested against a real local `HttpServer`; never refuses ANDROID (the remote server is expected to carry the SDK/AGP); see Section 5h |
| core-build: a real BuildExecutor for ANDROID projects running on-device/on-host (AndroidGradleBuildExecutor) | PLANNED | Needs the Android SDK/AGP this environment does not have |
| core-build-local: LocalProcessBuildExecutor (real, not mocked) | IMPLEMENTED | Refuses ANDROID outright before spawning anything; command/artifact declarations come entirely from `BuildRequest.metadata`, never guessed; delegates spawning to `core-shell.ShellExecutor`; real SHA-256 checksum + size + workspace-containment validation on every reported artifact |
| core-build-local: BuildPipeline composition | IMPLEMENTED | `LocalProcessBuildExecutorPipelineIntegrationTest` — a real `javac` build runs end to end through `WorkspaceManager` + `BuildPipeline` + this executor, producing a pipeline-validated artifact |
| core-build-remote: RemoteBuildExecutor (real, not mocked) | IMPLEMENTED | Never refuses ANDROID; owns a self-defined synchronous request/response protocol (no vendor API exists to conform to); archives the real workspace source directory into a real ZIP; a returned artifact's bytes are decoded, written to disk, and given a locally recomputed SHA-256 checksum rather than trusting the server's own claims; a path-traversal `fileName` or an artifact over `maxArtifactBytes` is rejected; tested against a real local `HttpServer`, never a real build service; see Section 5h |
| core-apk-lifecycle: domain model (InstallRequest/Result, LaunchResult, LogEntry, TestCaseResult, ApkLifecycleError/Event) | IMPLEMENTED | Compiles, unit-tested |
| core-apk-lifecycle: ApkLifecyclePipeline | IMPLEMENTED | Unit-tested for every stage's success/failure path, incl. best-effort log collection vs. fatal install/launch/test-harness failures |
| core-apk-lifecycle: ApkLifecycleTool + core-security integration | IMPLEMENTED | `ApkLifecycleToolSecureExecutorIntegrationTest` — a denied deployment never reaches the executor |
| core-apk-lifecycle: NullApkLifecycleExecutor | IMPLEMENTED (explicitly non-real) | Every method fails explicitly ("no real device/adb is connected"); never fabricates a successful install, launch, or test run |
| core-apk-lifecycle: a real adb-backed ApkLifecycleExecutor | PLANNED | Needs a connected/emulated Android device this environment does not have |
| core-remote: RemoteEndpoint / HttpTransport / RemoteClient | IMPLEMENTED | `RemoteEndpoint.kt`, `HttpTransport.kt`, `RemoteClient.kt`, unit-tested against a fake transport |
| core-remote: JdkHttpTransport (real HTTP client) | IMPLEMENTED | `JdkHttpTransport.kt`, tested against a real local `HttpServer` on loopback — a genuine network round trip and a genuine timeout, not mocked |
| core-remote: mutual TLS (client certificates) | IMPLEMENTED | `MutualTlsConfig.kt`, wired into `JdkHttpTransport`'s optional constructor param; tested against a real TLS handshake with a real `keytool`-generated private CA and server/client certificate chain |
| core-remote: certificate pinning | IMPLEMENTED | Added 2026-09-05: `CertificatePinner.kt` pins a peer's SubjectPublicKeyInfo (SHA-256) — the same "pin the key, not the CA chain" approach as OkHttp's `CertificatePinner` — rejecting construction with zero pins. Wired into `JdkHttpTransport` as a second optional constructor param, composable with `mutualTls`: pinning takes precedence for server validation when both are set (mTLS's own trust store is a fallback, its client-certificate presentation is always independent), and the platform default CA trust applies when neither is set. `CertificatePinnerIntegrationTest` proves this against a real TLS handshake with a real `keytool`-generated self-signed certificate: a correct pin completes the handshake, a wrong one fails it for real, no pinner falls back to ordinary CA trust (and correctly rejects the same self-signed certificate), and pinning's precedence over an mTLS trust store is proven both ways (a correct pin succeeds despite an empty mTLS trust store; a wrong pin fails despite an mTLS trust store that would have accepted the certificate) |
| core-remote: a concrete LlmProvider using RemoteClient | IMPLEMENTED | `core-llm-anthropic.AnthropicLlmProvider` consumes `RemoteClient`/`HttpTransport` directly; no build-server client on top of `RemoteClient` exists yet |
| core-llm-anthropic: AnthropicRequest/AnthropicResponse JSON mapping | IMPLEMENTED | `AnthropicMessagesApi.kt`, kotlinx.serialization, unit-tested against a real local server's real JSON |
| core-llm-anthropic: AnthropicLlmProvider (real HTTP LlmProvider) | IMPLEMENTED (real, not mocked) | Real request encoding/response parsing over `RemoteClient`; x-api-key auth read fresh per call, never cached; status-code -> LlmError mapping (401/403 Authentication, 429/5xx ModelUnavailable, other non-2xx InvalidResponse); tested against a real local `HttpServer`, never api.anthropic.com |
| core-llm-openai: OpenAiChatRequest/OpenAiChatResponse JSON mapping | IMPLEMENTED | `OpenAiChatApi.kt`, kotlinx.serialization, unit-tested against a real local server's real JSON |
| core-llm-openai: OpenAiLlmProvider (real HTTP LlmProvider) | IMPLEMENTED (real, not mocked) | Real request encoding/response parsing over `RemoteClient`, reusing its built-in bearer-token auth; status-code -> LlmError mapping identical to core-llm-anthropic; tested against a real local `HttpServer`, never api.openai.com or a self-hosted server |
| core-security: SecurityPolicy / SecurityPolicyEnforcer | IMPLEMENTED | `SecurityPolicy.kt`, `SecurityPolicyEnforcer.kt`, compiles, unit-tested |
| core-security: SecureToolExecutor (controlled execution boundary) | IMPLEMENTED | `SecureToolExecutor.kt`, unit-tested incl. "denied tool is never invoked" and "AwaitingApproval before prompting". As of 2026-09-05, also enforces an optional grant lifecycle and audit-log fail-closed behavior (both additive, default off) |
| core-security: real root detection / real Android permission grants | PLANNED | `rootAvailable`/`grantedPermissions` are injected functions, now exercised end-to-end by `core-root.RootToolSecureExecutorIntegrationTest` — but still only against fixtures, not a real device |
| core-security: Grant / GrantStore (authorization-grant lifecycle: issue, single-use consumption only on success, expiry, revocation) | IMPLEMENTED | Added 2026-09-05, `Grant.kt`/`GrantStore.kt`; `InMemoryGrantStore` fails closed at capacity (refuses a new grant rather than evicting an older one). Deliberately avoids DroidPilot's own documented single-use-grant bug (PHASE_3_BUGS.md P3-01) by consuming only after the delegate call actually succeeds, never merely once every gate passes |
| core-security: AuditLog (security-relevant decision trail) | IMPLEMENTED | Added 2026-09-05, `AuditLog.kt`; `InMemoryAuditLog` fails closed at capacity; `SecureToolExecutor` denies a SENSITIVE/ROOT invocation it cannot record rather than running it unaudited. |
| core-security: ApprovalRequest / ApprovalResponse / RiskTier / ApprovalProvider / RiskApprovalPolicy / TimeoutApprovalProvider (CAP-014, first slice) | IMPLEMENTED (see deviation notes) | Added 2026-09-12 — `ApprovalFlow.kt`/`TimeoutApprovalProvider.kt`, closing the concrete gap the CAP-014 reconciliation row named: "a hung `ApprovalPrompt` implementation today has no timeout at all." `RiskTier` (`READ_ONLY`/`REVERSIBLE`/`DESTRUCTIVE`/`IRREVERSIBLE`) is a new, separate type from `ai.droidcommand.agent.SecurityLevel` rather than folding a 4th value into that existing 3-value enum, matching the reconciliation's own observation that `SecurityLevel` "isn't targeted at execution routing." `ApprovalRequest.targetType`/`capabilityId` were originally plain `String` (CAP-008/CAP-009/CAP-011 were still MISSING at the time) and have since been migrated to the real `ExecutionTargetType`/`CapabilityId`, per the 2026-09-12 follow-up entry below. `ApprovalPrompt` itself is unchanged (every existing `SecureToolExecutor` caller/test is unaffected); `TimeoutApprovalProvider` is an additive bridge from `ApprovalPrompt` to the new `ApprovalProvider` contract, enforcing `ApprovalRequest.timeoutMs` by running the prompt call on a daemon thread and bounding the wait with `Thread.join(timeoutMs)` — the same pattern `core-shell.ProcessBuilderShellExecutor` already uses to bound a blocking external call, rather than a new `ExecutorService` dependency. Honestly documented limitation: since `ApprovalPrompt.requestApproval` has no cancellation hook, a genuinely hung prompt implementation keeps running on its background thread after `TimeoutApprovalProvider` returns `TimedOut` to the caller — the caller sees a bounded, default-deny outcome, but the hung call itself isn't stopped; a non-positive `timeoutMs` (e.g. `RiskApprovalPolicy`'s own `READ_ONLY` default of `0`) never calls `Thread.join` at all, avoiding its `0`-means-wait-forever semantics. Four new `AuditEventType` entries (`APPROVAL_APPROVED`/`APPROVAL_DENIED`/`APPROVAL_TIMED_OUT`/`APPROVAL_UNAVAILABLE`) let an optional audit log distinguish all four outcomes, including the two (`TimedOut`/`Unavailable`) a plain `Boolean`-based trail could never previously record. **Not built in this slice:** wiring `ApprovalProvider`/`TimeoutApprovalProvider` into `SecureToolExecutor` itself (which still calls `ApprovalPrompt` directly) — left for a follow-up so this slice doesn't ripple through every existing `SecureToolExecutor` call site and test. Unit-tested (`ApprovalFlowTest`, 12 cases): `RiskApprovalPolicy`'s requires-approval and default-timeout tables; `TimeoutApprovalProvider` returning `Approved`/`Denied`/`Unavailable`; a hung prompt returning `TimedOut` promptly rather than blocking (measured elapsed time); a non-positive timeout returning immediately; and audit events recorded per outcome, including `TimedOut` and `Unavailable` as distinct event types. |
| core-security: CapabilityId / ExecutionTargetType / ExecutionRequest / ExecutionResponse (CAP-008, Agent↔Router Interface, first slice) | IMPLEMENTED (see deviation notes) | Added 2026-09-12 — `AgentRouterInterface.kt`, shipped close to the roadmap prompt's P1.0 shape verbatim, with one deliberate deviation: the prompt's own P1.0 section re-declares a 4-value `RiskTier` enum (`READ_ONLY`/`REVERSIBLE`/`DESTRUCTIVE`/`IRREVERSIBLE`) identical in name and every value to the `RiskTier` this module already shipped for CAP-014 — `ExecutionRequest`/`ExecutionResponse.RequiresApproval` reuse that existing type rather than declaring a duplicate, since the two are the same type, not a deliberately-distinct axis the way `RiskTier` and `ai.droidcommand.agent.SecurityLevel` already are. Placed in `core-security` (not a new module) to sit next to `RiskTier`/`ApprovalRequest`, which this type set is the direct successor to — `ApprovalRequest.targetType`/`capabilityId` and `SecretsVault`'s `capabilityId` parameter are exactly the `String`-typed stand-ins the CAP-013/CAP-014 rows above already named as waiting on this type. `CapabilityId` enforces the prompt's own `^[a-z0-9][a-z0-9._-]*$` format via an `init` block `require`. **Not built in this slice, named rather than silently skipped:** no `CapabilityManager`/registry (CAP-009, still MISSING — nothing yet proves an `ExecutionRequest.capabilityId` refers to a live capability), no `ExecutionTargetAbstraction`/`ExecutionRouter` (CAP-011/CAP-012, still MISSING — nothing yet turns an `ExecutionRequest` into a real dispatched call, so these types are a pure contract with no implementation behind them yet); `ApprovalRequest`/`SecretsVault` are left with their existing `String`-typed fields rather than migrated to consume `CapabilityId`/`ExecutionTargetType` here, matching CAP-013/014's own "additive, no ripple through existing call sites" restraint — that migration is real follow-up work, not done speculatively in the same slice that defines the types being migrated to. Unit-tested (`AgentRouterInterfaceTest`, 12 cases): `CapabilityId` accepting valid namespace-qualified/mixed-character ids and rejecting empty/uppercase-leading/separator-leading/whitespace-containing ones, plus value equality; `ExecutionRequest` field carriage; all four `ExecutionResponse` subtypes' field carriage including `Denied`'s optional `suggestedAlternative` defaulting to `null`. |
| core-security: JsonFileGrantStore / JsonFileAuditLog (persistent grant + audit storage, ROADMAP-048/-049) | IMPLEMENTED | Added 2026-09-10 — extends the `JsonFileMacroStore`/`JsonFileConversationStore`/`JsonFileKnowledgeStore` pattern (`core-agent`) to `core-security`'s two in-memory-only stores, closing a gap earlier addenda flagged as blocked on "the still-missing memory/persistence layer" — that layer has since shipped three times over in `core-agent`, and this applies the same pattern here. **`JsonFileGrantStore`:** one JSON file per grant under a directory, with the same id-allow-list + normalize-then-`startsWith` path-escape defense `JsonFileKnowledgeStore` uses. The one real difference from the keyed `JsonFile*Store`s before it: a grant's `consumed`/`revoked` flags are lifecycle state, not immutable data, so persistence would be security theater if they didn't survive a restart too (a revoked grant reverting to live, or a single-use grant becoming reusable) — both flags are stored as fields on the same record file, and `consume`/`revoke` are read-modify-write operations on it rather than a separate in-memory set. `issue()` fails closed at capacity by counting existing grant files fresh (not an in-process counter), so capacity is enforced correctly across a restart too. **`JsonFileAuditLog`:** append-only JSON-Lines file — deliberately no delete/rewrite path, since an audit trail's purpose is proving something happened even after the process is gone. `record` fails closed at capacity by counting the file's lines fresh on every call, the same "re-read from disk, never just cached" discipline `JsonFileKnowledgeStore` already documents — the only way capacity stays correct if more than one instance (or process) appends to the same file. Unit-tested (`JsonFileGrantStoreTest`/`JsonFileAuditLogTest`): every `InMemoryGrantStore`/`InMemoryAuditLog` test case reproduced against the file-backed version, plus cross-instance persistence of revocation/consumption/recorded-events over the same directory/file, path-escape rejection, and capacity enforcement staying correct across separate instances over the same file. |
| core-security/core-root: AI_ROOT-style initiator-scoped permission category (ROADMAP-051) | IMPLEMENTED | Added 2026-09-05: `core-agent.Initiator` (`AI`/`DEVICE_OWNER`/`REMOTE`) and `ToolSpec.requiredInitiator`; enforced by both `ToolExecutor.run` and `SecureToolExecutor.run` (initiator check runs before the grant check, so a mismatched initiator is denied even with no grant store configured at all), and `ToolRegistry.list(initiator = ...)` filters a planner's own candidate list the same way `allowedModes` already does. `ObjectiveEngine` always plans/acts as `Initiator.AI`; `DroidCommandSession.runPilotInstruction` defaults to `Initiator.DEVICE_OWNER`. Explicitly documented, matching DroidPilot's own disclosure, as a self-declared policy boundary, not a cryptographic one — nothing here defeats a hostile caller who simply passes a different `Initiator` value |
| core-config: ConfigSource / ConfigReader | IMPLEMENTED | `ConfigSource.kt`, `ConfigReader.kt`, compiles, unit-tested |
| core-config: LlmConfigLoader | IMPLEMENTED | `LlmConfigLoader.kt`, unit-tested incl. that `authToken()` re-reads the source on every call rather than caching |
| core-config: MultiLlmConfigLoader / ConfiguredLlmProvider (config-driven multi-provider construction, config-loading half) | IMPLEMENTED (see deviation notes) | Added 2026-09-12 — `MultiLlmConfigLoader.kt`, closing the gap named repeatedly across the CAP-004/005/006/007 addenda: `LlmConfigLoader` builds exactly one `LlmConfig`; this builds a `List<ConfiguredLlmProvider>` (each pairing an `LlmConfig` with the `core-llm.AiProviderInfo` `AiProviderSelector`/`RegisteredProvider` need), driven by a new `ConfigKeys.LLM_PROVIDER_IDS` (comma-separated ids) plus per-id `DROIDCOMMAND_LLM_PROVIDER_<ID>_*` keys (`_PROVIDER`/`_MODEL`/`_TYPE`/`_MAX_CONTEXT_TOKENS` required; `_NAME`/`_ENDPOINT`/`_TEMPERATURE`/`_MAX_OUTPUT_TOKENS`/`_API_KEY`/`_AVAILABLE`/`_CAPABILITIES`/`_COST_INPUT_PER_MILLION`+`_COST_OUTPUT_PER_MILLION` optional). Additive throughout: `LlmConfigLoader`/`ConfigKeys`'s existing single-provider keys are completely unchanged, and an absent/blank `LLM_PROVIDER_IDS` yields an empty list. A new `InvalidLlmProviderConfigException` is used only for a present-but-invalid value (unrecognized `_TYPE`/`_CAPABILITIES` entry, non-numeric `_MAX_CONTEXT_TOKENS`, or exactly one of the two `_COST_*` keys present without the other) — a genuinely absent required key still throws the existing `MissingConfigException`. **Only the config-loading half of the named gap, stated plainly rather than silently narrowed:** the other half those same addenda name — reading `LlmConfig.provider`'s string to auto-construct a concrete `AnthropicLlmProvider`/`OpenAiLlmProvider` — cannot live in `core-config`, which depends on neither `core-llm-anthropic`/`core-llm-openai` (each needing a real `core-remote.HttpTransport` to construct) nor `core-remote` itself; giving it those dependencies would invert this repository's existing leaf-module shape for `core-config`. That factory step needs an assembly-root module this repository does not yet have (confirmed: no `fun main()` exists anywhere in it) — named here as real, separate future work, not built. Unit-tested (`MultiLlmConfigLoaderTest`, 21 cases): absent/blank `LLM_PROVIDER_IDS` yielding an empty list; one fully-configured provider's every field (`LlmConfig` and `AiProviderInfo` both); `_NAME`/`_AVAILABLE`/`_CAPABILITIES`/cost defaults when absent; two providers loading independently with order preserved; whitespace/blank entries in `LLM_PROVIDER_IDS` trimmed and skipped; a hyphenated id mapping to the correct upper-cased/underscored key prefix; each of the four required keys' absence throwing `MissingConfigException`; an unrecognized `_TYPE`/`_CAPABILITIES` value, a non-numeric `_MAX_CONTEXT_TOKENS`/cost value, and exactly one `_COST_*` key present without the other all throwing `InvalidLlmProviderConfigException` naming the bad value; `_API_KEY`/`authToken()` re-reading the source on every call. |
| core-llm-factory: LlmProviderFactory (provider-construction factory, config-driven multi-provider construction, construction half) | PLANNED — scoped 2026-09-12, not yet implemented | No directory, `build.gradle.kts`, Kotlin source, or `settings.gradle.kts` entry exists yet. `docs/AUDIT_2026-09-05.md`'s 2026-09-12 "scoping the provider-construction factory module" addendum specifies the design in full: a new `core-llm-factory` module (depending on `core-agent`/`core-llm`/`core-config`/`core-remote`/`core-llm-anthropic`/`core-llm-openai`) exposing `LlmProviderFactory.build`/`buildAll`/`load`, switching on `ConfiguredLlmProvider.config.provider` (`"anthropic"`/`"openai"`, case-insensitive) to construct a real `AnthropicLlmProvider`/`OpenAiLlmProvider` over a caller-supplied shared `HttpTransport`, throwing a new `UnknownLlmProviderException` for anything else — read that entry before implementing, rather than re-deriving the design |
| core-config: SecurityPolicyLoader | IMPLEMENTED | `SecurityPolicyLoader.kt`, unit-tested |
| core-config: a real, deployed configuration source (device settings UI, secure storage) | PLANNED | Only `EnvConfigSource`/`MapConfigSource`/`CompositeConfigSource` exist; no Android-backed source (e.g. EncryptedSharedPreferences) has been built |
| core-security: ExecutionRouter / DefaultExecutionRouter / RoutingDecision (CAP-012, Execution Router, first slice) | IMPLEMENTED (see deviation notes) | Added 2026-09-12 — `ExecutionRouter.kt`. `DefaultExecutionRouter.routeExecution` filters `availableTargets` to the requested `ExecutionTargetType`, then to ones declaring the requested `CapabilityId`, then to healthy ones, and picks the survivor with the lowest `PrivilegeLevel` ordinal (least-privilege-first, per P1.4's own "Router logic" list), pairing it with whatever `SecurityPolicyEnforcer.authorize` decides. **Three deliberate deviations from the roadmap prompt's literal shape, stated plainly:** (1) `routeExecution`'s fourth parameter is a real `ToolSpec`, not the prompt's own `toolId: String` — `SecurityPolicyEnforcer.authorize` (the only thing this router can consult for a `PolicyDecision`) requires a full `ToolSpec`, and no id-to-`ToolSpec` registry exists anywhere in this repository to resolve a plain string into one (that lookup is exactly what the still-MISSING Capability Manager, CAP-009, would provide) — a `toolId` parameter this method could not actually use would be dead weight, not a thinner-but-honest stand-in; (2) both interface methods are synchronous `fun`, not `suspend fun`, the same "no coroutines dependency anywhere in this chain" reasoning `AiProviderSelector` and `ExecutionTarget` (CAP-011) already document; (3) `routeExecution` returns `RoutingDecision` directly rather than the prompt's own `RoutingDecision?` — `RoutingDecision.NoSuitableTarget` already names "no target found" as its own case, so a nullable return type on top of it would let the identical condition be expressed two different ways. A `PolicyDecision.Deny` is still returned wrapped in `RoutingDecision.Route` naming the target that *would* have run it, per the prompt's own "return the target + required policy decision" wording — the caller decides whether to actually invoke `ExecutionTarget.execute` based on `RoutingDecision.Route.decision`. `verifyResult` combines `ExecutionResult.verified` (honestly `false` from every target in this repository today) with a routing-specific sanity check that the result's `target` matches the request's `targetType` — not independent state re-verification, which no target here performs. **Not built in this slice:** no `CapabilityManager`/registry (CAP-009, still MISSING) — target capability declarations are still caller-supplied, not populated from anything live; no second `ExecutionTarget` implementation to actually route between (CAP-011 shipped exactly one, `LocalProcessExecutionTarget`); no wiring of this router into any existing `Tool`/`SecureToolExecutor` call site — additive and reachable only by a caller that opts in. Unit-tested (`DefaultExecutionRouterTest`, 9 cases): no-target-of-requested-type, right-type-wrong-capability, capable-but-unhealthy, least-privilege selection among multiple viable targets, an unhealthy least-privileged target correctly skipped in favor of a healthy more-privileged one, `Allow`/`Deny`/`RequireApproval` decisions correctly threaded through from `SecurityPolicyEnforcer`, and `verifyResult`'s three truth-table cases (verified+matching, verified+mismatched target, unverified). |
| core-security: CapabilityState / CapabilityMetadata / CapabilityFilter / CapabilityRegistry / InMemoryCapabilityRegistry / CapabilityHealthChecker (CAP-009, Capability Manager, first slice) | IMPLEMENTED (in-memory only, no auto-reverify-on-access; see deviation notes) | Added 2026-09-12 — `CapabilityRegistry.kt`. `CapabilityState`'s 11 values and `CapabilityMetadata`'s fields are verbatim from the roadmap prompt's P1.1 section; `CapabilityFilter` is not defined by the prompt itself (the same "referenced but never defined" gap `ProviderPreferences`/`AllocatedContext` already hit elsewhere) and was designed as the minimal `state`/`providerId`/`riskTier` filter `CapabilityMetadata`'s own fields support, `null` meaning "don't filter on this" per `ProviderPreferences`' own convention. **Deliberate deviation from the prompt's own "re-verification logic"** ("on access, ... kick off an async re-verify; return current state immediately but tag it with staleness"): this registry does not auto-trigger a background re-verify from `getCapability`/`listCapabilities`, and does not tag a read with staleness — `CapabilityMetadata`'s own literal shape has no staleness field to carry that tag, and no module in this dependency chain has a general async/background-execution primitive (only the narrow bounded-wait pattern `TimeoutApprovalProvider` already uses for one blocking call). Instead, a new `CapabilityMetadata.isStale(reverifyIntervalMs, now)` extension function is exposed as a plain, composable check a caller can run against a read result to decide for itself whether to call `reverify` — the registry supplies the building block rather than imposing an async policy, matching `PersonaContextProvider`'s own "which persona is active" restraint. `reverify`/`CapabilityHealthChecker.verify` are synchronous `fun`, not `suspend fun` — the same "no coroutines dependency anywhere in this chain" reasoning `AiProviderSelector`/`ExecutionTarget`/`ExecutionRouter` already document. `InMemoryCapabilityRegistry`'s `healthChecker` constructor parameter is nullable (`null` default) rather than a `Null*` object: `CapabilityHealthChecker.verify`'s return type is a *required*, fully-populated `CapabilityMetadata` with no honest placeholder for `version`/`description` a null-object implementation could fabricate without misrepresenting real data (unlike `NullRootExecutor`, whose result type has a genuine "cannot do this" case) — `reverify` fails loudly with `IllegalStateException` instead when no `healthChecker` is configured. A separate, narrower `ai.droidcommand.root.RootProviderState` already exists in `core-root` (predating this type, added for the Magisk support work) and is deliberately left as-is rather than folded into `CapabilityState`: root-specific vocabulary a single provider needs today, versus the umbrella vocabulary a multi-provider registry needs — the same "distinct axis, not a competing model" relationship `RiskTier`/`SecurityLevel` already have. **Not built in this slice:** no persistence (in-memory only, unlike `JsonFileGrantStore`/`JsonFileAuditLog`); no real `CapabilityHealthChecker` implementation for any provider (e.g. a `MagiskCapabilityHealthChecker` wrapping `core-root.MagiskProvider`) — every test uses a scripted fake; no wiring into `ExecutionRouter`/any `Tool`. Unit-tested (`CapabilityMetadataIsStaleTest`, 4 cases; `InMemoryCapabilityRegistryTest`, 13 cases): never-verified/within-interval/at-boundary/past-interval staleness; get/register/upsert; list with no filter and with each filter field individually and combined; reverify on an unknown id, with no health checker configured, and with one configured (provider id threaded through, result stored); invalidate on an unknown id (no-op) and on a known one (clears `lastVerifiedAt` only). |
| core-agent/core-security: PermissionCategory / ROOT_EQUIVALENT_CATEGORIES / SecurityPolicy.isCategoryGranted / EscalationTier / ToolSpec.permissionCategory (CAP-010, Policy & Permission Engine extension, wired into ToolSpec) | IMPLEMENTED (see deviation notes) | Added 2026-09-12 — `PermissionCategory.kt` plus one new optional constructor parameter on `SecurityPolicy`. `PermissionCategory`'s 13 values are verbatim from the roadmap prompt's P1.2 list (an earlier CAP-010 audit row above miscounted this as 12; corrected here rather than perpetuated). **Deliberate deviation from a literal reading of the prompt's own "extend the existing `SecurityLevel` enum" heading:** `SecurityLevel` itself is unchanged — `PermissionCategory` is a new, separate type. Folding 13 category values into the existing 3-value `SecurityLevel` (a confirmation-requirement axis every `ToolSpec`/`SecurityPolicyEnforcer.authorize` call site already depends on) would conflate two orthogonal concepts, the same "distinct axis, not a competing model" relationship `RiskTier`/`SecurityLevel` already have. The prompt's own "CRITICAL: Docker/container socket access is root-equivalent... Document this explicitly in the policy engine" instruction is made an executable gate, not just a comment: `ROOT_EQUIVALENT_CATEGORIES = {ROOT, CONTAINER}`, consulted by `SecurityPolicy.isCategoryGranted(category)`, which fails closed — `CONTAINER` (or `ROOT`) listed in `SecurityPolicy.grantedCategories` is still denied unless `rootEnabled` and a true `rootAvailable()` both hold. **This entry's own follow-up (2026-09-12, continuation session): `PermissionCategory` and `ROOT_EQUIVALENT_CATEGORIES` moved from `core-security` to `core-agent`** (alongside `SecurityLevel`/`Initiator`, which already lived there) — `core-security` depends on `core-agent`, not the reverse, and `ToolSpec` (`core-agent`) needed to reference the type directly, so the type declaration had to live on the `core-agent` side of that boundary; `isCategoryGranted` and `EscalationTier` stay in `core-security/PermissionCategory.kt`, now importing the moved type. `ToolSpec` gained `permissionCategory: PermissionCategory? = null` (`core-agent/Tool.kt`) — nullable, default `null`, so every existing tool definition across the codebase (`ShellTool`, `BuildTool`, `RootTool`, every `core-tools-android` device tool) is unaffected and none was touched. `SecurityPolicyEnforcer.authorize` now consults it as a fourth hard-`Deny` check (alongside root/permissions, before confirmation): a non-null `permissionCategory` the policy's `isCategoryGranted` refuses is denied outright, exactly like a missing `requiredPermissions` entry. `EscalationTier` (7 values, declaration order = the prompt's own preference order, lowest-privilege first) still captures the escalation sequence only; `TERMUX`'s "not a privilege tier, a separate execution environment" and `SHIZUKU`'s "requires ADB or root to bootstrap, it's the API surface not the grant" caveats from the prompt's own "Note" remain as doc comments, since a bare enum value can't express them. **Follow-up (2026-09-12, continuation session): every real tool in this repository now has a non-null `permissionCategory`.** All 38 real `ToolSpec` construction sites across `core-shell.ShellTool` (`TERMINAL`), `core-root.RootTool` (`ROOT`), `core-build.BuildTool` (`TERMINAL` — no dedicated "build" category exists in the roadmap prompt's 13-value list, and a build ultimately spawns an external compiler process via `core-build-local.LocalProcessBuildExecutor`, the same execution shape as `ShellTool`), `core-apk-lifecycle.ApkLifecycleTool` (`DEVICE_CONTROL`), and all 34 `core-tools-android` tools (`VIEW` for read-only queries; `NETWORK` for `GetNetworkStateTool` specifically; `FILES` for all six file tools including reads and directory listing, since `FILES` is its own named category rather than a `VIEW` subset; `AUTOMATION` for UI-level tap/swipe/type/press-key/find-and-tap actions; `DEVICE_CONTROL` for everything else that mutates or actuates a device feature — clipboard writes, launch/productivity/media/navigation/SMS/call tools — with a read counterpart, where one exists, assigned `VIEW` instead, e.g. `GetClipboardTool`/`VIEW` vs. `SetClipboardTool`/`DEVICE_CONTROL`, `ListContactsTool`/`VIEW` vs. `SendSmsTool`/`MakeCallTool`/`DEVICE_CONTROL`) were assigned a category by direct read-vs-write/domain judgment, since the roadmap prompt's P1.2 section gives only a bare 13-value list with no per-category definitions to mechanically apply. **Test fixtures updated to match, not just left to silently start failing:** 7 existing test files that construct a real tool against a real `SecurityPolicyEnforcer` with a bare `SecurityPolicy()` (`ShellToolSecureExecutorIntegrationTest`, `RootToolSecureExecutorIntegrationTest`, `RootToolGrantIntegrationTest`, `MagiskProviderSecureExecutorIntegrationTest`, `BuildToolSecureExecutorIntegrationTest`, `ApkLifecycleToolSecureExecutorIntegrationTest`, `DeviceToolSecureExecutorIntegrationTest`) would otherwise have started failing the moment their tool gained a non-null category, since `SecurityPolicy()`'s default `grantedCategories = emptySet()` would now deny outright before ever reaching the approval-flow behavior each test actually exists to prove — each was updated to grant the one category its tool now declares (two of `RootToolSecureExecutorIntegrationTest`'s five cases and one of `MagiskProviderSecureExecutorIntegrationTest`'s three needed no change, since they already deny earlier at the `requiresRoot` check, before the category check is ever reached). New/extended tests: `core-tools-android/ToolPermissionCategoryTest` (7 cases, new) asserts every remaining tool's category; `RootToolTest`, `ShellToolSecureExecutorIntegrationTest`, `BuildToolSecureExecutorIntegrationTest`, `ApkLifecycleToolSecureExecutorIntegrationTest`, and `DeviceToolSecureExecutorIntegrationTest` each gained one `permissionCategory` assertion alongside their existing spec-shape test. Unit-tested (`core-agent/PermissionCategoryTest`, 3 cases, moved verbatim from `core-security`; `core-security/PermissionCategoryPolicyTest.kt`'s `SecurityPolicyIsCategoryGrantedTest`, 7 cases, and `EscalationTierTest`, 3 cases, unchanged; `SecurityPolicyEnforcerTest` gained 6 cases in the prior entry): category count and root-equivalent-set correctness; granted/not-granted for an ordinary category; `CONTAINER`/`ROOT` denied when root disabled, denied when root enabled-but-unavailable, granted only when both the root gate and `grantedCategories` membership hold, and denied when the root gate holds but membership doesn't; escalation tier ordering and the `TERMUX`-before-`SHIZUKU`/`ROOT`-last invariants; a `null` `permissionCategory` tool unaffected by `grantedCategories`; a `FILES`-category tool denied when ungranted and allowed when granted; a `CONTAINER`-category tool denied despite being granted when root is disabled, and allowed when granted with root enabled and available; the category check confirmed to deny outright rather than merely require approval; every real tool's actual assigned category. **Not built in this follow-up:** no wiring of `EscalationTier` into any real escalation logic (P2's Shizuku/Termux/ADB targets still don't exist) — unrelated to this category-assignment slice and still correctly deferred. |
| core-security/core-config: ApprovalRequest and SecretsVault migrated to real CapabilityId/ExecutionTargetType | IMPLEMENTED | Added 2026-09-12, a follow-up named by both the CAP-013 and CAP-014 entries above once CAP-008 shipped the real types. `ApprovalRequest.targetType`/`capabilityId` (`core-security/ApprovalFlow.kt`) and `SecretsVault.putSecret`/`listSecretIds`'s `capabilityId` parameter (`core-config/SecretsVault.kt`) now take the real `ExecutionTargetType`/`CapabilityId` instead of a plain `String` stand-in. Both migrations were small and contained: `ApprovalRequest` is constructed only in `ApprovalFlowTest.kt` (not yet wired into `SecureToolExecutor`), and `SecretsVault` only in `SecretsVaultTest.kt` — `core-config` already depended on `core-security`, so no new module dependency was needed. `toolId` on `ApprovalRequest` stays `String`: it identifies a `Tool`, a distinct concept CAP-008 doesn't define a type for. No behavior changed — this is a type-safety migration only, verified by the full suite staying at 862 tests (no new tests needed; every existing case just now exercises the real types). |
| core-config: SecretsVault / InMemorySecretsVault / EnhancedConfigSource / VaultBackedConfigSource (CAP-013, first slice) | IMPLEMENTED (in-memory only; see deviation notes) | Added 2026-09-12 — `SecretsVault.kt`. `capabilityId` was originally plain `String` (the Capability Registry, CAP-008/CAP-009, was still MISSING) and has since been migrated to the real `CapabilityId`, per the 2026-09-12 follow-up entry below. The prompt's own `putSecret(secretId, value)` has no `capabilityId` parameter even though `listSecretIds(capabilityId)` needs one to filter by — resolved by giving `putSecret` an optional `capabilityId` (default `null`, excluded from every `listSecretIds` result) since put time is the only point a vault ever observes an owning capability at all. `InMemorySecretsVault` wires an optional `core-security.AuditLog` (additive, matching `SecureToolExecutor`'s own optional collaborators) and logs secret *use* — every `getSecret` call, found or not — plus revocation, naming only the secret id and owning capability, never the value; two new `AuditEventType` entries (`SECRET_ACCESSED`/`SECRET_REVOKED`) were added for this. `VaultBackedConfigSource` composes an existing `ConfigSource` with a `SecretsVault` per the prompt's own `EnhancedConfigSource` shape. **Not built in this slice:** persistence (no `JsonFileSecretsVault` yet, unlike `JsonFileGrantStore`/`JsonFileAuditLog`); wiring into `LlmConfigLoader`/`SecurityPolicyLoader` or any tool. Unit-tested (`SecretsVaultTest`, 12 cases): get/put/revoke round-trip, unknown-id lookup, no-op revoke of an unknown id, capability-scoped listing (including an unscoped secret never appearing), audit records on access (found and not-found) and revoke never containing the secret value, and `VaultBackedConfigSource` delegation. |
| Pilot Mode (end-to-end) | PARTIAL | `DroidCommandSession.runPilotInstruction` is implemented and tested against `core-tools-android`'s real `Tool` wrappers, but every one of them is backed by `NullDeviceController` — no real Android-backed `DeviceController` exists yet |
| Forge Mode (end-to-end) | PARTIAL | The objective loop itself (planning/tool-selection/execution/observation/bounded iteration) is implemented and tested; it has never run against a real LLM or a real device tool; core-build's pipeline/workspace scaffolding now exists but has no real BuildExecutor to actually compile anything |
| Mode switching (Pilot <-> Forge) | IMPLEMENTED | `DroidCommandSession.switchMode`, unit-tested for the idle case and for rejection during an active task. Mode also now genuinely scopes tool availability: `ToolSpec.allowedModes` (default: both) is enforced by `ToolExecutor.run` and filters what `ObjectiveEngine` offers its planner — a mode-restricted tool is neither invocable via the other mode's Pilot call nor ever presented to the other mode's planner. Added 2026-09-05 to close a gap `docs/AUDIT_2026-09-05.md` found: previously mode was a dispatch-shape/task-lifecycle switch only, with no capability difference. |
| Root capabilities | PARTIAL | `core-root`'s Tool/gate/policy wiring is implemented and tested end-to-end against `core-security`; no real root command has ever executed, since that requires a rooted test device this environment does not have |
| LLM integration | PARTIAL | The abstraction, the planner adapter, and two real HTTP-backed `LlmProvider`s (Anthropic-shaped and OpenAI-shaped) are all implemented and tested (each provider against a real local server, not a fake); neither has ever made a live call to a real provider endpoint, since this environment has no LLM credentials |
| APK build/install/test pipeline | PARTIAL | `core-build.BuildPipeline` now has real executors for JVM/NATIVE/GENERIC builds (`core-build-local.LocalProcessBuildExecutor`, proven against real `javac`) and for delegating to a remote build server (`core-build-remote.RemoteBuildExecutor`, proven against a real local test server) — the latter never refuses `ProjectType.ANDROID`, but has never been run against an actual build service, so no real Android APK has been produced by either executor; `core-apk-lifecycle.ApkLifecyclePipeline` still only has `NullApkLifecycleExecutor`, and installing/launching still needs a connected/emulated device this environment does not have |
| MCP integration (ROADMAP-123 / DP-001) | IMPLEMENTED | Added 2026-09-09 — `core-mcp.McpToolServer` wraps a `core-agent.ToolRegistry`/`ToolExecutor` and exposes every tool available to a fixed `mode`/`initiator` (default `Initiator.REMOTE`, the existing initiator-scoping category for an external caller) as a real MCP tool, using the official `io.modelcontextprotocol:kotlin-sdk-server` SDK rather than a hand-rolled protocol implementation. `runStdio()` runs it over real process stdin/stdout via `StdioServerTransport`. Proven end to end by `McpToolServerTest`, which drives a real `Client`/`Server` handshake over that same `StdioServerTransport`/`StdioClientTransport` pair wired through in-process pipes (not a mock, and deliberately not the SDK's own `ChannelTransport` test helper — that class is `@ExperimentalMcpApi` and was verified, directly, to race on a `tools/call` round trip under this SDK version): `tools/list` correctness (name/description/permissive input schema, and that an `Initiator.DEVICE_OWNER`-scoped tool is invisible to a `REMOTE`-scoped server), `tools/call` for all four `ToolResult` variants mapped through the same `describe()` this codebase already uses for `ObjectiveEngine`'s conversation context, and argument mapping (JSON string/number primitives arrive as strings; a nested array argument is dropped rather than guessed at, since `ToolSpec` has no parameter schema to say what shape it should take). Adding this dependency required bumping the whole repository's Kotlin toolchain from 2.0.21 to 2.4.10 (Section 7b) — every other module's own status above is unaffected, confirmed by the full suite passing unchanged after the bump. |

## 7. Environment constraints recorded for this implementation pass

Verified 2026-09-04 in this sandboxed session:
- JDK 21.0.10, Gradle 8.14.3, Kotlin 2.0.21 toolchain: present (see Section
  7b for the later project-wide bump to 2.4.10).
- `ANDROID_HOME`/`ANDROID_SDK_ROOT`: unset. No `adb`, `emulator`, `sdkmanager`,
  `avdmanager` on PATH. No `~/Android/Sdk` or `/opt/android-sdk`.
- Network reachability to `dl.google.com` and `maven.google.com` confirmed
  (HTTP 200/301), so an Android SDK *could* be provisioned in a session with
  time/disk budget for it — but no physical or emulated device exists here to
  install or launch an APK on regardless of SDK presence.
- No LLM provider credentials configured in this environment.
- No root-capable Android device attached.

## 7b. Kotlin toolchain: 2.0.21 -> 2.4.10 project-wide (2026-09-09)

Every module's `kotlin("jvm")`/`kotlin("plugin.serialization")` plugin
version moved from 2.0.21 to 2.4.10, root `build.gradle.kts` included. This
was required, not optional, to add `core-mcp`:

- `io.modelcontextprotocol:kotlin-sdk-server:0.15.0` (and its transitive
  `kotlinx-io`/`kotlinx-serialization`/Ktor jars) is published with Kotlin
  metadata newer than a 2.0.21 compiler can read — verified directly:
  `compileKotlin` against it under 2.0.21 fails with "Module was compiled
  with an incompatible version of Kotlin" across more than a dozen
  transitive jars, not just one.
- Gradle resolves a single version of a given plugin ID for the whole
  build; `core-mcp` cannot privately pin its own newer Kotlin version while
  every other module stays on 2.0.21 — attempting that fails with "the
  plugin is already on the classpath with a different version," confirmed
  directly as well.
- 2.4.10 was chosen (not a guess) because it's the exact Kotlin version the
  SDK's own samples (e.g. `samples/weather-stdio-server`) pin against this
  same SDK release, per its committed `gradle/libs.versions.toml`.

Verified safe: the full `./gradlew test --continue` (58 tasks, all 15
modules) and `./gradlew ktlintCheck` both passed unchanged after the bump,
before `core-mcp`'s own code was even written — i.e., the version change
alone regressed nothing. The bump did surface a small number of new,
harmless compiler warnings in existing modules ("Unnecessary non-null
assertion", "No cast needed") from 2.4.10's improved smart-casting;
left as-is (warnings, not failures, and out of scope for this change) but
worth a cheap cleanup pass in a future session.

These are session facts, not permanent project constraints — a developer
machine or CI runner with the Android SDK and a connected/emulated device
removes most of them. They are recorded here so that "PLANNED" status above
is auditable rather than asserted.
