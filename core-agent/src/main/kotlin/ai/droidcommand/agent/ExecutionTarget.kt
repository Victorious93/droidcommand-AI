package ai.droidcommand.agent

/**
 * How privileged an [ExecutionContext] runs as (CAP-011, P1.3).
 *
 * **Deliberate, stated name collision:** `ai.droidcommand.root.PrivilegeLevel`
 * already exists (`NONE`/`USER`/`ROOT`, a different 3-value enum for a
 * narrower, root-specific purpose in `core-root`). Different package, so no
 * compile-time collision, but a real, open duplication — reconciling the
 * two privilege vocabularies is out of scope for this slice, the same
 * "not unified here" call CAP-008 already made keeping `RiskTier` separate
 * from `SecurityLevel`.
 */
enum class PrivilegeLevel {
    USER,
    ELEVATED,
    ROOT,
}

/** The environment one [ExecutionTarget] runs commands under (CAP-011, P1.3) — its default working directory, identity, and privilege level. */
data class ExecutionContext(
    val workingDir: String,
    val user: String,
    val uid: Int? = null,
    val environment: Map<String, String>,
    val privilegeLevel: PrivilegeLevel,
)

/** The outcome of one [ExecutionTarget.execute] call (CAP-011, P1.3) — a flat shape (unlike [ai.droidcommand.agent.ExecutionResponse]/`ShellExecutionResult`'s sealed ones): failure is represented through [exitCode]/[stderr], not a separate case. [verified] is never set `true` by any implementation in this repository — real result verification is CAP-012's `verifyResult`, a separate later item. */
data class ExecutionResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val target: ExecutionTargetType,
    val verified: Boolean,
)

/**
 * One concrete environment a [CapabilityId] can be executed against
 * (CAP-011, P1.3) — the abstraction `docs/CAPABILITY_ROADMAP_PROMPT.md`'s
 * P1.3 asks for so "other targets can implement" alongside a real local
 * one. [availableCapabilities] is caller-declared, never inferred — the
 * same "declared metadata, not introspected" honesty [AiProviderInfo]
 * already carries for its own `capabilities`.
 *
 * **Deliberate deviation from the roadmap prompt's literal signatures:**
 * every method here is a plain `fun`, not `suspend fun` — no module in
 * this repository declares a coroutines dependency, direct or transitive,
 * the same reasoning `AiProviderSelector`/`PersonaManager` already state
 * for their own identical deviation.
 *
 * See `core-shell.ShellExecutorExecutionTarget` for the one real
 * implementation in this repository today.
 */
interface ExecutionTarget {
    val id: String
    val type: ExecutionTargetType
    val context: ExecutionContext
    val availableCapabilities: Set<CapabilityId>

    fun isHealthy(): Boolean

    fun execute(argv: List<String>, workingDir: String? = null, env: Map<String, String>? = null, timeoutMs: Long = 30_000): ExecutionResult
}
