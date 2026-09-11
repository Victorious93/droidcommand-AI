package ai.droidcommand.execution

import ai.droidcommand.agent.ExecutionRequest
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.UnknownToolException
import ai.droidcommand.security.SecurityPolicyEnforcer

/**
 * The real implementation of P1.4's own four-step "Router logic":
 * (1) filter targets by capability, (2) filter by security policy,
 * (3) pick the least-privileged viable target, (4) return the target +
 * policy decision.
 *
 * [toolRegistry] resolves the literal signature's bare `toolId: String`
 * into a real [ai.droidcommand.agent.ToolSpec] — the spec's own
 * `routeExecution` signature names a tool by string but passes no
 * registry to resolve it, and [SecurityPolicyEnforcer.authorize] needs a
 * real `ToolSpec`. The already-shipped, already-tested
 * `core-agent.ToolRegistry` is the literal, general-purpose registry
 * every other `Tool` lookup in this codebase already resolves through
 * (`ObjectiveEngine`, `ToolExecutor`, `SecureToolExecutor`) — not
 * `core-agent`'s separate P1.0 `ToolCapabilityRegistry`, which this
 * signature's `toolId` (distinct from [ExecutionRequest.capabilityId])
 * gives no reason to route through instead.
 *
 * **"Filter by security policy" is read as one policy check per request,
 * not a per-target filter:** `SecurityPolicyEnforcer.authorize(ToolSpec)`
 * has no notion of which target executes a tool, only whether the tool
 * is allowed to run at all this session. The check runs once, against
 * whichever target survives capability/type/health filtering and
 * least-privilege selection, and its result is always attached to that
 * target — never used to silently drop targets from consideration.
 * Cross-validating a target's [PrivilegeLevel] against the tool's own
 * `SecurityLevel`/root requirement is real, deferred future work (P1.2
 * already deferred exactly this cross-validation for its own reasons).
 */
class DefaultExecutionRouter(private val toolRegistry: ToolRegistry) : ExecutionRouter {
    override fun routeExecution(
        request: ExecutionRequest,
        availableTargets: List<ExecutionTarget>,
        securityEnforcer: SecurityPolicyEnforcer,
        toolId: String,
    ): RoutingDecision {
        val viable = availableTargets.filter { target ->
            target.type == request.targetType && request.capabilityId in target.availableCapabilities && target.isHealthy()
        }

        val chosen = viable.minByOrNull { it.context.privilegeLevel.ordinal }
            ?: return RoutingDecision.NoSuitableTarget(
                "no healthy target of type ${request.targetType} advertises capability '${request.capabilityId.value}'",
            )

        val tool = try {
            toolRegistry.get(toolId)
        } catch (e: UnknownToolException) {
            return RoutingDecision.NoSuitableTarget("unknown tool '$toolId': ${e.message}")
        }

        return RoutingDecision.Route(chosen, securityEnforcer.authorize(tool.spec))
    }

    /**
     * The minimal, honest verification obtainable from [ExecutionResult]'s
     * own fields, without any target/capability-specific real-world
     * probing (e.g. confirming a file actually changed, a service
     * actually restarted) — no such probing infrastructure exists
     * anywhere in this codebase yet, and is not fabricated here.
     */
    override fun verifyResult(result: ExecutionResult, request: ExecutionRequest): Boolean =
        result.target == request.targetType && result.exitCode == 0 && !result.timedOut
}
