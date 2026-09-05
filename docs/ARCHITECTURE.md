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
│                            not have.
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
└── core-config               ConfigSource/ConfigReader plus LlmConfigLoader
                             and SecurityPolicyLoader, which build
                             core-llm's LlmConfig and core-security's
                             SecurityPolicy from a key/value source. No
                             secret is ever held as a plain field — an API
                             key is read from the source fresh on every
                             authToken() call, not captured at load time.
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
| core-agent: Tool interface | IMPLEMENTED | `Tool.kt`, compiles, unit-tested |
| core-agent: ToolRegistry | IMPLEMENTED | `ToolRegistry.kt`, compiles, unit-tested |
| core-agent: ToolExecutor + bounded retry | IMPLEMENTED | `ToolExecutor.kt`, compiles, unit-tested |
| core-agent: ConversationContext | IMPLEMENTED | `Conversation.kt`, compiles, unit-tested |
| core-agent: Planner contract | IMPLEMENTED | `Planner.kt` (interface only — see LlmPlanner for the one implementation) |
| core-agent: ObjectiveEngine (bounded Forge loop) | IMPLEMENTED | `ObjectiveEngine.kt`, compiles, unit-tested incl. the maxIterations bound. As of 2026-09-05, a planner naming an unregistered/wrong-mode tool no longer fails the objective outright — it's told what's actually available and replans, still bounded by maxIterations |
| core-agent: ToolSpec.allowedModes (mode genuinely scopes tool availability) | IMPLEMENTED | Added 2026-09-05 — `ToolExecutor`/`ObjectiveEngine` enforce it; default (both modes) leaves existing tools unaffected |
| core-agent: DroidCommandSession (Pilot/Forge mode switching) | IMPLEMENTED | `DroidCommandSession.kt`, unit-tested incl. a rejected mode switch attempted mid-task |
| app (Android shell) | PLANNED | No directory, Gradle file, or manifest exists yet — nothing scaffolded, and no Android SDK in this environment either (Section 7). Corrected 2026-09-05: an earlier version of this row implied a manifest/Gradle scaffold already existed on disk; it does not — see `docs/AUDIT_2026-09-05.md`. |
| core-llm: request/response/error types, LlmProvider interface | IMPLEMENTED | `LlmTypes.kt`, `LlmProvider.kt`, compiles |
| core-llm: LlmPlanner (Planner adapter) | IMPLEMENTED | `LlmPlanner.kt`, unit-tested, and exercised end-to-end with `ObjectiveEngine` in `ObjectiveEngineIntegrationTest` |
| core-llm: concrete provider (Anthropic) | IMPLEMENTED | `core-llm-anthropic.AnthropicLlmProvider`, real HTTP + real JSON, tested against a real local `HttpServer`; see Section 5e |
| core-llm: concrete provider (OpenAI / OpenAI-compatible) | IMPLEMENTED | `core-llm-openai.OpenAiLlmProvider`, real HTTP + real JSON, tested against a real local `HttpServer`; see Section 5f. No local-model-specific (non-OpenAI-shaped) provider exists yet |
| core-tools-android: domain model (UiNode/UiTree/Selector/Rect) + UiTreeRenderer | IMPLEMENTED | Compiles, unit-tested — pure Kotlin, no Android dependency |
| core-tools-android: DeviceController + Tool wrappers (tap/swipe/type/pressKey/launchApp/findElement/tapElement/getUiTree/listInstalledApps/takeScreenshot) | IMPLEMENTED | Unit-tested against a scripted `DeviceController` fake, incl. invalid-input paths that never call the device |
| core-tools-android: core-security integration | IMPLEMENTED | `DeviceToolSecureExecutorIntegrationTest` — a denied tap never reaches the device controller |
| core-tools-android: NullDeviceController | IMPLEMENTED (explicitly non-real) | Every method fails explicitly ("no real device is connected"); never fabricates a successful tap, swipe, or UI-tree read |
| core-tools-android: a real Android-backed DeviceController | PLANNED | Needs the Android SDK (to compile against real Accessibility/PackageManager APIs) and a connected/emulated device, neither present in this environment |
| core-shell: ShellCommand / ShellSecurityPolicy / ShellExecutor | IMPLEMENTED | Compiles, unit-tested |
| core-shell: ProcessBuilderShellExecutor | IMPLEMENTED (real, not mocked) | Tested against real subprocesses (`echo`/`true`/`false`/`sleep`/`pwd`/`env`/`seq`) — real timeout, real cancellation, real working-directory containment, real fail-closed executable allow-list, real output truncation |
| core-shell: ShellTool + core-security integration | IMPLEMENTED | `ShellToolSecureExecutorIntegrationTest`, using the real executor — a denied command provably never spawns a process |
| core-root: RootCommand / RootSecurityPolicy / RootExecutor | IMPLEMENTED | Compiles, unit-tested |
| core-root: PolicyEnforcingRootExecutor | IMPLEMENTED | Unit-tested — rejects a command outside its allow-list without reaching the delegate; fail-closed by default (empty allow-list) |
| core-root: RootTool + core-security integration | IMPLEMENTED | `RootToolSecureExecutorIntegrationTest` exercises the full root test matrix (root disabled, root unavailable, user denies, approved-and-executed, command failure) against real `SecureToolExecutor`/`SecurityPolicyEnforcer` |
| core-root: NullRootExecutor | IMPLEMENTED (explicitly non-real) | `isRootAvailable()` truthfully returns false; `execute()` fails explicitly rather than fabricating a successful elevated command |
| core-root: a real rooted-device RootExecutor | PLANNED | Needs an actual rooted device this environment does not have |
| core-root: RootTool opt-in grant requirement (e.g. distinguishing AI-initiated `ai_root` from device-owner root) | IMPLEMENTED | Added 2026-09-05 — `RootTool(executor, grantCapability = "ai_root")`; `RootToolGrantIntegrationTest` proves a live single-use grant permits exactly one execution then is spent, and that no grant/no store denies without ever reaching the executor. Opt-in (defaults to `null`, so existing `RootTool(executor)` callers are unaffected) |
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
| core-remote: certificate pinning | PLANNED | Not built in this module yet (tracked separately from mTLS) |
| core-remote: a concrete LlmProvider using RemoteClient | IMPLEMENTED | `core-llm-anthropic.AnthropicLlmProvider` consumes `RemoteClient`/`HttpTransport` directly; no build-server client on top of `RemoteClient` exists yet |
| core-llm-anthropic: AnthropicRequest/AnthropicResponse JSON mapping | IMPLEMENTED | `AnthropicMessagesApi.kt`, kotlinx.serialization, unit-tested against a real local server's real JSON |
| core-llm-anthropic: AnthropicLlmProvider (real HTTP LlmProvider) | IMPLEMENTED (real, not mocked) | Real request encoding/response parsing over `RemoteClient`; x-api-key auth read fresh per call, never cached; status-code -> LlmError mapping (401/403 Authentication, 429/5xx ModelUnavailable, other non-2xx InvalidResponse); tested against a real local `HttpServer`, never api.anthropic.com |
| core-llm-openai: OpenAiChatRequest/OpenAiChatResponse JSON mapping | IMPLEMENTED | `OpenAiChatApi.kt`, kotlinx.serialization, unit-tested against a real local server's real JSON |
| core-llm-openai: OpenAiLlmProvider (real HTTP LlmProvider) | IMPLEMENTED (real, not mocked) | Real request encoding/response parsing over `RemoteClient`, reusing its built-in bearer-token auth; status-code -> LlmError mapping identical to core-llm-anthropic; tested against a real local `HttpServer`, never api.openai.com or a self-hosted server |
| core-security: SecurityPolicy / SecurityPolicyEnforcer | IMPLEMENTED | `SecurityPolicy.kt`, `SecurityPolicyEnforcer.kt`, compiles, unit-tested |
| core-security: SecureToolExecutor (controlled execution boundary) | IMPLEMENTED | `SecureToolExecutor.kt`, unit-tested incl. "denied tool is never invoked" and "AwaitingApproval before prompting". As of 2026-09-05, also enforces an optional grant lifecycle and audit-log fail-closed behavior (both additive, default off) |
| core-security: real root detection / real Android permission grants | PLANNED | `rootAvailable`/`grantedPermissions` are injected functions, now exercised end-to-end by `core-root.RootToolSecureExecutorIntegrationTest` — but still only against fixtures, not a real device |
| core-security: Grant / GrantStore (authorization-grant lifecycle: issue, single-use consumption only on success, expiry, revocation) | IMPLEMENTED | Added 2026-09-05, `Grant.kt`/`GrantStore.kt`; `InMemoryGrantStore` fails closed at capacity (refuses a new grant rather than evicting an older one). Deliberately avoids DroidPilot's own documented single-use-grant bug (PHASE_3_BUGS.md P3-01) by consuming only after the delegate call actually succeeds, never merely once every gate passes |
| core-security: AuditLog (security-relevant decision trail) | IMPLEMENTED | Added 2026-09-05, `AuditLog.kt`; `InMemoryAuditLog` fails closed at capacity; `SecureToolExecutor` denies a SENSITIVE/ROOT invocation it cannot record rather than running it unaudited. Persistent (cross-process) audit storage remains PLANNED — this is in-memory only, matching `ConversationContext`'s current scope |
| core-security/core-root: AI_ROOT-style initiator-scoped permission category (ROADMAP-051) | PARTIAL | The grant-capability mechanism (above) supports naming a distinct capability like `"ai_root"`, but nothing yet supplies or checks an actual "who initiated this" field the way DroidPilot's `AuthorizationManager` does — the plumbing exists, the initiator-distinction policy itself does not yet |
| core-config: ConfigSource / ConfigReader | IMPLEMENTED | `ConfigSource.kt`, `ConfigReader.kt`, compiles, unit-tested |
| core-config: LlmConfigLoader | IMPLEMENTED | `LlmConfigLoader.kt`, unit-tested incl. that `authToken()` re-reads the source on every call rather than caching |
| core-config: SecurityPolicyLoader | IMPLEMENTED | `SecurityPolicyLoader.kt`, unit-tested |
| core-config: a real, deployed configuration source (device settings UI, secure storage) | PLANNED | Only `EnvConfigSource`/`MapConfigSource`/`CompositeConfigSource` exist; no Android-backed source (e.g. EncryptedSharedPreferences) has been built |
| Pilot Mode (end-to-end) | PARTIAL | `DroidCommandSession.runPilotInstruction` is implemented and tested against `core-tools-android`'s real `Tool` wrappers, but every one of them is backed by `NullDeviceController` — no real Android-backed `DeviceController` exists yet |
| Forge Mode (end-to-end) | PARTIAL | The objective loop itself (planning/tool-selection/execution/observation/bounded iteration) is implemented and tested; it has never run against a real LLM or a real device tool; core-build's pipeline/workspace scaffolding now exists but has no real BuildExecutor to actually compile anything |
| Mode switching (Pilot <-> Forge) | IMPLEMENTED | `DroidCommandSession.switchMode`, unit-tested for the idle case and for rejection during an active task. Mode also now genuinely scopes tool availability: `ToolSpec.allowedModes` (default: both) is enforced by `ToolExecutor.run` and filters what `ObjectiveEngine` offers its planner — a mode-restricted tool is neither invocable via the other mode's Pilot call nor ever presented to the other mode's planner. Added 2026-09-05 to close a gap `docs/AUDIT_2026-09-05.md` found: previously mode was a dispatch-shape/task-lifecycle switch only, with no capability difference. |
| Root capabilities | PARTIAL | `core-root`'s Tool/gate/policy wiring is implemented and tested end-to-end against `core-security`; no real root command has ever executed, since that requires a rooted test device this environment does not have |
| LLM integration | PARTIAL | The abstraction, the planner adapter, and two real HTTP-backed `LlmProvider`s (Anthropic-shaped and OpenAI-shaped) are all implemented and tested (each provider against a real local server, not a fake); neither has ever made a live call to a real provider endpoint, since this environment has no LLM credentials |
| APK build/install/test pipeline | PARTIAL | `core-build.BuildPipeline` now has real executors for JVM/NATIVE/GENERIC builds (`core-build-local.LocalProcessBuildExecutor`, proven against real `javac`) and for delegating to a remote build server (`core-build-remote.RemoteBuildExecutor`, proven against a real local test server) — the latter never refuses `ProjectType.ANDROID`, but has never been run against an actual build service, so no real Android APK has been produced by either executor; `core-apk-lifecycle.ApkLifecyclePipeline` still only has `NullApkLifecycleExecutor`, and installing/launching still needs a connected/emulated device this environment does not have |

## 7. Environment constraints recorded for this implementation pass

Verified 2026-09-04 in this sandboxed session:
- JDK 21.0.10, Gradle 8.14.3, Kotlin 2.0.21 toolchain: present.
- `ANDROID_HOME`/`ANDROID_SDK_ROOT`: unset. No `adb`, `emulator`, `sdkmanager`,
  `avdmanager` on PATH. No `~/Android/Sdk` or `/opt/android-sdk`.
- Network reachability to `dl.google.com` and `maven.google.com` confirmed
  (HTTP 200/301), so an Android SDK *could* be provisioned in a session with
  time/disk budget for it — but no physical or emulated device exists here to
  install or launch an APK on regardless of SDK presence.
- No LLM provider credentials configured in this environment.
- No root-capable Android device attached.

These are session facts, not permanent project constraints — a developer
machine or CI runner with the Android SDK and a connected/emulated device
removes most of them. They are recorded here so that "PLANNED" status above
is auditable rather than asserted.
