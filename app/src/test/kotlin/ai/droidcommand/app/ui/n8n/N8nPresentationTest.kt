package ai.droidcommand.app.ui.n8n

import ai.droidcommand.app.ui.components.PillTone
import ai.droidcommand.app.ui.settings.KeyStatus
import ai.droidcommand.n8n.N8nWebhook
import ai.droidcommand.n8n.N8nWorkflow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class N8nPresentationTest {
    private val hooked = N8nWorkflow("1", "Backup", active = true, webhook = N8nWebhook("backup", "POST"))
    private val plain = N8nWorkflow("2", "Manual only", active = false, webhook = null)
    private val ready = N8nUiState(serverUrl = "https://192.168.1.20:5678", keyStatus = KeyStatus.SAVED)

    @Test
    fun `a fresh screen has three steps and none done`() {
        val steps = N8nPresentation.steps(N8nUiState())
        assertEquals(listOf("Server address", "API key", "Workflows"), steps.map { it.title })
        assertTrue(steps.none { it.done })
    }

    @Test
    fun `an address with an error is not done and shows the error`() {
        val ui = N8nUiState(serverUrl = "ftp://nope", urlError = "Use http or https.")
        val step = N8nPresentation.steps(ui).first()
        assertFalse(step.done)
        assertEquals("Use http or https.", step.detail)
    }

    @Test
    fun `a saved address and key complete the first two steps`() {
        val steps = N8nPresentation.steps(ready)
        assertTrue(steps[0].done)
        assertTrue(steps[1].done)
        assertFalse(steps[2].done)
    }

    @Test
    fun `workflows complete the last step and are counted`() {
        val steps = N8nPresentation.steps(ready.copy(workflows = listOf(hooked, plain)))
        assertTrue(steps[2].done)
        assertEquals("2 loaded.", steps[2].detail)
    }

    @Test
    fun `the status never claims a connection before workflows have loaded`() {
        assertEquals("Not set up" to PillTone.Warning, N8nPresentation.statusPill(N8nUiState()))
        assertEquals("Not set up" to PillTone.Warning, N8nPresentation.statusPill(ready.copy(keyStatus = KeyStatus.NOT_SET)))
        assertEquals("Ready to test" to PillTone.Neutral, N8nPresentation.statusPill(ready))
        assertEquals("1 workflow" to PillTone.Success, N8nPresentation.statusPill(ready.copy(workflows = listOf(hooked))))
        assertEquals("2 workflows" to PillTone.Success, N8nPresentation.statusPill(ready.copy(workflows = listOf(hooked, plain))))
    }

    @Test
    fun `every key status has a sentence, and a key that needs re-entry says so`() {
        for (status in KeyStatus.entries) assertTrue(N8nPresentation.keyDetail(status).isNotBlank())
        assertTrue(N8nPresentation.keyDetail(KeyStatus.NEEDS_REENTRY).contains("again"))
    }

    @Test
    fun `workflow badges say active and webhook state in words`() {
        assertEquals(listOf("Active" to PillTone.Success, "Has webhook" to PillTone.Neutral), N8nPresentation.workflowBadges(hooked))
        assertEquals(listOf("Inactive" to PillTone.Neutral, "No webhook" to PillTone.Warning), N8nPresentation.workflowBadges(plain))
    }

    @Test
    fun `a workflow can be run only when it has a webhook and nothing else is running`() {
        assertTrue(N8nPresentation.canRun(ready, hooked))
        assertFalse(N8nPresentation.canRun(ready, plain))
        assertFalse(N8nPresentation.canRun(ready, null))
        assertFalse(N8nPresentation.canRun(ready.copy(busy = true), hooked))
    }

    @Test
    fun `the run explanation mentions the confirmation only when running is possible`() {
        assertTrue(N8nPresentation.runExplanation(hooked).contains("confirm"))
        assertTrue(N8nPresentation.runExplanation(plain).contains("cannot start it"))
    }
}
