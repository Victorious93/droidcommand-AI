package ai.droidcommand.security

/**
 * CAP-009, P1.1. The 11 states a registered capability can report, verbatim
 * from the roadmap prompt's own list. A separate, narrower
 * `ai.droidcommand.root.RootProviderState` already exists in `core-root`
 * (predating this type, added for the Magisk support work) and is
 * deliberately left as-is rather than folded into this one: it is
 * root-specific vocabulary a single provider package needs today, while
 * this is the umbrella vocabulary a multi-provider registry needs — the
 * same "distinct axis, not a competing model" relationship [RiskTier] and
 * [ai.droidcommand.agent.SecurityLevel] already have. Wiring a real
 * `RootProvider`/`MagiskProvider` into this registry (mapping
 * `RootProviderState` values onto these) is real follow-up work, not done
 * here.
 */
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

/** Everything the registry knows about one registered capability. */
data class CapabilityMetadata(
    val id: CapabilityId,
    val providerId: String,
    val version: String,
    val state: CapabilityState,
    val permissionsRequired: List<String>,
    val dependencies: List<CapabilityId>,
    val lastVerifiedAt: Long? = null,
    val lastError: String? = null,
    val description: String,
    val riskTier: RiskTier,
)

/**
 * Not defined by the roadmap prompt itself (the same "referenced but never
 * defined" gap `ProviderPreferences`/`AllocatedContext` already hit
 * elsewhere in this codebase) — designed as the minimal filter
 * [CapabilityMetadata]'s own fields already support querying on. `null` on
 * any field means "don't filter on this," matching `ProviderPreferences`'
 * own convention.
 */
data class CapabilityFilter(
    val state: CapabilityState? = null,
    val providerId: String? = null,
    val riskTier: RiskTier? = null,
)

private fun CapabilityMetadata.matches(filter: CapabilityFilter): Boolean =
    (filter.state == null || filter.state == state) &&
        (filter.providerId == null || filter.providerId == providerId) &&
        (filter.riskTier == null || filter.riskTier == riskTier)

/**
 * Whether [CapabilityMetadata.lastVerifiedAt] is old enough, against
 * [reverifyIntervalMs] (typically [CapabilityHealthChecker.suggestedReverifyIntervalMs]
 * for this capability's provider), that a caller should call
 * [CapabilityRegistry.reverify] before trusting [CapabilityMetadata.state].
 * Never-verified ([CapabilityMetadata.lastVerifiedAt] `null`) is always
 * stale. This is a plain, composable check, not an imposed policy: see
 * [CapabilityRegistry]'s own doc comment for why this registry does not
 * itself decide *when* to call [CapabilityRegistry.reverify].
 */
fun CapabilityMetadata.isStale(reverifyIntervalMs: Long, now: Long = System.currentTimeMillis()): Boolean {
    val last = lastVerifiedAt ?: return true
    return now - last >= reverifyIntervalMs
}

class UnknownCapabilityException(id: CapabilityId) : Exception("Unknown capability: ${id.value}")

/**
 * A live, re-verifiable capability registry — "capabilities are NOT
 * detected once at boot and cached forever," per the roadmap prompt's own
 * P1.1 framing.
 *
 * **Deliberate deviation from the roadmap prompt's own "re-verification
 * logic" ("on access, ... kick off an async re-verify; return current
 * state immediately but tag it with staleness"):** this registry does not
 * auto-trigger a background re-verify from [getCapability]/[listCapabilities],
 * and does not tag a read with staleness — [CapabilityMetadata]'s own
 * literal shape (verbatim from the prompt) has no staleness field to carry
 * that tag through a plain read, and no module in this dependency chain
 * has an async/background-execution primitive beyond the narrow
 * bounded-wait pattern `TimeoutApprovalProvider` already uses for a single
 * blocking call — not a general "fire and forget in the background"
 * mechanism. Instead, [isStale] is exposed as a plain, composable check a
 * caller can run against a read result to decide for itself whether to
 * call [reverify] (synchronously, or from its own background thread if it
 * wants that policy) — the registry supplies the building block rather
 * than imposing an async policy of its own, the same restraint
 * `PersonaContextProvider` already takes for "which persona is active."
 *
 * `reverify`/`verify` are synchronous `fun`, not the prompt's own
 * `suspend fun` — no module in this dependency chain declares a
 * coroutines dependency, the same reasoning `AiProviderSelector`/
 * `ExecutionTarget`/`ExecutionRouter` already document for themselves.
 */
interface CapabilityRegistry {
    fun getCapability(id: CapabilityId): CapabilityMetadata?

    fun listCapabilities(filter: CapabilityFilter = CapabilityFilter()): List<CapabilityMetadata>

    fun register(metadata: CapabilityMetadata)

    /** @throws UnknownCapabilityException if [id] was never [register]ed. */
    fun reverify(id: CapabilityId): CapabilityMetadata

    fun invalidate(id: CapabilityId)
}

/**
 * Verifies whether a capability is actually available and reports how
 * often it's worth re-checking. A real implementation belongs to a single
 * provider (e.g. a future `MagiskCapabilityHealthChecker`) — this
 * interface deliberately cannot be implemented generically across
 * providers, since [verify] must reconstruct a *complete*
 * [CapabilityMetadata] (`version`, `description`, `permissionsRequired`,
 * `dependencies` included) from just an id and provider id, which only a
 * provider-specific implementation can honestly know.
 */
interface CapabilityHealthChecker {
    fun verify(id: CapabilityId, providerId: String): CapabilityMetadata

    /** Local checks are cheap (seconds); remote checks are expensive (minutes). */
    fun suggestedReverifyIntervalMs(providerId: String): Long
}

/**
 * The real [CapabilityRegistry]: an in-memory map, `@Synchronized`
 * throughout matching [InMemoryGrantStore]/[InMemoryAuditLog]'s own
 * convention. [healthChecker] is `null` by default rather than a `Null*`
 * object: [CapabilityHealthChecker.verify]'s return type is a *required*,
 * fully-populated [CapabilityMetadata] with no honest placeholder for
 * `version`/`description` a null-object implementation could fabricate
 * without misrepresenting real data — unlike [ai.droidcommand.root.NullRootExecutor],
 * whose result type ([ai.droidcommand.root.RootExecutionResult]) has a
 * genuine "cannot do this" case to report through. [reverify] fails loudly
 * with [IllegalStateException] instead when no [healthChecker] is
 * configured, rather than inventing one.
 */
class InMemoryCapabilityRegistry(private val healthChecker: CapabilityHealthChecker? = null) : CapabilityRegistry {
    private val capabilities = mutableMapOf<CapabilityId, CapabilityMetadata>()

    @Synchronized
    override fun getCapability(id: CapabilityId): CapabilityMetadata? = capabilities[id]

    @Synchronized
    override fun listCapabilities(filter: CapabilityFilter): List<CapabilityMetadata> =
        capabilities.values.filter { it.matches(filter) }

    @Synchronized
    override fun register(metadata: CapabilityMetadata) {
        capabilities[metadata.id] = metadata
    }

    @Synchronized
    override fun reverify(id: CapabilityId): CapabilityMetadata {
        val existing = capabilities[id] ?: throw UnknownCapabilityException(id)
        val checker = healthChecker
            ?: throw IllegalStateException("Cannot reverify '${id.value}': no CapabilityHealthChecker is configured for this registry")
        val updated = checker.verify(id, existing.providerId)
        capabilities[id] = updated
        return updated
    }

    @Synchronized
    override fun invalidate(id: CapabilityId) {
        val existing = capabilities[id] ?: return
        capabilities[id] = existing.copy(lastVerifiedAt = null)
    }
}
