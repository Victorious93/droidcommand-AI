package ai.droidcommand.agent

/**
 * The 11 states a [CapabilityMetadata] can report (CAP-009, P1.1), shipped
 * verbatim from the spec — no additions or omissions.
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

/** Everything known about one capability's live status (CAP-009, P1.1), shipped verbatim from the spec. [dependencies] is stored/returned as declared metadata only — no validation or traversal over it happens anywhere in this module. */
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
 * Criteria for [CapabilityManager.listCapabilities] — designed here, since
 * the roadmap prompt references `CapabilityFilter()` as a default argument
 * but never defines it, the same "referenced but never defined" gap
 * `AllocatedContext`/`ProviderPreferences`/`AiProvider` already hit in
 * earlier CAP entries. Deliberately minimal: only the two fields
 * [CapabilityMetadata] itself makes filterable without inventing criteria
 * the spec never named. `null` in either field means "no constraint."
 */
data class CapabilityFilter(val state: CapabilityState? = null, val providerId: String? = null)

/** Thrown by [CapabilityManager.reverify] for an [id] never [CapabilityManager.register]ed — the signature is non-nullable, so absence must throw, the same reasoning [ToolRegistry.get]/[UnknownToolException] already establish. */
class UnknownCapabilityException(val id: CapabilityId) : NoSuchElementException("No capability registered under '${id.value}'")

/**
 * A live, re-verifiable capability registry (CAP-009, P1.1) — the richer
 * counterpart to [CapabilityRegistry]'s bare `isRegistered` boolean gate,
 * which [DefaultExecutionRouter] already depends on. **Deliberately not
 * named `CapabilityRegistry`**: the roadmap prompt's own P1.1 section
 * defines an `interface CapabilityRegistry` with an entirely different
 * shape (`getCapability`/`listCapabilities`/`register`/`reverify`/
 * `invalidate`) than the `isRegistered(id): Boolean` fun interface CAP-008
 * already shipped under that exact name. This interface is named after
 * P1.1's own section title ("Capability Manager (with re-verification)")
 * instead — avoiding the collision without inventing an unrelated name.
 *
 * **Deliberate deviation from the roadmap prompt's literal signature:**
 * `reverify` is a plain `fun`, not `suspend fun` — no module in this
 * repository declares a coroutines dependency, the same reasoning stated
 * repeatedly elsewhere in this codebase (`AiProviderSelector`,
 * `PersonaManager`, `ExecutionTarget`).
 *
 * **Re-verification, made concrete without fabricating async
 * infrastructure this codebase doesn't have:** the spec's own
 * "re-verification logic" calls for kicking off an async re-verify on
 * stale access, returning the current value immediately. This repository
 * has no fire-and-forget one-shot async primitive suited to that
 * (`Scheduler` only supports recurring fixed-rate tasks, not a one-off
 * background job safely writing back into a shared map). Instead,
 * staleness is a synchronous, explicit query ([isStale]) rather than an
 * invisible background action: [getCapability]/[listCapabilities] never
 * silently reverify anything — pure, side-effect-free lookups, the same
 * discipline [KnowledgeStore.load]/[ToolRegistry.get] already hold
 * themselves to. A caller checks [isStale] and explicitly calls
 * [reverify] when it wants fresh data — the same "decision, not automatic
 * action" boundary [PolicyDecision][ai.droidcommand.security.PolicyDecision]/
 * [RoutingDecision][ai.droidcommand.security.RoutingDecision]/
 * [ExecutionResponse.RequiresApproval] already keep.
 */
interface CapabilityManager {
    fun getCapability(id: CapabilityId): CapabilityMetadata?

    fun listCapabilities(filter: CapabilityFilter = CapabilityFilter()): List<CapabilityMetadata>

    fun register(metadata: CapabilityMetadata)

    /** @throws UnknownCapabilityException if [id] was never [register]ed. */
    fun reverify(id: CapabilityId): CapabilityMetadata

    fun invalidate(id: CapabilityId)

    fun isStale(id: CapabilityId, referenceTimeMs: Long = System.currentTimeMillis()): Boolean
}

/**
 * Verifies whether a capability is actually available (CAP-009, P1.1).
 * No implementation of this in the current codebase is backed by a real
 * Android/Termux/Docker/Proxmox/root capability — none of those
 * environments exist here. Only a scripted fake exercises this in tests,
 * the same honesty convention every `core-llm` extractor test already
 * follows for its own provider seam.
 *
 * **Deliberate deviation:** `verify` is a plain `fun`, not `suspend fun`,
 * the same reasoning [CapabilityManager] states for itself.
 */
interface CapabilityHealthChecker {
    fun verify(id: CapabilityId, providerId: String): CapabilityMetadata

    /** Local checks are cheap (seconds); remote checks are expensive (minutes) — this is a suggestion [DefaultCapabilityManager.isStale] consults, not an enforced schedule. */
    fun suggestedReverifyIntervalMs(providerId: String): Long
}

/**
 * The real [CapabilityManager] — and, by also implementing
 * [CapabilityRegistry], the concrete integration CAP-008's own doc comment
 * predicted ("a real extension point for CAP-009 to fill later"):
 * [DefaultExecutionRouter] can be constructed directly against an instance
 * of this class as its registry dependency. [isRegistered] treats only
 * [CapabilityState.AVAILABLE]/[CapabilityState.ENABLED] as "live" for that
 * purpose — every `REQUIRES_*`/[CapabilityState.DISABLED]/
 * [CapabilityState.UNAVAILABLE]/[CapabilityState.ERROR] state correctly
 * does not count.
 *
 * `synchronized` map, mirroring [InMemoryKnowledgeStore]'s lock
 * discipline. [register] **overwrites** any existing entry for the same
 * id — matching [KnowledgeStore.save]/[PersonaStore.save]'s "register is
 * an upsert" semantics (capability metadata is expected to evolve over
 * time via re-verification), deliberately **not** [ToolRegistry.register]'s
 * duplicate-rejection (tools are singleton-per-name; capability metadata
 * is not).
 */
class DefaultCapabilityManager(
    private val healthChecker: CapabilityHealthChecker,
    private val clock: () -> Long = System::currentTimeMillis,
) : CapabilityManager, CapabilityRegistry {
    private val capabilities = mutableMapOf<CapabilityId, CapabilityMetadata>()
    private val lock = Any()

    override fun getCapability(id: CapabilityId): CapabilityMetadata? = synchronized(lock) { capabilities[id] }

    override fun listCapabilities(filter: CapabilityFilter): List<CapabilityMetadata> = synchronized(lock) {
        capabilities.values
            .filter { filter.state == null || it.state == filter.state }
            .filter { filter.providerId == null || it.providerId == filter.providerId }
            .sortedBy { it.id.value }
    }

    override fun register(metadata: CapabilityMetadata) {
        synchronized(lock) { capabilities[metadata.id] = metadata }
    }

    override fun reverify(id: CapabilityId): CapabilityMetadata {
        val providerId = synchronized(lock) { capabilities[id]?.providerId } ?: throw UnknownCapabilityException(id)
        val verified = healthChecker.verify(id, providerId)
        synchronized(lock) { capabilities[id] = verified }
        return verified
    }

    override fun invalidate(id: CapabilityId) {
        synchronized(lock) {
            capabilities[id]?.let { capabilities[id] = it.copy(lastVerifiedAt = null) }
        }
    }

    override fun isStale(id: CapabilityId, referenceTimeMs: Long): Boolean {
        val metadata = getCapability(id) ?: return false
        val lastVerifiedAt = metadata.lastVerifiedAt ?: return true
        return referenceTimeMs - lastVerifiedAt > healthChecker.suggestedReverifyIntervalMs(metadata.providerId)
    }

    override fun isRegistered(id: CapabilityId): Boolean =
        getCapability(id)?.state in setOf(CapabilityState.AVAILABLE, CapabilityState.ENABLED)
}
