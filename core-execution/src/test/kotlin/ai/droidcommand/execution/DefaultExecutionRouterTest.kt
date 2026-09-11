package ai.droidcommand.execution

import ai.droidcommand.agent.CapabilityId
import ai.droidcommand.agent.ExecutionRequest
import ai.droidcommand.agent.ExecutionTargetType
import ai.droidcommand.agent.RiskTier
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec
import ai.droidcommand.security.PolicyDecision
import ai.droidcommand.security.SecurityPolicy
import ai.droidcommand.security.SecurityPolicyEnforcer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class StubTool(override val spec: ToolSpec) : Tool {
    override fun execute(input: Map<String, String>): ToolResult = ToolResult.Success("stub")
}

private class FakeExecutionTarget(
    override val id: String,
    override val type: ExecutionTargetType,
    override val availableCapabilities: Set<CapabilityId>,
    privilegeLevel: PrivilegeLevel,
    private val healthy: Boolean = true,
) : ExecutionTarget {
    override val context = ExecutionContext(workingDir = "/", user = "test", environment = emptyMap(), privilegeLevel = privilegeLevel)

    override fun isHealthy(): Boolean = healthy

    override fun execute(argv: List<String>, workingDir: String?, env: Map<String, String>?, timeoutMs: Long): ExecutionResult =
        error("not exercised by DefaultExecutionRouterTest")
}

private fun request(capabilityId: String = "shell.echo", riskTier: RiskTier = RiskTier.READ_ONLY) = ExecutionRequest(
    capabilityId = CapabilityId(capabilityId),
    targetType = ExecutionTargetType.LOCAL_PC,
    parameters = emptyMap(),
    riskTier = riskTier,
)

private fun registryWith(spec: ToolSpec): ToolRegistry {
    val registry = ToolRegistry()
    registry.register(StubTool(spec))
    return registry
}

class DefaultExecutionRouterTest {
    @Test
    fun `routes to the least-privileged of two capability- and type-matching healthy targets`() {
        val capability = CapabilityId("shell.echo")
        val userTarget = FakeExecutionTarget("user", ExecutionTargetType.LOCAL_PC, setOf(capability), PrivilegeLevel.ELEVATED)
        val rootTarget = FakeExecutionTarget("root", ExecutionTargetType.LOCAL_PC, setOf(capability), PrivilegeLevel.ROOT)
        val leastPrivileged = FakeExecutionTarget("least", ExecutionTargetType.LOCAL_PC, setOf(capability), PrivilegeLevel.USER)

        val router = DefaultExecutionRouter(registryWith(ToolSpec(name = "echo", description = "d")))
        val decision = assertIs<RoutingDecision.Route>(
            router.routeExecution(request(), listOf(userTarget, rootTarget, leastPrivileged), SecurityPolicyEnforcer(SecurityPolicy()), "echo"),
        )

        assertEquals("least", decision.target.id)
    }

    @Test
    fun `a NORMAL tool auto-approved by policy returns Allow`() {
        val target = FakeExecutionTarget("t", ExecutionTargetType.LOCAL_PC, setOf(CapabilityId("shell.echo")), PrivilegeLevel.USER)
        val router = DefaultExecutionRouter(registryWith(ToolSpec(name = "echo", description = "d")))

        val decision = assertIs<RoutingDecision.Route>(
            router.routeExecution(request(), listOf(target), SecurityPolicyEnforcer(SecurityPolicy()), "echo"),
        )

        assertEquals(PolicyDecision.Allow, decision.decision)
        assertEquals("t", decision.target.id)
    }

    @Test
    fun `a SENSITIVE tool requires approval with the real reason`() {
        val target = FakeExecutionTarget("t", ExecutionTargetType.LOCAL_PC, setOf(CapabilityId("shell.echo")), PrivilegeLevel.USER)
        val spec = ToolSpec(name = "sensitive-op", description = "d", securityLevel = SecurityLevel.SENSITIVE)
        val router = DefaultExecutionRouter(registryWith(spec))

        val decision = assertIs<RoutingDecision.Route>(
            router.routeExecution(request(), listOf(target), SecurityPolicyEnforcer(SecurityPolicy()), "sensitive-op"),
        )

        val approval = assertIs<PolicyDecision.RequireApproval>(decision.decision)
        assertTrue(approval.reason.contains("sensitive-op"))
    }

    @Test
    fun `a root-required tool under a root-disabled policy is denied but the target is still returned`() {
        val target = FakeExecutionTarget("t", ExecutionTargetType.LOCAL_PC, setOf(CapabilityId("shell.echo")), PrivilegeLevel.USER)
        val spec = ToolSpec(name = "root-op", description = "d", requiresRoot = true)
        val router = DefaultExecutionRouter(registryWith(spec))

        val decision = assertIs<RoutingDecision.Route>(
            router.routeExecution(request(), listOf(target), SecurityPolicyEnforcer(SecurityPolicy(rootEnabled = false)), "root-op"),
        )

        val deny = assertIs<PolicyDecision.Deny>(decision.decision)
        assertTrue(deny.reason.contains("root"))
        assertEquals("t", decision.target.id)
    }

    @Test
    fun `no target of the requested type is NoSuitableTarget naming the type`() {
        val target = FakeExecutionTarget("t", ExecutionTargetType.TERMUX, setOf(CapabilityId("shell.echo")), PrivilegeLevel.USER)
        val router = DefaultExecutionRouter(registryWith(ToolSpec(name = "echo", description = "d")))

        val decision = assertIs<RoutingDecision.NoSuitableTarget>(
            router.routeExecution(request(), listOf(target), SecurityPolicyEnforcer(SecurityPolicy()), "echo"),
        )

        assertTrue(decision.reason.contains("LOCAL_PC"))
    }

    @Test
    fun `a target of the right type not advertising the capability is NoSuitableTarget naming the capability`() {
        val target = FakeExecutionTarget("t", ExecutionTargetType.LOCAL_PC, setOf(CapabilityId("other.capability")), PrivilegeLevel.USER)
        val router = DefaultExecutionRouter(registryWith(ToolSpec(name = "echo", description = "d")))

        val decision = assertIs<RoutingDecision.NoSuitableTarget>(
            router.routeExecution(request(), listOf(target), SecurityPolicyEnforcer(SecurityPolicy()), "echo"),
        )

        assertTrue(decision.reason.contains("shell.echo"))
    }

    @Test
    fun `every matching target unhealthy is NoSuitableTarget`() {
        val capability = CapabilityId("shell.echo")
        val stub = NullExecutionTarget(id = "stub", type = ExecutionTargetType.LOCAL_PC, context = ExecutionContext("/", "test", environment = emptyMap(), privilegeLevel = PrivilegeLevel.USER), availableCapabilities = setOf(capability))
        val router = DefaultExecutionRouter(registryWith(ToolSpec(name = "echo", description = "d")))

        assertIs<RoutingDecision.NoSuitableTarget>(
            router.routeExecution(request(), listOf(stub), SecurityPolicyEnforcer(SecurityPolicy()), "echo"),
        )
    }

    @Test
    fun `an unknown toolId is NoSuitableTarget naming it, caught rather than thrown`() {
        val target = FakeExecutionTarget("t", ExecutionTargetType.LOCAL_PC, setOf(CapabilityId("shell.echo")), PrivilegeLevel.USER)
        val router = DefaultExecutionRouter(ToolRegistry())

        val decision = assertIs<RoutingDecision.NoSuitableTarget>(
            router.routeExecution(request(), listOf(target), SecurityPolicyEnforcer(SecurityPolicy()), "does-not-exist"),
        )

        assertTrue(decision.reason.contains("does-not-exist"))
    }

    @Test
    fun `verifyResult is true only for a matching target, exit code 0, and no timeout`() {
        val router = DefaultExecutionRouter(ToolRegistry())
        val req = request()

        assertTrue(router.verifyResult(ExecutionResult(0, "", "", timedOut = false, target = ExecutionTargetType.LOCAL_PC, verified = false), req))
    }

    @Test
    fun `verifyResult is false for a non-zero exit code`() {
        val router = DefaultExecutionRouter(ToolRegistry())
        assertEquals(
            false,
            router.verifyResult(ExecutionResult(1, "", "", timedOut = false, target = ExecutionTargetType.LOCAL_PC, verified = false), request()),
        )
    }

    @Test
    fun `verifyResult is false for a target-type mismatch`() {
        val router = DefaultExecutionRouter(ToolRegistry())
        assertEquals(
            false,
            router.verifyResult(ExecutionResult(0, "", "", timedOut = false, target = ExecutionTargetType.TERMUX, verified = false), request()),
        )
    }

    @Test
    fun `verifyResult is false when timedOut is true`() {
        val router = DefaultExecutionRouter(ToolRegistry())
        assertEquals(
            false,
            router.verifyResult(ExecutionResult(0, "", "", timedOut = true, target = ExecutionTargetType.LOCAL_PC, verified = false), request()),
        )
    }
}
