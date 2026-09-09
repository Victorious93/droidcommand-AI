package ai.droidcommand.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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

private class CapturingPlanner : Planner {
    val contextsSeen = mutableListOf<ConversationContext>()

    override fun decide(
        objective: String,
        context: ConversationContext,
        availableTools: List<ToolSpec>,
        lastObservation: ToolResult?,
    ): PlannerDecision {
        contextsSeen += context
        return PlannerDecision.Complete("done")
    }
}

private class ThrowingAnalyzer : ObjectiveAnalyzer {
    override fun analyze(objective: String): ObjectiveAnalysis = throw RuntimeException("analysis exploded")
}

private class RecordingLogger : Logger {
    val events = mutableListOf<LogEvent>()
    override fun log(event: LogEvent) {
        events += event
    }
}

private fun newEngine(planner: Planner, registry: ToolRegistry = ToolRegistry(), maxIterations: Int = 25): Triple<ObjectiveEngine, AgentStateMachine, ToolRegistry> {
    val stateMachine = AgentStateMachine()
    val executor = ToolExecutor(registry, stateMachine, sleep = { })
    return Triple(ObjectiveEngine(registry, executor, stateMachine, planner, maxIterations), stateMachine, registry)
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
    fun `with no analyzer given, no analysis is added to the context`() {
        val planner = CapturingPlanner()
        val stateMachine = AgentStateMachine()
        val executor = ToolExecutor(ToolRegistry(), stateMachine, sleep = { })
        val engine = ObjectiveEngine(ToolRegistry(), executor, stateMachine, planner)
        val context = ConversationContext()

        engine.run("objective", context = context)

        assertEquals(true, context.messages.none { it.content.contains("Objective analysis") })
    }

    @Test
    fun `an analyzer's analysis is appended to the context before the first planning call`() {
        val planner = CapturingPlanner()
        val stateMachine = AgentStateMachine()
        val executor = ToolExecutor(ToolRegistry(), stateMachine, sleep = { })
        val analyzer = ObjectiveAnalyzer {
            ObjectiveAnalysis(requirements = listOf("do the thing"), tasks = listOf("step 1", "step 2"))
        }
        val engine = ObjectiveEngine(ToolRegistry(), executor, stateMachine, planner, analyzer = analyzer)
        val context = ConversationContext()

        engine.run("objective", context = context)

        val analysisMessage = context.messages.single { it.content.startsWith("Objective analysis:") }
        assertEquals(true, analysisMessage.content.contains("Requirements:\n- do the thing"))
        assertEquals(true, analysisMessage.content.contains("Tasks:\n- step 1\n- step 2"))
        assertEquals(false, analysisMessage.content.contains("Constraints:"))
        // The planner's very first call already saw the analysis in context.
        assertEquals(true, planner.contextsSeen.first().messages.any { it.content.startsWith("Objective analysis:") })
    }

    @Test
    fun `logs objective_analyzed with counts per category`() {
        val planner = CapturingPlanner()
        val stateMachine = AgentStateMachine()
        val executor = ToolExecutor(ToolRegistry(), stateMachine, sleep = { })
        val analyzer = ObjectiveAnalyzer { ObjectiveAnalysis(requirements = listOf("a", "b"), tasks = listOf("c")) }
        val logger = RecordingLogger()
        val engine = ObjectiveEngine(ToolRegistry(), executor, stateMachine, planner, logger = logger, analyzer = analyzer)

        engine.run("objective")

        val event = logger.events.single { it.message == "objective_analyzed" }
        assertEquals("2", event.fields["requirements"])
        assertEquals("1", event.fields["tasks"])
        assertEquals("0", event.fields["constraints"])
    }

    @Test
    fun `fails the objective when the analyzer throws, without ever calling the planner`() {
        val planner = CapturingPlanner()
        val stateMachine = AgentStateMachine()
        val executor = ToolExecutor(ToolRegistry(), stateMachine, sleep = { })
        val engine = ObjectiveEngine(ToolRegistry(), executor, stateMachine, planner, analyzer = ThrowingAnalyzer())

        val outcome = engine.run("objective")

        assertIs<AgentState.Failed>(outcome.finalState)
        assertEquals(0, planner.contextsSeen.size)
    }
}
