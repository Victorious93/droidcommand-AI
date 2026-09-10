package ai.droidcommand.shell

import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.security.ApprovalPrompt
import ai.droidcommand.security.SecureToolExecutor
import ai.droidcommand.security.SecurityPolicy
import ai.droidcommand.security.SecurityPolicyEnforcer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Proves shell execution goes through core-security's real approval
 * machinery — using the real ProcessBuilderShellExecutor, not a fake, so a
 * denied command provably never spawns a real OS process (verified via a
 * real allow-listed command's own side effect never happening).
 */
class ShellToolSecureExecutorIntegrationTest {
    private fun secure(executor: ShellExecutor, approvalPrompt: ApprovalPrompt): SecureToolExecutor {
        val registry = ToolRegistry().apply { register(ShellTool(executor)) }
        val stateMachine = AgentStateMachine()
        val delegate = ToolExecutor(registry, stateMachine, sleep = { })
        return SecureToolExecutor(registry, delegate, stateMachine, SecurityPolicyEnforcer(SecurityPolicy()), approvalPrompt)
    }

    @Test
    fun `run_shell_command is SENSITIVE and requires confirmation`() {
        val spec = ShellTool(ProcessBuilderShellExecutor(ShellSecurityPolicy())).spec
        assertEquals(SecurityLevel.SENSITIVE, spec.securityLevel)
        assertTrue(spec.requiresConfirmation)
    }

    @Test
    fun `a denied approval never spawns a real process`() {
        val marker = java.nio.file.Files.createTempFile("droidcommand-shell-integration", ".marker").toFile()
        marker.delete() // the "touch" command below would recreate it if it ever actually ran
        val executor = ProcessBuilderShellExecutor(ShellSecurityPolicy(allowedExecutables = setOf("touch")))

        val result = secure(executor, ApprovalPrompt { false })
            .run("run_shell_command", mapOf("executable" to "touch", "args" to marker.path))

        assertIs<ToolResult.Failure>(result)
        assertTrue(result.reason.contains("denied"))
        assertTrue(!marker.exists())
    }

    @Test
    fun `a granted approval runs the real command`() {
        val marker = java.nio.file.Files.createTempFile("droidcommand-shell-integration", ".marker").toFile()
        marker.delete()
        val executor = ProcessBuilderShellExecutor(ShellSecurityPolicy(allowedExecutables = setOf("touch")))

        val result = secure(executor, ApprovalPrompt { true })
            .run("run_shell_command", mapOf("executable" to "touch", "args" to marker.path))

        assertIs<ToolResult.Success>(result)
        assertTrue(marker.exists())
        marker.delete()
    }
}
