package ai.droidcommand.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TaskGraphTest {
    @Test
    fun `a single task with no dependencies is its own wave`() {
        val graph = TaskGraph(listOf(TaskSpec("a", "tool")))
        assertEquals(listOf(listOf(TaskSpec("a", "tool"))), graph.executionWaves())
    }

    @Test
    fun `independent tasks share the first wave`() {
        val graph = TaskGraph(listOf(TaskSpec("a", "tool"), TaskSpec("b", "tool")))
        val waves = graph.executionWaves()
        assertEquals(1, waves.size)
        assertEquals(setOf("a", "b"), waves[0].map { it.name }.toSet())
    }

    @Test
    fun `a linear chain produces one task per wave, in order`() {
        val graph = TaskGraph(
            listOf(
                TaskSpec("a", "tool"),
                TaskSpec("b", "tool", dependsOn = setOf("a")),
                TaskSpec("c", "tool", dependsOn = setOf("b")),
            ),
        )
        val waves = graph.executionWaves()
        assertEquals(listOf("a", "b", "c"), waves.map { it.single().name })
    }

    @Test
    fun `a diamond dependency groups the middle tasks into the same wave`() {
        val graph = TaskGraph(
            listOf(
                TaskSpec("a", "tool"),
                TaskSpec("b", "tool", dependsOn = setOf("a")),
                TaskSpec("c", "tool", dependsOn = setOf("a")),
                TaskSpec("d", "tool", dependsOn = setOf("b", "c")),
            ),
        )
        val waves = graph.executionWaves()
        assertEquals(3, waves.size)
        assertEquals("a", waves[0].single().name)
        assertEquals(setOf("b", "c"), waves[1].map { it.name }.toSet())
        assertEquals("d", waves[2].single().name)
    }

    @Test
    fun `rejects a duplicate task name`() {
        assertFailsWith<IllegalArgumentException> {
            TaskGraph(listOf(TaskSpec("a", "tool"), TaskSpec("a", "tool")))
        }
    }

    @Test
    fun `rejects a dependency on an unknown task`() {
        assertFailsWith<IllegalArgumentException> {
            TaskGraph(listOf(TaskSpec("a", "tool", dependsOn = setOf("nope"))))
        }
    }

    @Test
    fun `detects a two-node cycle`() {
        assertFailsWith<CyclicTaskGraphException> {
            TaskGraph(
                listOf(
                    TaskSpec("a", "tool", dependsOn = setOf("b")),
                    TaskSpec("b", "tool", dependsOn = setOf("a")),
                ),
            ).executionWaves()
        }
    }

    @Test
    fun `detects a self-dependency`() {
        assertFailsWith<CyclicTaskGraphException> {
            TaskGraph(listOf(TaskSpec("a", "tool", dependsOn = setOf("a")))).executionWaves()
        }
    }
}
