package ai.droidcommand.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class ScriptedPlanner(private val decisions: MutableList<PlannerDecision>) : Planner {
    var invocations = 0
        private set

    override fun decide(
        objective: String,
        context: ConversationContext,
        availableTools: List<ToolSpec>,
        lastObservation: ToolResult?,
    ): PlannerDecision {
        invocations++
        check(decisions.isNotEmpty()) { "ScriptedPlanner ran out of scripted decisions" }
        return decisions.removeAt(0)
    }
}

private class ThrowingPlanner : Planner {
    override fun decide(
        objective: String,
        context: ConversationContext,
        availableTools: List<ToolSpec>,
        lastObservation: ToolResult?,
    ): PlannerDecision = throw RuntimeException("planner exploded")
}

private class AlwaysInvokePlanner(private val toolName: String) : Planner {
    var invocations = 0
        private set

    override fun decide(
        objective: String,
        context: ConversationContext,
        availableTools: List<ToolSpec>,
        lastObservation: ToolResult?,
    ): PlannerDecision {
        invocations++
        return PlannerDecision.InvokeTool(toolName, emptyMap())
    }
}

private class EngineNoopTool(name: String) : Tool {
    override val spec = ToolSpec(name = name, description = "no-op")
    override fun execute(input: Map<String, String>) = ToolResult.Success("noop")
}

private class EnginePartialTool(name: String) : Tool {
    override val spec = ToolSpec(name = name, description = "always returns Partial")
    override fun execute(input: Map<String, String>) = ToolResult.Partial("2 of 4 done", "hit a limit")
}

private class EngineUnexpectedTool(name: String) : Tool {
    override val spec = ToolSpec(name = name, description = "always returns Unexpected")
    override fun execute(input: Map<String, String>) = ToolResult.Unexpected("unrecognized device state")
}

private class EngineFailingTool(name: String) : Tool {
    override val spec = ToolSpec(name = name, description = "always fails")
    override fun execute(input: Map<String, String>) = ToolResult.Failure("nope")
}

private class RecordingLogger : Logger {
    val events = mutableListOf<LogEvent>()
    override fun log(event: LogEvent) {
        events += event
    }
}

private fun newEngine(
    planner: Planner,
    registry: ToolRegistry = ToolRegistry(),
    maxIterations: Int = 25,
    reservedFinalizationIterations: Int = 0,
): Triple<ObjectiveEngine, AgentStateMachine, ToolRegistry> {
    val stateMachine = AgentStateMachine()
    val executor = ToolExecutor(registry, stateMachine, sleep = { })
    val engine = ObjectiveEngine(
        registry,
        executor,
        stateMachine,
        planner,
        maxIterations,
        reservedFinalizationIterations = reservedFinalizationIterations,
    )
    return Triple(engine, stateMachine, registry)
}

class ObjectiveEngineTest {
    @Test
    fun `completes immediately when the planner declares the objective satisfied`() {
        val (engine, _, _) = newEngine(ScriptedPlanner(mutableListOf(PlannerDecision.Complete("done"))))
        val outcome = engine.run("do nothing")
        assertIs<AgentState.Completed>(outcome.finalState)
        assertEquals(1, outcome.iterations)
    }

    @Test
    fun `runs a tool then completes on the planner's next decision`() {
        val registry = ToolRegistry().apply { register(EngineNoopTool("echo")) }
        val planner = ScriptedPlanner(
            mutableListOf(
                PlannerDecision.InvokeTool("echo", emptyMap()),
                PlannerDecision.Complete("done"),
            ),
        )
        val (engine, _, _) = newEngine(planner, registry)
        val outcome = engine.run("echo something")
        assertIs<AgentState.Completed>(outcome.finalState)
        assertEquals(2, outcome.iterations)
        assertEquals(2, planner.invocations)
    }

    @Test
    fun `never exceeds maxIterations when the planner keeps requesting tools`() {
        val registry = ToolRegistry().apply { register(EngineNoopTool("echo")) }
        val planner = AlwaysInvokePlanner("echo")
        val (engine, _, _) = newEngine(planner, registry, maxIterations = 4)
        val outcome = engine.run("never finishes")
        assertIs<AgentState.Failed>(outcome.finalState)
        assertEquals(4, outcome.iterations)
        assertEquals(4, planner.invocations)
    }

    @Test
    fun `aborts with Failed when the planner throws`() {
        val (engine, _, _) = newEngine(ThrowingPlanner())
        val outcome = engine.run("objective")
        assertIs<AgentState.Failed>(outcome.finalState)
    }

    @Test
    fun `aborts with Failed when the planner explicitly aborts`() {
        val (engine, _, _) = newEngine(ScriptedPlanner(mutableListOf(PlannerDecision.Abort("policy blocked this"))))
        val outcome = engine.run("objective")
        assertIs<AgentState.Failed>(outcome.finalState)
    }

    @Test
    fun `stops with Failed when the planner selects an unregistered tool`() {
        val planner = ScriptedPlanner(mutableListOf(PlannerDecision.InvokeTool("does-not-exist", emptyMap())))
        val (engine, _, _) = newEngine(planner)
        val outcome = engine.run("objective")
        assertIs<AgentState.Failed>(outcome.finalState)
    }

    @Test
    fun `replans instead of failing when the planner names an unknown tool, listing what is actually available`() {
        val registry = ToolRegistry().apply { register(EngineNoopTool("echo")) }
        val planner = ScriptedPlanner(
            mutableListOf(
                PlannerDecision.InvokeTool("does-not-exist", emptyMap()),
                PlannerDecision.Complete("done"),
            ),
        )
        val (engine, _, _) = newEngine(planner, registry)
        val outcome = engine.run("objective")

        assertIs<AgentState.Completed>(outcome.finalState)
        assertEquals(2, planner.invocations) // the planner got a second chance, it wasn't just failed outright
    }

    @Test
    fun `a Partial tool result does not fail the objective and is surfaced to the planner`() {
        val registry = ToolRegistry().apply { register(EnginePartialTool("partial")) }
        val planner = ScriptedPlanner(
            mutableListOf(
                PlannerDecision.InvokeTool("partial", emptyMap()),
                PlannerDecision.Complete("done"),
            ),
        )
        val (engine, _, _) = newEngine(planner, registry)
        val outcome = engine.run("do the thing", context = ConversationContext())
        assertIs<AgentState.Completed>(outcome.finalState)
        assertEquals(2, planner.invocations)
    }

    @Test
    fun `a Partial tool result is described distinctly in the conversation context`() {
        val registry = ToolRegistry().apply { register(EnginePartialTool("partial")) }
        val planner = ScriptedPlanner(
            mutableListOf(
                PlannerDecision.InvokeTool("partial", emptyMap()),
                PlannerDecision.Complete("done"),
            ),
        )
        val (engine, _, _) = newEngine(planner, registry)
        val context = ConversationContext()
        engine.run("do the thing", context = context)
        val toolMessage = context.messages.single { it.role == Role.TOOL }
        assertEquals("PARTIAL: 2 of 4 done (hit a limit)", toolMessage.content)
    }

    @Test
    fun `an Unexpected tool result does not fail the objective and is described distinctly`() {
        val registry = ToolRegistry().apply { register(EngineUnexpectedTool("unexpected")) }
        val planner = ScriptedPlanner(
            mutableListOf(
                PlannerDecision.InvokeTool("unexpected", emptyMap()),
                PlannerDecision.Complete("done"),
            ),
        )
        val (engine, _, _) = newEngine(planner, registry)
        val context = ConversationContext()
        val outcome = engine.run("do the thing", context = context)
        assertIs<AgentState.Completed>(outcome.finalState)
        val toolMessage = context.messages.single { it.role == Role.TOOL }
        assertEquals("UNEXPECTED: unrecognized device state", toolMessage.content)
    }

    @Test
    fun `with no logger given, nothing is logged and behavior is unchanged`() {
        val registry = ToolRegistry().apply { register(EngineNoopTool("echo")) }
        val planner = ScriptedPlanner(
            mutableListOf(
                PlannerDecision.InvokeTool("echo", emptyMap()),
                PlannerDecision.Complete("done"),
            ),
        )
        val (engine, _, _) = newEngine(planner, registry)
        val outcome = engine.run("do the thing")
        assertIs<AgentState.Completed>(outcome.finalState)
    }

    @Test
    fun `logs objective_started, tool_result, and objective_completed when a logger is given`() {
        val registry = ToolRegistry().apply { register(EngineNoopTool("echo")) }
        val planner = ScriptedPlanner(
            mutableListOf(
                PlannerDecision.InvokeTool("echo", emptyMap()),
                PlannerDecision.Complete("done"),
            ),
        )
        val stateMachine = AgentStateMachine()
        val executor = ToolExecutor(registry, stateMachine, sleep = { })
        val logger = RecordingLogger()
        val engine = ObjectiveEngine(registry, executor, stateMachine, planner, logger = logger)

        engine.run("do the thing")

        val messages = logger.events.map { it.message }
        assertEquals(listOf("objective_started", "tool_result", "objective_completed"), messages)
        assertEquals(LogLevel.INFO, logger.events.single { it.message == "tool_result" }.level)
    }

    @Test
    fun `logs a Failure tool result at ERROR level`() {
        val registry = ToolRegistry().apply { register(EngineFailingTool("fails")) }
        val planner = ScriptedPlanner(
            mutableListOf(
                PlannerDecision.InvokeTool("fails", emptyMap()),
                PlannerDecision.Complete("done"),
            ),
        )
        val stateMachine = AgentStateMachine()
        val executor = ToolExecutor(registry, stateMachine, sleep = { })
        val logger = RecordingLogger()
        val engine = ObjectiveEngine(registry, executor, stateMachine, planner, logger = logger)

        engine.run("do the thing")

        assertEquals(LogLevel.ERROR, logger.events.single { it.message == "tool_result" }.level)
    }

    @Test
    fun `logs objective_aborted when the planner aborts`() {
        val stateMachine = AgentStateMachine()
        val executor = ToolExecutor(ToolRegistry(), stateMachine, sleep = { })
        val logger = RecordingLogger()
        val engine = ObjectiveEngine(ToolRegistry(), executor, stateMachine, ScriptedPlanner(mutableListOf(PlannerDecision.Abort("policy blocked this"))), logger = logger)

        engine.run("objective")

        val aborted = logger.events.single { it.message == "objective_aborted" }
        assertEquals(LogLevel.WARN, aborted.level)
        assertEquals("policy blocked this", aborted.fields["reason"])
    }

    @Test
    fun `honors cancellation before the first iteration`() {
        val (engine, stateMachine, _) = newEngine(AlwaysInvokePlanner("echo"))
        val outcome = engine.run("objective", isCancelled = { true })
        assertIs<AgentState.Cancelled>(outcome.finalState)
        assertEquals(0, outcome.iterations)
        assertEquals(stateMachine.state, outcome.finalState)
    }

    @Test
    fun `reservedFinalizationIterations defaults to 0 and never appends a nudge`() {
        val registry = ToolRegistry().apply { register(EngineNoopTool("echo")) }
        val planner = AlwaysInvokePlanner("echo")
        val (engine, _, _) = newEngine(planner, registry, maxIterations = 4)
        val context = ConversationContext()

        val outcome = engine.run("never finishes", context = context)

        assertIs<AgentState.Failed>(outcome.finalState)
        assertEquals(4, outcome.iterations)
        assertEquals(4, planner.invocations)
        assertEquals(0, context.messages.count { it.role == Role.SYSTEM })
    }

    @Test
    fun `appends a step-budget nudge for each iteration inside the reserved window`() {
        val registry = ToolRegistry().apply { register(EngineNoopTool("echo")) }
        val planner = AlwaysInvokePlanner("echo")
        val (engine, _, _) = newEngine(planner, registry, maxIterations = 4, reservedFinalizationIterations = 2)
        val context = ConversationContext()

        engine.run("never finishes", context = context)

        val systemMessages = context.messages.filter { it.role == Role.SYSTEM }
        assertEquals(2, systemMessages.size)
        assertTrue(systemMessages[0].content.contains("2 iteration(s) remain"))
        assertTrue(systemMessages[1].content.contains("1 iteration(s) remain"))

        val messages = context.messages
        for (systemMessage in systemMessages) {
            val index = messages.indexOf(systemMessage)
            assertTrue(messages[index + 1].role == Role.ASSISTANT && messages[index + 1].content.contains("Invoking tool"))
        }
    }

    @Test
    fun `completes with fewer than maxIterations when the planner responds to the finalization nudge`() {
        val registry = ToolRegistry().apply { register(EngineNoopTool("echo")) }
        val planner = ScriptedPlanner(
            mutableListOf(
                PlannerDecision.InvokeTool("echo", emptyMap()),
                PlannerDecision.Complete("best-effort summary"),
            ),
        )
        val (engine, _, _) = newEngine(planner, registry, maxIterations = 3, reservedFinalizationIterations = 2)

        val outcome = engine.run("never finishes")

        assertIs<AgentState.Completed>(outcome.finalState)
        assertEquals(2, outcome.iterations)
        assertEquals(2, planner.invocations)
    }

    @Test
    fun `still ends in Failed, never fabricating Completed, when the planner ignores the finalization nudge`() {
        val registry = ToolRegistry().apply { register(EngineNoopTool("echo")) }
        val planner = AlwaysInvokePlanner("echo")
        val (engine, _, _) = newEngine(planner, registry, maxIterations = 4, reservedFinalizationIterations = 2)
        val context = ConversationContext()

        val outcome = engine.run("never finishes", context = context)

        assertIs<AgentState.Failed>(outcome.finalState)
        assertEquals(4, outcome.iterations)
        assertEquals(4, planner.invocations)
        assertEquals(2, context.messages.count { it.role == Role.SYSTEM })
    }

    @Test
    fun `rejects a negative reservedFinalizationIterations`() {
        val stateMachine = AgentStateMachine()
        val registry = ToolRegistry()
        val executor = ToolExecutor(registry, stateMachine, sleep = { })
        assertFailsWith<IllegalArgumentException> {
            ObjectiveEngine(registry, executor, stateMachine, AlwaysInvokePlanner("echo"), reservedFinalizationIterations = -1)
        }
    }

    @Test
    fun `rejects reservedFinalizationIterations equal to maxIterations`() {
        val stateMachine = AgentStateMachine()
        val registry = ToolRegistry()
        val executor = ToolExecutor(registry, stateMachine, sleep = { })
        assertFailsWith<IllegalArgumentException> {
            ObjectiveEngine(registry, executor, stateMachine, AlwaysInvokePlanner("echo"), maxIterations = 4, reservedFinalizationIterations = 4)
        }
    }

    @Test
    fun `accepts reservedFinalizationIterations one less than maxIterations`() {
        val stateMachine = AgentStateMachine()
        val registry = ToolRegistry()
        val executor = ToolExecutor(registry, stateMachine, sleep = { })
        ObjectiveEngine(registry, executor, stateMachine, AlwaysInvokePlanner("echo"), maxIterations = 4, reservedFinalizationIterations = 3)
    }

    @Test
    fun `cancellation on the iteration that would enter the reserved window still gets no nudge`() {
        // isCancelled is also threaded through to the tool executor, which checks it once per
        // attempt (RetryPolicy defaults to 1 attempt) — so iteration 1's normal engine-level check
        // (call 1) plus its successful tool invocation's own check (call 2) both return false
        // before iteration 2's engine-level check (call 3) triggers cancellation right at the
        // start of the one iteration that would otherwise enter the reserved window.
        val registry = ToolRegistry().apply { register(EngineNoopTool("echo")) }
        val planner = AlwaysInvokePlanner("echo")
        val (engine, _, _) = newEngine(planner, registry, maxIterations = 2, reservedFinalizationIterations = 1)
        val context = ConversationContext()
        var calls = 0
        val isCancelled = {
            calls++
            calls == 3
        }

        val outcome = engine.run("never finishes", context = context, isCancelled = isCancelled)

        assertIs<AgentState.Cancelled>(outcome.finalState)
        assertEquals(1, outcome.iterations)
        assertTrue(context.messages.none { it.role == Role.SYSTEM })
    }

    @Test
    fun `logs finalization_nudge_sent at INFO with iteration, remaining, and maxIterations fields`() {
        val registry = ToolRegistry().apply { register(EngineNoopTool("echo")) }
        val planner = AlwaysInvokePlanner("echo")
        val stateMachine = AgentStateMachine()
        val executor = ToolExecutor(registry, stateMachine, sleep = { })
        val logger = RecordingLogger()
        val engine = ObjectiveEngine(registry, executor, stateMachine, planner, maxIterations = 4, logger = logger, reservedFinalizationIterations = 2)

        engine.run("never finishes")

        val nudgeEvents = logger.events.filter { it.message == "finalization_nudge_sent" }
        assertEquals(2, nudgeEvents.size)
        assertTrue(nudgeEvents.all { it.level == LogLevel.INFO })
        assertEquals("2", nudgeEvents[0].fields["remaining"])
        assertEquals("1", nudgeEvents[1].fields["remaining"])
        assertTrue(nudgeEvents.all { it.fields["maxIterations"] == "4" })
    }
}
