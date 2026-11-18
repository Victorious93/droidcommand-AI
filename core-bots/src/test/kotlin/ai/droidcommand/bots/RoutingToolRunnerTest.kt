package ai.droidcommand.bots

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.RetryPolicy
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolRunner
import ai.droidcommand.agent.ToolSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private class Named(private val label: String) : ToolRunner {
    override fun run(
        toolName: String,
        input: Map<String, String>,
        retryPolicy: RetryPolicy,
        isCancelled: () -> Boolean,
        mode: AgentMode?,
        initiator: Initiator?,
        grantId: String?,
    ): ToolResult = ToolResult.Success("$label:$toolName")
}

private fun tool(name: String) = object : Tool {
    override val spec = ToolSpec(name, "d")
    override fun execute(input: Map<String, String>): ToolResult = ToolResult.Success(name)
}

class RoutingToolRunnerTest {
    private val runner = RoutingToolRunner(setOf("sandbox_run"), Named("own"), Named("app"))

    @Test
    fun `bound tool names go to the bot's own runner, everything else to the app's`() {
        assertEquals(ToolResult.Success("own:sandbox_run"), runner.run("sandbox_run", emptyMap()))
        assertEquals(ToolResult.Success("app:run_shell"), runner.run("run_shell", emptyMap()))
    }

    @Test
    fun `a name that only looks similar is not routed to the bot's runner`() {
        assertEquals(ToolResult.Success("app:sandbox_run2"), runner.run("sandbox_run2", emptyMap()))
    }

    @Test
    fun `a kit exposes its names and specs and refuses duplicates`() {
        val kit = BotToolKit(listOf(tool("a"), tool("b")))
        assertEquals(setOf("a", "b"), kit.names)
        assertEquals(listOf("a", "b"), kit.specs.map { it.name })
        assertFailsWith<IllegalArgumentException> { BotToolKit(listOf(tool("a"), tool("a"))) }
    }
}
