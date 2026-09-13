package ai.droidcommand.termux

import ai.droidcommand.security.CapabilityId
import ai.droidcommand.security.ExecutionContext
import ai.droidcommand.security.ExecutionResult
import ai.droidcommand.security.ExecutionTarget
import ai.droidcommand.security.ExecutionTargetType

/**
 * Fills `core-security.ExecutionTargetType.TERMUX` — declared in that enum
 * since CAP-011 (P1.3) as a target this repository did not yet implement.
 * Structured exactly like `core-root.RootExecutionTarget`/
 * `core-shell.LocalProcessExecutionTarget`: a thin translation from the
 * generic [ExecutionTarget.execute] argv/workingDir/env/timeoutMs shape
 * into this module's own [TermuxCommand], delegating everything else to an
 * injected [TermuxExecutor] (typically [AdbTermuxExecutor]).
 *
 * [type] is [ExecutionTargetType.TERMUX], not [ExecutionTargetType.ANDROID]
 * — Termux is a genuinely distinct execution environment from bare
 * Android/root, with its own userland, package manager and `PATH`, exactly
 * the distinction the enum already draws.
 */
class TermuxExecutionTarget(
    override val id: String,
    override val context: ExecutionContext,
    private val executor: TermuxExecutor,
    override val availableCapabilities: Set<CapabilityId> = emptySet(),
) : ExecutionTarget {
    override val type: ExecutionTargetType = ExecutionTargetType.TERMUX

    override fun isHealthy(): Boolean = executor.isAvailable()

    override fun execute(
        argv: List<String>,
        workingDir: String?,
        env: Map<String, String>?,
        timeoutMs: Long,
    ): ExecutionResult {
        if (argv.isEmpty()) {
            return failure("argv must contain at least one element (the executable)")
        }

        val command = TermuxCommand(
            executable = argv.first(),
            args = argv.drop(1),
            workingDirectory = workingDir,
            environment = env ?: emptyMap(),
            timeoutMillis = timeoutMs,
        )

        return when (val result = executor.execute(command)) {
            is TermuxExecutionResult.Success -> ExecutionResult(
                exitCode = result.exitCode,
                stdout = result.stdout,
                stderr = result.stderr,
                timedOut = false,
                target = type,
                verified = false,
            )
            is TermuxExecutionResult.Failure -> ExecutionResult(
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
