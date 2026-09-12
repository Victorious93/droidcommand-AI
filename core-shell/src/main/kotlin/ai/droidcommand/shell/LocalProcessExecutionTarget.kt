package ai.droidcommand.shell

import ai.droidcommand.security.CapabilityId
import ai.droidcommand.security.ExecutionContext
import ai.droidcommand.security.ExecutionResult
import ai.droidcommand.security.ExecutionTarget
import ai.droidcommand.security.ExecutionTargetType

/**
 * CAP-011, P1.3's own "refactor the real `ProcessBuilderShellExecutor`
 * behind an abstraction" instruction, done as an additive wrapper rather
 * than changing [ShellExecutor]/[ShellTool] or any of their existing
 * callers/tests: this class delegates every [execute] call to an injected
 * [ShellExecutor] (typically [ProcessBuilderShellExecutor]), translating
 * between the two result shapes.
 *
 * [ShellExecutionResult.Failure] carries no distinct timeout signal of its
 * own — [ProcessBuilderShellExecutor] reports a timeout as a [reason]
 * string ("Command timed out after ...ms"), the only one of its several
 * failure reasons ([timedOut] is honestly derived from that literal prefix
 * rather than invented; a genuinely well-typed signal would mean widening
 * [ShellExecutionResult.Failure] itself, which would ripple through every
 * existing [ShellExecutor] caller and test — deliberately left for its own
 * follow-up rather than done speculatively here.
 *
 * [isHealthy] always returns `true`: unlike a remote host, Docker daemon,
 * or Proxmox node (P2-P6, none of which exist in this repository yet),
 * running a local process has no external liveness dependency to check
 * beyond the JVM this code is already executing in.
 */
class LocalProcessExecutionTarget(
    override val id: String,
    override val context: ExecutionContext,
    private val executor: ShellExecutor,
    override val availableCapabilities: Set<CapabilityId> = emptySet(),
) : ExecutionTarget {
    override val type: ExecutionTargetType = ExecutionTargetType.LOCAL_PC

    override fun isHealthy(): Boolean = true

    override fun execute(
        argv: List<String>,
        workingDir: String?,
        env: Map<String, String>?,
        timeoutMs: Long,
    ): ExecutionResult {
        if (argv.isEmpty()) {
            return ExecutionResult(
                exitCode = -1,
                stdout = "",
                stderr = "argv must contain at least one element (the executable)",
                timedOut = false,
                target = type,
                verified = false,
            )
        }

        val command = ShellCommand(
            executable = argv.first(),
            args = argv.drop(1),
            workingDirectory = workingDir,
            environment = env ?: emptyMap(),
            timeoutMillis = timeoutMs,
        )

        return when (val result = executor.execute(command)) {
            is ShellExecutionResult.Success -> ExecutionResult(
                exitCode = result.exitCode,
                stdout = result.stdout,
                stderr = result.stderr,
                timedOut = false,
                target = type,
                verified = false,
            )
            is ShellExecutionResult.Failure -> ExecutionResult(
                exitCode = -1,
                stdout = "",
                stderr = result.reason,
                timedOut = result.reason.startsWith(TIMEOUT_REASON_PREFIX),
                target = type,
                verified = false,
            )
        }
    }

    private companion object {
        const val TIMEOUT_REASON_PREFIX = "Command timed out"
    }
}
