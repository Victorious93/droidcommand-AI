package ai.droidcommand.llm

import ai.droidcommand.agent.AgentState
import ai.droidcommand.agent.LogEvent
import ai.droidcommand.agent.LogLevel
import ai.droidcommand.agent.Logger
import ai.droidcommand.agent.ObjectiveOutcome
import ai.droidcommand.agent.Task
import ai.droidcommand.agent.TaskGraphOutcome
import ai.droidcommand.agent.TaskRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private class RunnerFakeLlmProvider(private val response: LlmResponse) : LlmProvider {
    override val config = LlmConfig(provider = "fake", model = "fake-1")

    override fun complete(request: LlmRequest): LlmResponse = response
}

private class RecordingTaskRunner(private val outcomeFor: (Task) -> ObjectiveOutcome) : TaskRunner {
    val seenTasks = mutableListOf<Task>()

    override fun run(task: Task): ObjectiveOutcome {
        seenTasks += task
        return outcomeFor(task)
    }
}

private class RunnerRecordingLogger : Logger {
    val events = mutableListOf<LogEvent>()

    override fun log(event: LogEvent) {
        events += event
    }
}

class AnalyzedObjectiveRunnerTest {
    @Test
    fun `a successful analysis whose every task succeeds reports Executed with Completed`() {
        val provider = RunnerFakeLlmProvider(LlmResponse.Text("""{"tasks":[{"id":"a","description":"a"}]}"""))
        val runner = AnalyzedObjectiveRunner(ObjectiveAnalyzer(provider))
        val taskRunner = RecordingTaskRunner { ObjectiveOutcome(AgentState.Completed("done"), 1) }

        val outcome = runner.run("build a thing", taskRunner)

        val executed = assertIs<AnalyzedObjectiveOutcome.Executed>(outcome)
        assertIs<TaskGraphOutcome.Completed>(executed.taskGraphOutcome)
        assertEquals(listOf("a"), taskRunner.seenTasks.map { it.id })
    }

    @Test
    fun `a provider failure reports AnalysisFailed and never invokes runTask`() {
        val provider = RunnerFakeLlmProvider(LlmResponse.Error(LlmError.Authentication("bad key")))
        val runner = AnalyzedObjectiveRunner(ObjectiveAnalyzer(provider))
        val taskRunner = RecordingTaskRunner { ObjectiveOutcome(AgentState.Completed("done"), 1) }

        val outcome = runner.run("build a thing", taskRunner)

        val failed = assertIs<AnalyzedObjectiveOutcome.AnalysisFailed>(outcome)
        assertIs<ObjectiveAnalysisResult.ProviderFailed>(failed.result)
        assertEquals(0, taskRunner.seenTasks.size)
    }

    @Test
    fun `malformed analysis reports AnalysisFailed and never invokes runTask`() {
        val provider = RunnerFakeLlmProvider(LlmResponse.Text("not json"))
        val runner = AnalyzedObjectiveRunner(ObjectiveAnalyzer(provider))
        val taskRunner = RecordingTaskRunner { ObjectiveOutcome(AgentState.Completed("done"), 1) }

        val outcome = runner.run("build a thing", taskRunner)

        val failed = assertIs<AnalyzedObjectiveOutcome.AnalysisFailed>(outcome)
        assertIs<ObjectiveAnalysisResult.Malformed>(failed.result)
        assertEquals(0, taskRunner.seenTasks.size)
    }

    @Test
    fun `an invalid task graph reports AnalysisFailed and never invokes runTask`() {
        val provider = RunnerFakeLlmProvider(LlmResponse.Text("""{"tasks":[]}"""))
        val runner = AnalyzedObjectiveRunner(ObjectiveAnalyzer(provider))
        val taskRunner = RecordingTaskRunner { ObjectiveOutcome(AgentState.Completed("done"), 1) }

        val outcome = runner.run("build a thing", taskRunner)

        val failed = assertIs<AnalyzedObjectiveOutcome.AnalysisFailed>(outcome)
        assertIs<ObjectiveAnalysisResult.InvalidTaskGraph>(failed.result)
        assertEquals(0, taskRunner.seenTasks.size)
    }

    @Test
    fun `a mid-graph task failure surfaces as Executed with StoppedOnFailure`() {
        val provider = RunnerFakeLlmProvider(
            LlmResponse.Text("""{"tasks":[{"id":"a","description":"a"},{"id":"b","description":"b","dependencies":["a"]}]}"""),
        )
        val runner = AnalyzedObjectiveRunner(ObjectiveAnalyzer(provider))
        val taskRunner = RecordingTaskRunner { task ->
            if (task.id == "a") ObjectiveOutcome(AgentState.Failed(RuntimeException("boom")), 1) else ObjectiveOutcome(AgentState.Completed("done"), 1)
        }

        val outcome = runner.run("build a thing", taskRunner)

        val executed = assertIs<AnalyzedObjectiveOutcome.Executed>(outcome)
        assertIs<TaskGraphOutcome.StoppedOnFailure>(executed.taskGraphOutcome)
        assertEquals(listOf("a"), taskRunner.seenTasks.map { it.id })
    }

    @Test
    fun `objective_analysis_failed is logged at WARN on analysis failure`() {
        val provider = RunnerFakeLlmProvider(LlmResponse.Text("not json"))
        val logger = RunnerRecordingLogger()
        val runner = AnalyzedObjectiveRunner(ObjectiveAnalyzer(provider), logger = logger)
        val taskRunner = RecordingTaskRunner { ObjectiveOutcome(AgentState.Completed("done"), 1) }

        runner.run("build a thing", taskRunner)

        val event = logger.events.single { it.message == "objective_analysis_failed" }
        assertEquals(LogLevel.WARN, event.level)
    }
}
