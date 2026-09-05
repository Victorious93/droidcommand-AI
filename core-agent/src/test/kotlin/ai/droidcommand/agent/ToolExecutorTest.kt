package ai.droidcommand.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class FlakyTool(private val failuresBeforeSuccess: Int) : Tool {
    override val spec = ToolSpec(name = "flaky", description = "fails N times then succeeds")
    var invocations = 0
        private set

    override fun execute(input: Map<String, String>): ToolResult {
        invocations++
        return if (invocations <= failuresBeforeSuccess) {
            ToolResult.Failure("not yet")
        } else {
            ToolResult.Success("succeeded on attempt $invocations")
        }
    }
}

private class AlwaysFailsTool : Tool {
    override val spec = ToolSpec(name = "always-fails", description = "always fails")
    override fun execute(input: Map<String, String>) = ToolResult.Failure("nope")
}

private class ThrowingTool : Tool {
    override val spec = ToolSpec(name = "throws", description = "throws instead of returning Failure")
    override fun execute(input: Map<String, String>): ToolResult = throw RuntimeException("kaboom")
}

class ToolExecutorTest {
    @Test
    fun `succeeds without retry when the tool succeeds first try`() {
        val registry = ToolRegistry().apply { register(FlakyTool(failuresBeforeSuccess = 0)) }
        val executor = ToolExecutor(registry, AgentStateMachine(), sleep = { })
        val result = executor.run("flaky", emptyMap())
        assertIs<ToolResult.Success>(result)
    }

    @Test
    fun `retries up to maxAttempts and eventually succeeds`() {
        val tool = FlakyTool(failuresBeforeSuccess = 2)
        val registry = ToolRegistry().apply { register(tool) }
        val executor = ToolExecutor(registry, AgentStateMachine(), sleep = { })
        val result = executor.run("flaky", emptyMap(), retryPolicy = RetryPolicy(maxAttempts = 5))
        assertIs<ToolResult.Success>(result)
        assertEquals(3, tool.invocations)
    }

    @Test
    fun `never invokes the tool more than maxAttempts times`() {
        val tool = AlwaysFailsTool()
        val registry = ToolRegistry().apply { register(tool) }
        val executor = ToolExecutor(registry, AgentStateMachine(), sleep = { })
        var sleeps = 0
        val bounded = ToolExecutor(registry, AgentStateMachine(), sleep = { sleeps++ })
        val result = bounded.run("always-fails", emptyMap(), retryPolicy = RetryPolicy(maxAttempts = 3))
        assertIs<ToolResult.Failure>(result)
        assertEquals(2, sleeps) // backoff happens between attempts, not after the last one
    }

    @Test
    fun `a thrown exception is captured as a Failure, not propagated`() {
        val registry = ToolRegistry().apply { register(ThrowingTool()) }
        val executor = ToolExecutor(registry, AgentStateMachine(), sleep = { })
        val result = executor.run("throws", emptyMap())
        assertIs<ToolResult.Failure>(result)
        assertTrue(result.reason.contains("kaboom"))
    }

    @Test
    fun `honors cancellation before starting an attempt`() {
        val registry = ToolRegistry().apply { register(AlwaysFailsTool()) }
        val machine = AgentStateMachine()
        val executor = ToolExecutor(registry, machine, sleep = { })
        assertFailsWith<CancellationRequested> {
            executor.run("always-fails", emptyMap(), isCancelled = { true })
        }
        assertIs<AgentState.Cancelled>(machine.state)
    }

    @Test
    fun `unknown tool name throws before any state transition`() {
        val registry = ToolRegistry()
        val machine = AgentStateMachine()
        val executor = ToolExecutor(registry, machine, sleep = { })
        assertFailsWith<UnknownToolException> {
            executor.run("nope", emptyMap())
        }
        assertEquals(AgentState.Idle, machine.state)
    }
}
