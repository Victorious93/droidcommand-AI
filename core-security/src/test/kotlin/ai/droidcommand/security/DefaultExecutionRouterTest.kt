package ai.droidcommand.security

import ai.droidcommand.agent.ToolSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun context(privilegeLevel: PrivilegeLevel) = ExecutionContext(
    workingDir = "/",
    user = "user",
    environment = emptyMap(),
    privilegeLevel = privilegeLevel,
)

private class RoutableFakeTarget(
    override val id: String,
    override val type: ExecutionTargetType,
    override val availableCapabilities: Set<CapabilityId>,
    override val context: ExecutionContext,
    private val healthy: Boolean = true,
) : ExecutionTarget {
    override fun isHealthy(): Boolean = healthy

    override fun execute(argv: List<String>, workingDir: String?, env: Map<String, String>?, timeoutMs: Long): ExecutionResult =
        ExecutionResult(0, "", "", false, type, false)
}

private fun request(
    capabilityId: CapabilityId = CapabilityId("android.notifications.read"),
    targetType: ExecutionTargetType = ExecutionTargetType.ANDROID,
    riskTier: RiskTier = RiskTier.READ_ONLY,
) = ExecutionRequest(capabilityId = capabilityId, targetType = targetType, parameters = emptyMap(), riskTier = riskTier)

private fun policyEnforcer(policy: SecurityPolicy = SecurityPolicy()) = SecurityPolicyEnforcer(policy)

private fun toolSpec(
    requiresRoot: Boolean = false,
    requiredPermissions: Set<String> = emptySet(),
    securityLevel: ai.droidcommand.agent.SecurityLevel = ai.droidcommand.agent.SecurityLevel.NORMAL,
) = ToolSpec(name = "test_tool", description = "", requiresRoot = requiresRoot, requiredPermissions = requiredPermissions, securityLevel = securityLevel)

class DefaultExecutionRouterTest {
    private val router = DefaultExecutionRouter()

    @Test
    fun `no target of the requested type yields NoSuitableTarget`() {
        val targets = listOf(
            RoutableFakeTarget("t1", ExecutionTargetType.DOCKER, setOf(CapabilityId("android.notifications.read")), context(PrivilegeLevel.USER)),
        )
        val decision = router.routeExecution(request(), targets, policyEnforcer(), toolSpec())
        assertIs<RoutingDecision.NoSuitableTarget>(decision)
        assertTrue(decision.reason.contains("ANDROID"))
    }

    @Test
    fun `a target of the right type but wrong capability yields NoSuitableTarget`() {
        val targets = listOf(
            RoutableFakeTarget("t1", ExecutionTargetType.ANDROID, setOf(CapabilityId("android.other")), context(PrivilegeLevel.USER)),
        )
        val decision = router.routeExecution(request(), targets, policyEnforcer(), toolSpec())
        assertIs<RoutingDecision.NoSuitableTarget>(decision)
        assertTrue(decision.reason.contains("android.notifications.read"))
    }

    @Test
    fun `a capable but unhealthy target yields NoSuitableTarget`() {
        val targets = listOf(
            RoutableFakeTarget("t1", ExecutionTargetType.ANDROID, setOf(CapabilityId("android.notifications.read")), context(PrivilegeLevel.USER), healthy = false),
        )
        val decision = router.routeExecution(request(), targets, policyEnforcer(), toolSpec())
        assertIs<RoutingDecision.NoSuitableTarget>(decision)
        assertTrue(decision.reason.contains("unhealthy"))
    }

    @Test
    fun `among multiple viable targets, the least-privileged one is chosen`() {
        val cap = CapabilityId("android.notifications.read")
        val rootTarget = RoutableFakeTarget("root-1", ExecutionTargetType.ANDROID, setOf(cap), context(PrivilegeLevel.ROOT))
        val userTarget = RoutableFakeTarget("user-1", ExecutionTargetType.ANDROID, setOf(cap), context(PrivilegeLevel.USER))
        val elevatedTarget = RoutableFakeTarget("elevated-1", ExecutionTargetType.ANDROID, setOf(cap), context(PrivilegeLevel.ELEVATED))

        val decision = router.routeExecution(request(capabilityId = cap), listOf(rootTarget, elevatedTarget, userTarget), policyEnforcer(), toolSpec())

        assertIs<RoutingDecision.Route>(decision)
        assertEquals("user-1", decision.target.id)
    }

    @Test
    fun `an unhealthy least-privileged target is skipped in favor of a healthy more-privileged one`() {
        val cap = CapabilityId("android.notifications.read")
        val unhealthyUser = RoutableFakeTarget("user-1", ExecutionTargetType.ANDROID, setOf(cap), context(PrivilegeLevel.USER), healthy = false)
        val healthyElevated = RoutableFakeTarget("elevated-1", ExecutionTargetType.ANDROID, setOf(cap), context(PrivilegeLevel.ELEVATED))

        val decision = router.routeExecution(request(capabilityId = cap), listOf(unhealthyUser, healthyElevated), policyEnforcer(), toolSpec())

        assertIs<RoutingDecision.Route>(decision)
        assertEquals("elevated-1", decision.target.id)
    }

    @Test
    fun `a routed target is paired with the security enforcer's Allow decision`() {
        val cap = CapabilityId("android.notifications.read")
        val target = RoutableFakeTarget("t1", ExecutionTargetType.ANDROID, setOf(cap), context(PrivilegeLevel.USER))

        val decision = router.routeExecution(request(capabilityId = cap), listOf(target), policyEnforcer(), toolSpec())

        assertIs<RoutingDecision.Route>(decision)
        assertEquals(PolicyDecision.Allow, decision.decision)
    }

    @Test
    fun `a routed target requiring root with root disabled is paired with a Deny decision`() {
        val cap = CapabilityId("root.shell")
        val target = RoutableFakeTarget("t1", ExecutionTargetType.ANDROID, setOf(cap), context(PrivilegeLevel.ROOT))

        val decision = router.routeExecution(
            request(capabilityId = cap),
            listOf(target),
            policyEnforcer(SecurityPolicy(rootEnabled = false)),
            toolSpec(requiresRoot = true),
        )

        assertIs<RoutingDecision.Route>(decision)
        assertEquals("t1", decision.target.id)
        assertIs<PolicyDecision.Deny>(decision.decision)
    }

    @Test
    fun `a routed sensitive tool without auto-approval is paired with RequireApproval`() {
        val cap = CapabilityId("android.settings.write")
        val target = RoutableFakeTarget("t1", ExecutionTargetType.ANDROID, setOf(cap), context(PrivilegeLevel.USER))

        val decision = router.routeExecution(
            request(capabilityId = cap),
            listOf(target),
            policyEnforcer(),
            toolSpec(securityLevel = ai.droidcommand.agent.SecurityLevel.SENSITIVE),
        )

        assertIs<RoutingDecision.Route>(decision)
        assertIs<PolicyDecision.RequireApproval>(decision.decision)
    }

    @Test
    fun `verifyResult is true only when verified and the target type matches the request`() {
        val target = ExecutionTargetType.ANDROID
        val matching = ExecutionResult(0, "", "", false, target, verified = true)
        val mismatchedTarget = ExecutionResult(0, "", "", false, ExecutionTargetType.DOCKER, verified = true)
        val unverified = ExecutionResult(0, "", "", false, target, verified = false)

        assertTrue(router.verifyResult(matching, request(targetType = target)))
        assertFalse(router.verifyResult(mismatchedTarget, request(targetType = target)))
        assertFalse(router.verifyResult(unverified, request(targetType = target)))
    }
}
