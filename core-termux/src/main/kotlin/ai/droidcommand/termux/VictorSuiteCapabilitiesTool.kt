package ai.droidcommand.termux

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec

/**
 * Reads VictorSuite's Termux-side capabilities (proot-distro, Kali + nmap/msfconsole presence,
 * ZTSocketService's port) over the existing RUN_COMMAND path — see
 * [VictorSuiteCapabilitiesProbe]'s doc comment for why this needs no VictorSuite-side code change.
 * A read: nothing is installed or written.
 */
class VictorSuiteCapabilitiesTool(private val executor: TermuxExecutor) : Tool {
    override val spec = ToolSpec(
        name = "victorsuite_capabilities",
        description = "Reads VictorSuite's Termux environment (proot-distro, Kali + nmap/msfconsole presence, " +
            "ZTSocketService port) via a read-only bash probe sent over com.termux.RUN_COMMAND. " +
            "Installs and writes nothing. Takes no input.",
        securityLevel = SecurityLevel.SENSITIVE,
        permissionCategory = PermissionCategory.TERMINAL,
    )

    override fun execute(input: Map<String, String>): ToolResult =
        when (val result = executor.execute(VictorSuiteCapabilitiesProbe.command())) {
            is TermuxExecutionResult.Success -> if (result.exitCode == 0) {
                ToolResult.Success(VictorSuiteCapabilitiesProbe.parse(result.stdout).describe())
            } else {
                ToolResult.Failure(
                    "capabilities probe exited with code ${result.exitCode}: ${result.stderr.ifBlank { result.stdout }}",
                )
            }
            is TermuxExecutionResult.Failure -> ToolResult.Failure(result.reason, result.cause)
        }
}
