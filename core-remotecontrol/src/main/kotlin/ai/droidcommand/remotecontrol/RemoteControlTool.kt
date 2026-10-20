package ai.droidcommand.remotecontrol

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec

/**
 * Approval-gated Remote Control tools. Names contain `remote`, so
 * [ai.droidcommand.security.ActionRiskClassifier] always requires a tap.
 * YOLO may waive [RemoteControlToolNames.COMMAND] / [RemoteControlToolNames.SCREEN]
 * / [RemoteControlToolNames.MACRO] the same way it waives shell; it must never
 * waive wipe / power_off / remote_lock / remote_destructive. Kali/pentest
 * tokens are unchanged.
 *
 * [execute] only calls [RemoteActionExecutor], which in this slice is a stub.
 * A denied confirmation never reaches the executor (proven by
 * `RemoteControlToolApprovalTest`).
 */
enum class RemoteControlToolKind(
    val toolName: String,
    val description: String,
    val destructive: Boolean,
) {
    COMMAND(
        RemoteControlToolNames.COMMAND,
        "Runs a non-destructive discrete remote command on a mapped target. Always confirmed. Live execution is a stub.",
        false,
    ),
    SCREEN(
        RemoteControlToolNames.SCREEN,
        "Hands off to RustDesk full-screen control on a mapped target. Always confirmed. Live RustDesk is a stub.",
        false,
    ),
    MACRO(
        RemoteControlToolNames.MACRO,
        "Runs a non-destructive macro (ordered fleet steps). Always confirmed. Live execution is a stub.",
        false,
    ),
    WIPE(
        RemoteControlToolNames.WIPE,
        "Destructive remote wipe. Never auto-approved, including in YOLO. Live wipe is not implemented.",
        true,
    ),
    POWER_OFF(
        RemoteControlToolNames.POWER_OFF,
        "Destructive remote power-off. Never auto-approved, including in YOLO. Live power-off is not implemented.",
        true,
    ),
    LOCK(
        RemoteControlToolNames.LOCK,
        "Destructive remote lock. Never auto-approved, including in YOLO. Live lock is not implemented.",
        true,
    ),
    DESTRUCTIVE(
        RemoteControlToolNames.DESTRUCTIVE,
        "User-flagged destructive remote command or a macro with a destructive step. " +
            "Never auto-approved, including in YOLO. Live execution is a stub.",
        true,
    ),
}

class RemoteControlTool(
    private val executor: RemoteActionExecutor,
    private val store: RemoteControlStore,
    private val kind: RemoteControlToolKind,
) : Tool {
    override val spec = ToolSpec(
        name = kind.toolName,
        description = kind.description,
        // Destructive tools still reach ApprovalPrompt without a live root backend:
        // requiresRoot stays false so a missing Magisk/Shizuku/device-admin cannot
        // hard-deny before the tap. Privilege is recorded on the mapping; live
        // execution is a stub anyway. securityLevel ROOT keeps confirmation on.
        securityLevel = if (kind.destructive) SecurityLevel.ROOT else SecurityLevel.SENSITIVE,
        requiresRoot = false,
        requiresConfirmation = true,
        permissionCategory = PermissionCategory.REMOTE_CONTROL,
    )

    override fun execute(input: Map<String, String>): ToolResult {
        val plan = planFrom(input) ?: return ToolResult.Failure(
            "Missing required input 'mappingId' or 'macroId'",
        )
        if (plan.toolName != kind.toolName) {
            return ToolResult.Failure(
                "Work routes to ${plan.toolName}, not ${kind.toolName}",
            )
        }
        return when (val result = executor.execute(plan)) {
            is RemoteActionResult.NotImplemented -> ToolResult.Failure(result.reason)
            is RemoteActionResult.Denied -> ToolResult.Failure(result.reason)
        }
    }

    private fun planFrom(input: Map<String, String>): RemoteDispatchPlan? {
        val trigger = input["trigger"]?.let { runCatching { TriggerKind.valueOf(it) }.getOrNull() }
            ?: TriggerKind.VOICE
        val mappingId = input["mappingId"]
        if (mappingId != null) {
            val mapping = store.load().mappings.find { it.id == mappingId } ?: return null
            return RemoteDispatchPlan(
                mapping = mapping,
                macro = null,
                command = null,
                trigger = trigger,
                envelope = null,
                toolName = mapping.toolName(),
                destructive = mapping.effectiveDestructive,
                privilege = mapping.action.requiredPrivilege,
            )
        }
        val macroId = input["macroId"] ?: return null
        val macro = store.load().macros.find { it.id == macroId } ?: return null
        return RemoteDispatchPlan(
            mapping = null,
            macro = macro,
            command = null,
            trigger = trigger,
            envelope = null,
            toolName = macro.toolName(),
            destructive = macro.hasDestructiveStep,
            privilege = if (macro.hasDestructiveStep) {
                RequiredPrivilege.DEVICE_ADMIN_OR_ROOT
            } else {
                RequiredPrivilege.UNSPECIFIED
            },
        )
    }

    companion object {
        fun all(executor: RemoteActionExecutor, store: RemoteControlStore): List<RemoteControlTool> =
            RemoteControlToolKind.entries.map { RemoteControlTool(executor, store, it) }
    }
}
