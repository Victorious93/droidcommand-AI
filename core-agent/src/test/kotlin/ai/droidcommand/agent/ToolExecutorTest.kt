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

private class PartialTool : Tool {
    override val spec = ToolSpec(name = "partial", description = "always returns a Partial result")
    var invocations = 0
        private set
    override fun execute(input: Map<String, String>): ToolResult {
        invocations++
        return ToolResult.Partial("3 of 5 items processed", "ran out of time")
    }
}

private class UnexpectedTool : Tool {
    override val spec = ToolSpec(name = "unexpected", description = "always returns an Unexpected result")
    var invocations = 0
        private set
    override fun execute(input: Map<String, String>): ToolResult {
        invocations++
        return ToolResult.Unexpected("device reported an unrecognized state", raw = "state=7")
    }
}

private class ForgeOnlyTool : Tool {
    override val spec = ToolSpec(name = "forge-only", description = "scoped to Forge", allowedModes = setOf(AgentMode.FORGE))
    var invocations = 0
        private set
    override fun execute(input: Map<String, String>): ToolResult {
        invocations++
        return ToolResult.Success("ran")
    }
}

private class OwnerOnlyTool : Tool {
    override val spec = ToolSpec(name = "owner-only", description = "scoped to the device owner", requiredInitiator = setOf(Initiator.DEVICE_OWNER))
    var invocations = 0
        private set
    override fun execute(input: Map<String, String>): ToolResult {
        invocations++
        return ToolResult.Success("ran")
    }
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

    @Test
    fun `no mode filter (default) executes a mode-restricted tool`() {
        val tool = ForgeOnlyTool()
        val registry = ToolRegistry().apply { register(tool) }
        val executor = ToolExecutor(registry, AgentStateMachine(), sleep = { })
        val result = executor.run("forge-only", emptyMap())
        assertIs<ToolResult.Success>(result)
        assertEquals(1, tool.invocations)
    }

    @Test
    fun `rejects invoking a tool outside its allowed mode, without ever calling it`() {
        val tool = ForgeOnlyTool()
        val registry = ToolRegistry().apply { register(tool) }
        val machine = AgentStateMachine()
        val executor = ToolExecutor(registry, machine, sleep = { })
        val result = executor.run("forge-only", emptyMap(), mode = AgentMode.PILOT)
        assertIs<ToolResult.Failure>(result)
        assertEquals(0, tool.invocations)
        assertEquals(AgentState.Idle, machine.state) // rejected before any ExecutingTool transition
    }

    @Test
    fun `permits invoking a tool inside its allowed mode`() {
        val tool = ForgeOnlyTool()
        val registry = ToolRegistry().apply { register(tool) }
        val executor = ToolExecutor(registry, AgentStateMachine(), sleep = { })
        val result = executor.run("forge-only", emptyMap(), mode = AgentMode.FORGE)
        assertIs<ToolResult.Success>(result)
        assertEquals(1, tool.invocations)
    }

    @Test
    fun `no initiator filter (default) executes an initiator-restricted tool`() {
        val tool = OwnerOnlyTool()
        val registry = ToolRegistry().apply { register(tool) }
        val executor = ToolExecutor(registry, AgentStateMachine(), sleep = { })
        val result = executor.run("owner-only", emptyMap())
        assertIs<ToolResult.Success>(result)
        assertEquals(1, tool.invocations)
    }

    @Test
    fun `rejects invoking a tool from an initiator it does not permit, without ever calling it`() {
        val tool = OwnerOnlyTool()
        val registry = ToolRegistry().apply { register(tool) }
        val machine = AgentStateMachine()
        val executor = ToolExecutor(registry, machine, sleep = { })
        val result = executor.run("owner-only", emptyMap(), initiator = Initiator.AI)
        assertIs<ToolResult.Failure>(result)
        assertEquals(0, tool.invocations)
        assertEquals(AgentState.Idle, machine.state)
    }

    @Test
    fun `permits invoking a tool from an initiator it does allow`() {
        val tool = OwnerOnlyTool()
        val registry = ToolRegistry().apply { register(tool) }
        val executor = ToolExecutor(registry, AgentStateMachine(), sleep = { })
        val result = executor.run("owner-only", emptyMap(), initiator = Initiator.DEVICE_OWNER)
        assertIs<ToolResult.Success>(result)
        assertEquals(1, tool.invocations)
    }

    @Test
    fun `a Partial result is returned immediately, without retrying`() {
        val tool = PartialTool()
        val registry = ToolRegistry().apply { register(tool) }
        var sleeps = 0
        val executor = ToolExecutor(registry, AgentStateMachine(), sleep = { sleeps++ })
        val result = executor.run("partial", emptyMap(), retryPolicy = RetryPolicy(maxAttempts = 5))
        assertIs<ToolResult.Partial>(result)
        assertEquals("3 of 5 items processed", result.output)
        assertEquals(1, tool.invocations)
        assertEquals(0, sleeps)
    }

    @Test
    fun `an Unexpected result is returned immediately, without retrying`() {
        val tool = UnexpectedTool()
        val registry = ToolRegistry().apply { register(tool) }
        var sleeps = 0
        val executor = ToolExecutor(registry, AgentStateMachine(), sleep = { sleeps++ })
        val result = executor.run("unexpected", emptyMap(), retryPolicy = RetryPolicy(maxAttempts = 5))
        assertIs<ToolResult.Unexpected>(result)
        assertEquals("device reported an unrecognized state", result.description)
        assertEquals(1, tool.invocations)
        assertEquals(0, sleeps)
    }
}
