package ai.droidcommand.shell

import ai.droidcommand.agent.CapabilityId
import ai.droidcommand.agent.ExecutionContext
import ai.droidcommand.agent.ExecutionResult
import ai.droidcommand.agent.ExecutionTarget
import ai.droidcommand.agent.ExecutionTargetType

/**
 * Adapts a [ShellExecutor] (real: [ProcessBuilderShellExecutor]) to
 * `core-agent`'s [ExecutionTarget] contract (CAP-011, P1.3) — the concrete
 * reading this repository takes of P1.3's "refactor the real
 * `ProcessBuilderShellExecutor` behind an abstraction that other targets
 * can implement": **composition, not an in-place rewrite**.
 * [ProcessBuilderShellExecutor]/[ShellExecutor] already has real, tested
 * callers ([ShellTool], `ShellToolSecureExecutorIntegrationTest`) a
 * literal rewrite to [ExecutionTarget]'s different shape (`argv:
 * List<String>` vs [ShellCommand]; a flat [ExecutionResult] vs the sealed
 * [ShellExecutionResult]) would break for zero benefit — the same
 * "adapter over existing tested code" choice
 * `core-tools-android.DeviceContextProvider` already makes composing
 * `DeviceController`.
 *
 * [type] is always [ExecutionTargetType.LOCAL_PC]: [ProcessBuilderShellExecutor]
 * runs directly on the local JVM host process, the correct mapping among
 * CAP-008's seven target types. [isHealthy] is unconditionally `true` — a
 * local process target has no external dependency to verify beyond the
 * JVM itself being alive, unlike a future TERMUX/DOCKER/REMOTE_HOST target
 * that would need a real liveness probe.
 *
 * **A real interaction between two independently fail-closed designs,
 * worth stating explicitly:** [ExecutionContext.workingDir] is
 * non-nullable, so [execute] always passes a non-null
 * [ShellCommand.workingDirectory] to [shellExecutor] — which means
 * [ShellSecurityPolicy.allowedWorkingDirectories] (empty by default,
 * rejecting any working-directory override at all) must explicitly
 * allow-list [context]'s own `workingDir` (and any per-call override a
 * caller passes to [execute]) or every single call fails closed with a
 * policy-rejection [ShellExecutionResult.Failure]. This is correct,
 * fail-closed behavior, not a bug — but easy to miss when wiring a real
 * [ProcessBuilderShellExecutor] behind this adapter.
 */
class ShellExecutorExecutionTarget(
    override val id: String,
    private val shellExecutor: ShellExecutor,
    override val context: ExecutionContext,
    override val availableCapabilities: Set<CapabilityId> = emptySet(),
) : ExecutionTarget {
    override val type: ExecutionTargetType = ExecutionTargetType.LOCAL_PC

    override fun isHealthy(): Boolean = true

    /**
     * Maps [argv]'s head/tail to [ShellCommand.executable]/[ShellCommand.args];
     * [workingDir]/[env] override [context]'s own default when given, the
     * same nullable-overrides-a-default shape the roadmap prompt's own
     * signature specifies. An empty [argv] is rejected before
     * [shellExecutor] is ever called — the same "fail before touching
     * anything" discipline [ProcessBuilderShellExecutor]'s own policy
     * checks already follow for themselves.
     *
     * **A stated, honest limitation:** [ShellExecutionResult] carries no
     * structured timeout signal today, only a human-readable
     * [ShellExecutionResult.Failure.reason] string — so [ExecutionResult.timedOut]
     * is always `false` here rather than guessed via fragile string-matching
     * on that text. [ExecutionResult.verified] is always `false`: no real
     * result verification happens in this slice — that is CAP-012's
     * `verifyResult`, a separate later item.
     */
    override fun execute(argv: List<String>, workingDir: String?, env: Map<String, String>?, timeoutMs: Long): ExecutionResult {
        if (argv.isEmpty()) {
            return ExecutionResult(exitCode = -1, stdout = "", stderr = "argv must not be empty", timedOut = false, target = type, verified = false)
        }

        val command = ShellCommand(
            executable = argv.first(),
            args = argv.drop(1),
            workingDirectory = workingDir ?: context.workingDir,
            environment = env ?: context.environment,
            timeoutMillis = timeoutMs,
        )

        return when (val result = shellExecutor.execute(command)) {
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
                timedOut = false,
                target = type,
                verified = false,
            )
        }
    }
}
