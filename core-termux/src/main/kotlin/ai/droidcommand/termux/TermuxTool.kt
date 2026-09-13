package ai.droidcommand.termux

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec

/**
 * Exposes [TermuxExecutor] to the agent as a `Tool`, gated by
 * core-security's real `SecureToolExecutor`/`SecurityPolicyEnforcer`
 * exactly like `core-shell.ShellTool` — running a command in a Termux
 * userland is `SENSITIVE`/`PermissionCategory.TERMINAL`, the same tier as
 * plain (non-root) shell execution, **not** `SecurityLevel.ROOT` —
 * `AdbTermuxExecutor`'s own root dependency for output readback is an
 * implementation detail of that one backend (see its doc comment), not a
 * property of the capability this tool grants.
 *
 * Input: `executable` (required), `args` (optional, space-separated — same
 * caveat as `ShellTool`/`RootTool` about arguments containing spaces),
 * `workingDirectory` (optional).
 */
class TermuxTool(private val executor: TermuxExecutor) : Tool {
    override val spec = ToolSpec(
        name = "run_termux_command",
        description = "Runs a command inside a Termux userland",
        securityLevel = SecurityLevel.SENSITIVE,
        permissionCategory = PermissionCategory.TERMINAL,
    )

    override fun execute(input: Map<String, String>): ToolResult {
        val executable = input["executable"] ?: return ToolResult.Failure("Missing required input 'executable'")
        val args = input["args"]?.split(" ")?.filter { it.isNotEmpty() } ?: emptyList()
        val workingDirectory = input["workingDirectory"]

        val command = TermuxCommand(executable = executable, args = args, workingDirectory = workingDirectory)

        return when (val result = executor.execute(command)) {
            is TermuxExecutionResult.Success -> if (result.exitCode == 0) {
                ToolResult.Success(result.stdout)
            } else {
                ToolResult.Failure("'$executable' exited with code ${result.exitCode}: ${result.stderr.ifBlank { result.stdout }}")
            }
            is TermuxExecutionResult.Failure -> ToolResult.Failure(result.reason, result.cause)
        }
    }
}
