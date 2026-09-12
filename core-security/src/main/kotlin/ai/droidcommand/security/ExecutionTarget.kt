package ai.droidcommand.security

/**
 * CAP-011, P1.3. The abstraction `core-shell.ProcessBuilderShellExecutor`
 * (today the only real execution backend in this repository) sits behind,
 * so a future Termux/Docker/remote-host/Proxmox target (CAP-019/CAP-024/
 * CAP-029, all still MISSING) can implement the same contract without the
 * caller needing to know which kind of target it's talking to. This is the
 * type only — no registry (CAP-009) and no router (CAP-012) exist yet to
 * pick *which* [ExecutionTarget] handles a given [ExecutionRequest]; a
 * caller constructs and calls one directly for now.
 *
 * **Deliberate deviation from the roadmap prompt's literal
 * `suspend fun isHealthy()`/`suspend fun execute(...)` signatures:** no
 * module in this dependency chain (`core-security`, `core-shell`) declares
 * a coroutines dependency, direct or transitive — the same gap
 * `ai.droidcommand.llm.AiProviderSelector`'s own doc comment already
 * documents for `selectProvider`. Both methods stay plain synchronous `fun`
 * for the same reason: adding `kotlinx-coroutines-core` to two more modules
 * for one interface would be inconsistent with every sibling interface
 * these methods sit beside ([ai.droidcommand.shell.ShellExecutor.execute],
 * [Tool.execute][ai.droidcommand.agent.Tool.execute]).
 */
interface ExecutionTarget {
    val id: String
    val type: ExecutionTargetType
    val context: ExecutionContext
    val availableCapabilities: Set<CapabilityId>

    fun isHealthy(): Boolean

    fun execute(
        argv: List<String>,
        workingDir: String? = null,
        env: Map<String, String>? = null,
        timeoutMs: Long = 30_000,
    ): ExecutionResult
}

/** How a caller's session on an [ExecutionTarget] is authorized to run. */
enum class PrivilegeLevel {
    USER,
    ELEVATED,
    ROOT,
}

/** Where and as whom an [ExecutionTarget] runs a command. */
data class ExecutionContext(
    val workingDir: String,
    val user: String,
    val uid: Int? = null,
    val environment: Map<String, String>,
    val privilegeLevel: PrivilegeLevel,
)

/**
 * The outcome of one [ExecutionTarget.execute] call. `verified` is
 * honestly `false` from every implementation in this repository today:
 * no target here re-queries state after running a command to confirm the
 * exit code reflects reality (the same "never claim a feature works when
 * it has not been verified" discipline the roadmap prompt states as a
 * hard rule) — a genuinely verifying target is real follow-up work, not
 * fabricated here to fill the field.
 */
data class ExecutionResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val target: ExecutionTargetType,
    val verified: Boolean,
)
