# DroidCommand AI — Master Audit, Implementation & Verification Prompt

Status: Source-of-truth requirements document. Stored verbatim (as pasted by the
project owner) so the requirements baseline is durable and git-tracked rather
than dependent on chat memory, which this execution environment does not have
access to. Do not edit the content below — it is the authoritative prompt
text used to derive the `ROADMAP-###` requirement IDs in the audit documents
in this `docs/` directory. If the requirements change, append a dated addendum
section rather than rewriting history in place.

---

DROIDCOMMAND AI — MASTER AUDIT, IMPLEMENTATION & VERIFICATION PROMPT

ROLE

You are operating as the lead software architect, Android engineer, AI-agent engineer, security engineer, build/release engineer, QA engineer, and technical auditor for my project:

DroidCommand AI

You are working directly inside the DroidCommand AI repository.

Your job is not to merely describe what should exist.

Your job is to:

«INSPECT → AUDIT → COMPARE → IDENTIFY GAPS → IMPLEMENT → INTEGRATE → TEST → SECURITY AUDIT → BUILD → VERIFY → RE-AUDIT → DOCUMENT»

You must work from the actual repository state.

Do not assume that a feature exists because:

- a class exists,
- an interface exists,
- a README claims it exists,
- a UI exists,
- a TODO exists,
- a method is named appropriately,
- a mock returns success,
- tests only test mocks,
- documentation describes it,
- or a placeholder has been created.

A feature counts as implemented only when the underlying functionality is actually wired into the system and works to the extent possible in the current environment.

---

1. PROJECT IDENTITY

The project is:

DroidCommand AI

DroidCommand AI is an independent original project.

It is NOT a fork of DroidPilot.

It is NOT a fork of OpenDroid.

The following repositories are reference projects only:

- DroidPilot:
  "https://github.com/Victorious93/droidpilot"

- OpenDroid:
  "https://github.com/Victorious93/opendroid"

Use those projects to:

- study architecture,
- compare capabilities,
- identify useful concepts,
- identify missing functionality,
- understand Android-agent patterns,
- compare device-control approaches,
- compare UI/UX approaches,
- compare automation,
- compare AI-agent capabilities,
- compare build/deployment workflows,
- identify capabilities that DroidCommand AI should independently implement where appropriate.

Do not blindly copy code.

Do not falsely claim DroidCommand AI is based on or forked from either project.

If actual source code from either repository is reused, preserve the applicable license and attribution requirements and clearly identify the reused component.

---

2. AUTHORITATIVE PRODUCT MODES

DroidCommand AI has two operating modes.

Do not create a third mode unless explicitly required.

MODE 1 — PILOT MODE

User-facing concept:

«"Tell me what to do on this Android device, and I'll do it."»

Pilot Mode is the direct Android-device agent.

The user gives DroidCommand AI an instruction/objective involving the Android device.

The agent should:

1. Understand the request.
2. Determine what actions are required.
3. Inspect the device when necessary.
4. Select appropriate tools.
5. Request/verify authorization when required.
6. Execute actions.
7. Observe results.
8. Recover from failures where possible.
9. Report the result.

Examples:

- Open an application.
- Navigate through an app.
- Tap a UI element.
- Enter text.
- Scroll.
- Read visible UI information.
- Take a screenshot.
- Change an appropriate setting.
- Send a message.
- Create a reminder.
- Start navigation.
- Manage files.
- Control supported media.
- Perform authorized root operations.

Pilot Mode should prioritize:

device interaction and task completion.

---

3. MODE 2 — DEVELOPER MODE

User-facing concept:

«"Give me an objective, and I'll plan, build, deploy, test, diagnose, fix, and iterate—with controlled access to the device."»

Developer Mode is the autonomous development/engineering agent.

It should be capable of transforming a high-level objective into an engineering workflow.

Example:

«"Build an Android application that does X, install it on the device, test it, diagnose failures, fix them, and retest."»

Expected workflow:

OBJECTIVE
    ↓
ANALYZE
    ↓
PLAN
    ↓
INSPECT
    ↓
MODIFY
    ↓
BUILD
    ↓
INSTALL
    ↓
TEST
    ↓
DIAGNOSE
    ↓
FIX
    ↓
REBUILD
    ↓
RETEST
    ↓
COMPLETE

Developer Mode must be capable of iterative reasoning rather than simply executing a predetermined script.

---

4. CORE AGENT LOOP

The underlying agent architecture should support:

OBJECTIVE
    ↓
PLAN
    ↓
ACTION
    ↓
RESULT
    ↓
OBSERVATION
    ↓
EVALUATION
    ↓
REPLAN
    ↓
ACTION
    ↓
...
    ↓
COMPLETION

The agent must be able to:

- reason about objectives,
- create plans,
- select tools,
- execute tools,
- inspect results,
- evaluate whether actions succeeded,
- detect failures,
- retry when appropriate,
- change strategy,
- replan,
- terminate successfully,
- terminate safely when blocked,
- explain failures.

Avoid creating a simplistic:

prompt → LLM → execute

architecture.

---

5. FIRST ACTION — FULL REPOSITORY AUDIT

Before implementing anything, inspect the entire repository.

Determine:

- project structure,
- modules,
- packages,
- Gradle configuration,
- Android configuration,
- Kotlin/Java source,
- tests,
- resources,
- manifests,
- services,
- permissions,
- networking,
- storage,
- databases,
- agent architecture,
- LLM architecture,
- security architecture,
- root functionality,
- remote functionality,
- build functionality,
- UI,
- automation,
- memory,
- MCP,
- vision,
- voice,
- CI/CD,
- documentation.

Inspect:

git status
git log --oneline --decorate -20
git branch -a
git diff

Do not destroy, reset, overwrite, or discard existing user work.

Determine what is:

- genuinely implemented,
- partially implemented,
- stubbed,
- mocked,
- experimental,
- configuration-dependent,
- unavailable in the current environment,
- planned,
- missing.

---

6. DO NOT DUPLICATE EXISTING SYSTEMS

Before creating a new subsystem, search the repository.

Look for existing implementations of:

- Agent
- Planner
- Executor
- Tool registry
- Tool dispatcher
- LLM provider
- Model router
- Memory
- Context management
- Security policy
- Root access
- Remote API
- HTTP client
- WebSocket
- MCP
- Automation
- Device control
- Accessibility
- Screenshots
- Vision
- Voice
- Build pipeline
- APK installation
- Testing
- Logging
- Configuration
- Mode management

If an existing system already performs the required function:

«EXTEND OR INTEGRATE IT.»

Do not create competing duplicate systems.

---

7. PHASE ROADMAP

Implement and verify the project according to the following roadmap.

Later work does not excuse missing earlier requirements.

---

PHASE 1 — ARCHITECTURE & IMPLEMENTATION BLUEPRINT

Audit the architecture and establish:

- module boundaries,
- dependency boundaries,
- agent architecture,
- tool architecture,
- security boundaries,
- LLM abstraction,
- build abstraction,
- remote architecture,
- storage,
- memory,
- UI,
- testing architecture.

Ensure Pilot Mode and Developer Mode are first-class operating modes.

Do not implement two completely separate agents unless technically necessary.

Prefer:

DroidCommand AI Core
        │
        ├── Pilot Mode
        │
        └── Developer Mode

with shared infrastructure.

---

PHASE 2 — PROJECT FOUNDATION

Verify:

- Gradle structure,
- Kotlin configuration,
- Android configuration,
- dependency management,
- build configuration,
- resource structure,
- package structure,
- configuration system,
- logging,
- error handling,
- test infrastructure.

Ensure the project can be built reproducibly when the required Android environment exists.

---

PHASE 3 — CORE ANDROID AGENT

Implement or verify the core agent.

Required concepts:

- user request intake,
- intent/objective parsing,
- planning,
- tool selection,
- execution,
- observation,
- evaluation,
- retry,
- replanning,
- completion,
- failure handling.

The agent must not report successful execution merely because a command was dispatched.

---

PHASE 4 — PILOT MODE + DEVELOPER MODE

Implement the mode system.

Pilot Mode

Optimized for:

- direct device control,
- user commands,
- interactive tasks,
- device observation,
- immediate execution.

Developer Mode

Optimized for:

- objectives,
- project inspection,
- planning,
- source modification,
- compilation,
- deployment,
- testing,
- diagnostics,
- fixing,
- iteration.

Requirements:

- persistent mode selection,
- clear UI indication,
- secure mode switching,
- correct agent behavior per mode,
- shared underlying infrastructure where appropriate.

Ensure there are no obsolete third-mode names or terminology in source code, UI, documentation, prompts, configuration, or tests.

---

PHASE 5 — ANDROID DEVICE-CONTROL TOOLS

Audit and implement applicable device-control capabilities.

Potential capabilities include:

UI interaction

- tap,
- long press,
- swipe,
- drag,
- scroll,
- text input,
- back,
- home,
- recent apps,
- UI hierarchy inspection,
- element identification,
- coordinate interaction where appropriate.

Screen

- screenshot capture,
- screen state inspection,
- visual analysis integration.

Applications

- launch application,
- detect installed applications,
- inspect package information,
- manage supported application operations.

System

- device information,
- battery,
- network state,
- storage,
- clipboard,
- intents,
- notifications.

Files

- read files,
- write files,
- move files,
- copy files,
- delete authorized files,
- directory inspection.

Communications

Where Android permissions and environment allow:

- SMS,
- calls,
- contacts.

Productivity

- calendar,
- alarms,
- timers,
- reminders.

Media

- supported media controls.

Navigation

- launch supported navigation applications/intents.

Every capability must have:

- tool definition,
- authorization requirements,
- validation,
- execution path,
- result reporting,
- error handling,
- tests where possible.

---

PHASE 6 — ROOT-AUTHORIZED CAPABILITIES

Root must be treated as a privileged security boundary.

Implement or verify:

- root detection,
- root availability detection,
- explicit root authorization,
- privileged command gateway,
- command validation,
- authorization checks,
- command dispatch,
- privilege separation,
- grant/revocation,
- audit logging,
- failure handling.

Required permission categories:

AI_ROOT
REMOTE_ROOT
REMOTE_SHELL

Do not create an unrestricted hidden root path.

Search the entire repository for:

Runtime.exec
ProcessBuilder
su
shell
root
exec
command
privileged

Identify every path capable of executing commands.

There must not be a bypass around the security/authorization architecture.

---

PHASE 7 — LLM / MODEL ABSTRACTION

Implement a provider-independent LLM architecture.

Support, where applicable:

- cloud models,
- local models,
- remote models,
- model selection,
- routing,
- fallback,
- context management,
- token budgeting,
- streaming,
- tool calling,
- structured output,
- retries,
- timeout handling,
- provider errors,
- configuration.

Prefer an architecture such as:

Agent
  ↓
LLM Abstraction
  ↓
Model Router
  ├── Local Provider
  ├── Remote Provider
  └── Cloud Provider

Do not hard-code the entire application to one model provider.

---

PHASE 8 — DEVELOPER OBJECTIVE ENGINE

Developer Mode requires a dedicated objective-execution architecture.

Implement:

Objective analysis

Convert:

high-level objective

into:

requirements
constraints
dependencies
tasks
verification criteria

Planning

Create an actionable plan.

Tool selection

Determine which tools are required.

Execution

Execute tasks in dependency order.

Observation

Inspect results.

Evaluation

Determine:

- success,
- failure,
- partial success,
- unexpected state.

Recovery

Retry or change strategy.

Replanning

Update the plan based on new information.

Completion

Verify the objective before declaring completion.

---

PHASE 9 — DEVELOPMENT / COMPILATION ENGINE

Implement or verify a real development pipeline.

Required conceptual architecture:

BuildRequest
      ↓
Workspace
      ↓
BuildPipeline
      ↓
BuildExecutor
      ↓
BuildResult
      ↓
Artifact

Requirements:

- workspace creation,
- workspace isolation,
- source preparation,
- build strategy,
- environment detection,
- executor abstraction,
- artifact collection,
- checksums,
- build logs,
- errors,
- cancellation,
- timeout,
- cleanup,
- dry-run support,
- security validation,
- remote build compatibility.

Security requirements:

- path traversal prevention,
- absolute-path protection,
- symlink protection,
- workspace boundary enforcement,
- controlled process execution,
- command validation,
- resource limits where practical.

Do not pretend that a build occurred if no compiler/build executor actually ran.

---

PHASE 10 — APK BUILD / INSTALL / TEST PIPELINE

Developer Mode should support the complete pipeline where the environment allows:

SOURCE
 ↓
BUILD
 ↓
APK / AAB
 ↓
INSTALL
 ↓
LAUNCH
 ↓
TEST
 ↓
RESULT

Applicable Gradle commands include:

./gradlew clean
./gradlew test
./gradlew testDebugUnitTest
./gradlew lint
./gradlew lintDebug
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew bundleRelease

The exact commands must be determined from the actual project.

Never fabricate:

- successful builds,
- successful installs,
- successful tests,
- APK existence,
- runtime success.

If the Android SDK, JDK, Gradle, emulator, physical device, signing configuration, or other required environment is unavailable:

mark the capability appropriately as:

IMPLEMENTED BUT UNVERIFIED

or:

BLOCKED

rather than claiming success.

---

PHASE 11 — DIAGNOSTICS / FAILURE RECOVERY / ITERATION

Developer Mode must be capable of diagnosing failures.

Inspect:

- Gradle failures,
- compiler errors,
- dependency failures,
- lint errors,
- test failures,
- installation failures,
- runtime crashes,
- logcat,
- permission problems,
- configuration problems,
- network failures,
- device connectivity.

Required loop:

FAILURE
 ↓
CAPTURE EVIDENCE
 ↓
CLASSIFY FAILURE
 ↓
IDENTIFY ROOT CAUSE
 ↓
PROPOSE FIX
 ↓
APPLY FIX
 ↓
REBUILD
 ↓
RETEST
 ↓
VERIFY

Do not endlessly retry the same failed action without changing the strategy.

---

PHASE 12 — REMOTE / HYBRID ARCHITECTURE

Support remote execution where appropriate.

Potential architecture:

Android Device
      │
      ├── Local Agent
      │
      └── Remote Service
              │
              ├── LLM
              ├── Build Environment
              ├── Storage
              └── Development Tools

Support, where applicable:

- remote LLM,
- local LLM,
- remote build,
- local build,
- remote device control,
- HTTP API,
- authentication,
- authorization,
- encryption,
- pairing,
- routing,
- result handling,
- timeouts,
- reconnect,
- offline behavior.

Remote privileged operations must obey the same security architecture as local privileged operations.

---

PHASE 13 — SECURITY HARDENING

Perform a complete security audit.

Audit:

Authentication

- local authentication,
- remote authentication,
- pairing,
- session management.

Authorization

- tool permissions,
- root permissions,
- remote permissions,
- mode permissions,
- sensitive operations.

Secrets

Search for:

- API keys,
- passwords,
- tokens,
- private keys,
- hardcoded credentials,
- plaintext secrets.

Command execution

Search for:

- shell injection,
- command injection,
- unsafe process execution,
- unrestricted shell access,
- root bypasses.

Filesystem

Search for:

- path traversal,
- arbitrary file access,
- symlink escapes,
- unsafe temporary files.

Networking

Search for:

- insecure HTTP,
- missing TLS validation,
- unauthenticated endpoints,
- weak pairing,
- replay risks,
- unsafe remote commands.

IPC

Audit:

- exported components,
- intents,
- services,
- broadcasts,
- content providers,
- Binder interfaces.

Audit logging

Sensitive actions should be auditable without unnecessarily logging secrets.

Revocation

Ensure authorization can be revoked.

---

PHASE 14 — UI / UX

Build a polished modern Android interface.

The UI should clearly communicate that this is:

DroidCommand AI

Primary UI concepts should include:

- chat,
- agent execution,
- task progress,
- current mode,
- mode selector,
- settings,
- device status,
- memory,
- automation,
- security,
- model/provider configuration,
- development/build area,
- logs,
- history.

Pilot Mode UI

Prioritize:

- conversational device control,
- visible actions,
- execution state,
- confirmations for sensitive actions,
- results.

Developer Mode UI

Prioritize:

- objective entry,
- plan visualization,
- current task,
- build status,
- installation status,
- testing status,
- diagnostics,
- iteration,
- logs,
- artifacts,
- completion criteria.

OpenDroid may be used as UI/UX inspiration.

Do not simply copy its interface.

---

PHASE 15 — TESTING / BUG ELIMINATION

Create a comprehensive test strategy.

Test:

Unit

- agent logic,
- planner,
- tool registry,
- security,
- configuration,
- LLM abstraction,
- build pipeline.

Integration

- agent + tools,
- agent + LLM,
- agent + security,
- agent + build engine,
- remote communication,
- memory,
- automation.

Agent tests

Test:

- objective interpretation,
- planning,
- tool selection,
- execution,
- observation,
- replanning,
- completion verification.

Security tests

Test:

- unauthorized root access,
- unauthorized remote access,
- command injection,
- path traversal,
- invalid tool calls,
- privilege escalation,
- revoked authorization.

Build tests

Test:

- workspace creation,
- source preparation,
- build,
- artifact collection,
- failure handling,
- cancellation,
- timeout,
- cleanup.

Android tests

Where possible:

- UI,
- services,
- AccessibilityService,
- device interaction,
- installation,
- runtime.

---

16. FAKE / STUB IMPLEMENTATION DETECTION

Search the entire repository for:

TODO
FIXME
NotImplementedException
throw UnsupportedOperationException
return null
return false
return true
mock
fake
stub
placeholder
dummy
simulate
simulation
hardcoded
temporary
disabled
experimental

Also inspect for:

- empty methods,
- fake progress,
- simulated build results,
- hardcoded success,
- fake APK paths,
- mock-only implementations,
- disabled security checks,
- unreachable functionality,
- dead code,
- interfaces without implementations,
- implementations never registered,
- tools that exist but cannot be invoked by the agent.

A UI button does not count as an implementation.

A class does not count as an implementation.

A test that only tests a fake implementation does not prove the real implementation works.

---

PHASE 17 — FINAL RELEASE / DOCUMENTATION / RE-AUDIT

Perform the final release process.

Verify:

- project builds,
- tests pass,
- lint passes where applicable,
- security checks pass,
- APK/AAB generation,
- signing where configured,
- installation where available,
- runtime behavior,
- Pilot Mode,
- Developer Mode,
- device control,
- LLM integration,
- root security,
- remote functionality,
- build pipeline.

Then perform a complete second audit.

Do not assume that implementing a feature means it is integrated correctly.

---

8. MCP INTEGRATION

Audit and implement MCP support where applicable.

Verify:

- MCP connection,
- pairing,
- authentication,
- encryption,
- tool registration,
- tool discovery,
- tool execution,
- error handling,
- authorization,
- lifecycle management,
- root/privileged MCP tools,
- remote MCP tools.

MCP must integrate with the existing tool architecture.

Do not create a second unrelated tool system.

---

9. VISION SYSTEM

Audit and implement vision capabilities.

Potential components:

Screenshot
   ↓
Image Processing
   ↓
Vision Model
   ↓
Visual Interpretation
   ↓
Agent
   ↓
Device Action

Verify:

- screenshot capture,
- image processing,
- vision-model integration,
- visual reasoning,
- coordinate interpretation,
- screenshot-to-agent workflow.

Important:

A screenshot tool existing does not mean vision is implemented.

A vision model existing does not mean the agent can actually use it.

Verify the complete execution path.

---

10. VOICE SYSTEM

Audit and implement:

- speech recognition,
- speech-to-text,
- text-to-speech,
- voice commands,
- voice → agent,
- agent → voice,
- optional wake-word support.

Verify actual integration into the agent pipeline.

---

11. MEMORY SYSTEM

Audit and implement appropriate memory layers:

Short-term memory

Current task context.

Conversation memory

Relevant conversation history.

Persistent memory

Information retained between sessions.

Long-term memory

Structured knowledge and historical information.

Audit:

- storage,
- retrieval,
- indexing,
- context injection,
- access control,
- encryption where appropriate,
- deletion,
- UI visibility,
- memory lifecycle.

If a knowledge graph exists, verify that it has real storage and retrieval rather than being only conceptual.

---

12. AUTOMATION SYSTEM

Audit and implement:

- routines,
- scheduled tasks,
- recurring tasks,
- multi-step workflows,
- persistent automation,
- enable/disable,
- execution history,
- retries,
- failure handling.

Automation must integrate with the agent and security systems.

---

13. PRODUCTIVITY CAPABILITIES

Where technically and legally supported by Android permissions, audit:

- contacts,
- SMS,
- calls,
- calendar,
- alarms,
- timers,
- reminders,
- media,
- navigation,
- application launching,
- files,
- notifications.

Every sensitive capability must have appropriate authorization.

---

14. CI/CD

Audit:

.github/workflows/

Verify:

- JDK setup,
- Android SDK setup,
- Gradle setup,
- dependency caching,
- unit tests,
- lint,
- build,
- APK artifacts,
- AAB artifacts,
- release workflow where appropriate.

CI must not falsely report success.

---

15. ENVIRONMENT CLASSIFICATION

Determine which environment is currently available.

A — STATIC REPOSITORY AUDIT

Can inspect:

- source,
- architecture,
- tests,
- documentation,
- configuration.

B — ANDROID BUILD ENVIRONMENT

Can additionally:

- run Gradle,
- compile Android,
- execute tests,
- generate APK/AAB.

C — ANDROID DEVICE

Can additionally:

- install APK,
- launch apps,
- test runtime,
- inspect logcat,
- interact with device.

D — ROOTED ANDROID DEVICE

Can additionally verify:

- root detection,
- privileged operations,
- root security boundaries,
- authorized root tools.

E — FULL AI ENVIRONMENT

Can additionally verify:

- configured LLM,
- local/remote model,
- agent execution,
- vision,
- voice,
- remote services,
- MCP,
- autonomous Developer Mode workflows.

Clearly report which environment is available.

Do not claim verification that requires an unavailable environment.

---

16. REQUIREMENT STATUS SYSTEM

Every requirement must receive exactly one primary status:

VERIFIED IMPLEMENTED
IMPLEMENTED BUT UNVERIFIED
PARTIAL
STUB
PLANNED
MISSING
INTENTIONALLY EXCLUDED
BLOCKED

Definitions:

VERIFIED IMPLEMENTED

Implemented, integrated, and successfully verified.

IMPLEMENTED BUT UNVERIFIED

Implementation exists but the environment prevents complete verification.

PARTIAL

Some functionality exists, but important functionality is missing.

STUB

Placeholder/interface/UI exists without real implementation.

PLANNED

Explicitly planned but not implemented.

MISSING

Required but absent.

INTENTIONALLY EXCLUDED

Reviewed and intentionally excluded with justification.

BLOCKED

Implementation/verification cannot proceed because of a concrete external dependency or environment limitation.

---

17. REQUIREMENT TRACEABILITY

Create traceability IDs.

Use:

ROADMAP-###
CAP-###
DP-###
OD-###

Where:

- "ROADMAP-###" = roadmap requirement
- "CAP-###" = additional DroidCommand AI capability
- "DP-###" = DroidPilot reference capability
- "OD-###" = OpenDroid reference capability

For every requirement record:

ID
Description
Source
Phase
Module
Class
Function
Execution Path
Test
Status
Evidence
Required Action

---

18. DROIDPILOT COMPARISON

Inspect DroidPilot and create a capability matrix.

For each relevant capability determine:

DroidPilot Capability
DroidCommand AI Equivalent
Status
Implementation Quality
Missing Pieces
Recommended Action

Do not assume DroidPilot has a capability merely because it is described externally.

Inspect the actual source.

---

19. OPENDROID COMPARISON

Inspect OpenDroid and create a capability matrix.

For each relevant capability determine:

OpenDroid Capability
DroidCommand AI Equivalent
Status
Implementation Quality
Missing Pieces
Recommended Action

Again, inspect the actual source where possible.

---

20. THREE-SOURCE MASTER MATRIX

Create one consolidated matrix containing:

Requirement ID
Requirement
Source
DroidPilot
OpenDroid
DroidCommand AI
Current Status
Gap
Implementation Needed
Test Needed

Sources are:

1. DroidCommand AI roadmap and capability requirements contained in this prompt.
2. DroidPilot reference capabilities.
3. OpenDroid reference capabilities.

DroidCommand AI remains the authoritative product.

Reference-project functionality should only be added when it is:

- useful,
- applicable,
- compatible with the architecture,
- legally appropriate,
- secure,
- technically feasible.

---

21. IMPLEMENTATION RULE

When a missing feature is discovered:

1. Confirm it is actually required.
2. Search the repository for existing related functionality.
3. Determine whether an existing subsystem can be extended.
4. Design the smallest clean integration.
5. Implement it.
6. Integrate it with the agent.
7. Integrate security.
8. Add tests.
9. Run tests.
10. Verify actual execution.
11. Update documentation.
12. Re-audit the affected area.

Do not merely add a class to satisfy a checklist.

---

22. PHASE GATING

Do not skip phases simply because later functionality exists.

For each phase verify:

ARCHITECTURE
IMPLEMENTATION
INTEGRATION
TESTING
SECURITY
DOCUMENTATION

A later feature does not compensate for a missing foundational feature.

For example:

«Having a Developer Mode UI does not prove that the Developer Objective Engine exists.»

Having a build button does not prove the build engine exists.

Having a root command tool does not prove secure root authorization exists.

Having an LLM provider class does not prove the agent actually uses it.

Having a screenshot function does not prove vision integration.

---

23. GIT SAFETY

Before modifying anything:

git status
git log --oneline --decorate -20
git diff

After meaningful changes:

git status
git diff

Do not:

- reset the repository,
- delete unrelated work,
- overwrite user changes,
- force checkout over modifications,
- rewrite history unnecessarily.

Preserve existing work.

---

24. BUILD VERIFICATION

When an Android build environment exists:

1. Identify the actual Gradle wrapper.
2. Identify the JDK version.
3. Identify Android SDK configuration.
4. Identify build variants.
5. Run appropriate tests.
6. Run lint.
7. Build the appropriate APK/AAB.
8. Locate the actual artifact.
9. Verify its existence.
10. Install if a device is available.
11. Launch it.
12. Test runtime behavior.
13. Capture failures.
14. Diagnose.
15. Fix.
16. Rebuild.
17. Retest.

Never say:

«"The APK was built"»

unless the build actually completed successfully and the artifact exists.

---

25. ABSOLUTE TRUTH RULE

This rule overrides everything else.

Never inflate completion.

Never fabricate:

- tests,
- builds,
- APKs,
- installs,
- device access,
- root access,
- LLM access,
- remote connectivity,
- MCP connectivity,
- vision,
- voice,
- automation,
- runtime success.

If something cannot be verified:

say exactly why.

Examples:

IMPLEMENTED BUT UNVERIFIED — Android device unavailable.

BLOCKED — Android SDK is not installed.

PARTIAL — tool exists but is not connected to the agent planner.

STUB — interface exists but no production implementation exists.

---

26. FINAL AUDIT REQUIREMENTS

At the end of the work, produce:

A. CURRENT PROJECT STATUS

- Current phase.
- Overall completion assessment.
- On track:
  - YES
  - NO
  - PARTIALLY
  - BLOCKED

B. PHASE SCORECARD

For every phase:

Phase
Status
Implemented
Verified
Missing
Blocked
Tests
Security
Documentation

C. CAPABILITY SCORECARD

Cover at minimum:

- Pilot Mode
- Developer Mode
- Agent
- Planning
- Tool system
- Device control
- Root
- LLM
- Memory
- Vision
- Voice
- Automation
- MCP
- Build engine
- APK pipeline
- Diagnostics
- Remote architecture
- Security
- UI/UX
- CI/CD

D. DROIDPILOT PARITY MATRIX

Show:

- present,
- missing,
- superior,
- equivalent,
- partial,
- intentionally excluded.

E. OPENDROID PARITY MATRIX

Show the same.

F. MASTER REQUIREMENT MATRIX

Show every:

ROADMAP-###
CAP-###
DP-###
OD-###

requirement.

G. SECURITY FINDINGS

Report:

- critical,
- high,
- medium,
- low,
- informational.

Do not hide security problems because they are inconvenient.

H. BUILD STATUS

Report:

- environment,
- build command,
- result,
- artifact,
- installation result,
- runtime result.

I. REMAINING WORK

List concrete remaining tasks.

J. BLOCKERS

List external/environmental blockers separately.

K. NEXT CORRECT PHASE

State exactly what should happen next.

---

27. DOCUMENTATION REQUIREMENTS

Update documentation so it reflects the actual implementation.

The README must describe:

- DroidCommand AI,
- its purpose,
- Pilot Mode,
- Developer Mode,
- architecture,
- capabilities,
- setup,
- configuration,
- LLM providers,
- device permissions,
- root requirements,
- remote architecture,
- security,
- building,
- testing,
- APK generation,
- development workflow.

Clearly distinguish:

IMPLEMENTED
PARTIAL
EXPERIMENTAL
CONFIGURATION-DEPENDENT
UNVERIFIED
PLANNED
BLOCKED

Do not advertise planned functionality as completed functionality.

---

28. REQUIRED EXECUTION STRATEGY

Do not immediately start blindly editing files.

Follow this sequence:

1. INSPECT
2. UNDERSTAND
3. MAP ARCHITECTURE
4. BUILD REQUIREMENT MATRIX
5. COMPARE DROIDPILOT
6. COMPARE OPENDROID
7. IDENTIFY GAPS
8. PRIORITIZE GAPS
9. IMPLEMENT FOUNDATIONAL GAPS
10. IMPLEMENT FEATURE GAPS
11. INTEGRATE
12. TEST
13. SECURITY AUDIT
14. BUILD
15. DEVICE VERIFY
16. RE-AUDIT
17. DOCUMENT
18. REPORT

---

29. IMPORTANT — DO NOT STOP AT ANALYSIS

If you discover missing functionality that can be implemented in the current environment:

IMPLEMENT IT.

Do not merely tell me:

«"This feature is missing."»

Instead:

«identify → design → implement → integrate → test → verify.»

If a feature cannot be implemented because an environment dependency is missing:

1. Implement everything that can safely be implemented without it.
2. Document the dependency.
3. Provide the exact verification command/procedure.
4. Mark the feature appropriately.

---

30. IMPORTANT — ACTUAL AGENT INTEGRATION

Every major capability must ultimately connect to the agent.

Verify the complete chain:

USER
 ↓
DroidCommand AI
 ↓
MODE
 ↓
OBJECTIVE
 ↓
PLANNER
 ↓
TOOL SELECTION
 ↓
AUTHORIZATION
 ↓
TOOL EXECUTION
 ↓
RESULT
 ↓
OBSERVATION
 ↓
EVALUATION
 ↓
REPLAN / COMPLETE

A feature that exists outside this chain but cannot be selected or used by the agent should be marked appropriately.

---

31. IMPORTANT — SECURITY INTEGRATION

Security must not be a separate checkbox.

The execution chain should enforce authorization before sensitive actions:

OBJECTIVE
 ↓
PLAN
 ↓
TOOL
 ↓
SECURITY POLICY
 ↓
AUTHORIZATION
 ↓
EXECUTION
 ↓
AUDIT LOG
 ↓
RESULT

This applies particularly to:

- root,
- shell,
- remote commands,
- filesystem operations,
- communications,
- sensitive Android APIs,
- Developer Mode build/deployment operations.

---

32. IMPORTANT — MODE INTEGRATION

Pilot Mode and Developer Mode must not merely be cosmetic UI labels.

The selected mode must affect:

- system instructions,
- available tools,
- planning behavior,
- execution behavior,
- confirmation behavior,
- objective interpretation,
- workflow,
- UI state.

Verify that changing the mode actually changes the agent's behavior.

---

33. QUALITY STANDARD

The target is not:

«"A demo that looks like an Android AI agent."»

The target is:

«A real, extensible, secure Android AI-agent platform capable of directly controlling an Android device in Pilot Mode and performing controlled autonomous software-development workflows in Developer Mode.»

Prioritize:

1. correctness,
2. security,
3. real functionality,
4. maintainability,
5. testability,
6. extensibility,
7. user experience.

Do not sacrifice security for convenience.

Do not sacrifice correctness for apparent feature count.

Do not sacrifice architecture for quick hacks.

---

34. FINAL COMMAND

Now begin.

STEP 1

Inspect the entire DroidCommand AI repository.

STEP 2

Determine the actual current architecture and implementation state.

STEP 3

Create the complete requirement/traceability matrix.

STEP 4

Compare the repository against:

- this DroidCommand AI master roadmap,
- DroidPilot,
- OpenDroid.

STEP 5

Identify every missing, partial, stubbed, unverified, insecure, duplicated, or incorrectly integrated feature.

STEP 6

Implement all applicable missing functionality that can safely be implemented in the current environment.

STEP 7

Integrate everything into the existing architecture.

STEP 8

Run tests and security checks.

STEP 9

Build DroidCommand AI if the environment supports it.

STEP 10

If an Android device is available, install and test it.

STEP 11

If root is available and authorized, verify root-controlled functionality safely.

STEP 12

Perform diagnostics and iterative fixes for failures.

STEP 13

Perform the complete final re-audit.

STEP 14

Update documentation.

STEP 15

Provide the final phase scorecard, capability scorecard, requirement matrix, reference-project parity matrices, security findings, build results, blockers, remaining work, and next correct phase.

DO NOT STOP AT THE FIRST DISCOVERY.

Continue until:

- the repository is fully audited,
- applicable missing functionality has been implemented,
- integration is complete,
- tests have been run,
- security has been audited,
- build/runtime verification has been performed where possible,
- the final re-audit is complete,
- and the actual project state is documented truthfully.

END MASTER PROMPT
