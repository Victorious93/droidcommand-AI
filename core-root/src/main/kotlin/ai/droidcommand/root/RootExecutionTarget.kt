package ai.droidcommand.root

import ai.droidcommand.security.CapabilityId
import ai.droidcommand.security.ExecutionContext
import ai.droidcommand.security.ExecutionResult
import ai.droidcommand.security.ExecutionTarget
import ai.droidcommand.security.ExecutionTargetType

/**
 * The second real [ExecutionTarget] in this repository (CAP-011, P1.3) —
 * `core-shell.LocalProcessExecutionTarget` was the first, wrapping
 * [ai.droidcommand.shell.ShellExecutor]; this one wraps any [RootExecutor]
 * (typically [MagiskProvider]) so `core-security.DefaultExecutionRouter`
 * (CAP-012) has a genuinely different second target to route between
 * (different [type], different privilege level) instead of only ever
 * seeing one implementation in a test.
 *
 * [type] is [ExecutionTargetType.ANDROID]: Magisk-style root is an
 * Android-specific mechanism, even though [RootExecutor.execute] itself
 * spawns a plain local process (`su`) with no Android-runtime dependency —
 * the type describes the execution *environment* this capability belongs
 * to, not the JVM mechanics underneath it.
 *
 * **Honest, explicit limitation, not a silent behavior change:**
 * [RootCommand] has no `workingDirectory`/`environment` fields at all
 * (unlike `core-shell.ShellCommand`), so this target cannot honor a
 * caller-supplied [workingDir]/[env] the way [LocalProcessExecutionTarget]
 * can — rather than silently ignoring them (which could surprise a caller
 * relying on a working-directory containment expectation), [execute]
 * fails explicitly with a clear reason when either is non-null, without
 * ever invoking [executor]. Widening [RootCommand] itself to carry them is
 * real follow-up work, not done here since it would ripple through every
 * existing [RootExecutor] implementation and test.
 */
class RootExecutionTarget(
    override val id: String,
    override val context: ExecutionContext,
    private val executor: RootExecutor,
    override val availableCapabilities: Set<CapabilityId> = emptySet(),
) : ExecutionTarget {
    override val type: ExecutionTargetType = ExecutionTargetType.ANDROID

    override fun isHealthy(): Boolean = executor.isRootAvailable()

    override fun execute(
        argv: List<String>,
        workingDir: String?,
        env: Map<String, String>?,
        timeoutMs: Long,
    ): ExecutionResult {
        if (argv.isEmpty()) {
            return failure("argv must contain at least one element (the executable)")
        }
        if (workingDir != null) {
            return failure("RootExecutionTarget does not support a working directory override: RootCommand has no such field")
        }
        if (env != null) {
            return failure("RootExecutionTarget does not support environment variables: RootCommand has no such field")
        }

        val command = RootCommand(executable = argv.first(), args = argv.drop(1), timeoutMillis = timeoutMs)

        return when (val result = executor.execute(command)) {
            is RootExecutionResult.Success -> ExecutionResult(
                exitCode = result.exitCode,
                stdout = result.stdout,
                stderr = result.stderr,
                timedOut = false,
                target = type,
                verified = false,
            )
            is RootExecutionResult.Failure -> ExecutionResult(
                exitCode = -1,
                stdout = "",
                stderr = result.reason,
                timedOut = result.reason.startsWith(TIMEOUT_REASON_PREFIX),
                target = type,
                verified = false,
            )
        }
    }

    private fun failure(reason: String) = ExecutionResult(
        exitCode = -1,
        stdout = "",
        stderr = reason,
        timedOut = false,
        target = type,
        verified = false,
    )

    private companion object {
        const val TIMEOUT_REASON_PREFIX = "Command timed out"
    }
}
