package ai.droidcommand.security

import ai.droidcommand.agent.ExecutionRequest
import ai.droidcommand.agent.ExecutionResponse
import ai.droidcommand.agent.ToolCapabilityRegistry
import ai.droidcommand.agent.checkCapabilityAvailability
import java.util.UUID

/**
 * Bridges P1.0's `ExecutionRequest`/`ExecutionResponse` contract to the
 * **existing, real, already-tested** [SecurityPolicyEnforcer] — reusing
 * its policy logic rather than duplicating it, the same "compose, don't
 * reinvent" discipline this codebase applies everywhere (e.g.
 * `ModelRouter` composing `LlmProvider`, `TokenBudgetManager` reusing
 * `ContextManager`'s `allocateByPriority`).
 *
 * **`route` returns `null` on genuine authorization, never a fabricated
 * [ExecutionResponse.Success]:** that response case requires an actual
 * execution result and a real `verified: Boolean`, which needs real
 * dispatch machinery — P1.3 (Execution Target Abstraction)/P1.4
 * (Execution Router)'s own separate, not-yet-scoped job. Forcing a
 * `Success` out of this slice with nothing real to dispatch to would be
 * exactly the kind of overreach this project's rules forbid.
 * [ExecutionResponse.CapabilityUnavailable], [ExecutionResponse.Denied],
 * and [ExecutionResponse.RequiresApproval] are all real, fully-produced
 * outcomes.
 *
 * [ExecutionRequest.riskTier] is passed through into
 * [ExecutionResponse.RequiresApproval] as given — it is **not**
 * cross-validated against the tool's real `SecurityLevel` in this slice;
 * that consistency check is named future work naturally belonging to
 * P1.2 (Policy & Permission Engine extension), not fabricated here.
 */
class AgentRouterGateway(
    private val registry: ToolCapabilityRegistry,
    private val enforcer: SecurityPolicyEnforcer,
    private val requestIdGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    fun route(request: ExecutionRequest): ExecutionResponse? {
        checkCapabilityAvailability(request, registry)?.let { return it }

        val capability = registry.lookup(request.capabilityId)
            ?: error("checkCapabilityAvailability passed but the capability vanished from the registry")

        return when (val decision = enforcer.authorize(capability.tool.spec)) {
            is PolicyDecision.Deny -> ExecutionResponse.Denied(decision.reason)
            is PolicyDecision.RequireApproval ->
                ExecutionResponse.RequiresApproval(decision.reason, request.riskTier, requestIdGenerator())
            is PolicyDecision.Allow -> null
        }
    }
}
