package ai.droidcommand.bots

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.RetryPolicy
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class RecordingToolRunner : ToolRunner {
    val calls = mutableListOf<String>()
    var nextResult: ToolResult = ToolResult.Success("ok")

    override fun run(
        toolName: String,
        input: Map<String, String>,
        retryPolicy: RetryPolicy,
        isCancelled: () -> Boolean,
        mode: AgentMode?,
        initiator: Initiator?,
        grantId: String?,
    ): ToolResult {
        calls += toolName
        return nextResult
    }
}

class ScopedToolRunnerTest {
    @Test
    fun `a tool in the allow-list delegates and returns the delegate's result`() {
        val delegate = RecordingToolRunner().apply { nextResult = ToolResult.Success("web result") }
        val runner = ScopedToolRunner(delegate, setOf("web_search_tool"))

        val result = runner.run("web_search_tool", emptyMap())

        assertEquals(listOf("web_search_tool"), delegate.calls)
        assertEquals(ToolResult.Success("web result"), result)
    }

    @Test
    fun `a tool not in the allow-list is denied without the delegate ever being invoked`() {
        val delegate = RecordingToolRunner()
        val runner = ScopedToolRunner(delegate, setOf("web_search_tool"))

        val result = runner.run("shell_tool", emptyMap())

        assertTrue(delegate.calls.isEmpty())
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).reason.contains("shell_tool"))
    }

    @Test
    fun `an empty allow-list denies everything`() {
        val delegate = RecordingToolRunner()
        val runner = ScopedToolRunner(delegate, emptySet())

        val result = runner.run("web_search_tool", emptyMap())

        assertTrue(delegate.calls.isEmpty())
        assertTrue(result is ToolResult.Failure)
    }
}
