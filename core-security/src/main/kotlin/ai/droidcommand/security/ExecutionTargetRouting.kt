package ai.droidcommand.security

import ai.droidcommand.agent.ExecutionRequest
import ai.droidcommand.agent.ExecutionResult
import ai.droidcommand.agent.ExecutionTarget
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.UnknownToolException

/** The outcome of [ExecutionTargetRouter.routeExecution] (CAP-012, P1.4) — a typed result instead of a thrown exception, the same discipline [PolicyDecision]/[ai.droidcommand.agent.ExecutionResponse] already apply to their own outcomes. */
sealed class RoutingDecision {
    data class Route(val target: ExecutionTarget, val decision: PolicyDecision) : RoutingDecision()

    data class NoSuitableTarget(val reason: String) : RoutingDecision()
}

/**
 * Routes an [ExecutionRequest] to the best available [ExecutionTarget]
 * (CAP-012, P1.4) — a materially different contract from
 * [ai.droidcommand.agent.ExecutionRouter] (CAP-008, P1.0), which routes
 * one request to a single [ai.droidcommand.agent.CapabilityExecutor] by
 * [ai.droidcommand.agent.CapabilityId]/[ai.droidcommand.agent.RiskTier]
 * alone, with no notion of *choosing between* multiple targets. The
 * roadmap prompt names both interfaces `ExecutionRouter` in its own two
 * different sections (P1.0 and P1.4); **this one is deliberately named
 * `ExecutionTargetRouter` instead**, in `core-security` rather than
 * `core-agent`, since it needs [SecurityPolicyEnforcer] as a parameter
 * per the spec's own literal signature — `core-security` already depends
 * on `core-agent` one-way for exactly this reason
 * ([SecureToolExecutor] does the same). `core-agent.ExecutionRouter`
 * itself is untouched by this file.
 *
 * **Deliberate deviation from the roadmap prompt's literal signatures:**
 * every method is a plain `fun`, not `suspend fun` — no module in this
 * repository declares a coroutines dependency, the same reasoning
 * `AiProviderSelector`/`PersonaManager`/`ExecutionTarget` already state
 * for their own identical deviation. [routeExecution] also returns a
 * non-null [RoutingDecision], not the spec's `RoutingDecision?` — a bare
 * `null` would discard exactly the "why no route was found" information
 * [RoutingDecision.NoSuitableTarget] exists to carry, so this contract
 * never returns it.
 */
interface ExecutionTargetRouter {
    fun routeExecution(
        request: ExecutionRequest,
        availableTargets: List<ExecutionTarget>,
        securityEnforcer: SecurityPolicyEnforcer,
        toolId: String,
    ): RoutingDecision

    /**
     * Structural verification only, never semantic: confirms [result]
     * completed cleanly (`exitCode == 0`, not timed out) and actually came
     * from the target type [request] asked for. It cannot and does not
     * verify that a command achieved its actual intent (e.g. that a file
     * was really deleted) — that would need per-capability verification
     * logic no implementation in this repository has.
     */
    fun verifyResult(result: ExecutionResult, request: ExecutionRequest): Boolean
}

/**
 * The real [ExecutionTargetRouter]. [toolRegistry] is a constructor
 * dependency, not a per-call parameter: the roadmap prompt's own
 * [routeExecution] signature takes only a [String] `toolId`, not a way to
 * resolve it to a [ai.droidcommand.agent.ToolSpec] (needed for
 * [SecurityPolicyEnforcer.authorize]) — resolving that is this
 * implementation's own added collaborator, the same "interface stays
 * literal, implementation adds what it actually needs" split
 * `DefaultExecutionRouter` already took for its own `requestIdGenerator`.
 *
 * [routeExecution] follows the roadmap prompt's own 4-step "Router logic"
 * list in order: (1) filter [availableTargets] to those declaring
 * [request]'s capability, short-circuiting to
 * [RoutingDecision.NoSuitableTarget] without ever resolving [toolId] or
 * consulting [securityEnforcer] if none do; (2) resolve [toolId] and
 * check [securityEnforcer], short-circuiting on an unregistered tool or a
 * [PolicyDecision.Deny]; (3) pick the least-privileged viable target
 * (ascending [ai.droidcommand.agent.PrivilegeLevel] ordinal, tie-broken by
 * `id` for determinism, the same "fix iteration order explicitly"
 * discipline [ai.droidcommand.agent.TaskGraph]/`LocalFirstOrdering`
 * already document for themselves); (4) return the chosen target with
 * whichever [PolicyDecision] `Allow`/`RequireApproval` [securityEnforcer]
 * returned.
 */
class DefaultExecutionTargetRouter(private val toolRegistry: ToolRegistry) : ExecutionTargetRouter {
    override fun routeExecution(
        request: ExecutionRequest,
        availableTargets: List<ExecutionTarget>,
        securityEnforcer: SecurityPolicyEnforcer,
        toolId: String,
    ): RoutingDecision {
        val capableTargets = availableTargets.filter { request.capabilityId in it.availableCapabilities }
        if (capableTargets.isEmpty()) {
            return RoutingDecision.NoSuitableTarget(
                "No available execution target supports capability '${request.capabilityId.value}'",
            )
        }

        val spec = try {
            toolRegistry.get(toolId).spec
        } catch (e: UnknownToolException) {
            return RoutingDecision.NoSuitableTarget("Tool '$toolId' is not registered")
        }

        val decision = securityEnforcer.authorize(spec)
        if (decision is PolicyDecision.Deny) {
            return RoutingDecision.NoSuitableTarget("Denied by security policy: ${decision.reason}")
        }

        val target = capableTargets.sortedWith(compareBy({ it.context.privilegeLevel.ordinal }, { it.id })).first()
        return RoutingDecision.Route(target, decision)
    }

    override fun verifyResult(result: ExecutionResult, request: ExecutionRequest): Boolean =
        result.exitCode == 0 && !result.timedOut && result.target == request.targetType
}
