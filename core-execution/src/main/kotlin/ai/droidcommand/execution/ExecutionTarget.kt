package ai.droidcommand.execution

import ai.droidcommand.agent.CapabilityId
import ai.droidcommand.agent.ExecutionTargetType

/**
 * P1.3's own required abstraction, verbatim: something other than the
 * agent itself that can actually run a command. Reuses the already-shipped
 * `core-agent` P1.0 types ([ExecutionTargetType], [CapabilityId]) rather
 * than redeclaring them. Both methods drop the spec's `suspend` — the same
 * stated deviation every `suspend fun` in this codebase has taken since
 * P0.1: there is no coroutines dependency anywhere in this repository.
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

/** Verbatim P1.3 shape — no validation, a plain data carrier a real target supplies. */
data class ExecutionContext(
    val workingDir: String,
    val user: String,
    val uid: Int? = null,
    val environment: Map<String, String>,
    val privilegeLevel: PrivilegeLevel,
)

/**
 * Verbatim P1.3 shape. **Coincidentally shares a name with
 * `core-root.PrivilegeLevel` (`NONE`/`USER`/`ROOT`), but is deliberately
 * not reconciled with it** — different package (`ai.droidcommand.execution`
 * vs `ai.droidcommand.root`), different values, different purpose
 * (root-mechanism state vs. execution-context privilege), and neither
 * module depends on the other. Unlike the P1.0/P1.1 `CapabilityRegistry`
 * collision, there is no real ambiguity here to resolve by renaming — only
 * a coincidental name reused for a genuinely different concept, stated
 * explicitly so a future session doesn't mistake it for an unresolved
 * collision.
 */
enum class PrivilegeLevel {
    USER,
    ELEVATED,
    ROOT,
}

/** Verbatim P1.3 shape. See [LocalProcessExecutionTarget] for how `timedOut`/`verified` are honestly populated. */
data class ExecutionResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val target: ExecutionTargetType,
    val verified: Boolean,
)
