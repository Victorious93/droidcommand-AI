package ai.droidcommand.security

/**
 * CAP-008, P1.0. The request/response contract between the LLM agent and the
 * (still-MISSING, CAP-012) execution router. Only the contract types are
 * defined here — no router, no capability registry, no target dispatch;
 * those are CAP-009/CAP-011/CAP-012's own, separately-gated work. This is
 * the "load-bearing type" the CAP-013/CAP-014 entries in
 * `docs/AUDIT_2026-09-05.md` already anticipated: [ApprovalRequest]'s
 * `targetType`/`capabilityId` and [ai.droidcommand.config.SecretsVault]'s
 * `capabilityId` parameter are plain [String] today precisely because this
 * type didn't exist yet.
 *
 * Namespace-qualified capability identifier (e.g. `android.notifications.read`).
 */
data class CapabilityId(val value: String) {
    init {
        require(value.matches(Regex("""^[a-z0-9][a-z0-9._-]*$"""))) {
            "Invalid capability ID format: $value"
        }
    }
}

/**
 * Where a capability may be executed. Mirrors the roadmap prompt's P1.0
 * list verbatim; only `ANDROID` has any real execution backing today
 * (`core-tools-android`/`core-root`) — the rest describe P2–P6 targets this
 * repository does not yet implement, and are declared here only as the
 * enum values a future [ExecutionTargetType]-aware router will dispatch on.
 */
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
 * A request from the agent to the execution router for one capability
 * invocation. `riskTier` reuses the existing [RiskTier] (CAP-014, P1.6)
 * rather than the roadmap prompt's own literal re-declaration of the same
 * four-value enum in its P1.0 section: the two are the identical type, not
 * merely similar, so declaring a second `RiskTier` here would create a real
 * duplicate rather than a deliberately-distinct axis (the way
 * [ai.droidcommand.agent.SecurityLevel] and [RiskTier] itself already are,
 * per [RiskTier]'s own doc comment).
 */
data class ExecutionRequest(
    val capabilityId: CapabilityId,
    val targetType: ExecutionTargetType,
    val parameters: Map<String, String>,
    val riskTier: RiskTier,
)

/** The router's response back to the agent for one [ExecutionRequest]. */
sealed class ExecutionResponse {
    data class Success(
        val result: String,
        val targetUsed: ExecutionTargetType,
        val verified: Boolean,
    ) : ExecutionResponse()

    /**
     * Mirrors [ApprovalRequest]/[ApprovalResponse] (CAP-014) rather than
     * duplicating their fields: `requestId` is the correlation id a caller
     * uses to look up the eventual [ApprovalResponse] via an
     * [ApprovalProvider], not a second parallel approval mechanism.
     */
    data class RequiresApproval(
        val operationDescription: String,
        val riskTier: RiskTier,
        val requestId: String,
    ) : ExecutionResponse()

    data class Denied(
        val reason: String,
        val suggestedAlternative: String? = null,
    ) : ExecutionResponse()

    data class CapabilityUnavailable(
        val capabilityId: CapabilityId,
        val reason: String,
        val details: String? = null,
    ) : ExecutionResponse()
}
