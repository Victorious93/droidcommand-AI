package ai.droidcommand.security

import ai.droidcommand.agent.CapabilityId
import ai.droidcommand.agent.ExecutionContext
import ai.droidcommand.agent.ExecutionRequest
import ai.droidcommand.agent.ExecutionResult
import ai.droidcommand.agent.ExecutionTarget
import ai.droidcommand.agent.ExecutionTargetType
import ai.droidcommand.agent.PrivilegeLevel
import ai.droidcommand.agent.RiskTier
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class CountingRouterTool(override val spec: ToolSpec) : Tool {
    override fun execute(input: Map<String, String>): ToolResult = ToolResult.Success("ran")
}

private class FixtureExecutionTarget(
    override val id: String,
    override val availableCapabilities: Set<CapabilityId>,
    privilegeLevel: PrivilegeLevel = PrivilegeLevel.USER,
) : ExecutionTarget {
    override val type: ExecutionTargetType = ExecutionTargetType.LOCAL_PC
    override val context: ExecutionContext = ExecutionContext(
        workingDir = "/",
        user = "tester",
        environment = emptyMap(),
        privilegeLevel = privilegeLevel,
    )

    var invocations = 0
        private set

    override fun isHealthy(): Boolean = true

    override fun execute(argv: List<String>, workingDir: String?, env: Map<String, String>?, timeoutMs: Long): ExecutionResult {
        invocations++
        return ExecutionResult(0, "", "", timedOut = false, target = type, verified = false)
    }
}

class DefaultExecutionTargetRouterTest {
    private val capability = CapabilityId("shell.exec")

    private fun request(riskTier: RiskTier = RiskTier.READ_ONLY) = ExecutionRequest(
        capabilityId = capability,
        targetType = ExecutionTargetType.LOCAL_PC,
        parameters = emptyMap(),
        riskTier = riskTier,
    )

    private fun registryWith(spec: ToolSpec): ToolRegistry = ToolRegistry().apply { register(CountingRouterTool(spec)) }

    @Test
    fun `no target supporting the capability returns NoSuitableTarget without consulting the tool registry`() {
        val target = FixtureExecutionTarget("t1", availableCapabilities = setOf(CapabilityId("other.capability")))
        val router = DefaultExecutionTargetRouter(ToolRegistry())

        val decision = router.routeExecution(request(), listOf(target), SecurityPolicyEnforcer(SecurityPolicy()), toolId = "nonexistent-tool")

        val noSuitable = assertIs<RoutingDecision.NoSuitableTarget>(decision)
        assertTrue(noSuitable.reason.contains(capability.value))
        assertEquals(0, target.invocations)
    }

    @Test
    fun `an unregistered toolId returns NoSuitableTarget naming the tool, when a target is capable`() {
        val target = FixtureExecutionTarget("t1", availableCapabilities = setOf(capability))
        val router = DefaultExecutionTargetRouter(ToolRegistry())

        val decision = router.routeExecution(request(), listOf(target), SecurityPolicyEnforcer(SecurityPolicy()), toolId = "ghost-tool")

        val noSuitable = assertIs<RoutingDecision.NoSuitableTarget>(decision)
        assertTrue(noSuitable.reason.contains("ghost-tool"))
    }

    @Test
    fun `a security policy Deny returns NoSuitableTarget even though a capable target exists`() {
        val target = FixtureExecutionTarget("t1", availableCapabilities = setOf(capability))
        val spec = ToolSpec(name = "rm", description = "d", requiresRoot = true, securityLevel = SecurityLevel.ROOT)
        val router = DefaultExecutionTargetRouter(registryWith(spec))
        val enforcer = SecurityPolicyEnforcer(SecurityPolicy(rootEnabled = false))

        val decision = router.routeExecution(request(), listOf(target), enforcer, toolId = "rm")

        val noSuitable = assertIs<RoutingDecision.NoSuitableTarget>(decision)
        assertTrue(noSuitable.reason.contains("disabled"))
    }

    @Test
    fun `the least-privileged capable target is chosen over a more-privileged one`() {
        val userTarget = FixtureExecutionTarget("user-target", availableCapabilities = setOf(capability), privilegeLevel = PrivilegeLevel.USER)
        val rootTarget = FixtureExecutionTarget("root-target", availableCapabilities = setOf(capability), privilegeLevel = PrivilegeLevel.ROOT)
        val spec = ToolSpec(name = "echo", description = "d")
        val router = DefaultExecutionTargetRouter(registryWith(spec))

        val decision = router.routeExecution(request(), listOf(rootTarget, userTarget), SecurityPolicyEnforcer(SecurityPolicy()), toolId = "echo")

        val route = assertIs<RoutingDecision.Route>(decision)
        assertEquals("user-target", route.target.id)
    }

    @Test
    fun `two equally-privileged capable targets are tie-broken deterministically by id`() {
        val targetB = FixtureExecutionTarget("b", availableCapabilities = setOf(capability))
        val targetA = FixtureExecutionTarget("a", availableCapabilities = setOf(capability))
        val spec = ToolSpec(name = "echo", description = "d")
        val router = DefaultExecutionTargetRouter(registryWith(spec))

        val decision = router.routeExecution(request(), listOf(targetB, targetA), SecurityPolicyEnforcer(SecurityPolicy()), toolId = "echo")

        val route = assertIs<RoutingDecision.Route>(decision)
        assertEquals("a", route.target.id)
    }

    @Test
    fun `an Allow decision is attached to the returned Route`() {
        val target = FixtureExecutionTarget("t1", availableCapabilities = setOf(capability))
        val spec = ToolSpec(name = "echo", description = "d")
        val router = DefaultExecutionTargetRouter(registryWith(spec))

        val decision = router.routeExecution(request(), listOf(target), SecurityPolicyEnforcer(SecurityPolicy()), toolId = "echo")

        val route = assertIs<RoutingDecision.Route>(decision)
        assertEquals(PolicyDecision.Allow, route.decision)
    }

    @Test
    fun `a RequireApproval decision is attached to the returned Route`() {
        val target = FixtureExecutionTarget("t1", availableCapabilities = setOf(capability))
        val spec = ToolSpec(name = "delete-app", description = "d", securityLevel = SecurityLevel.SENSITIVE)
        val router = DefaultExecutionTargetRouter(registryWith(spec))

        val decision = router.routeExecution(request(), listOf(target), SecurityPolicyEnforcer(SecurityPolicy()), toolId = "delete-app")

        val route = assertIs<RoutingDecision.Route>(decision)
        assertIs<PolicyDecision.RequireApproval>(route.decision)
    }

    @Test
    fun `routeExecution never invokes the chosen target itself`() {
        val target = FixtureExecutionTarget("t1", availableCapabilities = setOf(capability))
        val spec = ToolSpec(name = "echo", description = "d")
        val router = DefaultExecutionTargetRouter(registryWith(spec))

        router.routeExecution(request(), listOf(target), SecurityPolicyEnforcer(SecurityPolicy()), toolId = "echo")

        assertEquals(0, target.invocations)
    }

    @Test
    fun `verifyResult is true for a clean, matching-target, non-timed-out result`() {
        val router = DefaultExecutionTargetRouter(ToolRegistry())
        val result = ExecutionResult(0, "out", "", timedOut = false, target = ExecutionTargetType.LOCAL_PC, verified = false)

        assertTrue(router.verifyResult(result, request().copy(targetType = ExecutionTargetType.LOCAL_PC)))
    }

    @Test
    fun `verifyResult is false for a non-zero exit code`() {
        val router = DefaultExecutionTargetRouter(ToolRegistry())
        val result = ExecutionResult(1, "", "err", timedOut = false, target = ExecutionTargetType.LOCAL_PC, verified = false)

        assertFalse(router.verifyResult(result, request().copy(targetType = ExecutionTargetType.LOCAL_PC)))
    }

    @Test
    fun `verifyResult is false when the result timed out`() {
        val router = DefaultExecutionTargetRouter(ToolRegistry())
        val result = ExecutionResult(0, "", "", timedOut = true, target = ExecutionTargetType.LOCAL_PC, verified = false)

        assertFalse(router.verifyResult(result, request().copy(targetType = ExecutionTargetType.LOCAL_PC)))
    }

    @Test
    fun `verifyResult is false when the result's target doesn't match the request's targetType`() {
        val router = DefaultExecutionTargetRouter(ToolRegistry())
        val result = ExecutionResult(0, "", "", timedOut = false, target = ExecutionTargetType.DOCKER, verified = false)

        assertFalse(router.verifyResult(result, request().copy(targetType = ExecutionTargetType.LOCAL_PC)))
    }
}
