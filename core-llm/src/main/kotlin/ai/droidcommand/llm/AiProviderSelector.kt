package ai.droidcommand.llm

import ai.droidcommand.agent.Task
import ai.droidcommand.agent.TokenBudgetManager

/**
 * What a caller wants from [AiProviderSelector.selectProvider] (CAP-004,
 * P0.4). Honestly scoped to only the factors this codebase can mechanically
 * evaluate from a caller-declared [AiProviderInfo] plus a real [Task] —
 * P0.4 also names latency and resource (memory/GPU) requirements as
 * selection factors, but no real measurement of either exists anywhere in
 * this repository, and fabricating a number for them would misrepresent
 * state the same way this codebase's `Null*`/honesty convention exists to
 * prevent elsewhere. Neither is implemented here.
 */
data class ProviderPreferences(
    val preferredProviderId: String? = null,
    val requiredCapabilities: Set<ProviderCapability> = emptySet(),
    /** `null` means "derive from the [Task] via an injected [TokenBudgetManager], if any; otherwise don't filter on context length." */
    val minContextTokens: Int? = null,
    /** `true` excludes every [ProviderType.CLOUD] provider — P0.4's "privacy requirements (local vs. cloud)" factor. */
    val requireLocal: Boolean = false,
    /** A provider whose [AiProviderInfo.cost] is `null` cannot be verified to meet this and is excluded, not assumed to pass. */
    val maxCostPerMillionInputTokens: Double? = null,
)

/**
 * Selects an [LlmProvider] from a caller-declared, registered set by
 * capability/context-length/privacy/cost/task-complexity (CAP-004, P0.4).
 *
 * **Deliberate deviation from the roadmap prompt's literal
 * `suspend fun selectProvider(...): AiProvider?` signature:** no module in
 * this repository declares a coroutines dependency, direct or transitive
 * (`core-llm` included) — every other interface this class sits beside
 * ([LlmProvider.complete], [ai.droidcommand.agent.Planner.decide],
 * [ai.droidcommand.agent.ContextManager.buildContext],
 * [TokenBudgetManager.allocateTokens]) is plain synchronous `fun`. Adding a
 * new direct `kotlinx-coroutines-core` dependency for one interface would be
 * inconsistent with the rest of this module, so [selectProvider] stays
 * synchronous. Likewise, "`AiProvider`" is [LlmProvider] — this codebase's
 * own existing provider abstraction — rather than a new, parallel type,
 * avoiding exactly the "second, competing model manager concept"
 * [ModelRouter]'s own doc comment already says this codebase avoids.
 */
interface AiProviderSelector {
    fun selectProvider(task: Task, preferences: ProviderPreferences = ProviderPreferences()): LlmProvider?

    fun listProviders(): List<AiProviderInfo>
}

/**
 * The real [AiProviderSelector] implementation: filters [providers] to the
 * ones whose declared [AiProviderInfo] satisfies every [ProviderPreferences]
 * constraint, then picks [preferredProviderId] if it's among the eligible
 * set, otherwise the local-first-ranked eligible provider (ties broken by
 * registration order — the same determinism [LocalFirstOrdering] already
 * applies).
 *
 * [tokenBudgetManager] is optional (matching CAP-002's own caller-opt-in
 * precedent): when supplied and [ProviderPreferences.minContextTokens] is
 * `null`, a minimum context requirement is derived from
 * [TokenBudgetManager.selectBudget]'s real, mechanical complexity heuristic
 * over [Task] — real composition with CAP-002, not a fabricated number.
 * Without one, no context-length filtering happens unless the caller states
 * an explicit [ProviderPreferences.minContextTokens].
 */
class DefaultAiProviderSelector(
    private val providers: List<RegisteredProvider>,
    private val tokenBudgetManager: TokenBudgetManager? = null,
) : AiProviderSelector {
    override fun listProviders(): List<AiProviderInfo> = providers.map { it.info }

    override fun selectProvider(task: Task, preferences: ProviderPreferences): LlmProvider? {
        val minContext = preferences.minContextTokens
            ?: tokenBudgetManager?.selectBudget(task)?.tokens

        val eligible = providers.filter { registered ->
            val info = registered.info
            info.available &&
                info.capabilities.containsAll(preferences.requiredCapabilities) &&
                (minContext == null || info.maxContextTokens >= minContext) &&
                (!preferences.requireLocal || info.type != ProviderType.CLOUD) &&
                (
                    preferences.maxCostPerMillionInputTokens == null ||
                        (info.cost != null && info.cost.inputPerMillionTokens <= preferences.maxCostPerMillionInputTokens)
                )
        }

        preferences.preferredProviderId
            ?.let { id -> eligible.firstOrNull { it.info.id == id } }
            ?.let { return it.provider }

        return eligible.minByOrNull { it.info.type.ordinal }?.provider
    }
}
