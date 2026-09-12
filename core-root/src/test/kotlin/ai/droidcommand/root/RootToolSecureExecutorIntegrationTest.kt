package ai.droidcommand.root

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
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Exercises the "ROOT TEST MATRIX" (root unavailable, root available,
 * user denies authorization, command failure) end to end against
 * core-security's real, already-tested SecureToolExecutor/
 * SecurityPolicyEnforcer — this module supplies the Tool and the
 * RootExecutor; it never reimplements the gate itself.
 */
class RootToolSecureExecutorIntegrationTest {
    private class ScriptedRootExecutor(
        private val rootAvailable: Boolean,
        private val result: RootExecutionResult = RootExecutionResult.Failure("not scripted"),
    ) : RootExecutor {
        var executeCalls = 0
            private set

        override fun isRootAvailable(): Boolean = rootAvailable

        override fun execute(command: RootCommand, isCancelled: () -> Boolean): RootExecutionResult {
            executeCalls++
            return result
        }
    }

    private fun secure(executor: RootExecutor, policy: SecurityPolicy, approvalPrompt: ApprovalPrompt): SecureToolExecutor {
        val registry = ToolRegistry().apply { register(RootTool(executor)) }
        val stateMachine = AgentStateMachine()
        val delegate = ToolExecutor(registry, stateMachine, sleep = { })
        return SecureToolExecutor(registry, delegate, stateMachine, SecurityPolicyEnforcer(policy), approvalPrompt)
    }

    @Test
    fun `root disabled for the session denies outright, without ever prompting or executing`() {
        val executor = ScriptedRootExecutor(rootAvailable = true)
        var promptCalls = 0

        val result = secure(
            executor,
            SecurityPolicy(rootEnabled = false, rootAvailable = { true }),
            ApprovalPrompt {
                promptCalls++
                true
            },
        ).run("run_root_command", mapOf("executable" to "id"))

        assertIs<ToolResult.Failure>(result)
        assertTrue(result.reason.contains("disabled"))
        assertEquals(0, promptCalls)
        assertEquals(0, executor.executeCalls)
    }

    @Test
    fun `root enabled but unavailable on the device denies outright`() {
        val executor = ScriptedRootExecutor(rootAvailable = false)
        var promptCalls = 0

        val result = secure(
            executor,
            SecurityPolicy(rootEnabled = true, rootAvailable = { executor.isRootAvailable() }),
            ApprovalPrompt {
                promptCalls++
                true
            },
        ).run("run_root_command", mapOf("executable" to "id"))

        assertIs<ToolResult.Failure>(result)
        assertTrue(result.reason.contains("unavailable"))
        assertEquals(0, promptCalls)
        assertEquals(0, executor.executeCalls)
    }

    @Test
    fun `root enabled and available, but the user denies authorization`() {
        val executor = ScriptedRootExecutor(rootAvailable = true)

        val result = secure(
            executor,
            SecurityPolicy(rootEnabled = true, rootAvailable = { true }, grantedCategories = setOf(PermissionCategory.ROOT)),
            ApprovalPrompt { false },
        ).run("run_root_command", mapOf("executable" to "id"))

        assertIs<ToolResult.Failure>(result)
        assertTrue(result.reason.contains("denied"))
        assertEquals(0, executor.executeCalls)
    }

    @Test
    fun `root enabled, available, and approved actually reaches the executor`() {
        val executor = ScriptedRootExecutor(rootAvailable = true, result = RootExecutionResult.Success(0, "uid=0(root)", "", 5))

        val result = secure(
            executor,
            SecurityPolicy(rootEnabled = true, rootAvailable = { true }, grantedCategories = setOf(PermissionCategory.ROOT)),
            ApprovalPrompt { true },
        ).run("run_root_command", mapOf("executable" to "id"))

        assertIs<ToolResult.Success>(result)
        assertEquals(1, executor.executeCalls)
    }

    @Test
    fun `a command failure after approval surfaces through ToolResult, not as an exception`() {
        val executor = ScriptedRootExecutor(rootAvailable = true, result = RootExecutionResult.Failure("permission denied by SELinux"))

        val result = secure(
            executor,
            SecurityPolicy(rootEnabled = true, rootAvailable = { true }, grantedCategories = setOf(PermissionCategory.ROOT)),
            ApprovalPrompt { true },
        ).run("run_root_command", mapOf("executable" to "rm"))

        assertIs<ToolResult.Failure>(result)
        assertEquals("permission denied by SELinux", result.reason)
    }
}
