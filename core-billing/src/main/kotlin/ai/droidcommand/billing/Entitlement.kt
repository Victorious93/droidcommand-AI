package ai.droidcommand.billing

import java.time.Clock
import java.time.Instant

/**
 * Phase 6, Option A: a Play subscription unlocks a "Pro" tier on top of BYOK. It never substitutes a
 * server-held LLM key — users keep supplying their own keys via `SecretsVault`. No backend exists or is implied.
 */
enum class ProFeature { LOCAL_MODEL_DOWNLOADS, DOCUMENT_RAG, EXTENDED_TEMPLATES_AND_SKILLS }

enum class Plan { FREE, PRO }

/** What the store reported for a purchase, reduced to the states that matter for access decisions. */
enum class PurchaseState { PURCHASED, PENDING, UNSPECIFIED }

data class PurchaseSnapshot(
    val state: PurchaseState,
    val acknowledged: Boolean,
    /** Subscription expiry as reported by the store; null = unknown. */
    val expiresAt: Instant?,
)

data class Entitlement(val plan: Plan, val expiresAt: Instant? = null) {
    companion object {
        val FREE = Entitlement(Plan.FREE)
    }
}

/** Source of the current entitlement. The real Play Billing implementation is Android-only and not built yet. */
fun interface EntitlementProvider {
    fun current(): Entitlement
}

/** Always FREE. The honest default until a Play Billing-backed provider exists. */
object NullEntitlementProvider : EntitlementProvider {
    override fun current(): Entitlement = Entitlement.FREE
}

/**
 * Pure mapping from a store purchase to an entitlement. Fail-closed: PRO only for a PURCHASED, acknowledged
 * purchase with a known, future expiry. Pending, unacknowledged (Play refunds those after 3 days), unknown
 * expiry or any error all yield FREE.
 */
object PlayEntitlementMapper {
    fun map(purchases: List<PurchaseSnapshot>, clock: Clock = Clock.systemUTC()): Entitlement {
        val now = clock.instant()
        val best = purchases
            .filter { it.state == PurchaseState.PURCHASED && it.acknowledged }
            .mapNotNull { p -> p.expiresAt?.takeIf { it.isAfter(now) } }
            .maxOrNull()
        return if (best != null) Entitlement(Plan.PRO, best) else Entitlement.FREE
    }
}

/** Single decision point for Pro-gated features. A provider that throws is treated as FREE. */
class FeatureGate(
    private val provider: EntitlementProvider,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun isAllowed(feature: ProFeature): Boolean {
        val e = runCatching { provider.current() }.getOrDefault(Entitlement.FREE)
        if (e.plan != Plan.PRO) return false
        val exp = e.expiresAt ?: return false
        return exp.isAfter(clock.instant())
    }
}
