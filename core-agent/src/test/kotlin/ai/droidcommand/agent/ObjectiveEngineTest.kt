package ai.droidcommand.agent

import kotlin.test.Test
import kotlin.test.assertEquals
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

/** Records every [lastObservation] it was handed, so a test can inspect what the engine reported back. */
private class ObservationCapturingPlanner(private val decisions: MutableList<PlannerDecision>) : Planner {
    val observations = mutableListOf<ToolResult?>()

    override fun decide(
        objective: String,
        context: ConversationContext,
        availableTools: List<ToolSpec>,
        lastObservation: ToolResult?,
    ): PlannerDecision {
        observations += lastObservation
        check(decisions.isNotEmpty()) { "ObservationCapturingPlanner ran out of scripted decisions" }
        return decisions.removeAt(0)
    }
}

private class EngineNoopTool(name: String) : Tool {
    override val spec = ToolSpec(name = name, description = "no-op")
    override fun execute(input: Map<String, String>) = ToolResult.Success("noop")
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
    fun `recovers when the planner selects an unregistered tool, then completes once it names a real one`() {
        // A hallucinated/stale tool name from the planner is a recoverable
        // planning mistake, not a fatal engine error (OD-001's "re-prompt with
        // the real tool allowlist" pattern) — the objective must not abort
        // over a single bad name when a valid one follows.
        val registry = ToolRegistry().apply { register(EngineNoopTool("echo")) }
        val planner = ScriptedPlanner(
            mutableListOf(
                PlannerDecision.InvokeTool("does-not-exist", emptyMap()),
                PlannerDecision.InvokeTool("echo", emptyMap()),
                PlannerDecision.Complete("done"),
            ),
        )
        val (engine, _, _) = newEngine(planner, registry)
        val outcome = engine.run("objective")
        assertIs<AgentState.Completed>(outcome.finalState)
        assertEquals(3, outcome.iterations)
        assertEquals(3, planner.invocations)
    }

    @Test
    fun `the next planner call sees the unknown-tool failure and the real registered tool names`() {
        val registry = ToolRegistry().apply { register(EngineNoopTool("echo")) }
        val planner = ObservationCapturingPlanner(
            mutableListOf(
                PlannerDecision.InvokeTool("does-not-exist", emptyMap()),
                PlannerDecision.Complete("done"),
            ),
        )
        val (engine, _, _) = newEngine(planner, registry)
        engine.run("objective")

        assertEquals(2, planner.observations.size)
        assertEquals(null, planner.observations[0])
        val failure = assertIs<ToolResult.Failure>(planner.observations[1])
        assertTrue(failure.reason.contains("does-not-exist"))
        assertTrue(failure.reason.contains("echo"))
    }

    @Test
    fun `a planner that keeps repeating an unregistered tool still only terminates via maxIterations`() {
        val planner = AlwaysInvokePlanner("does-not-exist")
        val (engine, _, _) = newEngine(planner, maxIterations = 3)
        val outcome = engine.run("objective")
        assertIs<AgentState.Failed>(outcome.finalState)
        assertEquals(3, outcome.iterations)
        assertEquals(3, planner.invocations)
    }

    @Test
    fun `honors cancellation before the first iteration`() {
        val (engine, stateMachine, _) = newEngine(AlwaysInvokePlanner("echo"))
        val outcome = engine.run("objective", isCancelled = { true })
        assertIs<AgentState.Cancelled>(outcome.finalState)
        assertEquals(0, outcome.iterations)
        assertEquals(stateMachine.state, outcome.finalState)
    }
}
