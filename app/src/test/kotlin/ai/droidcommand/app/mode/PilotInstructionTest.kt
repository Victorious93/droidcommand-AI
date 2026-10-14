package ai.droidcommand.app.mode

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PilotInstructionTest {
    private class EchoTool : Tool {
        override val spec = ToolSpec(name = "echo", description = "Repeats text")
        override fun execute(input: Map<String, String>): ToolResult = ToolResult.Success(input["text"].orEmpty())
    }

    private class ForgeOnlyTool : Tool {
        override val spec = ToolSpec(name = "forge_only", description = "Forge", allowedModes = setOf(AgentMode.FORGE))
        override fun execute(input: Map<String, String>): ToolResult = ToolResult.Success("should not run")
    }

    @Test
    fun `parse accepts key=value and rejects a line with no key`() {
        val ok = PilotArguments.parse("executable=echo\n\nargs=hello=world")
        assertIs<PilotArguments.Ok>(ok)
        assertEquals(mapOf("executable" to "echo", "args" to "hello=world"), ok.input)
        val bad = PilotArguments.parse("no-equals")
        assertIs<PilotArguments.Invalid>(bad)
    }

    @Test
    fun `Ok input is read through an exhaustive when`() {
        val input = when (val parsed = PilotArguments.parse("text=hi")) {
            is PilotArguments.Ok -> parsed.input
            is PilotArguments.Invalid -> error(parsed.message)
        }
        assertEquals(mapOf("text" to "hi"), input)
    }

    @Test
    fun `run goes through a Pilot session`() {
        val registry = ToolRegistry().apply { register(EchoTool()) }
        val instruction = PilotInstruction(registry) { machine -> ToolExecutor(registry, machine) }
        val result = instruction.run("echo", mapOf("text" to "hi"))
        assertEquals("hi", assertIs<ToolResult.Success>(result).output)
    }

    @Test
    fun `a Forge-only tool is rejected while Pilot is active`() {
        val registry = ToolRegistry().apply { register(ForgeOnlyTool()) }
        val instruction = PilotInstruction(registry) { machine -> ToolExecutor(registry, machine) }
        val result = instruction.run("forge_only", emptyMap())
        val failure = assertIs<ToolResult.Failure>(result)
        assertTrue(failure.reason.contains("PILOT"))
    }
}
