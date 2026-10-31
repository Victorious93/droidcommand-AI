package ai.droidcommand.shizuku.toolbox

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec

/** Runs a built [ToolboxCommand]. The app supplies Shizuku or root behind this. */
fun interface ToolboxRunner {
    fun run(command: ToolboxCommand): ToolboxRunResult
}

data class ToolboxRunResult(val ok: Boolean, val exitCode: Int, val output: String)

/**
 * The AI's only door into the System Toolbox. It is registered, but
 * [aiEnabled] defaults OFF, and the name contains `shizuku` so
 * `ActionRiskClassifier` never auto-approves it: even with the gate on,
 * `SecureToolExecutor` asks every time. Free-form terminal input is not
 * offered to the AI at all, and reboot is never offered either.
 */
class SystemToolboxTool(
    private val runner: ToolboxRunner,
    private val aiEnabled: () -> Boolean,
) : Tool {
    override val spec = ToolSpec(
        name = NAME,
        description = "System Toolbox via Shizuku/root. Inputs: action (list_packages, force_stop, disable, enable, clear_data, " +
            "uninstall, grant, revoke, settings_get, settings_put, getprop, battery, storage, logcat), package, permission, " +
            "namespace, key, value, tag. Off until the owner turns on AI access in System Toolbox. Always asks before running.",
        securityLevel = SecurityLevel.SENSITIVE,
        requiresConfirmation = true,
        permissionCategory = PermissionCategory.DEVICE_CONTROL,
    )

    override fun execute(input: Map<String, String>): ToolResult {
        if (!aiEnabled()) return ToolResult.Failure(GATE_OFF)
        val command = try {
            build(input)
        } catch (e: IllegalArgumentException) {
            return ToolResult.Failure(e.message ?: "Bad input")
        }
        val result = runner.run(command)
        return if (result.ok) ToolResult.Success(result.output.take(MAX_OUTPUT)) else ToolResult.Failure("Exit ${result.exitCode}: ${result.output.take(2_000)}")
    }

    companion object {
        const val NAME = "shizuku_system_toolbox"
        const val GATE_OFF = "System Toolbox AI access is off. Turn it on in Advanced → System Toolbox. It still asks every time."
        const val DEFAULT_AI_ENABLED = false
        private const val MAX_OUTPUT = 16_000

        fun build(input: Map<String, String>): ToolboxCommand {
            val pkg = { input["package"].orEmpty() }
            val ns = {
                SettingsNamespace.entries.firstOrNull { it.wire == input["namespace"] } ?: throw IllegalArgumentException("namespace must be global, secure, or system")
            }
            return when (input["action"]) {
                "list_packages" -> ToolboxCommands.listPackages()
                "force_stop" -> ToolboxCommands.forceStop(pkg())
                "disable" -> ToolboxCommands.disable(pkg())
                "enable" -> ToolboxCommands.enable(pkg())
                "clear_data" -> ToolboxCommands.clearData(pkg())
                "uninstall" -> ToolboxCommands.uninstall(pkg())
                "grant" -> ToolboxCommands.grant(pkg(), input["permission"].orEmpty())
                "revoke" -> ToolboxCommands.revoke(pkg(), input["permission"].orEmpty())
                "settings_get" -> ToolboxCommands.settingsGet(ns(), input["key"].orEmpty())
                "settings_put" -> ToolboxCommands.settingsPut(ns(), input["key"].orEmpty(), input["value"].orEmpty())
                "getprop" -> ToolboxCommands.getprop()
                "battery" -> ToolboxCommands.battery()
                "storage" -> ToolboxCommands.storage()
                "logcat" -> ToolboxCommands.logcat(input["tag"], lines = 300)
                else -> throw IllegalArgumentException("Unknown or not-allowed action")
            }
        }
    }
}
