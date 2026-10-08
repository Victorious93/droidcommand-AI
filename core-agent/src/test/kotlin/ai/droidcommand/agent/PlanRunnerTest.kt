package ai.droidcommand.agent

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class PlanScriptedPlanner(private val decisions: MutableList<PlannerDecision>) : Planner {
    override fun decide(
        objective: String,
        context: ConversationContext,
        availableTools: List<ToolSpec>,
        lastObservation: ToolResult?,
    ): PlannerDecision = decisions.removeAt(0)
}

private class PlanFixedTool(name: String, private val result: ToolResult) : Tool {
    override val spec = ToolSpec(name = name, description = "fixed result")
    override fun execute(input: Map<String, String>) = result
}

class PlanRunnerTest {
    private fun registry(vararg tools: Tool) = ToolRegistry().apply { tools.forEach { register(it) } }

    private fun runner(registry: ToolRegistry, planner: Planner, maxIterations: Int = 15) =
        PlanRunner(
            registry = registry,
            planner = planner,
            newRunner = { machine -> ToolExecutor(registry, machine) },
            maxIterations = maxIterations,
            reservedFinalizationIterations = 0,
            now = { Instant.EPOCH },
            newId = { "run-1" },
        )

    @Test
    fun `records each tool invocation in order and completes with the planner summary`() {
        val registry = registry(
            PlanFixedTool("ok", ToolResult.Success("fine")),
            PlanFixedTool("half", ToolResult.Partial("2 of 4", "limit")),
            PlanFixedTool("bad", ToolResult.Failure("denied")),
        )
        val planner = PlanScriptedPlanner(
            mutableListOf(
                PlannerDecision.InvokeTool("ok", mapOf("a" to "1")),
                PlannerDecision.InvokeTool("half", emptyMap()),
                PlannerDecision.InvokeTool("bad", emptyMap()),
                PlannerDecision.Complete("all done"),
            ),
        )

        val run = runner(registry, planner).run("do things")

        assertEquals("run-1", run.id)
        assertEquals("do things", run.objective)
        assertEquals(PlanStatus.COMPLETED, run.status)
        assertEquals("all done", run.summary)
        assertEquals(listOf("ok", "half", "bad"), run.steps.map { it.toolName })
        assertEquals(listOf(1, 2, 3), run.steps.map { it.index })
        assertEquals(
            listOf(PlanStepStatus.SUCCEEDED, PlanStepStatus.PARTIAL, PlanStepStatus.FAILED),
            run.steps.map { it.status },
        )
        assertEquals(mapOf("a" to "1"), run.steps[0].input)
        assertEquals("fine", run.steps[0].output)
        assertEquals("ERROR: denied", run.steps[2].output)
        assertNotNull(run.finishedAt)
    }

    @Test
    fun `emits a running snapshot first and a running-then-finished snapshot per step`() {
        val registry = registry(PlanFixedTool("ok", ToolResult.Success("fine")))
        val planner = PlanScriptedPlanner(mutableListOf(PlannerDecision.InvokeTool("ok", emptyMap()), PlannerDecision.Complete("x")))
        val seen = mutableListOf<PlanRun>()

        runner(registry, planner).run("o", onUpdate = { seen += it })

        // started, step running, step finished, run finished
        assertEquals(4, seen.size)
        assertEquals(PlanStatus.RUNNING, seen[0].status)
        assertTrue(seen[0].steps.isEmpty())
        assertEquals(PlanStepStatus.RUNNING, seen[1].steps.single().status)
        assertNull(seen[1].steps.single().output)
        assertEquals(PlanStepStatus.SUCCEEDED, seen[2].steps.single().status)
        assertEquals(PlanStatus.COMPLETED, seen[3].status)
    }

    @Test
    fun `an abort from the planner is a failed run carrying the reason`() {
        val run = runner(registry(), PlanScriptedPlanner(mutableListOf(PlannerDecision.Abort("no key")))).run("o")

        assertEquals(PlanStatus.FAILED, run.status)
        assertEquals("no key", run.summary)
        assertTrue(run.steps.isEmpty())
    }

    @Test
    fun `an unknown tool becomes a failed step and the run can still complete`() {
        val planner = PlanScriptedPlanner(
            mutableListOf(PlannerDecision.InvokeTool("ghost", emptyMap()), PlannerDecision.Complete("gave up")),
        )

        val run = runner(registry(), planner).run("o")

        assertEquals(PlanStatus.COMPLETED, run.status)
        assertEquals(PlanStepStatus.FAILED, run.steps.single().status)
    }

    @Test
    fun `a run that never finishes fails after maxIterations`() {
        val registry = registry(PlanFixedTool("ok", ToolResult.Success("fine")))
        val planner = object : Planner {
            override fun decide(objective: String, context: ConversationContext, availableTools: List<ToolSpec>, lastObservation: ToolResult?) =
                PlannerDecision.InvokeTool("ok", emptyMap())
        }

        val run = runner(registry, planner, maxIterations = 3).run("o")

        assertEquals(PlanStatus.FAILED, run.status)
        assertEquals(3, run.steps.size)
    }

    @Test
    fun `cancelling before the first iteration yields a cancelled run`() {
        val run = runner(registry(), PlanScriptedPlanner(mutableListOf())).run("o", isCancelled = { true })

        assertEquals(PlanStatus.CANCELLED, run.status)
        assertTrue(run.steps.isEmpty())
    }

    @Test
    fun `two runs on one runner each get their own state machine`() {
        val registry = registry(PlanFixedTool("ok", ToolResult.Success("fine")))
        val r = runner(registry, PlanScriptedPlanner(mutableListOf(PlannerDecision.Complete("a"), PlannerDecision.Complete("b"))))

        // A shared terminal-sticky machine would throw IllegalAgentTransition on the second run.
        assertEquals("a", r.run("one").summary)
        assertEquals("b", r.run("two").summary)
    }
}
