package ai.droidcommand.llm

import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.ObjectiveEngine
import ai.droidcommand.agent.TaskGraphOutcome
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private class ScriptedAnalysisLlmProvider(private val responses: MutableList<LlmResponse>) : LlmProvider {
    override val config = LlmConfig(provider = "scripted", model = "scripted-1")
    val requests = mutableListOf<LlmRequest>()

    override fun complete(request: LlmRequest): LlmResponse {
        requests += request
        check(responses.isNotEmpty()) { "ScriptedAnalysisLlmProvider ran out of scripted responses" }
        return responses.removeAt(0)
    }
}

/**
 * Proves the full analyze -> dependency-ordered multi-task execution loop
 * works end to end using real `core-agent`/`core-llm` collaborators (real
 * [ObjectiveAnalyzer], [ai.droidcommand.agent.TaskGraph],
 * [ai.droidcommand.agent.TaskGraphExecutor], [ObjectiveEngine], [LlmPlanner]),
 * driven only by a scripted fake provider since no real LLM credentials
 * exist in this environment — the same "honest substitute for a live-model
 * test" pattern [ObjectiveEngineIntegrationTest] already established.
 *
 * This is also where [ai.droidcommand.agent.TaskGraphExecutor]'s
 * fresh-`AgentStateMachine`/`ToolExecutor`-per-task requirement gets a
 * real, not just documented, test: the [ai.droidcommand.agent.TaskRunner]
 * below constructs both fresh per task and would throw
 * `IllegalAgentTransition` on task B's first tool-executor transition if it
 * mistakenly reused task A's.
 */
class AnalyzedObjectiveIntegrationTest {
    @Test
    fun `a two-task objective is analyzed then both tasks run in dependency order`() {
        val provider = ScriptedAnalysisLlmProvider(
            mutableListOf(
                LlmResponse.Text(
                    """{"tasks":[{"id":"a","description":"Do task A"},{"id":"b","description":"Do task B","dependencies":["a"]}]}""",
                ),
                LlmResponse.Text("Task A complete"),
                LlmResponse.Text("Task B complete"),
            ),
        )
        val registry = ToolRegistry()
        val planner = LlmPlanner(provider)
        val runner = AnalyzedObjectiveRunner(ObjectiveAnalyzer(provider))

        val outcome = runner.run(
            "Build a thing in two steps",
            runTask = { task ->
                val stateMachine = AgentStateMachine()
                val executor = ToolExecutor(registry, stateMachine, sleep = { })
                ObjectiveEngine(registry, executor, stateMachine, planner).run(task.description)
            },
        )

        val executed = assertIs<AnalyzedObjectiveOutcome.Executed>(outcome)
        val completed = assertIs<TaskGraphOutcome.Completed>(executed.taskGraphOutcome)
        assertEquals(listOf("a", "b"), completed.completedTasks.map { it.task.id })
        // 1 analysis call + 1 planner call per task
        assertEquals(3, provider.requests.size)
    }
}
