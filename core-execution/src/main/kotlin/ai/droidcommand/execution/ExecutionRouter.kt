package ai.droidcommand.execution

import ai.droidcommand.agent.ExecutionRequest
import ai.droidcommand.security.PolicyDecision
import ai.droidcommand.security.SecurityPolicyEnforcer

/**
 * P1.4's own required abstraction, verbatim: routes a task to the best
 * available [ExecutionTarget], applying least-privilege logic. Reuses the
 * already-shipped `core-agent.ExecutionRequest` (P1.0) rather than
 * redeclaring it. Both methods drop the spec's `suspend` — the same
 * stated deviation every `suspend fun` in this codebase has taken since
 * P0.1: there is no coroutines dependency anywhere in this repository.
 *
 * See [DefaultExecutionRouter] for how the literal signature's `toolId:
 * String` is resolved into a [ai.droidcommand.agent.ToolSpec] the existing
 * [SecurityPolicyEnforcer] can actually authorize.
 */
interface ExecutionRouter {
    fun routeExecution(
        request: ExecutionRequest,
        availableTargets: List<ExecutionTarget>,
        securityEnforcer: SecurityPolicyEnforcer,
        toolId: String,
    ): RoutingDecision?

    fun verifyResult(result: ExecutionResult, request: ExecutionRequest): Boolean
}

/** Verbatim P1.4 shape — the router's answer for a routing attempt. */
sealed class RoutingDecision {
    data class Route(val target: ExecutionTarget, val decision: PolicyDecision) : RoutingDecision()

    data class NoSuitableTarget(val reason: String) : RoutingDecision()
}
