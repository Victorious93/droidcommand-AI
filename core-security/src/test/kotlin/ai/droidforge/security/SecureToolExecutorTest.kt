package ai.droidforge.security

import ai.droidforge.agent.AgentState
import ai.droidforge.agent.AgentStateMachine
import ai.droidforge.agent.SecurityLevel
import ai.droidforge.agent.Tool
import ai.droidforge.agent.ToolExecutor
import ai.droidforge.agent.ToolRegistry
import ai.droidforge.agent.ToolResult
import ai.droidforge.agent.ToolSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class CountingTool(override val spec: ToolSpec) : Tool {
    var invocations = 0
        private set

    override fun execute(input: Map<String, String>): ToolResult {
        invocations++
        return ToolResult.Success("ran")
    }
}

private fun newHarness(
    spec: ToolSpec,
    policy: SecurityPolicy,
    approvalPrompt: ApprovalPrompt = ApprovalPrompt { true },
): Quad {
    val tool = CountingTool(spec)
    val registry = ToolRegistry().apply { register(tool) }
    val stateMachine = AgentStateMachine()
    val delegate = ToolExecutor(registry, stateMachine, sleep = { })
    val secure = SecureToolExecutor(registry, delegate, stateMachine, SecurityPolicyEnforcer(policy), approvalPrompt)
    return Quad(secure, tool, stateMachine, registry)
}

private data class Quad(
    val executor: SecureToolExecutor,
    val tool: CountingTool,
    val stateMachine: AgentStateMachine,
    val registry: ToolRegistry,
)

class SecureToolExecutorTest {
    @Test
    fun `denies a root tool when root is unavailable and never invokes it`() {
        val spec = ToolSpec(name = "rm", description = "d", requiresRoot = true, securityLevel = SecurityLevel.ROOT)
        val (secure, tool, _, _) = newHarness(spec, SecurityPolicy(rootEnabled = true, rootAvailable = { false }))

        val result = secure.run("rm", emptyMap())

        assertIs<ToolResult.Failure>(result)
        assertEquals(0, tool.invocations)
    }

    @Test
    fun `runs a root tool when root is available and approval is granted`() {
        val spec = ToolSpec(name = "rm", description = "d", requiresRoot = true, securityLevel = SecurityLevel.ROOT)
        val (secure, tool, _, _) = newHarness(
            spec,
            SecurityPolicy(rootEnabled = true, rootAvailable = { true }),
            approvalPrompt = ApprovalPrompt { true },
        )

        val result = secure.run("rm", emptyMap())

        assertIs<ToolResult.Success>(result)
        assertEquals(1, tool.invocations)
    }

    @Test
    fun `never invokes the tool when the user denies authorization`() {
        val spec = ToolSpec(name = "delete-app", description = "d", securityLevel = SecurityLevel.SENSITIVE)
        val (secure, tool, _, _) = newHarness(spec, SecurityPolicy(), approvalPrompt = ApprovalPrompt { false })

        val result = secure.run("delete-app", emptyMap())

        assertIs<ToolResult.Failure>(result)
        assertTrue((result as ToolResult.Failure).reason.contains("denied"))
        assertEquals(0, tool.invocations)
    }

    @Test
    fun `denies and never invokes when a required permission is missing`() {
        val spec = ToolSpec(name = "record", description = "d", requiredPermissions = setOf("MICROPHONE"))
        val (secure, tool, _, _) = newHarness(spec, SecurityPolicy(grantedPermissions = emptySet()))

        val result = secure.run("record", emptyMap())

        assertIs<ToolResult.Failure>(result)
        assertEquals(0, tool.invocations)
    }

    @Test
    fun `transitions to AwaitingApproval before prompting, and to Observing once resolved`() {
        val spec = ToolSpec(name = "delete-app", description = "d", securityLevel = SecurityLevel.SENSITIVE)
        val registry = ToolRegistry().apply { register(CountingTool(spec)) }
        val stateMachine = AgentStateMachine()
        val delegate = ToolExecutor(registry, stateMachine, sleep = { })
        var stateAtPromptTime: AgentState? = null
        val secure = SecureToolExecutor(
            registry,
            delegate,
            stateMachine,
            SecurityPolicyEnforcer(SecurityPolicy()),
            approvalPrompt = ApprovalPrompt { reason ->
                stateAtPromptTime = stateMachine.state
                true
            },
        )

        secure.run("delete-app", emptyMap())

        assertIs<AgentState.AwaitingApproval>(stateAtPromptTime)
        assertIs<AgentState.Observing>(stateMachine.state)
    }

    @Test
    fun `auto-approved security levels never invoke the approval prompt`() {
        val spec = ToolSpec(name = "delete-app", description = "d", securityLevel = SecurityLevel.SENSITIVE)
        var promptCalls = 0
        val (secure, tool, _, _) = newHarness(
            spec,
            SecurityPolicy(autoApprove = setOf(SecurityLevel.NORMAL, SecurityLevel.SENSITIVE)),
            approvalPrompt = ApprovalPrompt { promptCalls++; true },
        )

        val result = secure.run("delete-app", emptyMap())

        assertIs<ToolResult.Success>(result)
        assertEquals(0, promptCalls)
        assertEquals(1, tool.invocations)
    }
}
