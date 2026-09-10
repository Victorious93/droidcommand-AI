package ai.droidcommand.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TaskGraphTest {
    @Test
    fun `a single task with no dependencies is valid`() {
        val result = TaskGraph.from(listOf(Task(id = "a", description = "do a")))

        val valid = assertIs<TaskGraphResult.Valid>(result)
        assertEquals(listOf("a"), valid.graph.executionOrder)
        assertEquals("do a", valid.graph.task("a").description)
    }

    @Test
    fun `a linear chain resolves in dependency order`() {
        val tasks = listOf(
            Task(id = "a", description = "a"),
            Task(id = "b", description = "b", dependencies = setOf("a")),
            Task(id = "c", description = "c", dependencies = setOf("b")),
        )

        val valid = assertIs<TaskGraphResult.Valid>(TaskGraph.from(tasks))

        assertEquals(listOf("a", "b", "c"), valid.graph.executionOrder)
    }

    @Test
    fun `a diamond resolves correctly and deterministically`() {
        val tasks = listOf(
            Task(id = "a", description = "a"),
            Task(id = "b", description = "b", dependencies = setOf("a")),
            Task(id = "c", description = "c", dependencies = setOf("a")),
            Task(id = "d", description = "d", dependencies = setOf("b", "c")),
        )

        val valid = assertIs<TaskGraphResult.Valid>(TaskGraph.from(tasks))

        assertEquals(listOf("a", "b", "c", "d"), valid.graph.executionOrder)
    }

    @Test
    fun `duplicate task ids are rejected`() {
        val tasks = listOf(Task(id = "a", description = "first"), Task(id = "a", description = "second"))

        val invalid = assertIs<TaskGraphResult.Invalid>(TaskGraph.from(tasks))

        assertEquals(listOf(TaskGraphError.DuplicateTaskId("a")), invalid.errors)
    }

    @Test
    fun `an unknown dependency id is rejected with the offending pair`() {
        val tasks = listOf(Task(id = "a", description = "a", dependencies = setOf("z")))

        val invalid = assertIs<TaskGraphResult.Invalid>(TaskGraph.from(tasks))

        assertEquals(listOf(TaskGraphError.UnknownDependency("a", "z")), invalid.errors)
    }

    @Test
    fun `a self-dependency is reported as a cycle`() {
        val tasks = listOf(Task(id = "a", description = "a", dependencies = setOf("a")))

        val invalid = assertIs<TaskGraphResult.Invalid>(TaskGraph.from(tasks))

        assertEquals(1, invalid.errors.size)
        val cycle = assertIs<TaskGraphError.CyclicDependency>(invalid.errors.single())
        assertEquals(listOf("a", "a"), cycle.cycle)
    }

    @Test
    fun `a longer cycle is reported with the real path`() {
        val tasks = listOf(
            Task(id = "a", description = "a", dependencies = setOf("b")),
            Task(id = "b", description = "b", dependencies = setOf("c")),
            Task(id = "c", description = "c", dependencies = setOf("a")),
        )

        val invalid = assertIs<TaskGraphResult.Invalid>(TaskGraph.from(tasks))

        assertEquals(1, invalid.errors.size)
        val cycle = assertIs<TaskGraphError.CyclicDependency>(invalid.errors.single())
        assertEquals(listOf("a", "b", "c", "a"), cycle.cycle)
    }

    @Test
    fun `duplicate ids and unknown dependencies are both reported together`() {
        val tasks = listOf(
            Task(id = "a", description = "first"),
            Task(id = "a", description = "second"),
            Task(id = "b", description = "b", dependencies = setOf("z")),
        )

        val invalid = assertIs<TaskGraphResult.Invalid>(TaskGraph.from(tasks))

        assertEquals(
            setOf(TaskGraphError.DuplicateTaskId("a"), TaskGraphError.UnknownDependency("b", "z")),
            invalid.errors.toSet(),
        )
    }

    @Test
    fun `an empty task list is rejected`() {
        val invalid = assertIs<TaskGraphResult.Invalid>(TaskGraph.from(emptyList()))

        assertEquals(listOf(TaskGraphError.EmptyTaskGraph), invalid.errors)
    }

    @Test
    fun `executionOrder is stable across repeated calls with identical input`() {
        val tasks = listOf(
            Task(id = "a", description = "a"),
            Task(id = "b", description = "b", dependencies = setOf("a")),
            Task(id = "c", description = "c", dependencies = setOf("a")),
        )

        val first = assertIs<TaskGraphResult.Valid>(TaskGraph.from(tasks))
        val second = assertIs<TaskGraphResult.Valid>(TaskGraph.from(tasks))

        assertEquals(first.graph.executionOrder, second.graph.executionOrder)
    }

    @Test
    fun `task looks up the right Task by id`() {
        val tasks = listOf(Task(id = "a", description = "alpha"), Task(id = "b", description = "beta"))
        val valid = assertIs<TaskGraphResult.Valid>(TaskGraph.from(tasks))

        assertEquals("alpha", valid.graph.task("a").description)
        assertEquals("beta", valid.graph.task("b").description)
        assertTrue(valid.graph.tasks.containsAll(tasks))
    }
}
