package ai.droidcommand.shizuku.toolbox

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec
import ai.droidcommand.security.ApprovalPrompt
import ai.droidcommand.shizuku.PrivilegedResult

/**
 * The only way the AI can reach the System Toolbox. Off unless [aiEnabled] (Advanced → System Toolbox,
 * default OFF). Even when on, every call asks through [approval] directly with the exact command, so YOLO
 * and saved grants cannot skip it. Only a fixed set of actions is exposed; there is no free-form shell and
 * no reboot for the AI.
 */
class SystemToolboxTool(
    private val backend: () -> ToolboxBackend?,
    private val aiEnabled: () -> Boolean,
    private val approval: ApprovalPrompt,
) : Tool {
    override val spec = ToolSpec(
        name = NAME,
        description = "System Toolbox over Shizuku or root. Off until the owner turns on AI access in System Toolbox. " +
            "Inputs: action (list_packages, permissions, force_stop, disable, enable, get_setting, battery, storage), " +
            "package, namespace, key. Asks the owner before every call.",
        securityLevel = SecurityLevel.SENSITIVE,
        permissionCategory = PermissionCategory.DEVICE_CONTROL,
        requiresConfirmation = true,
    )

    override fun execute(input: Map<String, String>): ToolResult {
        if (!aiEnabled()) return ToolResult.Failure(GATE_OFF)
        val built = build(input)
        val command = when (built) {
            is ToolboxBuild.Ok -> built.command
            is ToolboxBuild.Rejected -> return ToolResult.Failure(built.reason)
        }
        val target = backend() ?: return ToolResult.Failure("Neither Shizuku nor root is available.")
        val shown = target.display(command)
        val ok = approval.requestApproval(
            toolName = NAME,
            input = mapOf("command" to shown, "backend" to target.name, "action" to command.label),
            reason = "The assistant wants to run on this phone: $shown",
        )
        if (!ok) return ToolResult.Failure("You said no. Nothing ran.")
        return when (val r = target.run(command)) {
            is PrivilegedResult.Success -> if (r.exitCode == 0 && !r.timedOut) {
                ToolResult.Success(r.stdout.take(MAX_OUTPUT))
            } else {
                ToolResult.Failure("exited with code ${r.exitCode}: ${r.stderr.ifBlank { r.stdout }.take(2_000)}")
            }
            is PrivilegedResult.Failure -> ToolResult.Failure(r.reason)
        }
    }

    private fun build(input: Map<String, String>): ToolboxBuild {
        val pkg = input["package"].orEmpty()
        return when (input["action"]) {
            "list_packages" -> ToolboxBuild.Ok(ToolboxCommands.listPackages(PackageFilter.USER))
            "permissions" -> ToolboxCommands.packagePermissions(pkg)
            "force_stop" -> ToolboxCommands.forceStop(pkg)
            "disable" -> ToolboxCommands.disable(pkg)
            "enable" -> ToolboxCommands.enable(pkg)
            "get_setting" -> {
                val ns = SettingsNamespace.entries.firstOrNull { it.wire == input["namespace"] }
                    ?: return ToolboxBuild.Rejected("namespace must be global, secure, or system")
                ToolboxCommands.getSetting(ns, input["key"].orEmpty())
            }
            "battery" -> ToolboxBuild.Ok(ToolboxCommands.battery())
            "storage" -> ToolboxBuild.Ok(ToolboxCommands.storage())
            else -> ToolboxBuild.Rejected("Unknown action. Allowed: ${ACTIONS.joinToString()}")
        }
    }

    companion object {
        const val NAME = "shizuku_root_system_toolbox"
        const val GATE_OFF = "System Toolbox is off for the assistant. Turn on AI access in Advanced → System Toolbox. It still asks every time."
        const val MAX_OUTPUT = 16_000
        val ACTIONS = listOf("list_packages", "permissions", "force_stop", "disable", "enable", "get_setting", "battery", "storage")
    }
}
