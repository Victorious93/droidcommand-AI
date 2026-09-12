package ai.droidcommand.security

/**
 * CAP-014, P1.6. How reversible an operation is, ordered least to most
 * consequential. This is a distinct axis from [ai.droidcommand.agent.SecurityLevel]
 * (`NORMAL`/`SENSITIVE`/`ROOT`, an existing 3-value permission/root axis) —
 * `docs/AUDIT_2026-09-05.md`'s CAP-### reconciliation already notes
 * [ai.droidcommand.agent.SecurityLevel] is "the closest analog to `RiskTier`
 * but has 3 values, not 4, and isn't targeted at execution routing"; this is
 * that missing fourth-value type, kept separate rather than folded into
 * [ai.droidcommand.agent.SecurityLevel] and reused as-is by
 * [RiskApprovalPolicy] below.
 */
enum class RiskTier {
    READ_ONLY,
    REVERSIBLE,
    DESTRUCTIVE,
    IRREVERSIBLE,
}

/**
 * A request for explicit authorization of one operation. `targetType` and
 * `capabilityId` are now the real [ExecutionTargetType]/[CapabilityId]
 * types (CAP-008, P1.0) — originally shipped as plain [String] stand-ins
 * while those types were still MISSING per the CAP-### reconciliation, the
 * same gap [ai.droidcommand.config.SecretsVault]'s own doc comment
 * documented for its identical `capabilityId` parameter. `toolId` stays
 * [String]: it identifies a [ai.droidcommand.agent.Tool], a distinct
 * concept CAP-008 doesn't define a type for.
 */
data class ApprovalRequest(
    val requestId: String,
    val operationDescription: String,
    val riskTier: RiskTier,
    val targetType: ExecutionTargetType,
    val toolId: String,
    val capabilityId: CapabilityId,
    val timeoutMs: Long = 60_000,
)

/**
 * [TimedOut] is a distinct outcome from [Denied] precisely so a caller can
 * default-deny on it without conflating "a human explicitly said no" with
 * "no human answered in time" — the roadmap prompt's own `// default-deny`
 * comment on this case. [Unavailable] is likewise distinct: the approval
 * mechanism itself failed (no UI attached, the prompt implementation
 * threw), not that anyone weighed in at all.
 */
sealed class ApprovalResponse {
    data object Approved : ApprovalResponse()
    data object Denied : ApprovalResponse()
    data object TimedOut : ApprovalResponse()
    data object Unavailable : ApprovalResponse()
}

/**
 * The richer, typed successor to [ApprovalPrompt]'s plain
 * `(String) -> Boolean`. Kept as a separate interface rather than changing
 * [ApprovalPrompt] itself — every existing [SecureToolExecutor] caller and
 * test keeps working unchanged; [TimeoutApprovalProvider] is the bridge
 * between the two for a caller that wants to adopt this shape today.
 */
fun interface ApprovalProvider {
    fun requestApproval(request: ApprovalRequest): ApprovalResponse
}

/**
 * The roadmap prompt's own risk/timeout defaults, verbatim. Real, but
 * arbitrary — a first reasonable default, not a proven calibration.
 */
object RiskApprovalPolicy {
    fun requiresApproval(riskTier: RiskTier): Boolean =
        riskTier in setOf(RiskTier.REVERSIBLE, RiskTier.DESTRUCTIVE, RiskTier.IRREVERSIBLE)

    fun defaultTimeoutMs(riskTier: RiskTier): Long = when (riskTier) {
        RiskTier.READ_ONLY -> 0
        RiskTier.REVERSIBLE -> 30_000
        RiskTier.DESTRUCTIVE -> 120_000
        RiskTier.IRREVERSIBLE -> 300_000
    }
}
