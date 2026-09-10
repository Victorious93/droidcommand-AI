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
