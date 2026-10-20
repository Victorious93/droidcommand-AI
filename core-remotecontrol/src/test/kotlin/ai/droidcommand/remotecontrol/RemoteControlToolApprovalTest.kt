package ai.droidcommand.remotecontrol

import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.security.ApprovalPrompt
import ai.droidcommand.security.AutoApprovalDecision
import ai.droidcommand.security.DefaultActionRisks
import ai.droidcommand.security.SecureToolExecutor
import ai.droidcommand.security.SecurityPolicy
import ai.droidcommand.security.SecurityPolicyEnforcer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RemoteControlToolApprovalTest {
    private val store = InMemoryRemoteControlStore(
        RemoteControlConfig(
            mappings = listOf(
                mapping(id = "ping", smsCommandId = "ping", action = RemoteAction.CUSTOM, destructive = false),
                mapping(id = "wipe", smsCommandId = "wipe", voicePhrase = "wipe it", action = RemoteAction.WIPE, destructive = true),
            ),
        ),
    )
    private val counting = CountingExecutor()
    private val tools = RemoteControlTool.all(counting, store)

    @Test
    fun `every remote tool name is hard-gated when YOLO is off`() {
        val policy = DefaultActionRisks.policy()
        tools.forEach { tool ->
            val decision = policy.evaluate(tool.spec)
            assertIs<AutoApprovalDecision.RequireApproval>(decision, tool.spec.name)
        }
    }

    @Test
    fun `YOLO waives non-destructive remote tools and still refuses wipe power-off lock and custom-destructive`() {
        val policy = DefaultActionRisks.policy(yoloMode = true)
        val waived = tools.filter {
            it.spec.name in setOf(
                RemoteControlToolNames.COMMAND,
                RemoteControlToolNames.SCREEN,
                RemoteControlToolNames.MACRO,
            )
        }
        val gated = tools.filter {
            it.spec.name in setOf(
                RemoteControlToolNames.WIPE,
                RemoteControlToolNames.POWER_OFF,
                RemoteControlToolNames.LOCK,
                RemoteControlToolNames.DESTRUCTIVE,
            )
        }
        waived.forEach { tool ->
            assertIs<AutoApprovalDecision.AutoApprove>(policy.evaluate(tool.spec), tool.spec.name)
        }
        gated.forEach { tool ->
            val decision = assertIs<AutoApprovalDecision.RequireApproval>(policy.evaluate(tool.spec), tool.spec.name)
            assertTrue(decision.reason.contains("never auto-approved"), decision.reason)
        }
    }

    @Test
    fun `a denied confirmation never reaches the stub executor`() {
        val registry = ToolRegistry().apply { tools.forEach { register(it) } }
        var prompts = 0
        val executor = SecureToolExecutor(
            registry,
            ToolExecutor(registry, AgentStateMachine()),
            AgentStateMachine(),
            SecurityPolicyEnforcer(
                SecurityPolicy(
                    grantedCategories = setOf(PermissionCategory.REMOTE_CONTROL),
                    autoApprovalPolicy = DefaultActionRisks.policy(),
                ),
            ),
            ApprovalPrompt {
                prompts++
                false
            },
        )
        val result = assertIs<ToolResult.Failure>(executor.run(RemoteControlToolNames.WIPE, mapOf("mappingId" to "wipe")))
        assertTrue(result.reason.contains("denied") || result.reason.contains("approval") || result.reason.isNotBlank(), result.reason)
        assertEquals(1, prompts)
        assertEquals(0, counting.calls)
    }

    @Test
    fun `an approved run still hits the stub and does not claim success`() {
        val registry = ToolRegistry().apply { tools.forEach { register(it) } }
        val executor = SecureToolExecutor(
            registry,
            ToolExecutor(registry, AgentStateMachine()),
            AgentStateMachine(),
            SecurityPolicyEnforcer(
                SecurityPolicy(
                    grantedCategories = setOf(PermissionCategory.REMOTE_CONTROL),
                    autoApprovalPolicy = DefaultActionRisks.policy(yoloMode = true),
                ),
            ),
            ApprovalPrompt { error("prompt should have been waived for non-destructive YOLO") },
        )
        val result = assertIs<ToolResult.Failure>(executor.run(RemoteControlToolNames.COMMAND, mapOf("mappingId" to "ping")))
        assertEquals(1, counting.calls)
        assertTrue(result.reason.contains("not implemented"), result.reason)
    }
}

private class CountingExecutor : RemoteActionExecutor {
    var calls = 0
        private set

    override fun execute(plan: RemoteDispatchPlan): RemoteActionResult {
        calls++
        return StubRemoteActionExecutor().execute(plan)
    }
}
