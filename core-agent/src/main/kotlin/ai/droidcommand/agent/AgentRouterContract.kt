package ai.droidcommand.agent

/**
 * A namespace-qualified capability identifier (P1.0). Validated exactly
 * per `docs/CAPABILITY_ROADMAP_PROMPT.md`'s own regex — the same
 * validated-value-class discipline [TaskGraph]/[JsonFileKnowledgeStore.ID_PATTERN]
 * already establish elsewhere in this module.
 */
data class CapabilityId(val value: String) {
    init {
        require(value.matches(Regex("""^[a-z0-9][a-z0-9._-]*$"""))) {
            "Invalid capability ID format: $value"
        }
    }
}

/** The seven execution target categories P1.0 names, verbatim. */
enum class ExecutionTargetType {
    ANDROID,
    TERMUX,
    LOCAL_PC,
    DOCKER,
    REMOTE_HOST,
    PROXMOX_VM,
    PROXMOX_LXC,
}

/** The four risk tiers P1.0 names, verbatim. */
enum class RiskTier {
    READ_ONLY,
    REVERSIBLE,
    DESTRUCTIVE,
    IRREVERSIBLE,
}

/** A request from the agent to the execution router — verbatim P1.0's own shape. */
data class ExecutionRequest(
    val capabilityId: CapabilityId,
    val targetType: ExecutionTargetType,
    val parameters: Map<String, String>,
    val riskTier: RiskTier,
)

/**
 * The router's response back to the agent — verbatim P1.0's own four
 * cases. **This slice never constructs [Success]** — see
 * `core-security.AgentRouterGateway`'s own doc comment for why: it needs
 * real dispatch machinery (P1.3/P1.4) this slice does not build.
 */
sealed class ExecutionResponse {
    data class Success(val result: String, val targetUsed: ExecutionTargetType, val verified: Boolean) : ExecutionResponse()

    data class RequiresApproval(val operationDescription: String, val riskTier: RiskTier, val requestId: String) : ExecutionResponse()

    data class Denied(val reason: String, val suggestedAlternative: String? = null) : ExecutionResponse()

    data class CapabilityUnavailable(val capabilityId: CapabilityId, val reason: String, val details: String? = null) : ExecutionResponse()
}

/**
 * A capability the router can actually reach: a real, already-existing
 * [Tool] (not a fabricated concept), registered for a specific
 * [targetType]. [id] is the namespace-qualified identifier an
 * [ExecutionRequest] names.
 */
data class RegisteredCapability(val id: CapabilityId, val targetType: ExecutionTargetType, val tool: Tool)

class DuplicateCapabilityException(id: CapabilityId) : IllegalArgumentException("Capability '${id.value}' is already registered")

/**
 * The "live registry" P1.0's own prose demands ("the agent must never
 * invoke a capability string not present in the live registry") —
 * **deliberately minimal, id-membership only, not P1.1's separate,
 * richer 11-state re-verification lifecycle** (`AVAILABLE`/`ENABLED`/
 * `DISABLED`/`REQUIRES_ROOT`/etc.), which is real, larger, not-yet-scoped
 * future work. Mirrors [ToolRegistry]'s exact registration pattern.
 */
interface CapabilityRegistry {
    fun register(capability: RegisteredCapability)

    fun lookup(id: CapabilityId): RegisteredCapability?

    fun list(): List<CapabilityId>
}

/** The real, immediately usable [CapabilityRegistry] — does not survive a process restart. */
class InMemoryCapabilityRegistry : CapabilityRegistry {
    private val capabilities = mutableMapOf<String, RegisteredCapability>()
    private val lock = Any()

    override fun register(capability: RegisteredCapability) {
        synchronized(lock) {
            if (capabilities.containsKey(capability.id.value)) throw DuplicateCapabilityException(capability.id)
            capabilities[capability.id.value] = capability
        }
    }

    override fun lookup(id: CapabilityId): RegisteredCapability? = synchronized(lock) { capabilities[id.value] }

    override fun list(): List<CapabilityId> = synchronized(lock) { capabilities.keys.toList().sorted().map { CapabilityId(it) } }
}

/**
 * The literal implementation of P1.0's own explicit rule: "the agent must
 * never invoke a capability string not present in the live registry."
 * Returns `null` when [request]'s capability is both registered *and*
 * registered for the requested [ExecutionRequest.targetType] — otherwise
 * a real [ExecutionResponse.CapabilityUnavailable] naming exactly why.
 */
fun checkCapabilityAvailability(request: ExecutionRequest, registry: CapabilityRegistry): ExecutionResponse.CapabilityUnavailable? {
    val capability = registry.lookup(request.capabilityId)
        ?: return ExecutionResponse.CapabilityUnavailable(request.capabilityId, "capability is not registered in the live registry")

    if (capability.targetType != request.targetType) {
        return ExecutionResponse.CapabilityUnavailable(
            request.capabilityId,
            "capability is registered for ${capability.targetType}, not ${request.targetType}",
        )
    }

    return null
}
