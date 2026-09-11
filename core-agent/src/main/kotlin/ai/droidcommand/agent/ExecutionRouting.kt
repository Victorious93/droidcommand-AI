package ai.droidcommand.agent

import java.util.UUID

/**
 * A namespace-qualified capability identifier (CAP-008, P1.0) — e.g.
 * `"android.tap"`, `"termux.exec"`. Validated verbatim against the roadmap
 * prompt's own regex; an invalid value throws [IllegalArgumentException]
 * immediately at construction, the same "make the illegal state
 * unrepresentable" discipline [TaskGraph]/[JsonFileKnowledgeStore] already
 * apply to their own ids.
 */
data class CapabilityId(val value: String) {
    init {
        require(value.matches(ID_PATTERN)) { "Invalid capability ID format: $value" }
    }

    companion object {
        val ID_PATTERN = Regex("""^[a-z0-9][a-z0-9._-]*$""")
    }
}

/** What kind of execution environment a [CapabilityId] would run against (CAP-008, P1.0). None of these targets exist as real, executable environments in this repository yet — see CAP-011/CAP-019/CAP-024/CAP-029 in `docs/AUDIT_2026-09-05.md`. */
enum class ExecutionTargetType {
    ANDROID,
    TERMUX,
    LOCAL_PC,
    DOCKER,
    REMOTE_HOST,
    PROXMOX_VM,
    PROXMOX_LXC,
}

/**
 * How consequential invoking a capability is (CAP-008, P1.0) — a 4-value
 * risk taxonomy distinct from [SecurityLevel]'s 3-value one and not unified
 * with it in this slice (that reconciliation is CAP-010's job, a separate
 * later roadmap item). `READ_ONLY`/`REVERSIBLE` carry no "requires
 * approval" language in the roadmap prompt's own inline comments;
 * `DESTRUCTIVE`/`IRREVERSIBLE` explicitly do — [DefaultExecutionRouter]'s
 * default `autoApprove` set is drawn directly from that distinction.
 */
enum class RiskTier {
    READ_ONLY,
    REVERSIBLE,
    DESTRUCTIVE,
    IRREVERSIBLE,
}

/** A request from the agent to an [ExecutionRouter] to invoke [capabilityId] against [targetType] (CAP-008, P1.0). */
data class ExecutionRequest(
    val capabilityId: CapabilityId,
    val targetType: ExecutionTargetType,
    val parameters: Map<String, String>,
    val riskTier: RiskTier,
)

/** The router's response to an [ExecutionRequest] (CAP-008, P1.0) — a typed result instead of a thrown exception, the same discipline [ToolResult]/[PolicyDecision] already apply to their own outcomes. */
sealed class ExecutionResponse {
    data class Success(val result: String, val targetUsed: ExecutionTargetType, val verified: Boolean) : ExecutionResponse()

    /**
     * Not a blocking prompt callback (unlike [ai.droidcommand.security.ApprovalPrompt.requestApproval]):
     * this is a terminal response carrying [requestId] for a caller to
     * resubmit against later. The roadmap prompt names this field but
     * specifies no resubmission contract — none is invented here; see
     * [DefaultExecutionRouter]'s own doc comment.
     */
    data class RequiresApproval(val operationDescription: String, val riskTier: RiskTier, val requestId: String) : ExecutionResponse()

    data class Denied(val reason: String, val suggestedAlternative: String? = null) : ExecutionResponse()

    data class CapabilityUnavailable(val capabilityId: CapabilityId, val reason: String, val details: String? = null) : ExecutionResponse()
}

/**
 * The minimal "is this capability id currently live" membership check
 * [DefaultExecutionRouter] uses to enforce P1.0's one prose requirement:
 * "the agent must never invoke a capability string not present in the live
 * registry." **Deliberately not CAP-009's full 11-state
 * `CapabilityMetadata`/dependency-graph/reverification-cadence registry** —
 * that is a separate, later roadmap item needing real capabilities to
 * register against. This is a narrower boolean gate a CAP-009
 * implementation can satisfy later with zero change to [ExecutionRouter]'s
 * own interface, the same "placeholder extension point, real adapter lands
 * later" relationship [ContextKind] already has toward several of its own
 * kinds.
 */
fun interface CapabilityRegistry {
    fun isRegistered(id: CapabilityId): Boolean
}

/**
 * The seam a real capability-execution backend plugs into once one exists.
 * No implementation of this in the current codebase is backed by a real
 * [ExecutionTargetType] — CAP-011 (Execution Target Abstraction) is
 * MISSING, so there is nothing yet to genuinely execute against. Only a
 * scripted fake exercises this in tests, the same "scripted provider, never
 * a live one" honesty convention every `core-llm` extractor test already
 * follows for its own [LlmProvider][ai.droidcommand.llm]-shaped seam.
 */
fun interface CapabilityExecutor {
    fun execute(request: ExecutionRequest): ExecutionResponse
}

/** Routes an [ExecutionRequest] to an [ExecutionResponse] (CAP-008, P1.0) — the agent-facing contract between the LLM agent and whatever executes a capability. */
interface ExecutionRouter {
    fun route(request: ExecutionRequest): ExecutionResponse
}

/**
 * The real [ExecutionRouter] implementation. Two gates run before
 * [executor] is ever consulted, in this order:
 *
 * 1. [registry] membership — an unregistered [CapabilityId] returns
 *    [ExecutionResponse.CapabilityUnavailable] and [executor] is never
 *    called. This is the concrete enforcement point for P1.0's "the agent
 *    must never invoke a capability string not present in the live
 *    registry."
 * 2. [autoApprove] — a [RiskTier] outside this set returns
 *    [ExecutionResponse.RequiresApproval] immediately (non-blocking,
 *    unlike [ai.droidcommand.security.SecureToolExecutor]'s synchronous
 *    [ai.droidcommand.security.ApprovalPrompt]) and [executor] is never
 *    called.
 *
 * Only once both gates pass does [executor] run, and its [ExecutionResponse]
 * is returned unchanged — a real executor may itself report
 * [ExecutionResponse.Denied]/[ExecutionResponse.CapabilityUnavailable] for
 * target-specific reasons this router can't anticipate, and is not
 * second-guessed here.
 */
class DefaultExecutionRouter(
    private val registry: CapabilityRegistry,
    private val executor: CapabilityExecutor,
    private val autoApprove: Set<RiskTier> = setOf(RiskTier.READ_ONLY, RiskTier.REVERSIBLE),
    private val requestIdGenerator: () -> String = { UUID.randomUUID().toString() },
    private val logger: Logger = NoOpLogger,
) : ExecutionRouter {
    override fun route(request: ExecutionRequest): ExecutionResponse {
        if (!registry.isRegistered(request.capabilityId)) {
            val reason = "Capability '${request.capabilityId.value}' is not present in the live registry"
            logger.warn("capability_unavailable", mapOf("capabilityId" to request.capabilityId.value))
            return ExecutionResponse.CapabilityUnavailable(request.capabilityId, reason)
        }

        if (request.riskTier !in autoApprove) {
            val description = "Invoke '${request.capabilityId.value}' on ${request.targetType} (risk: ${request.riskTier})"
            val requestId = requestIdGenerator()
            logger.info(
                "requires_approval",
                mapOf("capabilityId" to request.capabilityId.value, "riskTier" to request.riskTier.name, "requestId" to requestId),
            )
            return ExecutionResponse.RequiresApproval(description, request.riskTier, requestId)
        }

        logger.info("execution_delegated", mapOf("capabilityId" to request.capabilityId.value, "targetType" to request.targetType.name))
        return executor.execute(request)
    }
}
