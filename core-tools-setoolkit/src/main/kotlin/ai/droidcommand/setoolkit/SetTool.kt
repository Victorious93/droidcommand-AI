package ai.droidcommand.setoolkit

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec

/**
 * Exposes [SetExecutor] to the agent as a `Tool`, gated by
 * core-security's real `SecureToolExecutor`/`SecurityPolicyEnforcer`
 * exactly like `ai.droidcommand.metasploit.MetasploitTool` — running a
 * social-engineering attack vector against a named target is
 * `SENSITIVE`/`PermissionCategory.NETWORK`, always requiring explicit
 * confirmation.
 *
 * Every input is a structured field a GUI surface (attack-vector picker,
 * target field, options form) fills in via buttons/fields — never a raw
 * string an operator types. [SetCommand]'s own validation is what actually
 * enforces the injection-safety and "no blank/mass target" guarantees, not
 * this tool's input parsing.
 *
 * Input keys: `attackVector` (required, one of [SetAttackVector]),
 * `targetHost` or `targetEmail` (at least one required — this tool fails
 * closed rather than accept an unspecified/mass target), `payload`
 * (optional), and any number of `option.<NAME>` keys mapped to SET
 * automation-config entries.
 */
class SetTool(private val executor: SetExecutor) : Tool {
    override val spec = ToolSpec(
        name = "run_setoolkit_attack",
        description = "Runs a Social-Engineer Toolkit attack vector against an explicitly named, authorized target",
        securityLevel = SecurityLevel.SENSITIVE,
        permissionCategory = PermissionCategory.NETWORK,
    )

    override fun execute(input: Map<String, String>): ToolResult {
        val attackVectorRaw = input["attackVector"] ?: return ToolResult.Failure("Missing required input 'attackVector'")
        val attackVector = runCatching { SetAttackVector.valueOf(attackVectorRaw.uppercase()) }.getOrElse {
            return ToolResult.Failure(
                "Unknown attackVector '$attackVectorRaw'; expected one of ${SetAttackVector.entries.joinToString()}",
            )
        }
        val targetHost = input["targetHost"]?.takeIf { it.isNotBlank() }
        val targetEmail = input["targetEmail"]?.takeIf { it.isNotBlank() }
        if (targetHost == null && targetEmail == null) {
            return ToolResult.Failure("Missing required input 'targetHost' or 'targetEmail' — this tool never runs against an unspecified target")
        }
        val payload = input["payload"]
        val options = input.filterKeys { it.startsWith("option.") }.mapKeys { it.key.removePrefix("option.") }

        val command = try {
            SetCommand(
                attackVector = attackVector,
                target = SetTarget(targetHost, targetEmail),
                payload = payload,
                options = options,
            )
        } catch (e: IllegalArgumentException) {
            return ToolResult.Failure("Invalid SET command: ${e.message}")
        }

        return when (val result = executor.execute(command)) {
            is SetExecutionResult.Success -> if (result.exitCode == 0) {
                ToolResult.Success(result.stdout)
            } else {
                ToolResult.Failure(
                    "SET attack vector '$attackVector' exited with code ${result.exitCode}: ${result.stderr.ifBlank { result.stdout }}",
                )
            }
            is SetExecutionResult.Failure -> ToolResult.Failure(result.reason, result.cause)
        }
    }
}
