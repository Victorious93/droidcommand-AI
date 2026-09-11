package ai.droidcommand.agent

/** The 11 supported capability lifecycle states P1.1 names, verbatim. */
enum class CapabilityState {
    AVAILABLE,
    ENABLED,
    DISABLED,
    UNAVAILABLE,
    REQUIRES_PERMISSION,
    REQUIRES_ROOT,
    REQUIRES_SHIZUKU,
    REQUIRES_TERMUX,
    REQUIRES_CONFIGURATION,
    REQUIRES_EXTERNAL_SERVICE,
    ERROR,
}

/** A capability's live lifecycle record — verbatim P1.1's own 10-field shape. */
data class CapabilityMetadata(
    val id: CapabilityId,
    val providerId: String,
    val version: String,
    val state: CapabilityState,
    val permissionsRequired: List<String>,
    val dependencies: List<CapabilityId>,
    val lastVerifiedAt: Long?,
    val lastError: String?,
    val description: String,
    val riskTier: RiskTier,
)

/**
 * Undefined in the roadmap prompt — only referenced as
 * [CapabilityRegistry.listCapabilities]'s default parameter type.
 * Designed as a simple, all-optional multi-criteria AND filter, the same
 * shape [ToolRegistry.list] already establishes for this codebase's other
 * registries: a `null` field means "no constraint on this field."
 */
data class CapabilityFilter(
    val state: CapabilityState? = null,
    val providerId: String? = null,
    val riskTier: RiskTier? = null,
)

/**
 * A live, re-verifiable capability registry (P1.1) — verbatim the spec's
 * own shape, with one stated deviation: not `suspend` on [reverify], the
 * same deviation P0.1/P0.4/P1.0 already took, since this codebase has no
 * coroutines dependency anywhere.
 *
 * **Not the same type as [ToolCapabilityRegistry] (P1.0):** that earlier,
 * simpler registry (this codebase's own invention, since P1.0's own
 * "Required types" block never named a registry type) is `Tool`-backed
 * and membership-only; this one is `CapabilityMetadata`-backed and models
 * the richer lifecycle P1.1's own spec demands. Reconciling the two into
 * one registry an actual router consults is real, separate future work —
 * not attempted here.
 */
interface CapabilityRegistry {
    fun getCapability(id: CapabilityId): CapabilityMetadata?

    fun listCapabilities(filter: CapabilityFilter = CapabilityFilter()): List<CapabilityMetadata>

    fun register(metadata: CapabilityMetadata)

    fun reverify(id: CapabilityId): CapabilityMetadata

    fun invalidate(id: CapabilityId)
}

/**
 * Verifies whether a capability is actually available — verbatim P1.1's
 * own shape (same not-`suspend` deviation as [CapabilityRegistry]). No
 * real implementation ships in this slice; a real bridge to existing
 * infrastructure (e.g. a `RootProviderHealthChecker` wrapping
 * `core-root.RootProvider.checkHealth`) is named future work per provider.
 */
interface CapabilityHealthChecker {
    fun verify(id: CapabilityId, providerId: String): CapabilityMetadata

    /** Local checks are cheap (e.g. 10s); remote checks are expensive (e.g. 5m) — per P1.1's own comment. */
    fun suggestedReverifyIntervalMs(providerId: String): Long
}

/** [CapabilityRegistry.reverify]'s return type is non-nullable, so calling it for an unregistered id must throw rather than fabricate a value — mirrors [UnknownToolException]'s exact precedent. */
class UnknownCapabilityException(id: CapabilityId) : NoSuchElementException("No capability registered under '${id.value}'")

/**
 * The real [CapabilityRegistry] implementation.
 *
 * **Re-verification, a real but deliberately synchronous simplification
 * of P1.1's own prose ("kick off an async re-verify, return current state
 * immediately but tag it with staleness"):** this codebase has no
 * coroutines/async execution model, and [CapabilityMetadata] itself
 * (verbatim from the spec) has no staleness field to carry that signal
 * without inventing one — so [getCapability] re-verifies **synchronously**
 * on stale access instead, returning fresh metadata rather than a
 * current-but-possibly-stale one. This is still the real, working
 * behavior the spec's own rule demands ("on access, check `lastVerifiedAt`
 * against the provider's suggested cadence; if stale, re-verify") — just
 * blocking rather than non-blocking. Genuine background re-verification
 * (mirroring `Scheduler`/`ScheduledExecutorServiceScheduler`'s already-
 * shipped real-threading precedent from ROADMAP-127) is named future work.
 *
 * [listCapabilities] deliberately does **not** force re-verification of
 * every listed capability — the spec's own comment names remote health
 * checks as expensive (5 minutes), so a bulk list call silently triggering
 * N of them would be a real, surprising cost; only [getCapability]
 * (a targeted "access") and explicit [reverify] trigger a real check.
 */
class DefaultCapabilityRegistry(
    private val healthChecker: CapabilityHealthChecker,
    private val clock: () -> Long = System::currentTimeMillis,
) : CapabilityRegistry {
    private val capabilities = mutableMapOf<String, CapabilityMetadata>()
    private val lock = Any()

    override fun getCapability(id: CapabilityId): CapabilityMetadata? {
        val current = synchronized(lock) { capabilities[id.value] } ?: return null
        return if (isStale(current)) reverify(id) else current
    }

    override fun listCapabilities(filter: CapabilityFilter): List<CapabilityMetadata> =
        synchronized(lock) { capabilities.values.toList() }
            .filter { matches(it, filter) }
            .sortedBy { it.id.value }

    override fun register(metadata: CapabilityMetadata) {
        synchronized(lock) { capabilities[metadata.id.value] = metadata }
    }

    override fun reverify(id: CapabilityId): CapabilityMetadata {
        val existing = synchronized(lock) { capabilities[id.value] } ?: throw UnknownCapabilityException(id)
        val verified = healthChecker.verify(id, existing.providerId)
        synchronized(lock) { capabilities[id.value] = verified }
        return verified
    }

    override fun invalidate(id: CapabilityId) {
        synchronized(lock) {
            val existing = capabilities[id.value] ?: return
            capabilities[id.value] = existing.copy(lastVerifiedAt = null)
        }
    }

    private fun isStale(metadata: CapabilityMetadata): Boolean {
        val lastVerifiedAt = metadata.lastVerifiedAt ?: return true
        val interval = healthChecker.suggestedReverifyIntervalMs(metadata.providerId)
        return clock() - lastVerifiedAt >= interval
    }

    private fun matches(metadata: CapabilityMetadata, filter: CapabilityFilter): Boolean =
        (filter.state == null || metadata.state == filter.state) &&
            (filter.providerId == null || metadata.providerId == filter.providerId) &&
            (filter.riskTier == null || metadata.riskTier == filter.riskTier)
}
