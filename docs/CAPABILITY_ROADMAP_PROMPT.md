# DroidCommand AI — Complete Master Priority & Architecture Roadmap

Status: Source-of-truth capability/architecture roadmap. Stored verbatim (as
supplied by the project owner) so it is durable and git-tracked rather than
dependent on chat memory, which this execution environment does not have
access to. This is the `CAP-###` prompt referenced in `docs/AUDIT_2026-09-05.md`
and in this repo's `CLAUDE.md` as previously unrecovered. Do not edit the
content below — it is the authoritative prompt text. If the roadmap changes,
append a dated addendum section rather than rewriting history in place.

---

# DroidCommand AI — Complete Master Priority & Architecture Roadmap

## FOUNDATIONAL PURPOSE

DroidCommand AI must be developed as a **universal, capability-driven, modular AI operating platform**, not as a collection of disconnected features or integrations.

The architecture must be implemented in dependency order.

Do not build individual features as isolated implementations.

Build the underlying:

- AI architecture
- conversation/context architecture
- persona system
- capability system
- policy system
- execution system
- provider/adapter system
- networking system
- infrastructure system
- verification system

**first**, then build individual integrations on top of those foundations.

### The Core Question

The goal is a system that can determine:

> **«What can I do in this environment, what is the user authorized to let me do, what is the best execution target, what context/persona should influence the response, and how can I verify the result?»**

---

# NON-NEGOTIABLE DEVELOPMENT RULES

Claude Code **must** follow these rules unconditionally:

1. Inspect the existing DroidCommand AI repository before making architectural changes.
2. Preserve working functionality.
3. Reuse existing architecture where appropriate.
4. Never blindly rewrite working modules.
5. Never fabricate implementation status.
6. Never claim a feature works when it has not been verified.
7. Never create fake implementations merely to satisfy a checklist.
8. Never create UI-only placeholders.
9. Never replace real functionality with mocks except inside explicitly isolated tests.
10. Add tests for meaningful functionality.
11. Add integration tests where practical.
12. Document environmental requirements.
13. Detect capabilities dynamically.
14. Gracefully degrade when optional capabilities are unavailable.
15. Keep security and authorization centralized.
16. Keep provider-specific logic isolated.
17. Keep AI persona/style behavior separate from security policy.
18. Prefer the safest and least-privileged execution mechanism capable of completing a task.
19. Never assume root, Shizuku, Termux, Docker, Proxmox, VPN connectivity, or any other optional dependency exists.
20. Never mark an unverified capability as functional.

**If something cannot be tested in the current environment, explicitly state:**

```
IMPLEMENTED — NOT RUNTIME VERIFIED
[environment requirement]
```

rather than claiming successful operation.

---

# P0 — AI FOUNDATION

## P0.1 — Context Manager

Implement or strengthen a centralized "ContextManager".

It must manage:

- conversation context
- system instructions
- user context
- project context
- device context
- task context
- tool context
- execution context
- knowledge graph context
- persona context
- conversation summaries
- relevant historical information

The system must **prioritize relevant context** instead of blindly sending entire conversation histories to the model.

**Required types:**

```kotlin
interface ContextManager {
    /**
     * Get the current context, prioritized and within the configured token budget.
     */
    suspend fun buildContext(task: Task, budget: TokenBudget): ContextSnapshot

    /**
     * Register a context provider.
     */
    fun registerProvider(provider: ContextProvider)

    /**
     * Get context metadata (source, tokens contributed, priority).
     */
    fun inspectContext(): ContextInspection
}

data class ContextSnapshot(
    val task: Task,
    val conversationContext: ConversationContext,
    val systemInstructions: String,
    val userContext: UserContext,
    val projectContext: ProjectContext,
    val deviceContext: DeviceContext,
    val activePersona: Persona?,
    val executionContext: ExecutionContext,
    val knowledgeGraphContext: KnowledgeGraphSnapshot,
    val relevantSummaries: List<String>,
    val tokenBudgetUsed: Int,
    val tokenBudgetRemaining: Int
)
```

---

## P0.2 — Token Budget Manager

Implement configurable context/token budgets with intelligent prioritization.

**Required budget profiles:**

```
1000 tokens   (lightweight, mobile-first)
3000 tokens   (balanced)
8000 tokens   (comprehensive)
16000 tokens  (full context, expensive)
```

The system should select the appropriate budget based on task complexity.

**Prioritize in this order:**

1. Current task
2. Relevant local context
3. Relevant project context
4. Active persona
5. Relevant knowledge graph information
6. Summaries
7. Older conversation history (only when useful)

Do not send unnecessary history. The system must provide token/context visibility where practical.

**Required types:**

```kotlin
enum class TokenBudget(val tokens: Int) {
    LIGHTWEIGHT(1000),
    BALANCED(3000),
    COMPREHENSIVE(8000),
    FULL(16000)
}

interface TokenBudgetManager {
    /**
     * Determine the appropriate budget for a task.
     */
    fun selectBudget(task: Task): TokenBudget

    /**
     * Allocate tokens within a budget, respecting priority order.
     */
    fun allocateTokens(
        snapshot: ContextSnapshot,
        budget: TokenBudget
    ): AllocatedContext

    /**
     * Provide visibility into token usage.
     */
    fun inspectAllocation(): AllocationReport
}

data class AllocationReport(
    val budgetSelected: TokenBudget,
    val contributions: List<ContextContribution>,
    val totalUsed: Int,
    val remaining: Int,
    val overflowStrategy: String  // "truncate", "prioritize", "summarize"
)
```

---

## P0.3 — Local-Context-First AI

DroidCommand AI should prefer locally available information before performing expensive external retrieval.

**Priority order:**

```
Current Task
    ↓
Active Project Context
    ↓
Local Conversation Context
    ↓
Local Knowledge Graph
    ↓
Relevant Summaries
    ↓
Local Files
    ↓
Configured Local/Self-Hosted AI
    ↓
Remote AI Provider (Cloud API)
    ↓
External Resources
```

The architecture must remain **provider-agnostic**. Local-first is a preference, not a hard constraint.

---

## P0.4 — AI Provider Abstraction

Support an abstraction layer for AI providers so the core agent is not hard-coded to a single model.

**Potential providers:**

```
Ollama (local)
Self-hosted local models
Self-hosted model servers
Remote LLM services
Cloud APIs (Anthropic, OpenAI, etc.)
Future providers
```

The system should determine which provider is appropriate for a task based on:

- Capability (can this provider handle this task type?)
- Latency (local vs. remote)
- Context length (max tokens)
- Availability (is the provider up and authorized?)
- Privacy requirements (local vs. cloud)
- Resource requirements (memory, GPU, etc.)
- User configuration (preferred provider)
- Token cost
- Task complexity

**Required types:**

```kotlin
interface AiProviderSelector {
    /**
     * Determine the best provider for a task.
     */
    suspend fun selectProvider(task: Task, preferences: ProviderPreferences): AiProvider?

    /**
     * List available providers with their capabilities.
     */
    fun listProviders(): List<AiProviderInfo>
}

data class AiProviderInfo(
    val id: String,
    val name: String,
    val type: ProviderType,  // LOCAL, SELF_HOSTED, CLOUD, etc.
    val maxContextTokens: Int,
    val available: Boolean,
    val cost: Cost?,
    val capabilities: Set<ProviderCapability>
)
```

---

## P0.5 — Uploadable Conversation Persona / Style Mimic System

Implement a **first-class Conversation Persona System**. This is a core AI capability.

The user must be able to upload conversation history or export files and use them to create optional persona/style profiles.

**Persona extraction should analyze:**

```
Tone & Formality
Vocabulary & Language patterns
Sentence structure & rhythm
Verbosity & conciseness
Formatting & punctuation habits
Humor patterns & wit
Directness vs. indirectness
Common expressions & phrases
Preferred response structure
Communication style
Recurring behavioral patterns
```

Do not simply copy the uploaded conversation into every prompt. Create a compact, reusable representation.

**Required types:**

```kotlin
data class Persona(
    val id: String,
    val name: String,
    val category: PersonaCategory,  // PERSONAL, PROFESSIONAL, CODING, CUSTOM, etc.
    val sourceConversations: List<String>,
    val styleCharacteristics: StyleProfile,
    val contextContribution: String,  // compact representation for LLM
    val version: String,
    val enabled: Boolean
)

data class StyleProfile(
    val tone: String,
    val vocabulary: VocabProfile,
    val sentenceStructure: StructureProfile,
    val formality: Formality,
    val verbosity: Verbosity,
    val humor: HumorProfile,
    val responseStructure: String,
    val commonExpressions: List<String>
)

interface PersonaManager {
    /**
     * Create a persona from uploaded conversation(s).
     */
    suspend fun createPersonaFromConversation(
        files: List<File>,
        name: String,
        category: PersonaCategory
    ): Persona

    /**
     * Enable/disable a persona.
     */
    suspend fun setActivePersona(personaId: String, enabled: Boolean)

    /**
     * Test a persona against sample prompts.
     */
    suspend fun testPersona(personaId: String, samplePrompts: List<String>): TestResult

    /**
     * Manage personas: create, rename, duplicate, update, delete, export, import.
     */
    suspend fun listPersonas(): List<Persona>
}

enum class PersonaActivationStatus {
    UPLOADED,
    PARSED,
    ANALYZED,
    PROFILE_CREATED,
    ACTIVATED,
    RUNTIME_TESTED
}
```

**Critical Isolation:** Persona information must **never** override:

- Security policies
- Permission policies
- Authorization
- Tool restrictions
- Root restrictions
- System-level safety requirements
- Factuality requirements
- Execution policies

Persona = communication behavior. Policy = authorization. These must remain **architecturally separate**.

---

## P0.6 — Conversation Import System

Build a reusable conversation import pipeline capable of supporting multiple formats.

**Architecture:**

```
File
  ↓
Format Detection
  ↓
Parser
  ↓
Normalization
  ↓
Validation
  ↓
Conversation Records
  ↓
Analyzer
  ↓
Storage
```

Imported conversations become **structured data**, not opaque files. This pipeline feeds the Persona system (P0.5).

---

## P0.7 — Knowledge Graph

Maintain a structured knowledge graph for entities and relationships relevant to DroidCommand AI.

**Potential entities:**

```
Notes
Automations
Devices
Commands
Logs
Projects
Concepts
Variables
Plugins
AI Context
Documents
Conversations
Personas
Tasks
Execution Targets
```

The graph must support relationships and retrieval. Use graph-based retrieval where useful instead of indiscriminately loading all stored information.

---

# P1 — CAPABILITY ARCHITECTURE

## P1.0 — Agent ↔ Router Interface

Define the request/response contract between the LLM agent and the execution router. The agent must never invoke a capability string not present in the live registry.

**Required types:**

```kotlin
// Namespace-qualified capability identifier
data class CapabilityId(val value: String) {
    init {
        require(value.matches(Regex("""^[a-z0-9][a-z0-9._-]*$"""))) {
            "Invalid capability ID format: $value"
        }
    }
}

// Request from agent to router
data class ExecutionRequest(
    val capabilityId: CapabilityId,
    val targetType: ExecutionTargetType,
    val parameters: Map<String, String>,
    val riskTier: RiskTier
)

// Response from router back to agent
sealed class ExecutionResponse {
    data class Success(
        val result: String,
        val targetUsed: ExecutionTargetType,
        val verified: Boolean
    ) : ExecutionResponse()

    data class RequiresApproval(
        val operationDescription: String,
        val riskTier: RiskTier,
        val requestId: String
    ) : ExecutionResponse()

    data class Denied(
        val reason: String,
        val suggestedAlternative: String? = null
    ) : ExecutionResponse()

    data class CapabilityUnavailable(
        val capabilityId: CapabilityId,
        val reason: String,
        val details: String? = null
    ) : ExecutionResponse()
}

enum class RiskTier {
    READ_ONLY,      // Query operations, no state change
    REVERSIBLE,     // Can be undone or has minor impact
    DESTRUCTIVE,    // Deletion, uninstall, data loss — requires approval
    IRREVERSIBLE    // VM shutdown, partition wipe — requires explicit approval
}

enum class ExecutionTargetType {
    ANDROID,
    TERMUX,
    LOCAL_PC,
    DOCKER,
    REMOTE_HOST,
    PROXMOX_VM,
    PROXMOX_LXC
}
```

---

## P1.1 — Capability Manager (with re-verification)

Implement a live, re-verifiable capability registry. Capabilities are **NOT** detected once at boot and cached forever.

**Supported states:**

```
AVAILABLE                    // Verified present and working
ENABLED                      // Registered but enabled by user/policy
DISABLED                     // Registered but disabled
UNAVAILABLE                  // Present but not accessible
REQUIRES_PERMISSION          // Needs Android runtime permission
REQUIRES_ROOT                // Requires rooted device
REQUIRES_SHIZUKU             // Requires Shizuku privilege service
REQUIRES_TERMUX              // Requires Termux installation
REQUIRES_CONFIGURATION       // Missing required config
REQUIRES_EXTERNAL_SERVICE    // Needs remote service
ERROR                        // Last verification failed
```

**Required types:**

```kotlin
data class CapabilityMetadata(
    val id: CapabilityId,
    val providerId: String,         // "android", "termux", "docker", "proxmox", etc.
    val version: String,            // semver
    val state: CapabilityState,
    val permissionsRequired: List<String>,
    val dependencies: List<CapabilityId>,
    val lastVerifiedAt: Long?,      // milliseconds since epoch
    val lastError: String?,
    val description: String,
    val riskTier: RiskTier
)

interface CapabilityRegistry {
    fun getCapability(id: CapabilityId): CapabilityMetadata?
    fun listCapabilities(filter: CapabilityFilter = CapabilityFilter()): List<CapabilityMetadata>
    fun register(metadata: CapabilityMetadata)
    suspend fun reverify(id: CapabilityId): CapabilityMetadata
    fun invalidate(id: CapabilityId)
}

interface CapabilityHealthChecker {
    /**
     * Verify whether a capability is actually available.
     * Called on initial registration, on schedule, on-demand when invalidated,
     * and after failed execution.
     */
    suspend fun verify(id: CapabilityId, providerId: String): CapabilityMetadata

    /**
     * Suggest re-verification cadence for this provider.
     * Local checks are cheap (10s); remote checks are expensive (5m).
     */
    fun suggestedReverifyIntervalMs(providerId: String): Long
}
```

**Re-verification logic:**

- On access, check `lastVerifiedAt` against the provider's suggested cadence.
- If stale, kick off an async re-verify; return current state immediately but tag it with staleness.
- On provider failure, call `invalidate()` so the next call re-verifies.

---

## P1.2 — Policy & Permission Engine (extend existing)

Extend the existing `core-security` module's `SecurityLevel` enum and `SecurityPolicy`.

**Permission types:**

```
VIEW
AUTOMATION
TERMINAL
FILES
NETWORK
AI
REMOTE_CONTROL
DEVICE_CONTROL
KNOWLEDGE_GRAPH
ROOT
CONTAINER               (⚠️ TREAT AS ROOT-EQUIVALENT)
VIRTUALIZATION
INFRASTRUCTURE
```

**CRITICAL:** Docker/container socket access is **root-equivalent**. Never present `CONTAINER` as a peer permission to `VIEW`/`AUTOMATION`. Document this explicitly in the policy engine.

**Escalation preference order:**

```
Android system APIs
    ↓
AccessibilityService
    ↓
Android runtime permissions
    ↓
Termux (separate execution environment)
    ↓
Shizuku (scoped privilege API)
    ↓
ADB (where available)
    ↓
Root (last resort)
```

**Note:** Termux is not a privilege tier; it's a separate execution environment. Shizuku requires ADB or root to bootstrap; it's the API surface, not the privilege grant.

---

## P1.3 — Execution Target Abstraction

Refactor the real `ProcessBuilderShellExecutor` behind an abstraction that other targets can implement.

**Required types:**

```kotlin
interface ExecutionTarget {
    val id: String
    val type: ExecutionTargetType
    val context: ExecutionContext
    val availableCapabilities: Set<CapabilityId>

    suspend fun isHealthy(): Boolean

    suspend fun execute(
        argv: List<String>,
        workingDir: String? = null,
        env: Map<String, String>? = null,
        timeoutMs: Long = 30_000
    ): ExecutionResult
}

data class ExecutionContext(
    val workingDir: String,
    val user: String,
    val uid: Int? = null,
    val environment: Map<String, String>,
    val privilegeLevel: PrivilegeLevel
)

enum class PrivilegeLevel {
    USER,
    ELEVATED,  // via ADB, Shizuku, sudo, etc.
    ROOT
}

data class ExecutionResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val target: ExecutionTargetType,
    val verified: Boolean
)
```

---

## P1.4 — Execution Router

Routes a task to the best available execution target, applying least-privilege logic.

**Required types:**

```kotlin
interface ExecutionRouter {
    suspend fun routeExecution(
        request: ExecutionRequest,
        availableTargets: List<ExecutionTarget>,
        securityEnforcer: SecurityPolicyEnforcer,
        toolId: String
    ): RoutingDecision?

    suspend fun verifyResult(
        result: ExecutionResult,
        request: ExecutionRequest
    ): Boolean
}

sealed class RoutingDecision {
    data class Route(
        val target: ExecutionTarget,
        val decision: PolicyDecision
    ) : RoutingDecision()

    data class NoSuitableTarget(val reason: String) : RoutingDecision()
}
```

**Router logic:**

1. Check which targets can perform the operation (capability-wise).
2. Filter by security policy.
3. Pick the least-privileged viable target.
4. Return the target + required policy decision (APPROVED, REQUIRES_APPROVAL, DENIED).

---

## P1.5 — Secrets Management (foundational)

Extend the existing `ConfigSource` to provide a unified secrets vault. Providers request secrets by reference, never see plaintext outside the boundary that needs it.

**Required types:**

```kotlin
interface SecretsVault {
    suspend fun getSecret(secretId: String): String?
    suspend fun putSecret(secretId: String, value: String)
    suspend fun revokeSecret(secretId: String)
    fun listSecretIds(capabilityId: CapabilityId): List<String>
}

interface EnhancedConfigSource : ConfigSource {
    fun getSecretsVault(): SecretsVault
}
```

**Audit logging:**

- Log secret *use*, not values.
- Example: "capability:termux.package_manager accessed secret:termux-api-token — success"

---

## P1.6 — Approval Flow Specification

Define the approval mechanism and integrate it into the agent's execution loop.

**Required types:**

```kotlin
interface ApprovalProvider {
    suspend fun requestApproval(request: ApprovalRequest): ApprovalResponse
}

data class ApprovalRequest(
    val requestId: String,
    val operationDescription: String,
    val riskTier: RiskTier,
    val targetType: ExecutionTargetType,
    val toolId: String,
    val capabilityId: CapabilityId,
    val timeoutMs: Long = 60_000
)

sealed class ApprovalResponse {
    object Approved : ApprovalResponse()
    object Denied : ApprovalResponse()
    object TimedOut : ApprovalResponse()  // default-deny
    object Unavailable : ApprovalResponse()
}

object RiskApprovalPolicy {
    fun requiresApproval(riskTier: RiskTier): Boolean =
        riskTier in setOf(RiskTier.REVERSIBLE, RiskTier.DESTRUCTIVE, RiskTier.IRREVERSIBLE)

    fun defaultTimeoutMs(riskTier: RiskTier): Long = when (riskTier) {
        RiskTier.READ_ONLY -> 0
        RiskTier.REVERSIBLE -> 30_000
        RiskTier.DESTRUCTIVE -> 120_000
        RiskTier.IRREVERSIBLE -> 300_000
    }
}
```

---

## P1.7 — Provider / Adapter Architecture

All external integrations use a provider/adapter architecture:

```
AndroidProvider
TermuxProvider
ShizukuProvider
RootProvider
DockerProvider
WireGuardProvider
HeadscaleProvider
VNCProvider
X11Provider
ProxmoxProvider
SSHProvider
```

Providers must be modular, independently testable, capability-aware, permission-aware, replaceable, failure-isolated, observable, and auditable. The core agent must not depend on implementation details.

---

# P2 — UNIVERSAL ANDROID EXECUTION

## P2.1 — Universal Android Support

Do not hard-code support around a specific manufacturer, ROM, Android version, root, Shizuku, Termux, or ADB.

Capabilities must be detected dynamically. Non-root Android must remain first-class.

---

## P2.2 — Root Integration

Root is optional. Detect availability, shell availability, actual privilege level, usable commands, and restrictions. Never assume root is functional. Require explicit authorization.

---

## P2.3 — Shizuku Integration

Implement Shizuku as an optional capability provider. Detect installation, running state, authorization, supported APIs, available operations. Gracefully degrade when unavailable.

---

## P2.4 — Termux Integration

Integrate with Termux as an optional execution environment. Support shell, packages, processes, filesystem, scripts, services, and Termux APIs where available. Do not make Termux mandatory. Do not bundle/impersonate Termux without deliberate architectural reason.

---

## P2.5 — Built-In Terminal

Provide a terminal interface capable of selecting execution target:

```
Android | Termux | Local PC | Remote Linux | Docker | VM | LXC
```

Display target, user, privilege level, working directory, connection, environment, command history, stdout/stderr, exit status, audit information. Use the same policy and execution system as the AI.

---

# P3 — PRIVATE NETWORKING

## P3.1 — WireGuard

Implement as an optional networking capability. Support tunnel status, interface status, peer status, diagnostics, configuration, controlled enable/disable, health checks.

---

## P3.2 — Headscale

Split into two distinct capabilities:

- **`headscale.member`** — DroidCommand AI operates as a Headscale-managed node.
- **`headscale.admin`** — DroidCommand AI administers a Headscale server via API.

**Critical:** mDNS/discovery is not authorization. Device identity and authorization are separate.

---

## P3.3 — Remote Device Control

Support pairing, identity, authentication, authorization, capability discovery, remote command execution, remote status, logs, sessions, connection health, audit trails. Use secure persistent connections. Network reachability is not authorization.

---

# P4 — CONTAINERIZATION

## P4.1 — Docker Integration

Treat Docker as an infrastructure provider. Support images, containers, volumes, networks, compose, logs, resources, lifecycle, exec, health.

Docker must remain optional.

---

## P4.2 — Container-Aware AI Execution

Allow the agent to intentionally use containers for coding, builds, testing, dependency installation, automation, isolated environments, reproducible workloads, experimental work. The policy engine determines whether execution is permitted.

---

# P5 — REMOTE GRAPHICS

## P5.1 — VNC

Implement VNC as an optional provider/client. Support connection profiles, authentication, session status, health, display, input, secure transport. Clearly distinguish VNC Client / VNC Server / Remote Android Display / Remote Linux Display.

---

## P5.2 — X11

Support X11 where an appropriate environment exists: Termux/X11, Linux, container, VM, remote Linux. X11 is optional.

---

## P5.3 — Unified Remote Desktop Abstraction

Design for future protocols (VNC, RDP, SPICE, X11, Wayland). Do not redesign the agent when adding a new protocol.

---

# P6 — INFRASTRUCTURE CONTROL

## P6.1 — Proxmox Integration

Proxmox is an infrastructure integration, not an Android dependency. Implement a dedicated provider/API layer.

Support where authorized: clusters, nodes, VMs, LXC, storage, networks, resources, start, stop, restart, shutdown, task status, console, health.

---

## P6.2 — Infrastructure Manager

Create a common abstraction for infrastructure providers: Docker, Proxmox, Kubernetes, LXC, VMware, OpenStack, Cloud APIs, NAS, OpenWrt. Each provider implements the common interface.

---

# P7 — FUTURE PROVIDER ECOSYSTEM

Potential future integrations:

```
SSH
Kubernetes
OpenWrt
NAS
MQTT
Home Assistant
Cloud APIs
Git hosting
CI/CD systems
Additional virtualization
Additional remote desktop
Additional AI providers
```

Do not implement merely to increase feature count. Prioritize reliability, security, actual usefulness, maintainability, testability.

---

# FEATURE TOGGLE SYSTEM

Every optional capability must be independently configurable.

The user must be able to: **ENABLE, DISABLE, CONFIGURE, AUTHORIZE, REVOKE, TEST, RESET, DIAGNOSE.**

**UI must display:**

```
✓ Termux
✓ Root
✓ Accessibility
✓ Docker

⚠ Shizuku
  Requires authorization

⚠ Proxmox
  Requires configuration

✗ Headscale
  Not configured

✗ VNC Server
  Not available

⚠ Root
  Verified 4m ago — next check in 6m
```

The UI must reflect reality, not fabricate availability.

---

# HEALTH & DIAGNOSTICS

Every provider must expose health information:

```
Provider
├── Installed?
├── Enabled?
├── Authorized?
├── Reachable?
├── Healthy?
├── Version
├── Capabilities
├── Dependencies
├── Last Verified At
└── Last Error
```

The agent must explain why a capability is unavailable:

```
Docker
✗ Engine unreachable
Reason: Docker daemon is not running

Shizuku
✗ Unauthorized
Reason: User authorization required

Termux
✓ Available (verified 12s ago)

Root
✓ Available (verified 12s ago)

Proxmox
✓ Configured, ✓ Reachable (verified 4m ago — cached, next check in 6m)
```

---

# VERIFICATION REQUIREMENTS

A feature is not implemented merely because:

- a class exists
- an interface exists
- a button exists
- a configuration option exists
- documentation describes it
- a mock returns success
- an API endpoint exists
- a TODO was replaced
- a screen displays the feature

A completed feature requires:

1. Real implementation.
2. Runtime capability detection.
3. Error handling.
4. Permission enforcement.
5. Tests appropriate to the feature.
6. Integration tests where possible.
7. Runtime verification of actual behavior.
8. Clear documentation of environmental requirements.
9. Accurate status reporting.

If real runtime verification is impossible in the current development environment, explicitly mark:

```
IMPLEMENTED — NOT RUNTIME VERIFIED
[environment requirement]
```

---

# UI PRINCIPLE

The UI must be generated from actual system state.

Do not display every possible feature as though it is available.

**Example:**

```
Capabilities

✓ Termux
✓ Root
✓ Accessibility
✓ Docker

⚠ Shizuku
   Requires authorization

⚠ Proxmox
   Requires configuration

✗ Headscale
   Not configured

✗ VNC Server
   Not available
```

The UI must reflect reality.

---

# AUDIT-FIRST ARCHITECTURE

Every meaningful action should be traceable.

Audit information should include:

```
Timestamp
User
Request
AI Decision
Persona (if active)
Execution Target
Provider
Permission
Privilege Level
Command/Action
Result
Verification
Error
```

Do not store sensitive information unnecessarily.

---

# IMPLEMENTATION ORDER FOR CLAUDE CODE

**Follow this dependency sequence strictly; do not parallelize or skip:**

## **Phase 1: P0 — AI Foundation (Build First)**

1. Context Manager (`core-context`)
2. Token Budget Manager (`core-token-budget`)
3. Local-Context-First Prioritization
4. AI Provider Abstraction (`core-ai-provider`)
5. Conversation Import & Analysis (`core-import`)
6. Persona / Style System (`core-persona`)
7. Knowledge Graph (`core-knowledge-graph`)

## **Phase 2: P1 — Capability Architecture (Build Second)**

1. Agent ↔ Router Interface (`core-capability`)
2. Capability Manager with re-verification (`core-capability`)
3. Policy & Permission Engine (extend `core-security`)
4. Execution Target Abstraction (`core-execution`)
5. Execution Router (`core-execution`)
6. Secrets Management (extend `core-config`)
7. Approval Flow (`core-agent` extension)
8. Provider / Adapter Architecture

## **Phase 3: P2 — Android Execution (Prove with Termux)**

1. Universal Android Support (extend `core-tools-android`)
2. Root Integration (extend `core-root`)
3. Shizuku Integration (`core-shizuku`)
4. Termux Integration (`core-termux`) ← **PROVE P0+P1 HERE**
5. Built-In Terminal (UI)

## **Phase 4: P3–P7 — Remaining (After P0–P2 Proven)**

1. P3 — Private Networking
2. P4 — Containerization
3. P5 — Remote Graphics
4. P6 — Infrastructure Control
5. P7 — Future Providers

---

# FINAL ARCHITECTURAL MODEL

The completed DroidCommand AI architecture should operate as:

```
                           USER
                             │
                             ▼
                      ┌─────────────┐
                      │ DroidCommand│
                      │     AI      │
                      └──────┬──────┘
                             │
              ┌──────────────┼──────────────┐
              ▼              ▼              ▼
         Context        Token Budget   AI Provider
         Manager        Manager        Selector
             │               │              │
             ├─────┬─────────┴──────────────┤
             ▼     ▼                        ▼
          Persona Knowledge   Local-Context-First
          System  Graph       Prioritization
             │     │          │
             └─────┼──────────┘
                   ▼
            ContextSnapshot
                   │
                   ▼
          Capability Manager
                   │
                   ▼
            Policy Engine
                   │
                   ▼
         Execution Router
                   │
      ┌────────────┼────────────┐
      ▼            ▼            ▼
   Android      Termux      Local/Remote
      │            │            │
      ├──┬──┐    ┌─┴─┐    ┌─────┼─────┐
      ▼  ▼  ▼    ▼   ▼    ▼     ▼     ▼
   Shizuku Root API  Pkg Docker Proxmox SSH
      │                            │     │
      └────────────────────────────▼─────▼
                                 VM/LXC
                                   │
                                   ▼
                          Verification Engine
                                   │
                                   ▼
                               Audit Log
```

---

# CORE PRINCIPLE

**DroidCommand AI must not be designed around a fixed device, operating system, privilege level, AI provider, or infrastructure platform.**

**It must be designed around:**

> **«Capabilities + Context + Persona + Policy + Execution + Verification.»**

**The system should dynamically determine:**

> **«What capabilities exist, what the user has authorized, what context is relevant, what persona is active, which AI/provider should be used, which execution target is safest, and whether the resulting action actually succeeded.»**

**Never sacrifice architectural correctness merely to make the feature checklist appear complete.**

---

# INTEGRATION WITH EXISTING MODULES

## `core-agent`

Modify the agent's tool-execution loop to:
1. Query `CapabilityRegistry` before invoking.
2. Route through the new `ExecutionRouter`.
3. Handle approval flows via `ApprovalProvider`.

Do not break the existing `LlmPlanner` or tool registry.

## `core-security`

Extend `SecurityLevel` enum and `SecurityPolicy`. Integrate new `checkCapabilityAccess` method. Do not break existing tests.

## `core-shell`

Refactor the real `ProcessBuilderShellExecutor` behind the new `ExecutionTarget` abstraction. Existing tests should still pass via the target interface.

## Gradle

Add new modules:

```kotlin
include(":core-context")
include(":core-token-budget")
include(":core-persona")
include(":core-capability")
include(":core-execution")
include(":core-ai-provider")
include(":core-import")
include(":core-knowledge-graph")
include(":core-shizuku")
include(":core-termux")
```

---

# TESTING STRATEGY

**Unit Tests (No External Dependencies):**

- Context prioritization, token allocation
- Capability registry, re-verification logic
- Policy decisions, permission enforcement
- Execution routing logic
- Persona extraction and testing
- Secrets vault (no plaintext in logs)

**Integration Tests (Local Subprocess/Mock Server):**

- Context Manager + Token Budget Manager
- Persona system + LLM context contribution
- LocalProcessExecutionTarget with real subprocess
- Execution Router + SecurityPolicyEnforcer
- TermuxExecutionTarget (or marked PLANNED)
- Full approval flow

**End-to-End Tests (Agent ↔ Router ↔ Target):**

- Agent requests shell command → routes to LocalProcessExecutionTarget → executes → verifies
- Agent requests Termux operation → routes to TermuxExecutionTarget → executes
- Agent requests DESTRUCTIVE operation → requires approval → execution proceeds/fails
- Capability unavailable → clean failure with reason

---

# SUCCESS CRITERIA

By the end of this implementation:

- [ ] P0.0–P0.7 (AI Foundation) modules exist, compile, pass unit tests.
- [ ] P1.0–P1.7 (Capability Architecture) modules exist, compile, pass unit tests.
- [ ] P2.1–P2.4 (Android/Termux) implemented; Termux proven end-to-end.
- [ ] No existing tests broken.
- [ ] End-to-end test: agent → router → target → execution → verification.
- [ ] ARCHITECTURE.md updated with status for each module (IMPLEMENTED vs. PLANNED).
- [ ] All commits clean with clear, descriptive messages.
- [ ] Unverifiable tests marked `IMPLEMENTED — NOT RUNTIME VERIFIED`.

---

# NOTES FOR IMPLEMENTATION

- **Do not speculate:** If unsure about an existing module's method signature, read the source first.
- **Production code only:** No placeholders or fake success responses.
- **Fail closed:** Unknown capabilities are UNAVAILABLE, not fabricated.
- **Re-verification is core:** Capabilities are not "detected once and trusted forever."
- **Audit everything:** Permissions, approvals, capability changes, secret access — all logged with timestamps, no plaintext secrets.
- **Test against real code:** Integration tests exercise actual `core-security` and `core-agent`, not mocks.

---

# Ready to Build

This prompt is complete and ready to use with Claude Code. It defines the entire architectural vision, non-negotiable development rules, detailed type signatures, and a clear implementation order.

**Start with Phase 1 (P0 — AI Foundation) and work through systematically. Do not skip ahead or parallelize without proven foundation.**

Good luck!
