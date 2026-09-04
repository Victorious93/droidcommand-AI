package ai.droidforge.root

import ai.droidforge.agent.SecurityLevel
import ai.droidforge.agent.Tool
import ai.droidforge.agent.ToolResult
import ai.droidforge.agent.ToolSpec

/**
 * Exposes [RootExecutor] to the agent as a `Tool` with `requiresRoot =
 * true` and `securityLevel = SecurityLevel.ROOT` — `core-security`'s
 * `SecurityPolicyEnforcer` denies it outright (no prompt, not even an
 * approval opportunity) unless the session's `SecurityPolicy.rootEnabled`
 * is true and `rootAvailable()` reports true, and only asks for explicit
 * approval after both of those hold. This module never reimplements that
 * gate; it only supplies the `RootExecutor` the gate protects.
 */
class RootTool(private val executor: RootExecutor) : Tool {
    override val spec = ToolSpec(
        name = "run_root_command",
        description = "Runs an allow-listed command with root privileges",
        requiresRoot = true,
        securityLevel = SecurityLevel.ROOT,
    )

    override fun execute(input: Map<String, String>): ToolResult {
        val executable = input["executable"] ?: return ToolResult.Failure("Missing required input 'executable'")
        val args = input["args"]?.split(" ")?.filter { it.isNotEmpty() } ?: emptyList()

        return when (val result = executor.execute(RootCommand(executable = executable, args = args))) {
            is RootExecutionResult.Success -> if (result.exitCode == 0) {
                ToolResult.Success(result.stdout)
            } else {
                ToolResult.Failure("'$executable' exited with code ${result.exitCode}: ${result.stderr.ifBlank { result.stdout }}")
            }
            is RootExecutionResult.Failure -> ToolResult.Failure(result.reason, result.cause)
        }
    }
}
