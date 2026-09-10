package ai.droidcommand.agent

/**
 * Executes a single [Task] to completion, returning the [ObjectiveOutcome]
 * that resulted. Almost certainly backed by a real [ObjectiveEngine] in
 * practice (see [TaskGraphExecutor]'s doc comment for the constraint that
 * matters when it is), but [TaskGraphExecutor] itself has no dependency on
 * [ObjectiveEngine]/[Planner] beyond this shape — it only needs to know how
 * a task turned out, not how.
 */
fun interface TaskRunner {
    fun run(task: Task): ObjectiveOutcome
}

data class TaskOutcome(val task: Task, val objectiveOutcome: ObjectiveOutcome)

/** The result of running a whole [TaskGraph] through [TaskGraphExecutor.run]. */
sealed class TaskGraphOutcome {
    abstract val completedTasks: List<TaskOutcome>

    data class Completed(override val completedTasks: List<TaskOutcome>) : TaskGraphOutcome()

    /**
     * A task's [ObjectiveOutcome.finalState] wasn't [AgentState.Completed]
     * or [AgentState.Cancelled] (almost always [AgentState.Failed]). Every
     * task after [failedTask] in the graph's execution order is reported in
     * [skippedTasks] rather than run — including tasks on an independent
     * branch that didn't actually depend on [failedTask]. Skipping only a
     * failed task's transitive dependents while continuing independent
     * branches is real, more-correct behavior, deliberately deferred: this
     * first slice matches [MacroExecutor]'s already-shipped
     * stop-on-first-failure precedent instead.
     */
    data class StoppedOnFailure(
        override val completedTasks: List<TaskOutcome>,
        val failedTask: Task,
        val failedOutcome: ObjectiveOutcome,
        val skippedTasks: List<Task>,
    ) : TaskGraphOutcome()

    data class Cancelled(override val completedTasks: List<TaskOutcome>) : TaskGraphOutcome()
}

/**
 * Runs every [Task] in a [TaskGraph] via a caller-supplied [TaskRunner], in
 * [TaskGraph.executionOrder] (dependencies before dependents) — the
 * ROADMAP-067 half of the objective-analysis slice (see [ObjectiveAnalyzer]
 * in `core-llm` for ROADMAP-064, the stage that produces a [TaskGraph] in
 * the first place).
 *
 * **First-slice scope, stated rather than silently assumed:** execution is
 * strictly sequential — one task at a time, in graph order — never
 * parallel. Two independent tasks (neither depending on the other) could
 * in principle run concurrently, but that needs a real fan-out design
 * (per-branch [ToolExecutor]/[AgentStateMachine] instances, a join
 * strategy) this slice deliberately does not attempt; it is a named future
 * slice, the same restraint [KnowledgeStore] applied by shipping literal
 * retrieval before semantic search.
 *
 * **Critical constraint for whoever writes a [TaskRunner] backed by a real
 * [ObjectiveEngine]:** [AgentStateMachine.transition] throws once the
 * machine is in a terminal state ([AgentState.Completed]/[AgentState.Cancelled]/
 * [AgentState.Failed]). Running two tasks' [ObjectiveEngine.run] calls
 * against **one shared** [AgentStateMachine] would crash on the second
 * task's first transition, since the first task already drove it terminal.
 * A [TaskRunner] must construct a **fresh [AgentStateMachine] *and* a fresh
 * [ToolExecutor]** per task — [ToolExecutor] itself is bound to one
 * [AgentStateMachine] at construction and transitions it directly (e.g.
 * `ExecutingTool`/`Observing`), so reusing a single [ToolExecutor] across
 * tasks would still crash even with a fresh [AgentStateMachine] passed to
 * [ObjectiveEngine] alone. Only `registry` and `planner` are safe to reuse
 * across tasks, e.g.:
 * ```
 * TaskRunner { task ->
 *     val stateMachine = AgentStateMachine()
 *     val executor = ToolExecutor(registry, stateMachine)
 *     ObjectiveEngine(registry, executor, stateMachine, planner).run(task.description)
 * }
 * ```
 * — the same "dedicated instance per independent task" rule
 * [AgentStateMachine]'s own doc comment already states for `MacroScheduler`.
 */
class TaskGraphExecutor(private val logger: Logger = NoOpLogger) {
    fun run(
        graph: TaskGraph,
        runTask: TaskRunner,
        isCancelled: () -> Boolean = { false },
    ): TaskGraphOutcome {
        logger.info("task_graph_started", mapOf("taskCount" to graph.tasks.size.toString()))
        val completed = mutableListOf<TaskOutcome>()

        for ((index, id) in graph.executionOrder.withIndex()) {
            if (isCancelled()) {
                logger.warn("task_graph_cancelled", mapOf("completed" to completed.size.toString()))
                return TaskGraphOutcome.Cancelled(completed)
            }

            val task = graph.task(id)
            logger.info("task_started", mapOf("task" to task.id))
            val outcome = runTask.run(task)

            when (outcome.finalState) {
                is AgentState.Completed -> {
                    logger.info("task_completed", mapOf("task" to task.id))
                    completed.add(TaskOutcome(task, outcome))
                }

                is AgentState.Cancelled -> {
                    logger.warn("task_graph_cancelled", mapOf("task" to task.id, "completed" to completed.size.toString()))
                    return TaskGraphOutcome.Cancelled(completed)
                }

                else -> {
                    val skipped = graph.executionOrder.subList(index + 1, graph.executionOrder.size).map { graph.task(it) }
                    logger.warn(
                        "task_graph_stopped_on_failure",
                        mapOf("task" to task.id, "completed" to completed.size.toString(), "skipped" to skipped.size.toString()),
                    )
                    return TaskGraphOutcome.StoppedOnFailure(completed, task, outcome, skipped)
                }
            }
        }

        logger.info("task_graph_completed", mapOf("completed" to completed.size.toString()))
        return TaskGraphOutcome.Completed(completed)
    }
}
