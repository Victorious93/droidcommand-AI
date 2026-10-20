package ai.droidcommand.app.mode

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec
import ai.droidcommand.app.automation.RecordingApproval
import ai.droidcommand.security.JsonFileAuditLog
import ai.droidcommand.security.SecurityPolicy
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PilotControllerTest {
    private class MemoryStore : ModePreferenceStore {
        private var mode: AgentMode = AgentMode.PILOT
        private val labels = mutableMapOf<AgentMode, String?>()

        override fun readMode(): AgentMode = mode

        override fun writeMode(mode: AgentMode) {
            this.mode = mode
        }

        override fun readLabel(mode: AgentMode): String? = labels[mode]

        override fun writeLabel(mode: AgentMode, stored: String?) {
            labels[mode] = stored
        }
    }

    private class EchoTool : Tool {
        override val spec = ToolSpec(name = "echo", description = "Repeats text")
        override fun execute(input: Map<String, String>): ToolResult = ToolResult.Success(input["text"].orEmpty())
    }

    private fun controller(modes: EngineModes = EngineModes(MemoryStore())): PilotController {
        val registry = ToolRegistry().apply { register(EchoTool()) }
        return PilotController(
            registry,
            SecurityPolicy(),
            RecordingApproval(answer = true),
            JsonFileAuditLog(Files.createTempFile("pilot-audit", ".jsonl")),
            modes,
        )
    }

    @Test
    fun `a line with no key is reported without starting a run`() {
        val modes = EngineModes(MemoryStore())
        val controller = controller(modes)
        controller.run("echo", "no-equals")
        assertEquals("Expected key=value, got 'no-equals'.", controller.state.value.error)
        assertFalse(controller.state.value.running)
        assertNull(controller.state.value.result)
        assertFalse(modes.state.value.busy)
    }

    @Test
    fun `key=value input reaches the tool`() {
        val controller = controller()
        controller.run("echo", "text=hi")
        val state = awaitFinished(controller)
        assertEquals("hi", state.result)
        assertNull(state.error)
        assertFalse(state.running)
    }

    private fun awaitFinished(controller: PilotController, timeoutMs: Long = 2_000): PilotUiState {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            val state = controller.state.value
            if (!state.running && (state.result != null || state.error != null)) return state
            Thread.sleep(10)
        }
        error("PilotController.run did not finish: ${controller.state.value}")
    }
}
