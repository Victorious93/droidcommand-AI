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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    private class HoldTool(
        name: String,
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : Tool {
        override val spec = ToolSpec(name = name, description = "Blocks until released")
        override fun execute(input: Map<String, String>): ToolResult {
            entered.countDown()
            check(release.await(8, TimeUnit.SECONDS)) { "hold ${spec.name} was not released" }
            return ToolResult.Success("held")
        }
    }

    private fun controller(modes: EngineModes = EngineModes(MemoryStore()), vararg extra: Tool): PilotController {
        val registry = ToolRegistry().apply {
            register(EchoTool())
            extra.forEach { register(it) }
        }
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

    @Test
    fun `stopping a run does not clear a newer run's busy flag`() {
        val modes = EngineModes(MemoryStore())
        val enteredA = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val enteredB = CountDownLatch(1)
        val releaseB = CountDownLatch(1)
        val controller = controller(
            modes,
            HoldTool("hold_a", enteredA, releaseA),
            HoldTool("hold_b", enteredB, releaseB),
        )

        controller.run("hold_a", "x=1")
        assertTrue(enteredA.await(2, TimeUnit.SECONDS), "first run never started")
        assertTrue(modes.state.value.busy)
        assertTrue(controller.state.value.running)

        controller.stop()
        assertFalse(controller.state.value.running)
        assertFalse(modes.state.value.busy)
        assertEquals("Stopped.", controller.state.value.result)

        controller.run("hold_b", "x=1")
        assertTrue(enteredB.await(2, TimeUnit.SECONDS), "second run never started")
        assertTrue(controller.state.value.running)
        assertTrue(modes.state.value.busy)

        // The first run's finally runs after the second run is already busy.
        // A new run clears the previous "Stopped." result; the old finally must not
        // write over it or clear the newer busy flag.
        releaseA.countDown()
        Thread.sleep(200)
        assertTrue(controller.state.value.running)
        assertTrue(modes.state.value.busy, "an old finally must not clear the newer run")
        assertEquals(null, controller.state.value.result)
        assertEquals(null, controller.state.value.error)

        releaseB.countDown()
        val finished = awaitFinished(controller)
        assertEquals("held", finished.result)
        assertFalse(finished.running)
        assertFalse(modes.state.value.busy)
    }

    @Test
    fun `stop is idempotent and a finished run can start again`() {
        val modes = EngineModes(MemoryStore())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val controller = controller(modes, HoldTool("hold_a", entered, release))
        controller.run("hold_a", "x=1")
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        controller.stop()
        controller.stop()
        assertFalse(controller.state.value.running)
        assertFalse(modes.state.value.busy)
        release.countDown()
        Thread.sleep(200)
        assertFalse(modes.state.value.busy)

        controller.run("echo", "text=hi")
        val state = awaitFinished(controller)
        assertEquals("hi", state.result)
        assertFalse(modes.state.value.busy)
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
