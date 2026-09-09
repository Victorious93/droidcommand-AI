package ai.droidcommand.agent

data class TaskGraphOutcome(
    val results: Map<String, ToolResult>,
    val skipped: Map<String, String>,
)

/**
 * Runs every [TaskSpec] in a [TaskGraph] through [ToolExecutor], one
 * [TaskGraph.executionWaves] wave at a time, in dependency order
 * (ROADMAP-067). A task whose dependency [ToolResult.Failure]ed — or was
 * itself skipped for the same reason, so a broken chain propagates rather
 * than silently stopping at one hop — is skipped rather than run on top of
 * a dependency that didn't hold up; the reason is recorded in
 * [TaskGraphOutcome.skipped] rather than the task simply being absent from
 * [TaskGraphOutcome.results] with no explanation. A [ToolResult.Partial] or
 * [ToolResult.Unexpected] dependency does not block its dependents — the
 * same "only Failure is treated as blocking" distinction [ToolExecutor]'s
 * own retry logic already draws.
 *
 * This is new, self-contained infrastructure: nothing in [ObjectiveEngine]
 * or [Planner] constructs or consumes a [TaskGraph] yet. Wiring a planner
 * that can emit a multi-task plan into this executor remains a follow-up,
 * not silently assumed to be covered by this class existing.
 */
class DependencyOrderedExecutor(
    private val executor: ToolExecutor,
    private val logger: Logger = NoOpLogger,
) {
    fun run(
        graph: TaskGraph,
        retryPolicy: RetryPolicy = RetryPolicy(),
        isCancelled: () -> Boolean = { false },
        mode: AgentMode? = null,
        initiator: Initiator? = null,
    ): TaskGraphOutcome {
        val results = mutableMapOf<String, ToolResult>()
        val skipped = mutableMapOf<String, String>()

        for (wave in graph.executionWaves()) {
            for (task in wave) {
                val blockedBy = task.dependsOn.firstOrNull { dep -> dep in skipped || results[dep] is ToolResult.Failure }
                if (blockedBy != null) {
                    val reason = "dependency '$blockedBy' did not succeed"
                    skipped[task.name] = reason
                    logger.warn("task_skipped", mapOf("task" to task.name, "reason" to reason))
                    continue
                }

                logger.info("task_started", mapOf("task" to task.name, "tool" to task.toolName))
                results[task.name] = executor.run(task.toolName, task.input, retryPolicy, isCancelled, mode, initiator)
            }
        }

        return TaskGraphOutcome(results, skipped)
    }
}
