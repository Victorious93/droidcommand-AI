package ai.droidcommand.shizuku

import ai.droidcommand.security.CapabilityId
import ai.droidcommand.security.ExecutionContext
import ai.droidcommand.security.ExecutionResult
import ai.droidcommand.security.ExecutionTarget
import ai.droidcommand.security.ExecutionTargetType

/**
 * Shizuku/Sui as an [ExecutionTarget]. [type] is [ExecutionTargetType.ANDROID]
 * — same slot as Magisk/root — because Shizuku is an Android ADB-shell
 * privilege surface, not a new enum value. [verified] stays false: this
 * environment has never runtime-verified a live Shizuku UserService.
 */
class ShizukuExecutionTarget(
    override val id: String,
    override val context: ExecutionContext,
    private val shell: PrivilegedShell,
    override val availableCapabilities: Set<CapabilityId> = emptySet(),
) : ExecutionTarget {
    override val type: ExecutionTargetType = ExecutionTargetType.ANDROID

    override fun isHealthy(): Boolean = shell.isAvailable()

    override fun execute(
        argv: List<String>,
        workingDir: String?,
        env: Map<String, String>?,
        timeoutMs: Long,
    ): ExecutionResult {
        if (workingDir != null) {
            return failure("ShizukuExecutionTarget does not honor workingDir (UserService cwd is the privileged process)")
        }
        if (!env.isNullOrEmpty()) {
            return failure("ShizukuExecutionTarget does not honor env")
        }
        return when (val result = shell.exec(argv, timeoutMs)) {
            is PrivilegedResult.Success -> ExecutionResult(
                exitCode = result.exitCode,
                stdout = result.stdout,
                stderr = result.stderr,
                timedOut = result.timedOut,
                target = type,
                verified = false,
            )
            is PrivilegedResult.Failure -> ExecutionResult(
                exitCode = -1,
                stdout = "",
                stderr = result.reason,
                timedOut = false,
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
}
