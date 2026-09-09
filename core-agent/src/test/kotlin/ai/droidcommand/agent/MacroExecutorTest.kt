package ai.droidcommand.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

private class RecordingTool(private val outcome: (Int) -> ToolResult) : Tool {
    override val spec = ToolSpec(name = "recording", description = "records invocations and returns a scripted outcome")
    var invocations = 0
        private set
    val seenInputs = mutableListOf<Map<String, String>>()

    override fun execute(input: Map<String, String>): ToolResult {
        invocations++
        seenInputs += input
        return outcome(invocations)
    }
}

private class NamedFailingTool(private val toolName: String = "always-fails") : Tool {
    override val spec = ToolSpec(name = toolName, description = "always fails")
    var invocations = 0
        private set
    override fun execute(input: Map<String, String>): ToolResult {
        invocations++
        return ToolResult.Failure("nope")
    }
}

private class MacroRecordingLogger : Logger {
    val events = mutableListOf<LogEvent>()
    override fun log(event: LogEvent) {
        events += event
    }
}

class MacroExecutorTest {
    @Test
    fun `runs every step in order and reports Completed`() {
        val tool = RecordingTool { ToolResult.Success("step $it") }
        val registry = ToolRegistry().apply { register(tool) }
        val executor = ToolExecutor(registry, AgentStateMachine(), sleep = { })
        val macro = Macro(
            "greet-twice",
            listOf(
                MacroStep("recording", mapOf("n" to "1")),
                MacroStep("recording", mapOf("n" to "2")),
            ),
        )

        val outcome = MacroExecutor(executor).run(macro)

        assertIs<MacroOutcome.Completed>(outcome)
        assertEquals(2, outcome.completedSteps.size)
        assertEquals(2, tool.invocations)
        assertEquals(listOf(mapOf("n" to "1"), mapOf("n" to "2")), tool.seenInputs)
    }

    @Test
    fun `stops on the first Failure and never runs later steps`() {
        val failing = NamedFailingTool("first")
        val neverRun = RecordingTool { ToolResult.Success("should not run") }
        val registry = ToolRegistry().apply {
            register(failing)
            register(neverRun)
        }
        val executor = ToolExecutor(registry, AgentStateMachine(), sleep = { })
        val macro = Macro("two-steps", listOf(MacroStep("first", emptyMap()), MacroStep("recording", emptyMap())))

        val outcome = MacroExecutor(executor).run(macro)

        assertIs<MacroOutcome.StoppedOnFailure>(outcome)
        assertEquals(1, outcome.completedSteps.size) // includes the failed step's own outcome
        assertSame(macro.steps[0], outcome.failedStep)
        assertEquals(0, neverRun.invocations)
    }

    @Test
    fun `a Partial result does not stop playback`() {
        val tool = RecordingTool { invocation ->
            if (invocation == 1) ToolResult.Partial("partial", "ran out of time") else ToolResult.Success("done")
        }
        val registry = ToolRegistry().apply { register(tool) }
        val executor = ToolExecutor(registry, AgentStateMachine(), sleep = { })
        val macro = Macro("two-steps", listOf(MacroStep("recording", emptyMap()), MacroStep("recording", emptyMap())))

        val outcome = MacroExecutor(executor).run(macro)

        assertIs<MacroOutcome.Completed>(outcome)
        assertEquals(2, tool.invocations)
    }

    @Test
    fun `an Unexpected result does not stop playback`() {
        val tool = RecordingTool { invocation ->
            if (invocation == 1) ToolResult.Unexpected("odd state") else ToolResult.Success("done")
        }
        val registry = ToolRegistry().apply { register(tool) }
        val executor = ToolExecutor(registry, AgentStateMachine(), sleep = { })
        val macro = Macro("two-steps", listOf(MacroStep("recording", emptyMap()), MacroStep("recording", emptyMap())))

        val outcome = MacroExecutor(executor).run(macro)

        assertIs<MacroOutcome.Completed>(outcome)
        assertEquals(2, tool.invocations)
    }

    @Test
    fun `an unknown tool name stops the macro as a Failure instead of throwing`() {
        val registry = ToolRegistry()
        val executor = ToolExecutor(registry, AgentStateMachine(), sleep = { })
        val macro = Macro("bad", listOf(MacroStep("does-not-exist", emptyMap())))

        val outcome = MacroExecutor(executor).run(macro)

        assertIs<MacroOutcome.StoppedOnFailure>(outcome)
        assertEquals(0, outcome.completedSteps.size)
        assertEquals("does-not-exist", outcome.failedStep.toolName)
    }

    @Test
    fun `cancellation before a step stops the macro as Cancelled`() {
        val tool = RecordingTool { ToolResult.Success("ran") }
        val registry = ToolRegistry().apply { register(tool) }
        val executor = ToolExecutor(registry, AgentStateMachine(), sleep = { })
        val macro = Macro("two-steps", listOf(MacroStep("recording", emptyMap()), MacroStep("recording", emptyMap())))

        val outcome = MacroExecutor(executor).run(macro, isCancelled = { true })

        assertIs<MacroOutcome.Cancelled>(outcome)
        assertEquals(0, outcome.completedSteps.size)
        assertEquals(0, tool.invocations)
    }

    @Test
    fun `passes mode and initiator through to each step`() {
        val tool = ForgeOnlyAndOwnerOnlyTool()
        val registry = ToolRegistry().apply { register(tool) }
        val executor = ToolExecutor(registry, AgentStateMachine(), sleep = { })
        val macro = Macro("one-step", listOf(MacroStep("scoped", emptyMap())))

        val rejected = MacroExecutor(executor).run(macro, mode = AgentMode.PILOT, initiator = Initiator.AI)
        assertIs<MacroOutcome.StoppedOnFailure>(rejected)
        assertEquals(0, tool.invocations)

        val permitted = MacroExecutor(executor).run(macro, mode = AgentMode.FORGE, initiator = Initiator.DEVICE_OWNER)
        assertIs<MacroOutcome.Completed>(permitted)
        assertEquals(1, tool.invocations)
    }

    @Test
    fun `logs macro_started and macro_completed on a full run`() {
        val tool = RecordingTool { ToolResult.Success("ran") }
        val registry = ToolRegistry().apply { register(tool) }
        val executor = ToolExecutor(registry, AgentStateMachine(), sleep = { })
        val logger = MacroRecordingLogger()
        val macro = Macro("one-step", listOf(MacroStep("recording", emptyMap())))

        MacroExecutor(executor, logger).run(macro)

        assertEquals(1, logger.events.count { it.message == "macro_started" })
        assertEquals(1, logger.events.count { it.message == "macro_completed" })
    }

    @Test
    fun `logs macro_stopped_on_failure when a step fails`() {
        val registry = ToolRegistry().apply { register(NamedFailingTool()) }
        val executor = ToolExecutor(registry, AgentStateMachine(), sleep = { })
        val logger = MacroRecordingLogger()
        val macro = Macro("one-step", listOf(MacroStep("always-fails", emptyMap())))

        MacroExecutor(executor, logger).run(macro)

        val event = logger.events.single { it.message == "macro_stopped_on_failure" }
        assertEquals(LogLevel.WARN, event.level)
    }
}

private class ForgeOnlyAndOwnerOnlyTool : Tool {
    override val spec = ToolSpec(
        name = "scoped",
        description = "scoped to Forge + the device owner",
        allowedModes = setOf(AgentMode.FORGE),
        requiredInitiator = setOf(Initiator.DEVICE_OWNER),
    )
    var invocations = 0
        private set
    override fun execute(input: Map<String, String>): ToolResult {
        invocations++
        return ToolResult.Success("ran")
    }
}
