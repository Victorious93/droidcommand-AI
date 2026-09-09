package ai.droidcommand.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class RecordingTool(name: String, private val result: ToolResult = ToolResult.Success("ran $name")) : Tool {
    override val spec = ToolSpec(name = name, description = "records invocation order")
    val invocations = mutableListOf<Map<String, String>>()

    override fun execute(input: Map<String, String>): ToolResult {
        invocations += input
        return result
    }
}

private class GlobalOrderTracker {
    val order = mutableListOf<String>()
}

private fun trackedTool(name: String, tracker: GlobalOrderTracker, result: ToolResult = ToolResult.Success("ran $name")): Tool = object : Tool {
    override val spec = ToolSpec(name = name, description = "tracked")
    override fun execute(input: Map<String, String>): ToolResult {
        tracker.order += name
        return result
    }
}

class DependencyOrderedExecutorTest {
    @Test
    fun `runs a linear chain of tasks in dependency order`() {
        val tracker = GlobalOrderTracker()
        val registry = ToolRegistry().apply {
            register(trackedTool("tool-a", tracker))
            register(trackedTool("tool-b", tracker))
            register(trackedTool("tool-c", tracker))
        }
        val stateMachine = AgentStateMachine()
        val toolExecutor = ToolExecutor(registry, stateMachine, sleep = { })
        val executor = DependencyOrderedExecutor(toolExecutor)
        val graph = TaskGraph(
            listOf(
                TaskSpec("a", "tool-a"),
                TaskSpec("b", "tool-b", dependsOn = setOf("a")),
                TaskSpec("c", "tool-c", dependsOn = setOf("b")),
            ),
        )

        val outcome = executor.run(graph)

        assertEquals(listOf("tool-a", "tool-b", "tool-c"), tracker.order)
        assertEquals(3, outcome.results.size)
        assertIs<ToolResult.Success>(outcome.results.getValue("a"))
        assertEquals(0, outcome.skipped.size)
    }

    @Test
    fun `passes each task's own input to its tool`() {
        val tool = RecordingTool("echo")
        val registry = ToolRegistry().apply { register(tool) }
        val stateMachine = AgentStateMachine()
        val toolExecutor = ToolExecutor(registry, stateMachine, sleep = { })
        val executor = DependencyOrderedExecutor(toolExecutor)
        val graph = TaskGraph(listOf(TaskSpec("a", "echo", input = mapOf("text" to "hello"))))

        executor.run(graph)

        assertEquals(listOf(mapOf("text" to "hello")), tool.invocations)
    }

    @Test
    fun `skips a task whose dependency failed, recording the reason, and never invokes it`() {
        val failingTool = object : Tool {
            override val spec = ToolSpec(name = "fails", description = "always fails")
            override fun execute(input: Map<String, String>) = ToolResult.Failure("nope")
        }
        val downstream = RecordingTool("downstream")
        val registry = ToolRegistry().apply {
            register(failingTool)
            register(downstream)
        }
        val stateMachine = AgentStateMachine()
        val toolExecutor = ToolExecutor(registry, stateMachine, sleep = { })
        val executor = DependencyOrderedExecutor(toolExecutor)
        val graph = TaskGraph(
            listOf(
                TaskSpec("a", "fails"),
                TaskSpec("b", "downstream", dependsOn = setOf("a")),
            ),
        )

        val outcome = executor.run(graph)

        assertIs<ToolResult.Failure>(outcome.results.getValue("a"))
        assertEquals(0, downstream.invocations.size)
        assertTrue(outcome.skipped.getValue("b").contains("a"))
        assertEquals(false, outcome.results.containsKey("b"))
    }

    @Test
    fun `a skip cascades transitively through a chain`() {
        val downstream = RecordingTool("downstream2")
        val registry = ToolRegistry().apply {
            register(
                object : Tool {
                    override val spec = ToolSpec(name = "fails2", description = "always fails")
                    override fun execute(input: Map<String, String>) = ToolResult.Failure("nope")
                },
            )
            register(downstream)
        }
        val stateMachine = AgentStateMachine()
        val toolExecutor = ToolExecutor(registry, stateMachine, sleep = { })
        val executor = DependencyOrderedExecutor(toolExecutor)
        val graph = TaskGraph(
            listOf(
                TaskSpec("a", "fails2"),
                TaskSpec("b", "downstream2", dependsOn = setOf("a")),
                TaskSpec("c", "downstream2", dependsOn = setOf("b")),
            ),
        )

        val outcome = executor.run(graph)

        assertEquals(0, downstream.invocations.size)
        assertTrue(outcome.skipped.getValue("b").contains("a"))
        assertTrue(outcome.skipped.getValue("c").contains("b"))
    }

    @Test
    fun `an independent task still runs even when an unrelated task in the same wave fails`() {
        val tracker = GlobalOrderTracker()
        val registry = ToolRegistry().apply {
            register(
                object : Tool {
                    override val spec = ToolSpec(name = "fails3", description = "always fails")
                    override fun execute(input: Map<String, String>) = ToolResult.Failure("nope")
                },
            )
            register(trackedTool("independent", tracker))
        }
        val stateMachine = AgentStateMachine()
        val toolExecutor = ToolExecutor(registry, stateMachine, sleep = { })
        val executor = DependencyOrderedExecutor(toolExecutor)
        val graph = TaskGraph(listOf(TaskSpec("a", "fails3"), TaskSpec("b", "independent")))

        val outcome = executor.run(graph)

        assertEquals(listOf("independent"), tracker.order)
        assertIs<ToolResult.Success>(outcome.results.getValue("b"))
        assertEquals(0, outcome.skipped.size)
    }

    @Test
    fun `a Partial dependency result does not block its dependent`() {
        val downstream = RecordingTool("downstream3")
        val registry = ToolRegistry().apply {
            register(
                object : Tool {
                    override val spec = ToolSpec(name = "partial", description = "returns Partial")
                    override fun execute(input: Map<String, String>) = ToolResult.Partial("2 of 4", "ran out of time")
                },
            )
            register(downstream)
        }
        val stateMachine = AgentStateMachine()
        val toolExecutor = ToolExecutor(registry, stateMachine, sleep = { })
        val executor = DependencyOrderedExecutor(toolExecutor)
        val graph = TaskGraph(listOf(TaskSpec("a", "partial"), TaskSpec("b", "downstream3", dependsOn = setOf("a"))))

        val outcome = executor.run(graph)

        assertEquals(1, downstream.invocations.size)
        assertIs<ToolResult.Success>(outcome.results.getValue("b"))
        assertEquals(0, outcome.skipped.size)
    }
}
