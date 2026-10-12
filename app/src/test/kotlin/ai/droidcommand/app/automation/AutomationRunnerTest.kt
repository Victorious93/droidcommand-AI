package ai.droidcommand.app.automation

import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolRunner
import ai.droidcommand.llm.LlmResponse
import ai.droidcommand.security.ApprovalPrompt
import ai.droidcommand.security.InMemoryAuditLog
import ai.droidcommand.security.SecureToolExecutor
import ai.droidcommand.security.SecurityPolicy
import ai.droidcommand.security.SecurityPolicyEnforcer
import ai.droidcommand.shell.ShellExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutomationRunnerTest {
    private val shellKey = AutomationKey("key-shell", "List", AutomationAction.Shell("ls", "-la"))
    private val aiKey = AutomationKey("key-ai", "Ask", AutomationAction.Ai("anthropic", "ping"))

    /** The same gate the app builds: policy, approval and audit around the real tool executor. */
    private fun gate(tools: List<Tool>, approval: ApprovalPrompt): ToolRunner {
        val registry = ToolRegistry().apply { tools.forEach { register(it) } }
        val state = AgentStateMachine()
        return SecureToolExecutor(
            registry,
            ToolExecutor(registry, state),
            state,
            SecurityPolicyEnforcer(
                SecurityPolicy(grantedCategories = setOf(PermissionCategory.TERMINAL, PermissionCategory.NETWORK)),
            ),
            approval,
            auditLog = InMemoryAuditLog(),
        )
    }

    @Test
    fun `a shell key whose executable is not allowed is refused before any approval is asked`() {
        val shell = FakeShell()
        val approval = RecordingApproval(answer = true)
        val result = AutomationRunner(gate(listOf(AutomationShellTool(shell)), approval)) { emptySet() }.run(shellKey)
        assertFalse(result.succeeded)
        assertTrue(result.text.contains("'ls' is not in the allowed executables"))
        assertEquals(emptyList(), approval.asked)
        assertEquals(emptyList(), shell.commands)
    }

    @Test
    fun `an allowed shell key that the owner denies never reaches the shell`() {
        val shell = FakeShell()
        val approval = RecordingApproval(answer = false)
        val result = AutomationRunner(gate(listOf(AutomationShellTool(shell)), approval)) { setOf("ls") }.run(shellKey)
        assertFalse(result.succeeded)
        assertTrue(result.text.contains("User denied authorization"))
        assertEquals(listOf(AutomationShellTool.NAME), approval.asked)
        assertEquals(emptyList(), shell.commands)
    }

    @Test
    fun `an allowed shell key the owner approves runs and returns its output`() {
        val shell = FakeShell()
        val result = AutomationRunner(gate(listOf(AutomationShellTool(shell)), RecordingApproval(answer = true))) { setOf("ls") }
            .run(shellKey)
        assertEquals(AutomationRunResult(true, "out"), result)
        assertEquals("ls", shell.commands.single().executable)
        assertEquals(listOf("-la"), shell.commands.single().args)
    }

    @Test
    fun `a failing shell command reports its exit code and does not count as a success`() {
        val shell = FakeShell(ShellExecutionResult.Success(2, "", "no such file", 1))
        val result = AutomationRunner(gate(listOf(AutomationShellTool(shell)), RecordingApproval(answer = true))) { setOf("ls") }
            .run(shellKey)
        assertFalse(result.succeeded)
        assertTrue(result.text.contains("exited with code 2"))
    }

    @Test
    fun `an AI key the owner approves sends its prompt and returns the reply`() {
        val provider = ScriptedProvider { LlmResponse.Text("pong") }
        val approval = RecordingApproval(answer = true)
        val result = AutomationRunner(gate(listOf(AiPromptTool(clientFor(provider))), approval)) { emptySet() }.run(aiKey)
        assertEquals(AutomationRunResult(true, "pong"), result)
        assertEquals("ping", provider.requests.single().messages.single().content)
        assertEquals(listOf(AiPromptTool.NAME), approval.asked)
    }

    @Test
    fun `an AI key the owner denies sends nothing`() {
        val provider = ScriptedProvider { LlmResponse.Text("pong") }
        val result = AutomationRunner(gate(listOf(AiPromptTool(clientFor(provider))), RecordingApproval(answer = false))) { emptySet() }
            .run(aiKey)
        assertFalse(result.succeeded)
        assertEquals(emptyList(), provider.requests)
    }
}
