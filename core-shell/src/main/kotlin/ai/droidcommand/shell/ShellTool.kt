package ai.droidcommand.shell

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec

/**
 * Exposes [ShellExecutor] to the agent as a `Tool`, gated by
 * core-security's real `SecureToolExecutor`/`SecurityPolicyEnforcer`
 * exactly like `core-build.BuildTool` and `core-tools-android`'s device
 * tools — non-root shell execution is still `SENSITIVE`, not `NORMAL`.
 * Input: `executable` (required), `args` (optional, space-separated —
 * this tool does not support arguments containing spaces; a caller
 * needing that should be a dedicated tool with structured input, not this
 * generic one), `workingDirectory` (optional).
 */
class ShellTool(private val executor: ShellExecutor) : Tool {
    override val spec = ToolSpec(
        name = "run_shell_command",
        description = "Runs an allow-listed shell command",
        securityLevel = SecurityLevel.SENSITIVE,
        permissionCategory = PermissionCategory.TERMINAL,
    )

    override fun execute(input: Map<String, String>): ToolResult {
        val executable = input["executable"] ?: return ToolResult.Failure("Missing required input 'executable'")
        val args = input["args"]?.split(" ")?.filter { it.isNotEmpty() } ?: emptyList()
        val workingDirectory = input["workingDirectory"]

        val command = ShellCommand(executable = executable, args = args, workingDirectory = workingDirectory)

        return when (val result = executor.execute(command)) {
            is ShellExecutionResult.Success -> if (result.exitCode == 0) {
                ToolResult.Success(result.stdout)
            } else {
                ToolResult.Failure("'$executable' exited with code ${result.exitCode}: ${result.stderr.ifBlank { result.stdout }}")
            }
            is ShellExecutionResult.Failure -> ToolResult.Failure(result.reason, result.cause)
        }
    }
}
