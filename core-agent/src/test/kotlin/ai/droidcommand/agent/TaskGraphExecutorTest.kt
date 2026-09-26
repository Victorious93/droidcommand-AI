package ai.droidcommand.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class ScriptedTaskRunner(private val outcomeFor: (Task) -> ObjectiveOutcome) : TaskRunner {
    val seenTasks = mutableListOf<Task>()

    override fun run(task: Task): ObjectiveOutcome {
        seenTasks += task
        return outcomeFor(task)
    }
}

private class TaskGraphRecordingLogger : Logger {
    val events = mutableListOf<LogEvent>()

    override fun log(event: LogEvent) {
        events += event
    }
}

private fun graphOf(vararg tasks: Task): TaskGraph = assertIs<TaskGraphResult.Valid>(TaskGraph.from(tasks.toList())).graph

class TaskGraphExecutorTest {
    @Test
    fun `runs every task in order and reports Completed`() {
        val graph = graphOf(
            Task(id = "a", description = "a"),
            Task(id = "b", description = "b", dependencies = setOf("a")),
        )
        val runner = ScriptedTaskRunner { ObjectiveOutcome(AgentState.Completed("done"), 1) }

        val outcome = TaskGraphExecutor().run(graph, runner)

        assertIs<TaskGraphOutcome.Completed>(outcome)
        assertEquals(2, outcome.completedTasks.size)
        assertEquals(listOf("a", "b"), runner.seenTasks.map { it.id })
    }

    @Test
    fun `a Failed task stops the graph and skips every later task`() {
        val graph = graphOf(
            Task(id = "a", description = "a"),
            Task(id = "b", description = "b"),
            Task(id = "c", description = "c"),
        )
        val runner = ScriptedTaskRunner { task ->
            if (task.id == "a") {
                ObjectiveOutcome(AgentState.Failed(RuntimeException("boom")), 1)
            } else {
                ObjectiveOutcome(AgentState.Completed("done"), 1)
            }
        }

        val outcome = TaskGraphExecutor().run(graph, runner)

        val stopped = assertIs<TaskGraphOutcome.StoppedOnFailure>(outcome)
        assertEquals(0, stopped.completedTasks.size)
        assertSame(graph.task("a"), stopped.failedTask)
        assertEquals(setOf("b", "c"), stopped.skippedTasks.map { it.id }.toSet())
        assertEquals(listOf("a"), runner.seenTasks.map { it.id })
    }

    @Test
    fun `a Cancelled task outcome maps to Cancelled, not StoppedOnFailure`() {
        val graph = graphOf(Task(id = "a", description = "a"), Task(id = "b", description = "b"))
        val runner = ScriptedTaskRunner { ObjectiveOutcome(AgentState.Cancelled("user cancelled"), 1) }

        val outcome = TaskGraphExecutor().run(graph, runner)

        val cancelled = assertIs<TaskGraphOutcome.Cancelled>(outcome)
        assertEquals(0, cancelled.completedTasks.size)
        assertEquals(listOf("a"), runner.seenTasks.map { it.id })
    }

    @Test
    fun `the executor's own isCancelled stops the run before the next task`() {
        val graph = graphOf(Task(id = "a", description = "a"), Task(id = "b", description = "b"))
        val runner = ScriptedTaskRunner { ObjectiveOutcome(AgentState.Completed("done"), 1) }

        val outcome = TaskGraphExecutor().run(graph, runner, isCancelled = { true })

        assertIs<TaskGraphOutcome.Cancelled>(outcome)
        assertEquals(0, runner.seenTasks.size)
    }

    @Test
    fun `each Task reaches the runner unchanged`() {
        val task = Task(id = "a", description = "a", verificationCriteria = listOf("check it"))
        val graph = graphOf(task)
        val runner = ScriptedTaskRunner { ObjectiveOutcome(AgentState.Completed("done"), 1) }

        TaskGraphExecutor().run(graph, runner)

        assertSame(task, runner.seenTasks.single())
    }

    @Test
    fun `logs task_graph_started task_started task_completed and task_graph_completed on a full run`() {
        val graph = graphOf(Task(id = "a", description = "a"))
        val runner = ScriptedTaskRunner { ObjectiveOutcome(AgentState.Completed("done"), 1) }
        val logger = TaskGraphRecordingLogger()

        TaskGraphExecutor(logger).run(graph, runner)

        assertTrue(logger.events.any { it.message == "task_graph_started" })
        assertTrue(logger.events.any { it.message == "task_started" })
        assertTrue(logger.events.any { it.message == "task_completed" })
        assertTrue(logger.events.any { it.message == "task_graph_completed" })
    }

    @Test
    fun `logs task_graph_stopped_on_failure at WARN when a task fails`() {
        val graph = graphOf(Task(id = "a", description = "a"))
        val runner = ScriptedTaskRunner { ObjectiveOutcome(AgentState.Failed(RuntimeException("boom")), 1) }
        val logger = TaskGraphRecordingLogger()

        TaskGraphExecutor(logger).run(graph, runner)

        val event = logger.events.single { it.message == "task_graph_stopped_on_failure" }
        assertEquals(LogLevel.WARN, event.level)
    }
}

class TaskGraphExecutorVerificationTest {
    private val done = ObjectiveOutcome(AgentState.Completed("done"), 1)

    private class RecordingVerifier(private val result: (Task) -> TaskVerification) : TaskVerifier {
        val seenTasks = mutableListOf<Task>()

        override fun verify(task: Task, outcome: ObjectiveOutcome): TaskVerification {
            seenTasks += task
            return result(task)
        }
    }

    @Test
    fun `a passing verification records Passed on the task outcome`() {
        val graph = graphOf(Task(id = "a", description = "a", verificationCriteria = listOf("a works")))
        val verifier = RecordingVerifier { TaskVerification.Passed }

        val outcome = TaskGraphExecutor(verifier = verifier).run(graph, ScriptedTaskRunner { done })

        val completed = assertIs<TaskGraphOutcome.Completed>(outcome)
        assertEquals(TaskVerification.Passed, completed.completedTasks.single().verification)
        assertEquals(listOf("a"), verifier.seenTasks.map { it.id })
    }

    @Test
    fun `a failed verification stops the graph and skips every later task`() {
        val graph = graphOf(
            Task(id = "a", description = "a", verificationCriteria = listOf("a works")),
            Task(id = "b", description = "b", dependencies = setOf("a")),
        )
        val failed = TaskVerification.Failed(listOf("a works"), "it does not")
        val runner = ScriptedTaskRunner { done }

        val outcome = TaskGraphExecutor(verifier = RecordingVerifier { failed }).run(graph, runner)

        val stopped = assertIs<TaskGraphOutcome.StoppedOnVerificationFailure>(outcome)
        assertEquals("a", stopped.failedTask.id)
        assertSame(failed, stopped.verification)
        assertEquals(listOf("b"), stopped.skippedTasks.map { it.id })
        assertTrue(stopped.completedTasks.isEmpty())
        assertEquals(listOf("a"), runner.seenTasks.map { it.id })
    }

    @Test
    fun `an inconclusive verification fails closed`() {
        val graph = graphOf(Task(id = "a", description = "a", verificationCriteria = listOf("a works")))

        val outcome = TaskGraphExecutor(verifier = RecordingVerifier { TaskVerification.Inconclusive("provider down") })
            .run(graph, ScriptedTaskRunner { done })

        assertIs<TaskVerification.Inconclusive>(assertIs<TaskGraphOutcome.StoppedOnVerificationFailure>(outcome).verification)
    }

    @Test
    fun `tasks without criteria are never sent to the verifier`() {
        val graph = graphOf(Task(id = "a", description = "a"))
        val verifier = RecordingVerifier { TaskVerification.Failed(emptyList(), "should not be called") }

        val outcome = TaskGraphExecutor(verifier = verifier).run(graph, ScriptedTaskRunner { done })

        val completed = assertIs<TaskGraphOutcome.Completed>(outcome)
        assertEquals(null, completed.completedTasks.single().verification)
        assertTrue(verifier.seenTasks.isEmpty())
    }

    @Test
    fun `a task that did not complete is never sent to the verifier`() {
        val graph = graphOf(Task(id = "a", description = "a", verificationCriteria = listOf("a works")))
        val verifier = RecordingVerifier { TaskVerification.Passed }
        val runner = ScriptedTaskRunner { ObjectiveOutcome(AgentState.Failed(RuntimeException("boom")), 1) }

        val outcome = TaskGraphExecutor(verifier = verifier).run(graph, runner)

        assertIs<TaskGraphOutcome.StoppedOnFailure>(outcome)
        assertTrue(verifier.seenTasks.isEmpty())
    }

    @Test
    fun `with no verifier configured criteria stay unchecked`() {
        val graph = graphOf(Task(id = "a", description = "a", verificationCriteria = listOf("a works")))

        val outcome = TaskGraphExecutor().run(graph, ScriptedTaskRunner { done })

        assertEquals(null, assertIs<TaskGraphOutcome.Completed>(outcome).completedTasks.single().verification)
    }
}
