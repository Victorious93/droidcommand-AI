package ai.droidcommand.termux

import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.security.ApprovalPrompt
import ai.droidcommand.security.SecureToolExecutor
import ai.droidcommand.security.SecurityPolicy
import ai.droidcommand.security.SecurityPolicyEnforcer
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class CountingTermuxExecutor(private val result: TermuxExecutionResult) : TermuxExecutor {
    var executeCallCount = 0
        private set

    override fun isAvailable(): Boolean = true

    override fun execute(command: TermuxCommand, isCancelled: () -> Boolean): TermuxExecutionResult {
        executeCallCount++
        return result
    }
}

/**
 * Proves `run_termux_command` goes through core-security's real approval machinery, exactly
 * `ShellToolSecureExecutorIntegrationTest`'s convention — a denied invocation provably never reaches
 * [TermuxExecutor.execute].
 */
class TermuxToolSecureExecutorIntegrationTest {
    private fun secure(executor: TermuxExecutor, approvalPrompt: ApprovalPrompt): SecureToolExecutor {
        val registry = ToolRegistry().apply { register(TermuxTool(executor)) }
        val stateMachine = AgentStateMachine()
        val delegate = ToolExecutor(registry, stateMachine, sleep = { })
        val policy = SecurityPolicy(grantedCategories = setOf(PermissionCategory.TERMINAL))
        return SecureToolExecutor(registry, delegate, stateMachine, SecurityPolicyEnforcer(policy), approvalPrompt)
    }

    @Test
    fun `a denied approval never reaches the underlying TermuxExecutor`() {
        val executor = CountingTermuxExecutor(TermuxExecutionResult.Success(0, "should never run", "", 0))

        val result = secure(executor, ApprovalPrompt { false })
            .run("run_termux_command", mapOf("executable" to "echo", "args" to "hi"))

        assertIs<ToolResult.Failure>(result)
        assertTrue(result.reason.contains("denied"))
        assertTrue(executor.executeCallCount == 0)
    }

    @Test
    fun `a granted approval runs the real executor`() {
        val executor = CountingTermuxExecutor(TermuxExecutionResult.Success(0, "hi", "", 0))

        val result = secure(executor, ApprovalPrompt { true })
            .run("run_termux_command", mapOf("executable" to "echo", "args" to "hi"))

        assertIs<ToolResult.Success>(result)
        assertTrue(executor.executeCallCount == 1)
    }
}
