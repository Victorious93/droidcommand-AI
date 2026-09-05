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
    fun `honors cancellation before the first iteration`() {
        val (engine, stateMachine, _) = newEngine(AlwaysInvokePlanner("echo"))
        val outcome = engine.run("objective", isCancelled = { true })
        assertIs<AgentState.Cancelled>(outcome.finalState)
        assertEquals(0, outcome.iterations)
        assertEquals(stateMachine.state, outcome.finalState)
    }
}
