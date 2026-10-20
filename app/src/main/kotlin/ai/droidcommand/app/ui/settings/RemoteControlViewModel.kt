package ai.droidcommand.app.ui.settings

import ai.droidcommand.agent.ToolRunner
import ai.droidcommand.agent.describe
import ai.droidcommand.remotecontrol.DeliveryMode
import ai.droidcommand.remotecontrol.DevicePairing
import ai.droidcommand.remotecontrol.Macro
import ai.droidcommand.remotecontrol.MacroStep
import ai.droidcommand.remotecontrol.MacroTrigger
import ai.droidcommand.remotecontrol.MacroTriggerKind
import ai.droidcommand.remotecontrol.MacroValidation
import ai.droidcommand.remotecontrol.NodeRef
import ai.droidcommand.remotecontrol.NodeStatusSnapshot
import ai.droidcommand.remotecontrol.RemoteAction
import ai.droidcommand.remotecontrol.RemoteCommandMapping
import ai.droidcommand.remotecontrol.RemoteControlConfig
import ai.droidcommand.remotecontrol.RemoteControlSettingsController
import ai.droidcommand.remotecontrol.RemoteDispatchResult
import ai.droidcommand.remotecontrol.TargetDevice
import ai.droidcommand.security.FleetIntegrationClass
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class RemoteControlUi(
    val config: RemoteControlConfig = RemoteControlConfig(),
    val lastPreview: String? = null,
    val lastRun: String? = null,
    val error: String? = null,
    val statusTray: List<NodeStatusSnapshot> = emptyList(),
)

/**
 * Settings skeleton over [RemoteControlSettingsController]. Execution goes through
 * [ToolRunner] then the stub executor — live wipe / power-off / lock / RustDesk
 * are not implemented. SMS whitelist / code word are not stored here.
 */
@HiltViewModel
class RemoteControlViewModel @Inject constructor(
    private val controller: RemoteControlSettingsController,
    private val toolRunner: ToolRunner,
) : ViewModel() {
    private val mutable = MutableStateFlow(
        RemoteControlUi(controller.snapshot(), statusTray = controller.statusTrayRows()),
    )
    val state: StateFlow<RemoteControlUi> = mutable.asStateFlow()

    fun refresh() {
        mutable.value = mutable.value.copy(
            config = controller.snapshot(),
            statusTray = controller.statusTrayRows(),
            error = null,
        )
    }

    fun saveMapping(
        id: String,
        displayName: String,
        voicePhrase: String,
        smsCommandId: String,
        targetNodeId: String,
        action: RemoteAction,
        deliveryMode: DeliveryMode,
        destructive: Boolean,
    ) {
        try {
            controller.putMapping(
                RemoteCommandMapping(
                    id = id.trim(),
                    displayName = displayName.trim(),
                    voicePhrase = voicePhrase.trim().ifBlank { null },
                    smsCommandId = smsCommandId.trim().ifBlank { null },
                    targetNodeId = targetNodeId.trim(),
                    action = action,
                    deliveryMode = deliveryMode,
                    destructive = destructive || action.destructiveByDefinition,
                ),
            )
            mutable.value = RemoteControlUi(controller.snapshot(), statusTray = controller.statusTrayRows())
        } catch (e: IllegalArgumentException) {
            mutable.value = mutable.value.copy(error = e.message)
        }
    }

    fun removeMapping(id: String) {
        controller.removeMapping(id)
        refresh()
    }

    fun saveTarget(nodeId: String, displayName: String, integrationClass: FleetIntegrationClass, packageName: String) {
        try {
            controller.putTarget(
                TargetDevice(
                    nodeId = nodeId.trim(),
                    displayName = displayName.trim(),
                    integrationClass = integrationClass,
                    packageName = packageName.trim().ifBlank { null },
                ),
            )
            refresh()
        } catch (e: IllegalArgumentException) {
            mutable.value = mutable.value.copy(error = e.message)
        }
    }

    fun removeTarget(nodeId: String) {
        controller.removeTarget(nodeId)
        refresh()
    }

    fun saveMacro(
        id: String,
        displayName: String,
        triggerKind: MacroTriggerKind,
        voicePhrase: String,
        smsCommandId: String,
        scheduleExpr: String,
        targetNodeId: String,
        integrationClass: FleetIntegrationClass,
        actionRef: String,
        destructive: Boolean,
    ) {
        try {
            val trigger = MacroTrigger(
                kind = triggerKind,
                voicePhrase = voicePhrase.trim().ifBlank { null },
                smsCommandId = smsCommandId.trim().ifBlank { null },
                scheduleExpr = scheduleExpr.trim().ifBlank { null },
            )
            controller.putMacro(
                Macro(
                    id = id.trim(),
                    displayName = displayName.trim(),
                    steps = listOf(
                        MacroStep(
                            id = "step-1",
                            target = NodeRef(
                                nodeId = targetNodeId.trim(),
                                integrationClass = integrationClass,
                            ),
                            actionRef = actionRef.trim(),
                            destructive = destructive,
                        ),
                    ),
                    trigger = trigger,
                ),
            )
            refresh()
        } catch (e: IllegalArgumentException) {
            mutable.value = mutable.value.copy(error = e.message)
        }
    }

    fun removeMacro(id: String) {
        controller.removeMacro(id)
        refresh()
    }

    fun setExternalControlEnabled(enabled: Boolean) {
        controller.setExternalControlEnabled(enabled)
        refresh()
    }

    fun savePairing(sourceNodeRef: String, packageName: String, certSha256: String) {
        try {
            controller.putPairing(
                DevicePairing(
                    sourceNodeRef = sourceNodeRef.trim(),
                    packageName = packageName.trim(),
                    certSha256 = certSha256.trim().ifBlank { null },
                ),
            )
            refresh()
        } catch (e: IllegalArgumentException) {
            mutable.value = mutable.value.copy(error = e.message)
        }
    }

    fun removePairing(sourceNodeRef: String) {
        controller.removePairing(sourceNodeRef)
        refresh()
    }

    fun previewVoice(transcript: String) {
        mutable.value = mutable.value.copy(lastPreview = describe(controller.previewVoice(transcript)), error = null)
    }

    fun dryRunMacro(macroId: String) {
        mutable.value = mutable.value.copy(
            lastPreview = when (val result = controller.dryRunMacro(macroId)) {
                is MacroValidation.Ok ->
                    "Dry-run ${result.dryRun.displayName}: ${result.dryRun.steps.size} steps, " +
                        result.dryRun.toolName +
                        if (result.dryRun.hasDestructiveStep) " (destructive — will prompt)" else ""
                is MacroValidation.Invalid -> "Invalid: ${result.issues.joinToString()}"
            },
            error = null,
        )
    }

    fun requestRun(mappingId: String) {
        val mapping = controller.snapshot().mappings.find { it.id == mappingId }
        if (mapping == null) {
            mutable.value = mutable.value.copy(error = "Unknown mapping")
            return
        }
        val result = toolRunner.run(mapping.toolName(), mapOf("mappingId" to mapping.id, "trigger" to "VOICE"))
        mutable.value = mutable.value.copy(lastRun = result.describe(), error = null)
    }

    fun requestMacroRun(macroId: String) {
        val macro = controller.snapshot().macros.find { it.id == macroId }
        if (macro == null) {
            mutable.value = mutable.value.copy(error = "Unknown macro")
            return
        }
        val result = toolRunner.run(macro.toolName(), mapOf("macroId" to macro.id, "trigger" to "MANUAL"))
        mutable.value = mutable.value.copy(lastRun = result.describe(), error = null)
    }

    fun exportBackup() {
        try {
            val backup = controller.exportBackup()
            mutable.value = mutable.value.copy(
                lastPreview = "Local backup saved (${backup.config.mappings.size} mappings, " +
                    "${backup.config.macros.size} macros, ${backup.config.pairings.size} pairings). " +
                    "API keys are not included.",
                error = null,
            )
        } catch (e: IllegalStateException) {
            mutable.value = mutable.value.copy(error = e.message)
        } catch (e: IllegalArgumentException) {
            mutable.value = mutable.value.copy(error = e.message)
        }
    }

    fun importBackup() {
        try {
            val backup = controller.importBackup()
            mutable.value = RemoteControlUi(
                config = controller.snapshot(),
                lastPreview = "Local backup restored from ${backup.exportedAtMs}",
                statusTray = controller.statusTrayRows(),
            )
        } catch (e: IllegalStateException) {
            mutable.value = mutable.value.copy(error = e.message)
        } catch (e: IllegalArgumentException) {
            mutable.value = mutable.value.copy(error = e.message)
        }
    }

    private fun describe(result: RemoteDispatchResult): String = when (result) {
        is RemoteDispatchResult.Ready ->
            "Matched ${result.plan.mapping?.displayName ?: result.plan.macro?.displayName ?: result.plan.spoof?.actionRef} → " +
                "${result.plan.toolName} " +
                if (result.plan.destructive) "(destructive)" else "(non-destructive)"
        is RemoteDispatchResult.Rejected -> "Rejected: ${result.reason} (${result.category.wire})"
        is RemoteDispatchResult.Capabilities ->
            "capabilities.get protocol ${result.protocolMajor}.${result.protocolMinor}: ${result.opNames.joinToString()}"
        is RemoteDispatchResult.MacroRun ->
            "macro ${if (result.result.dryRun) "dry-run" else "run"}: " +
                "${result.result.steps.size} steps, ok=${result.result.ok}"
    }
}
