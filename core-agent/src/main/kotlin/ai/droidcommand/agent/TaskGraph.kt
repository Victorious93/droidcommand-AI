package ai.droidcommand.agent

/** One tool invocation as a named node in a [TaskGraph], depending on zero or more other named tasks. */
data class TaskSpec(
    val name: String,
    val toolName: String,
    val input: Map<String, String> = emptyMap(),
    val dependsOn: Set<String> = emptySet(),
)

class CyclicTaskGraphException(cycle: Set<String>) : IllegalArgumentException("Cycle detected among tasks: $cycle")

/**
 * A dependency graph over [TaskSpec]s (ROADMAP-067 — "Execute tasks in
 * dependency order," master prompt Phase 8). Validated eagerly at
 * construction: every `dependsOn` name must refer to another task in the
 * same graph, and task names must be unique, so a caller building a graph
 * from planner output finds out immediately, not partway through execution.
 */
class TaskGraph(tasks: List<TaskSpec>) {
    private val byName: Map<String, TaskSpec>

    init {
        val duplicates = tasks.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate task name(s): $duplicates" }
        byName = tasks.associateBy { it.name }
        tasks.forEach { task ->
            task.dependsOn.forEach { dep ->
                require(dep in byName) { "Task '${task.name}' depends on unknown task '$dep'" }
            }
        }
    }

    val tasks: List<TaskSpec> = tasks

    /**
     * Tasks grouped into ordered "waves": every task in a wave has all its
     * dependencies satisfied by an earlier wave, and no dependency on any
     * other task in the same wave — so a caller free to run a wave's tasks
     * concurrently may, though [DependencyOrderedExecutor] itself runs them
     * sequentially within a wave. Throws [CyclicTaskGraphException] if no
     * further task ever becomes ready, rather than silently returning a
     * partial order.
     */
    fun executionWaves(): List<List<TaskSpec>> {
        val remaining = byName.toMutableMap()
        val done = mutableSetOf<String>()
        val waves = mutableListOf<List<TaskSpec>>()

        while (remaining.isNotEmpty()) {
            val ready = remaining.values.filter { task -> task.dependsOn.all { it in done } }
            if (ready.isEmpty()) throw CyclicTaskGraphException(remaining.keys.toSet())

            waves += ready
            ready.forEach {
                remaining.remove(it.name)
                done += it.name
            }
        }

        return waves
    }
}
