package ai.droidcommand.metasploit

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec

/**
 * Exposes [MetasploitExecutor] to the agent as a `Tool`, gated by
 * core-security's real `SecureToolExecutor`/`SecurityPolicyEnforcer`
 * exactly like `core-shell.ShellTool`/`core-termux.TermuxTool` — running an
 * exploit/auxiliary/post module against a network target is
 * `SENSITIVE`/`PermissionCategory.NETWORK`, always requiring explicit
 * confirmation (never auto-approved, per [ToolSpec.requiresConfirmation]'s
 * own default for a non-`NORMAL` [SecurityLevel]).
 *
 * Every input is a structured field — never a raw msfconsole command
 * string. This is deliberate: a GUI surface (module/payload picker, target
 * field, options form) constructs this input map from buttons and fields,
 * so an operator never needs to type an `msfconsole` command by hand, and
 * an LLM planner can never smuggle arbitrary resource-script text through
 * this tool either — [MetasploitCommand]'s own validation is what actually
 * enforces that, not this tool's input parsing.
 *
 * Input keys: `moduleType` (`EXPLOIT`|`AUXILIARY`|`POST`|`PAYLOAD`,
 * required), `modulePath` (required, e.g.
 * `"exploit/windows/smb/ms17_010_eternalblue"`), `targetHost` (required —
 * this tool fails closed rather than accept a blank/unspecified target),
 * `targetPort` (optional), `payload` (optional module path), and any number
 * of `option.<NAME>` keys mapped to Metasploit datastore options (e.g.
 * `option.LHOST`, `option.LPORT`).
 */
class MetasploitTool(private val executor: MetasploitExecutor) : Tool {
    override val spec = ToolSpec(
        name = "run_metasploit_module",
        description = "Runs a Metasploit exploit/auxiliary/post module against an explicitly named, authorized target",
        securityLevel = SecurityLevel.SENSITIVE,
        permissionCategory = PermissionCategory.NETWORK,
    )

    override fun execute(input: Map<String, String>): ToolResult {
        val moduleTypeRaw = input["moduleType"] ?: return ToolResult.Failure("Missing required input 'moduleType'")
        val moduleType = runCatching { MetasploitModuleType.valueOf(moduleTypeRaw.uppercase()) }.getOrElse {
            return ToolResult.Failure(
                "Unknown moduleType '$moduleTypeRaw'; expected one of ${MetasploitModuleType.entries.joinToString()}",
            )
        }
        val modulePath = input["modulePath"] ?: return ToolResult.Failure("Missing required input 'modulePath'")
        val targetHost = input["targetHost"]?.takeIf { it.isNotBlank() }
            ?: return ToolResult.Failure("Missing required input 'targetHost' — this tool never runs against a blank/unspecified target")
        val targetPort = input["targetPort"]?.toIntOrNull()
        val payload = input["payload"]
        val options = input.filterKeys { it.startsWith("option.") }.mapKeys { it.key.removePrefix("option.") }

        val command = try {
            MetasploitCommand(
                moduleType = moduleType,
                modulePath = modulePath,
                target = MetasploitTarget(targetHost, targetPort),
                payload = payload,
                options = options,
            )
        } catch (e: IllegalArgumentException) {
            return ToolResult.Failure("Invalid Metasploit command: ${e.message}")
        }

        return when (val result = executor.execute(command)) {
            is MetasploitExecutionResult.Success -> if (result.exitCode == 0) {
                ToolResult.Success(result.stdout)
            } else {
                ToolResult.Failure(
                    "Metasploit module '$modulePath' exited with code ${result.exitCode}: ${result.stderr.ifBlank { result.stdout }}",
                )
            }
            is MetasploitExecutionResult.Failure -> ToolResult.Failure(result.reason, result.cause)
        }
    }
}
