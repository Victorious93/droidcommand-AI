package ai.droidcommand.app.ui.n8n

import ai.droidcommand.app.ui.components.PillTone
import ai.droidcommand.app.ui.settings.KeyStatus
import ai.droidcommand.n8n.N8nWorkflow

/** One line of the setup checklist: what is needed, whether it is done, and one line on what to do or what it means. */
data class N8nSetupStep(val title: String, val done: Boolean, val detail: String)

/**
 * What the n8n screen says about where setup stands. Pure, so it is JVM-tested. Nothing here claims the server is
 * reachable: that is only known after "Test connection", and the screen says "Ready to test", not "Connected".
 */
internal object N8nPresentation {
    fun addressReady(ui: N8nUiState): Boolean = ui.serverUrl.isNotBlank() && ui.urlError == null

    fun keyReady(ui: N8nUiState): Boolean = ui.keyStatus == KeyStatus.SAVED

    fun steps(ui: N8nUiState): List<N8nSetupStep> = listOf(
        N8nSetupStep(
            title = "Server address",
            done = addressReady(ui),
            detail = when {
                ui.serverUrl.isBlank() -> "Type where n8n runs, for example https://192.168.1.20:5678."
                ui.urlError != null -> ui.urlError
                else -> "Saved on this phone."
            },
        ),
        N8nSetupStep(
            title = "API key",
            done = keyReady(ui),
            detail = keyDetail(ui.keyStatus),
        ),
        N8nSetupStep(
            title = "Workflows",
            done = ui.workflows.isNotEmpty(),
            detail = if (ui.workflows.isEmpty()) "Test the connection, or load them, once the two above are done." else "${ui.workflows.size} loaded.",
        ),
    )

    fun keyDetail(status: KeyStatus): String = when (status) {
        KeyStatus.NOT_SET -> "Create one in n8n (Settings, n8n API), then paste it here. It is stored encrypted and never shown again."
        KeyStatus.SAVED -> "Saved, encrypted on this phone."
        KeyStatus.NEEDS_REENTRY -> "The stored key can no longer be decrypted. Enter it again."
        KeyStatus.STORAGE_ERROR -> "Storage error. Try again."
    }

    /** The header pill. "Ready to test" is the most it ever claims before a test has run. */
    fun statusPill(ui: N8nUiState): Pair<String, PillTone> = when {
        !addressReady(ui) || !keyReady(ui) -> "Not set up" to PillTone.Warning
        ui.workflows.isEmpty() -> "Ready to test" to PillTone.Neutral
        ui.workflows.size == 1 -> "1 workflow" to PillTone.Success
        else -> "${ui.workflows.size} workflows" to PillTone.Success
    }

    fun workflowBadges(workflow: N8nWorkflow): List<Pair<String, PillTone>> = listOf(
        if (workflow.active) "Active" to PillTone.Success else "Inactive" to PillTone.Neutral,
        if (workflow.canTrigger) "Has webhook" to PillTone.Neutral else "No webhook" to PillTone.Warning,
    )

    fun runExplanation(workflow: N8nWorkflow): String =
        if (workflow.canTrigger) {
            "Sends your JSON to this workflow's webhook. n8n then does whatever the workflow is set up to do. You are asked to confirm first."
        } else {
            "This workflow has no webhook, so this app cannot start it."
        }

    fun canRun(ui: N8nUiState, workflow: N8nWorkflow?): Boolean = workflow != null && workflow.canTrigger && !ui.busy
}
