package ai.droidcommand.systemsettings

import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the assistant can see. Values are shared only for settings in the reviewed catalog; everything else (unknown keys, identity,
 * credentials, whole table dumps) stays on the phone, so nothing sensitive is sent to a model by this tool. The owner's inference policy still
 * decides which model receives what this returns.
 */
class SystemSettingsReadTool(
    private val engine: SystemSettingsEngine,
    private val profiles: () -> List<SettingsProfile>,
    private val profileService: ProfileService,
    private val device: () -> DeviceContext,
    private val enabled: () -> Boolean,
) : Tool {
    override val spec = ToolSpec(
        name = NAME,
        description = "Read System Settings (Victorious Framework). Off until the owner turns on assistant access. Inputs: action " +
            "(status, curated, read, history, profiles, preview_profile, preview_set), table, key, value, profile, include_optional. " +
            "Only reviewed settings are shown. Nothing here changes the phone.",
        securityLevel = SecurityLevel.SENSITIVE,
        permissionCategory = PermissionCategory.DEVICE_CONTROL,
        requiresConfirmation = true,
        requiredInitiator = setOf(Initiator.AI, Initiator.DEVICE_OWNER),
    )

    override fun execute(input: Map<String, String>): ToolResult {
        if (!enabled()) return ToolResult.Failure(GATE_OFF)
        return when (input["action"]) {
            "status" -> ToolResult.Success(engine.statuses().joinToString("\n") { "${it.kind.label}: ${it.state} - ${it.detail}" })
            "curated" -> ToolResult.Success(
                engine.curated().joinToString("\n") { row ->
                    val v = when {
                        row.unsupportedReason != null -> "unsupported (${row.unsupportedReason})"
                        else -> readText(row.current)
                    }
                    "${row.spec.table.wire}/${row.spec.name} (${row.spec.title}): $v"
                }.take(MAX),
            )
            "read" -> {
                val key = keyOf(input) ?: return ToolResult.Failure("Give table (system, secure, global) and key.")
                if (SettingsCatalog.find(key) == null || SettingsRisk.classify(key) != RiskClass.CURATED) {
                    return ToolResult.Failure("Only reviewed settings can be read by the assistant. Open System Settings in the app to look at others.")
                }
                ToolResult.Success("${key.table.wire}/${key.name}: " + readText(engine.read(key).result))
            }
            "history" -> {
                val limit = input["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 10
                ToolResult.Success(engine.history(limit).joinToString("\n") { describeOp(it) }.ifBlank { "No changes recorded." }.take(MAX))
            }
            "profiles" -> ToolResult.Success(profiles().joinToString("\n") { "${it.id}: ${it.name} - ${it.description}" })
            "preview_profile" -> {
                val p = profiles().firstOrNull { it.id == input["profile"] } ?: return ToolResult.Failure("Unknown profile id. Use action=profiles.")
                ToolResult.Success(previewText(profileService.plan(p, RequestSource.AI, input["include_optional"] == "true")))
            }
            "preview_set" -> {
                val key = keyOf(input) ?: return ToolResult.Failure("Give table and key.")
                val value = input["value"] ?: return ToolResult.Failure("Give value.")
                val plan = engine.plan(ChangeRequest("Assistant preview", listOf(Change(key, Proposed.Put(value))), RequestSource.AI))
                ToolResult.Success(planText(plan))
            }
            else -> ToolResult.Failure("Unknown action. Allowed: status, curated, read, history, profiles, preview_profile, preview_set.")
        }
    }

    private fun keyOf(input: Map<String, String>): SettingKey? {
        val table = SettingsTable.fromWire(input["table"].orEmpty()) ?: return null
        return SettingKey.parse(table, input["key"].orEmpty(), device().userId)
    }

    companion object {
        const val NAME = "system_settings_read"
        const val GATE_OFF = "Assistant access to System Settings is off. The owner can turn it on in System Settings -> Access. It still asks every time."
        private const val MAX = 8_000

        fun readText(r: ReadResult?): String = when (r) {
            null -> "unreadable"
            is ReadResult.Failed -> "unreadable (${r.failure.kind.name.lowercase()})"
            is ReadResult.Value -> r.snapshot.describe()
        }

        fun describeOp(op: Operation): String {
            val at = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(op.createdAt))
            val entries = op.entries.joinToString("; ") { e ->
                if (SettingsRisk.classify(e.key) == RiskClass.CURATED) "${e.key.name}: ${e.previous.describe()} -> ${e.proposed.describe()} [${e.outcome}]" else "(a setting that is not shared) [${e.outcome}]"
            }
            return "${op.id.take(8)} $at ${op.kind} ${op.status} \"${op.label}\" via ${op.backend.label}: $entries"
        }

        fun planText(plan: Plan): String = buildString {
            appendLine(if (plan.executable) "Would change (nothing has been changed):" else "Would NOT run:")
            plan.summaryLines().forEach { appendLine(it) }
            plan.blockers.forEach { appendLine("Blocked: ${it.message}") }
            plan.warnings.forEach { appendLine("Note: ${it.message}") }
            plan.backend?.let { appendLine("Access: ${it.label}") }
        }.trim()

        fun previewText(pp: ProfilePlan): String = buildString {
            appendLine(planText(pp.plan))
            pp.unsupported.forEach { appendLine("Unsupported on this phone: ${it.entry.name} - ${it.reason}") }
            pp.leftOut.forEach { appendLine("Optional, not included: ${it.name}") }
        }.trim()
    }
}

/**
 * The only way the assistant can ask for a change. Off unless the owner turned assistant access on (default OFF). Even then the engine
 * re-checks everything at execution time and asks through the existing approval flow with the exact old and new values, so a saved grant or
 * YOLO mode cannot skip it. Only reviewed settings, only stored values (never removal), never high-impact entries, and no free-form commands.
 */
class SystemSettingsChangeTool(
    private val engine: SystemSettingsEngine,
    private val profiles: () -> List<SettingsProfile>,
    private val profileService: ProfileService,
    private val device: () -> DeviceContext,
    private val enabled: () -> Boolean,
) : Tool {
    override val spec = ToolSpec(
        name = NAME,
        description = "Change System Settings (Victorious Framework) for the assistant. Off until the owner turns on assistant access. " +
            "Inputs: action (apply_profile, set, restore), profile, include_optional, table, key, value, operation. " +
            "Asks the owner before every call, showing old and new values. Reviewed settings only.",
        securityLevel = SecurityLevel.SENSITIVE,
        permissionCategory = PermissionCategory.DEVICE_CONTROL,
        requiresConfirmation = true,
        requiredInitiator = setOf(Initiator.AI, Initiator.DEVICE_OWNER),
    )

    override fun execute(input: Map<String, String>): ToolResult {
        if (!enabled()) return ToolResult.Failure(SystemSettingsReadTool.GATE_OFF)
        val result = when (input["action"]) {
            "apply_profile" -> {
                val p = profiles().firstOrNull { it.id == input["profile"] } ?: return ToolResult.Failure("Unknown profile id.")
                profileService.apply(p, RequestSource.AI, input["include_optional"] == "true", Confirmation.None)
            }
            "set" -> {
                val table = SettingsTable.fromWire(input["table"].orEmpty()) ?: return ToolResult.Failure("Give table (system, secure, global).")
                val key = SettingKey.parse(table, input["key"].orEmpty(), device().userId) ?: return ToolResult.Failure("Invalid setting name.")
                val value = input["value"] ?: return ToolResult.Failure("Give value.")
                engine.apply(ChangeRequest("Assistant: ${key.name}", listOf(Change(key, Proposed.Put(value))), RequestSource.AI), Confirmation.None)
            }
            "restore" -> {
                val id = input["operation"] ?: return ToolResult.Failure("Give the operation id from history.")
                val full = engine.history(200).firstOrNull { it.id == id || it.id.startsWith(id) && id.length >= 8 }
                    ?: return ToolResult.Failure("No such operation in history.")
                engine.restore(RestoreRequest(RequestSource.AI, operationId = full.id), Confirmation.None)
            }
            else -> return ToolResult.Failure("Unknown action. Allowed: apply_profile, set, restore.")
        }
        return when (result) {
            is ApplyResult.Finished ->
                if (result.operation.status == OperationStatus.COMPLETED) {
                    ToolResult.Success("Done (${result.operation.status}). " + SystemSettingsReadTool.describeOp(result.operation) + " Stored values are read back; effects are not proven unless stated.")
                } else {
                    ToolResult.Partial(SystemSettingsReadTool.describeOp(result.operation), "ended as ${result.operation.status}")
                }
            is ApplyResult.Declined -> ToolResult.Failure("The owner said no. Nothing changed.")
            is ApplyResult.NothingToDo -> ToolResult.Success("Nothing to change.")
            ApplyResult.Busy -> ToolResult.Failure("Another settings operation is running. Nothing changed.")
            is ApplyResult.PlanChanged -> ToolResult.Failure("The phone changed while waiting. Nothing changed.")
            is ApplyResult.Rejected -> ToolResult.Failure("Refused: " + result.plan.blockers.joinToString("; ") { it.message }.ifBlank { "not executable" })
        }
    }

    companion object {
        const val NAME = "system_settings_change"
    }
}

/** Plain, exact steps the owner follows to give this app write access. Built from the real application id and Android version. */
object AccessSetupInstructions {
    data class Step(val title: String, val body: String, val command: String? = null)

    fun steps(applicationId: String, sdkInt: Int, release: String): List<Step> = buildList {
        add(
            Step(
                "Standard permissions: Secure and Global tables",
                "On Android $release (API $sdkInt) this app needs the WRITE_SECURE_SETTINGS permission to change the Secure and Global tables. Android does not show a prompt for it. " +
                    "Grant it once from a computer with USB debugging on:",
                "adb shell pm grant $applicationId android.permission.WRITE_SECURE_SETTINGS",
            ),
        )
        add(
            Step(
                "Standard permissions: System table",
                "The System table needs the 'Modify system settings' special access. Open Settings → Apps → Special app access → Modify system settings, choose this app and allow it. " +
                    "Or from a computer:",
                "adb shell appops set $applicationId WRITE_SETTINGS allow",
            ),
        )
        add(
            Step(
                "Shizuku",
                "Instead of granting those permissions, start built-in Shizuku (Advanced) or the Shizuku app, then allow $applicationId when Shizuku asks. " +
                    "Shizuku runs the settings command as the shell user, which can write all three tables.",
            ),
        )
        add(
            Step(
                "Root",
                "Root is off for System Settings until you turn it on in Access. A rooted phone is not enough on its own. When root is on, each root command asks your root manager.",
            ),
        )
        add(Step("Check", "Reopen the Access tab. Each method shows ready, permission missing, or unavailable, and the tables it can write."))
    }
}
