package ai.droidcommand.root

import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.security.ApprovalPrompt
import ai.droidcommand.security.Grant
import ai.droidcommand.security.GrantCheck
import ai.droidcommand.security.InMemoryGrantStore
import ai.droidcommand.security.SecureToolExecutor
import ai.droidcommand.security.SecurityPolicy
import ai.droidcommand.security.SecurityPolicyEnforcer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Proves the opt-in `grantCapability` path end to end for [RootTool]: an
 * AI-initiated root request additionally requires a live, named grant on
 * top of the ordinary root/approval check `RootToolSecureExecutorIntegrationTest`
 * already covers — and a single-use grant is only actually spent once the
 * command genuinely succeeds.
 */
class RootToolGrantIntegrationTest {
    private class ScriptedRootExecutor(private val result: RootExecutionResult) : RootExecutor {
        var executeCalls = 0
            private set

        override fun isRootAvailable(): Boolean = true

        override fun execute(command: RootCommand, isCancelled: () -> Boolean): RootExecutionResult {
            executeCalls++
            return result
        }
    }

    @Test
    fun `root tool with a grant requirement is denied without a live grant, and never executes`() {
        val executor = ScriptedRootExecutor(RootExecutionResult.Success(0, "uid=0(root)", "", 5))
        val registry = ToolRegistry().apply { register(RootTool(executor, grantCapability = "ai_root")) }
        val stateMachine = AgentStateMachine()
        val delegate = ToolExecutor(registry, stateMachine, sleep = { })
        val grantStore = InMemoryGrantStore()
        val secure = SecureToolExecutor(
            registry,
            delegate,
            stateMachine,
            SecurityPolicyEnforcer(
                SecurityPolicy(rootEnabled = true, rootAvailable = { true }, grantedCategories = setOf(PermissionCategory.ROOT)),
            ),
            ApprovalPrompt { true },
            grantStore = grantStore,
        )

        val result = secure.run("run_root_command", mapOf("executable" to "id"), grantId = null)

        assertIs<ToolResult.Failure>(result)
        assertEquals(0, executor.executeCalls)
    }

    @Test
    fun `root tool with a live ai_root grant executes once, then the grant is spent`() {
        val executor = ScriptedRootExecutor(RootExecutionResult.Success(0, "uid=0(root)", "", 5))
        val registry = ToolRegistry().apply { register(RootTool(executor, grantCapability = "ai_root")) }
        val stateMachine = AgentStateMachine()
        val delegate = ToolExecutor(registry, stateMachine, sleep = { })
        val grantStore = InMemoryGrantStore().apply { issue(Grant(id = "g1", capability = "ai_root")) }
        val secure = SecureToolExecutor(
            registry,
            delegate,
            stateMachine,
            SecurityPolicyEnforcer(
                SecurityPolicy(rootEnabled = true, rootAvailable = { true }, grantedCategories = setOf(PermissionCategory.ROOT)),
            ),
            ApprovalPrompt { true },
            grantStore = grantStore,
        )

        val result = secure.run("run_root_command", mapOf("executable" to "id"), grantId = "g1")

        assertIs<ToolResult.Success>(result)
        assertEquals(1, executor.executeCalls)
        assertIs<GrantCheck.Denied>(grantStore.check("g1", "ai_root")) // consumed after the successful run
    }
}
