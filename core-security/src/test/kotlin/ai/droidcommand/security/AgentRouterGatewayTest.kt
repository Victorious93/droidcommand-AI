package ai.droidcommand.security

import ai.droidcommand.agent.CapabilityId
import ai.droidcommand.agent.ExecutionRequest
import ai.droidcommand.agent.ExecutionResponse
import ai.droidcommand.agent.ExecutionTargetType
import ai.droidcommand.agent.InMemoryToolCapabilityRegistry
import ai.droidcommand.agent.RegisteredCapability
import ai.droidcommand.agent.RiskTier
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class NoopTool(name: String, securityLevel: SecurityLevel = SecurityLevel.NORMAL, requiresRoot: Boolean = false) : Tool {
    override val spec = ToolSpec(name = name, description = "no-op", securityLevel = securityLevel, requiresRoot = requiresRoot)

    var calls = 0
        private set

    override fun execute(input: Map<String, String>): ToolResult {
        calls++
        return ToolResult.Success("noop")
    }
}

private fun request(id: String, targetType: ExecutionTargetType = ExecutionTargetType.LOCAL_PC, riskTier: RiskTier = RiskTier.READ_ONLY) =
    ExecutionRequest(CapabilityId(id), targetType, emptyMap(), riskTier)

class AgentRouterGatewayTest {
    /** A policy whose [SecurityPolicy.rootAvailable] throws if ever consulted — proves [SecurityPolicyEnforcer.authorize] was never called, not just assumed. */
    private fun policyThatMustNotBeConsulted() = SecurityPolicy(rootAvailable = { error("SecurityPolicyEnforcer.authorize should not have been called") })

    @Test
    fun `an unregistered capability short-circuits to CapabilityUnavailable without calling the enforcer`() {
        val gateway = AgentRouterGateway(InMemoryToolCapabilityRegistry(), SecurityPolicyEnforcer(policyThatMustNotBeConsulted()))

        val result = gateway.route(request("nope"))

        assertIs<ExecutionResponse.CapabilityUnavailable>(result)
    }

    @Test
    fun `a mismatched target type short-circuits to CapabilityUnavailable without calling the enforcer or executing the tool`() {
        val tool = NoopTool("shell.echo", securityLevel = SecurityLevel.ROOT, requiresRoot = true)
        val registry = InMemoryToolCapabilityRegistry().apply {
            register(RegisteredCapability(CapabilityId("shell.echo"), ExecutionTargetType.LOCAL_PC, tool))
        }
        val gateway = AgentRouterGateway(registry, SecurityPolicyEnforcer(policyThatMustNotBeConsulted()))

        val result = gateway.route(request("shell.echo", targetType = ExecutionTargetType.ANDROID))

        assertIs<ExecutionResponse.CapabilityUnavailable>(result)
        assertEquals(0, tool.calls)
    }

    @Test
    fun `a NORMAL tool auto-approved by policy returns null, not a fabricated Success`() {
        val registry = InMemoryToolCapabilityRegistry().apply {
            register(RegisteredCapability(CapabilityId("shell.echo"), ExecutionTargetType.LOCAL_PC, NoopTool("shell.echo")))
        }
        val gateway = AgentRouterGateway(registry, SecurityPolicyEnforcer(SecurityPolicy()))

        assertNull(gateway.route(request("shell.echo")))
    }

    @Test
    fun `a SENSITIVE tool requires approval, carrying the request's own riskTier and a generated requestId`() {
        val registry = InMemoryToolCapabilityRegistry().apply {
            register(
                RegisteredCapability(
                    CapabilityId("device.uninstall"),
                    ExecutionTargetType.ANDROID,
                    NoopTool("device.uninstall", securityLevel = SecurityLevel.SENSITIVE),
                ),
            )
        }
        val gateway = AgentRouterGateway(registry, SecurityPolicyEnforcer(SecurityPolicy()))

        val result = gateway.route(request("device.uninstall", ExecutionTargetType.ANDROID, RiskTier.DESTRUCTIVE))

        val approval = assertIs<ExecutionResponse.RequiresApproval>(result)
        assertEquals(RiskTier.DESTRUCTIVE, approval.riskTier)
        assertTrue(approval.requestId.isNotBlank())
    }

    @Test
    fun `a root-required tool under a policy with root disabled is Denied with the enforcer's real reason`() {
        val registry = InMemoryToolCapabilityRegistry().apply {
            register(
                RegisteredCapability(
                    CapabilityId("root.rm"),
                    ExecutionTargetType.ANDROID,
                    NoopTool("root.rm", securityLevel = SecurityLevel.ROOT, requiresRoot = true),
                ),
            )
        }
        val gateway = AgentRouterGateway(registry, SecurityPolicyEnforcer(SecurityPolicy(rootEnabled = false)))

        val result = gateway.route(request("root.rm", ExecutionTargetType.ANDROID, RiskTier.IRREVERSIBLE))

        val denied = assertIs<ExecutionResponse.Denied>(result)
        assertTrue(denied.reason.contains("disabled"))
    }

    @Test
    fun `a custom requestIdGenerator is honored instead of the default UUID one`() {
        val registry = InMemoryToolCapabilityRegistry().apply {
            register(
                RegisteredCapability(
                    CapabilityId("device.uninstall"),
                    ExecutionTargetType.ANDROID,
                    NoopTool("device.uninstall", securityLevel = SecurityLevel.SENSITIVE),
                ),
            )
        }
        val gateway = AgentRouterGateway(registry, SecurityPolicyEnforcer(SecurityPolicy()), requestIdGenerator = { "fixed-id" })

        val result = gateway.route(request("device.uninstall", ExecutionTargetType.ANDROID))

        assertEquals("fixed-id", (result as ExecutionResponse.RequiresApproval).requestId)
    }
}
