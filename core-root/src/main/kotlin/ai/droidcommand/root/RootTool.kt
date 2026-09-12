package ai.droidcommand.root

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec

/**
 * Exposes [RootExecutor] to the agent as a `Tool` with `requiresRoot =
 * true` and `securityLevel = SecurityLevel.ROOT` — `core-security`'s
 * `SecurityPolicyEnforcer` denies it outright (no prompt, not even an
 * approval opportunity) unless the session's `SecurityPolicy.rootEnabled`
 * is true and `rootAvailable()` reports true, and only asks for explicit
 * approval after both of those hold. This module never reimplements that
 * gate; it only supplies the `RootExecutor` the gate protects.
 *
 * [grantCapability] is opt-in (null by default, unaffecting existing
 * callers): when set, `SecureToolExecutor` additionally requires a live
 * grant for that capability name (e.g. `"root"` or `"ai_root"` to
 * distinguish an AI-initiated request from a device-owner-initiated one)
 * before this tool may run at all, on top of the ordinary root/permission
 * check above.
 */
class RootTool(
    private val executor: RootExecutor,
    grantCapability: String? = null,
) : Tool {
    override val spec = ToolSpec(
        name = "run_root_command",
        description = "Runs an allow-listed command with root privileges",
        requiresRoot = true,
        securityLevel = SecurityLevel.ROOT,
        grantCapability = grantCapability,
        permissionCategory = PermissionCategory.ROOT,
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
