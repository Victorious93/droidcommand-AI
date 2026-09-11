package ai.droidcommand.execution

import ai.droidcommand.agent.CapabilityId
import ai.droidcommand.agent.ExecutionTargetType
import ai.droidcommand.shell.ShellCommand
import ai.droidcommand.shell.ShellExecutionResult
import ai.droidcommand.shell.ShellExecutor

/**
 * The real, testable answer to P1.3's own instruction to "refactor the
 * real `ProcessBuilderShellExecutor` behind an abstraction that other
 * targets can implement" — achieved by composing the existing,
 * **unmodified** [ShellExecutor] interface rather than editing
 * `core-shell`'s already-shipped, already-tested
 * `ProcessBuilderShellExecutor` to implement [ExecutionTarget] directly.
 * [shellExecutor] is caller-supplied: this class never constructs a
 * `ProcessBuilderShellExecutor`/`ShellSecurityPolicy` itself, the same
 * "caller decides how to compose" restraint every prior slice applies.
 */
class LocalProcessExecutionTarget(
    override val id: String,
    override val context: ExecutionContext,
    override val availableCapabilities: Set<CapabilityId>,
    private val shellExecutor: ShellExecutor,
) : ExecutionTarget {
    override val type: ExecutionTargetType = ExecutionTargetType.LOCAL_PC

    /**
     * Always `true` — spawning a subprocess is a plain JVM capability
     * present in any environment this repository's own tooling already
     * assumes (the same claim `core-shell.ShellExecutor`'s own doc comment
     * makes), not a fabrication.
     */
    override fun isHealthy(): Boolean = true

    /**
     * Two honest, stated simplifications versus what a richer
     * `ShellExecutionResult` type could someday carry:
     * - [ExecutionResult.timedOut] is always `false`. Today's
     *   `ShellExecutionResult` sealed class folds a real timeout into
     *   `Failure.reason`'s free text rather than a distinct case;
     *   string-matching that text for "timed out" would be a fragile,
     *   undocumented coupling to `ProcessBuilderShellExecutor`'s exact
     *   wording. The timeout is still real — it still produces
     *   `exitCode = -1` with the reason in `stderr` — just not
     *   distinguished via this flag.
     * - [ExecutionResult.verified] is always `false`. Real result
     *   verification is P1.4's `ExecutionRouter.verifyResult`, explicitly
     *   not built here; claiming `true` would be exactly the kind of
     *   fabricated success `core-security.AgentRouterGateway` already
     *   refuses for `ExecutionResponse.Success`.
     */
    override fun execute(argv: List<String>, workingDir: String?, env: Map<String, String>?, timeoutMs: Long): ExecutionResult {
        require(argv.isNotEmpty()) { "argv must not be empty" }

        val command = ShellCommand(
            executable = argv.first(),
            args = argv.drop(1),
            workingDirectory = workingDir,
            environment = env ?: emptyMap(),
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
