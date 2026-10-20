package ai.droidcommand.shizuku

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec

/**
 * Privileged Shizuku ops as `Tool`s. Names contain `shizuku`, so
 * [ai.droidcommand.security.ActionRiskClassifier] always requires a tap
 * (YOLO may waive like shell/root/Termux; Kali/pentest tokens are
 * untouched and still never auto-approved).
 *
 * Every invocation is `SENSITIVE` / `DEVICE_CONTROL`. The Android app
 * grants that category and still routes each call through
 * `SecureToolExecutor` → `ApprovalPrompt`. This class does not confirm
 * and does not talk to Shizuku APIs.
 */
enum class ShizukuToolKind(val toolName: String, val description: String) {
    EXEC(
        "run_shizuku_command",
        "Runs a command as the Shizuku/Sui privileged user (ADB shell or Sui). Always confirmed.",
    ),
    DEVICE_STATE(
        "shizuku_read_device_state",
        "Reads a small device-state snapshot via Shizuku (uid and a few build props). Always confirmed.",
    ),
    PM_GRANT(
        "shizuku_pm_grant",
        "Grants a runtime permission to a package via pm grant over Shizuku. Always confirmed.",
    ),
}

class ShizukuTool(
    private val shell: PrivilegedShell,
    private val kind: ShizukuToolKind,
) : Tool {
    override val spec = ToolSpec(
        name = kind.toolName,
        description = kind.description,
        securityLevel = SecurityLevel.SENSITIVE,
        permissionCategory = PermissionCategory.DEVICE_CONTROL,
        requiresConfirmation = true,
    )

    override fun execute(input: Map<String, String>): ToolResult {
        if (!shell.isAvailable()) {
            return ToolResult.Failure(ShizukuCopy.UNAVAILABLE_FAILURE)
        }
        val result = when (kind) {
            ShizukuToolKind.EXEC -> {
                val executable = input["executable"]
                    ?: return ToolResult.Failure("Missing required input 'executable'")
                val args = input["args"]?.split(" ")?.filter { it.isNotEmpty() } ?: emptyList()
                val timeoutMs = input["timeoutMs"]?.toLongOrNull() ?: PrivilegedShell.DEFAULT_TIMEOUT_MS
                // onBehalfOf is displayed by ApprovalPrompt (it is in [input]) and is not executed.
                shell.exec(listOf(executable) + args, timeoutMs)
            }
            ShizukuToolKind.DEVICE_STATE -> shell.readDeviceState()
            ShizukuToolKind.PM_GRANT -> {
                val pkg = input["package"] ?: return ToolResult.Failure("Missing required input 'package'")
                val permission = input["permission"] ?: return ToolResult.Failure("Missing required input 'permission'")
                shell.pmGrant(pkg, permission)
            }
        }
        return when (result) {
            is PrivilegedResult.Success -> if (result.timedOut) {
                ToolResult.Failure("Command timed out")
            } else if (result.exitCode == 0) {
                ToolResult.Success(result.stdout)
            } else {
                ToolResult.Failure("exited with code ${result.exitCode}: ${result.stderr.ifBlank { result.stdout }}")
            }
            is PrivilegedResult.Failure -> ToolResult.Failure(result.reason, result.cause)
        }
    }

    companion object {
        fun all(shell: PrivilegedShell): List<ShizukuTool> =
            ShizukuToolKind.entries.map { ShizukuTool(shell, it) }
    }
}
