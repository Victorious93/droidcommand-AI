package ai.droidcommand.app.automation

import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.llm.LlmResponse
import ai.droidcommand.shell.ShellCommand
import ai.droidcommand.shell.ShellExecutionResult
import ai.droidcommand.shell.ShellTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AutomationToolsTest {
    @Test
    fun `the shell tool name matches the name the shell tool registers under`() {
        assertEquals(ShellTool(FakeShell()).spec.name, AutomationShellTool.NAME)
    }

    @Test
    fun `neither automation tool is offered to the planners`() {
        assertTrue(AutomationShellTool(FakeShell()).spec.allowedModes.isEmpty())
        assertTrue(AiPromptTool(clientFor(ScriptedProvider { LlmResponse.Text("x") })).spec.allowedModes.isEmpty())
    }

    @Test
    fun `the shell tool stays sensitive, so every run asks for approval`() {
        val spec = AutomationShellTool(FakeShell()).spec
        assertEquals(SecurityLevel.SENSITIVE, spec.securityLevel)
        assertTrue(spec.requiresConfirmation)
    }

    @Test
    fun `with an empty allowlist the executor refuses without starting a process`() {
        val result = AllowlistShellExecutor { emptySet() }.execute(ShellCommand("ls"))
        assertIs<ShellExecutionResult.Failure>(result)
    }

    @Test
    fun `the AI tool sends its prompt with the chosen model and returns the reply`() {
        val provider = ScriptedProvider { LlmResponse.Text("pong") }
        val models = mutableListOf<String>()
        val tool = AiPromptTool(clientFor(provider), modelFor = { spec -> "chosen-${spec.id}".also { models += it } })
        assertEquals(ToolResult.Success("pong"), tool.execute(mapOf("provider" to "anthropic", "prompt" to "ping")))
        assertEquals(listOf("chosen-anthropic"), models)
        assertEquals("ping", provider.requests.single().messages.single().content)
    }

    @Test
    fun `the AI tool refuses a missing input or an unknown provider`() {
        val tool = AiPromptTool(clientFor(ScriptedProvider { LlmResponse.Text("x") }))
        assertIs<ToolResult.Failure>(tool.execute(mapOf("prompt" to "ping")))
        assertIs<ToolResult.Failure>(tool.execute(mapOf("provider" to "anthropic")))
        assertIs<ToolResult.Failure>(tool.execute(mapOf("provider" to "nosuch", "prompt" to "ping")))
    }
}
