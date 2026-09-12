package ai.droidcommand.security

import ai.droidcommand.agent.ToolSpec

/**
 * CAP-012, P1.4. Routes an [ExecutionRequest] to the least-privileged
 * healthy [ExecutionTarget] that declares the requested capability, then
 * asks [SecurityPolicyEnforcer] whether that route may actually proceed.
 *
 * **Three deliberate deviations from the roadmap prompt's literal shape,
 * each stated plainly rather than silently worked around:**
 *
 * 1. `routeExecution`'s fourth parameter is a real [ToolSpec], not the
 *    prompt's own `toolId: String`. [SecurityPolicyEnforcer.authorize]
 *    (the only thing this router can consult for a [PolicyDecision])
 *    requires a full [ToolSpec] — no id-to-[ToolSpec] registry exists
 *    anywhere in this repository to resolve a plain string into one
 *    (that lookup is exactly what the still-MISSING Capability Manager,
 *    CAP-009, would provide). A `toolId: String` parameter this method
 *    could not actually use would be dead weight, not a thinner-but-honest
 *    stand-in the way `ApprovalRequest`'s `String` fields are elsewhere in
 *    this module.
 * 2. Both methods are synchronous `fun`, not `suspend fun` — the same
 *    "no coroutines dependency anywhere in this chain" reasoning
 *    `ai.droidcommand.llm.AiProviderSelector` and [ExecutionTarget]
 *    (CAP-011) already document for themselves.
 * 3. `routeExecution` returns [RoutingDecision] directly, not the prompt's
 *    own `RoutingDecision?`. [RoutingDecision.NoSuitableTarget] already
 *    represents "no target found" as a named case; a nullable return type
 *    on top of that would let the exact same condition be expressed two
 *    different ways, which every existing sealed-result type in this
 *    codebase ([ToolResult], [ExecutionResponse], [ApprovalResponse])
 *    deliberately avoids by giving every outcome its own case.
 */
interface ExecutionRouter {
    fun routeExecution(
        request: ExecutionRequest,
        availableTargets: List<ExecutionTarget>,
        securityEnforcer: SecurityPolicyEnforcer,
        toolSpec: ToolSpec,
    ): RoutingDecision

    /**
     * Whether [result] can be trusted as a genuine outcome of [request]:
     * [ExecutionResult.verified] itself (honestly `false` from every
     * [ExecutionTarget] in this repository today — see that field's own
     * doc comment) combined with a routing-specific sanity check that the
     * result actually came from the target type [request] asked for. This
     * is *not* independent state re-verification — no target here performs
     * that — so a `false` result never means more than "not confirmed."
     */
    fun verifyResult(result: ExecutionResult, request: ExecutionRequest): Boolean
}

sealed class RoutingDecision {
    data class Route(val target: ExecutionTarget, val decision: PolicyDecision) : RoutingDecision()

    data class NoSuitableTarget(val reason: String) : RoutingDecision()
}

/**
 * The real [ExecutionRouter]: capability-then-health-filters
 * [availableTargets] down to every candidate that could run [request],
 * picks the one with the lowest [PrivilegeLevel] ordinal among the
 * survivors (least-privilege-first, per P1.4's own "Router logic" list),
 * and pairs it with whatever [securityEnforcer] decides for [toolSpec] —
 * a [PolicyDecision.Deny] is still returned as a [RoutingDecision.Route]
 * naming the target that *would* have run it, per the prompt's own
 * "return the target + required policy decision" wording; the caller
 * decides whether to actually invoke [ExecutionTarget.execute] based on
 * [RoutingDecision.Route.decision].
 */
class DefaultExecutionRouter : ExecutionRouter {
    override fun routeExecution(
        request: ExecutionRequest,
        availableTargets: List<ExecutionTarget>,
        securityEnforcer: SecurityPolicyEnforcer,
        toolSpec: ToolSpec,
    ): RoutingDecision {
        val sameType = availableTargets.filter { it.type == request.targetType }
        if (sameType.isEmpty()) {
            return RoutingDecision.NoSuitableTarget("No execution target of type ${request.targetType} is available")
        }

        val capable = sameType.filter { request.capabilityId in it.availableCapabilities }
        if (capable.isEmpty()) {
            return RoutingDecision.NoSuitableTarget(
                "No ${request.targetType} target declares capability '${request.capabilityId.value}'",
            )
        }

        val healthy = capable.filter { it.isHealthy() }
        if (healthy.isEmpty()) {
            return RoutingDecision.NoSuitableTarget(
                "Every ${request.targetType} target declaring capability '${request.capabilityId.value}' is unhealthy",
            )
        }

        val leastPrivileged = healthy.minBy { it.context.privilegeLevel.ordinal }
        return RoutingDecision.Route(leastPrivileged, securityEnforcer.authorize(toolSpec))
    }

    override fun verifyResult(result: ExecutionResult, request: ExecutionRequest): Boolean =
        result.verified && result.target == request.targetType
}
