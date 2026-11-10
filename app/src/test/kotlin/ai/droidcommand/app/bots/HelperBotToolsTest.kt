package ai.droidcommand.app.bots

import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolRunner
import ai.droidcommand.agent.ToolSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HelperBotToolsTest {
    private val registry = ToolRegistry().apply {
        register(NamedTool("allowed_tool"))
        register(NamedTool("secret_tool"))
    }

    @Test
    fun `offered tools are only the ones on the allow-list`() {
        val offered = HelperBotTools.offeredTools(registry, setOf("allowed_tool"))
        assertEquals(listOf("allowed_tool"), offered.map { it.name })
    }

    @Test
    fun `a tool not on the list is refused without calling the delegate`() {
        val probe = ProbeRunner()
        val runner = HelperBotTools.scopedRunner(probe, setOf("allowed_tool"))
        val result = runner.run("secret_tool", emptyMap())
        val failure = assertIs<ToolResult.Failure>(result)
        assertTrue(failure.reason.contains("secret_tool"))
        assertTrue(failure.reason.contains("allowed-tool list"))
        assertEquals(emptyList(), probe.called)
    }

    @Test
    fun `an allowed tool is passed to the delegate`() {
        val probe = ProbeRunner()
        val runner = HelperBotTools.scopedRunner(probe, setOf("allowed_tool"))
        val result = runner.run("allowed_tool", mapOf("q" to "1"))
        assertEquals(ToolResult.Success("ok:allowed_tool"), result)
        assertEquals(listOf("allowed_tool"), probe.called)
    }

    @Test
    fun `an empty allow-list refuses every tool`() {
        val probe = ProbeRunner()
        val runner = HelperBotTools.scopedRunner(probe, emptySet())
        assertIs<ToolResult.Failure>(runner.run("allowed_tool", emptyMap()))
        assertEquals(emptyList(), probe.called)
    }

    private class NamedTool(name: String) : Tool {
        override val spec = ToolSpec(name = name, description = name)
        override fun execute(input: Map<String, String>): ToolResult = ToolResult.Success(spec.name)
    }

    private class ProbeRunner : ToolRunner {
        val called = mutableListOf<String>()

        override fun run(
            toolName: String,
            input: Map<String, String>,
            retryPolicy: ai.droidcommand.agent.RetryPolicy,
            isCancelled: () -> Boolean,
            mode: ai.droidcommand.agent.AgentMode?,
            initiator: ai.droidcommand.agent.Initiator?,
            grantId: String?,
        ): ToolResult {
            called += toolName
            return ToolResult.Success("ok:$toolName")
        }
    }
}
