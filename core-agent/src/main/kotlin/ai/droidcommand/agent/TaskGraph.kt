package ai.droidcommand.agent

/**
 * One unit of work inside a decomposed objective (ROADMAP-064): a
 * description a [Planner]/[ObjectiveEngine] can drive to completion, the
 * ids of other [Task]s that must complete first ([dependencies]), and
 * free-text [verificationCriteria] a caller may use to judge whether the
 * task actually succeeded (not itself evaluated by anything in this file —
 * that's a caller/planner concern, the same restraint [KnowledgeEntry]'s
 * [source] field already takes toward its own free-text content).
 */
data class Task(
    val id: String,
    val description: String,
    val dependencies: Set<String> = emptySet(),
    val verificationCriteria: List<String> = emptyList(),
)

/** A structural problem with a set of [Task]s that keeps them from forming a valid [TaskGraph]. */
sealed class TaskGraphError {
    data object EmptyTaskGraph : TaskGraphError()

    data class DuplicateTaskId(val id: String) : TaskGraphError()

    data class UnknownDependency(val taskId: String, val dependsOnId: String) : TaskGraphError()

    data class CyclicDependency(val cycle: List<String>) : TaskGraphError()
}

/** The outcome of [TaskGraph.from]: a typed result instead of throwing, matching this codebase's usual discipline. */
sealed class TaskGraphResult {
    data class Valid(val graph: TaskGraph) : TaskGraphResult()

    data class Invalid(val errors: List<TaskGraphError>) : TaskGraphResult()
}

/**
 * A validated, immutable set of [Task]s (ROADMAP-064/-067): every
 * dependency id refers to a real task, and the dependency edges form a DAG
 * (no cycles). [executionOrder] is a dependencies-before-dependents
 * topological order — deterministic across calls with identical input,
 * since [from] fixes iteration order (input order for tasks, lexicographic
 * for a task's own dependency ids) rather than relying on hash-map
 * iteration order.
 *
 * The only way to obtain an instance is [from]: there is no public
 * constructor, so an invalid graph (missing dependency, cycle) can never
 * exist as a [TaskGraph] value in the first place — the same "make the
 * illegal state unrepresentable" discipline [JsonFileKnowledgeStore]'s
 * path-escape check applies to a different kind of invalid input.
 */
class TaskGraph private constructor(
    val tasks: List<Task>,
    val executionOrder: List<String>,
) {
    private val byId = tasks.associateBy { it.id }

    fun task(id: String): Task = byId.getValue(id)

    companion object {
        fun from(tasks: List<Task>): TaskGraphResult {
            if (tasks.isEmpty()) {
                return TaskGraphResult.Invalid(listOf(TaskGraphError.EmptyTaskGraph))
            }

            val structuralErrors = mutableListOf<TaskGraphError>()
            val seenIds = mutableSetOf<String>()
            for (task in tasks) {
                if (!seenIds.add(task.id)) {
                    structuralErrors.add(TaskGraphError.DuplicateTaskId(task.id))
                }
            }

            val knownIds = tasks.map { it.id }.toSet()
            for (task in tasks) {
                for (dependsOnId in task.dependencies) {
                    if (dependsOnId !in knownIds) {
                        structuralErrors.add(TaskGraphError.UnknownDependency(task.id, dependsOnId))
                    }
                }
            }

            if (structuralErrors.isNotEmpty()) {
                return TaskGraphResult.Invalid(structuralErrors)
            }

            val byId = tasks.associateBy { it.id }
            val order = mutableListOf<String>()
            val state = mutableMapOf<String, VisitState>()
            val stack = mutableListOf<String>()

            for (task in tasks) {
                val cycle = visit(task.id, byId, state, stack, order)
                if (cycle != null) {
                    return TaskGraphResult.Invalid(listOf(TaskGraphError.CyclicDependency(cycle)))
                }
            }

            return TaskGraphResult.Valid(TaskGraph(tasks, order))
        }

        /** Iterative-recursion-free would be nicer, but this graph is expected to be small; returns the cycle path, or null if none was found from [id]. */
        private fun visit(
            id: String,
            byId: Map<String, Task>,
            state: MutableMap<String, VisitState>,
            stack: MutableList<String>,
            order: MutableList<String>,
        ): List<String>? {
            when (state[id]) {
                VisitState.DONE -> return null
                VisitState.IN_PROGRESS -> return stack.subList(stack.indexOf(id), stack.size) + id
                null -> Unit
            }

            state[id] = VisitState.IN_PROGRESS
            stack.add(id)

            for (dependsOnId in byId.getValue(id).dependencies.sorted()) {
                val cycle = visit(dependsOnId, byId, state, stack, order)
                if (cycle != null) return cycle
            }

            stack.removeAt(stack.size - 1)
            state[id] = VisitState.DONE
            order.add(id)
            return null
        }

        private enum class VisitState { IN_PROGRESS, DONE }
    }
}
