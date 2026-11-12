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
    /** A provider with no [AiProviderInfo.measuredTokensPerSecond] cannot be verified to meet this and is excluded. */
    val minTokensPerSecond: Double? = null,
    /** When set, only providers of these types are eligible. Null (the default) allows every type. */
    val allowedTypes: Set<ProviderType>? = null,
    /**
     * When set, eligible providers are ranked in this type order instead of [ProviderType]'s local-first declaration
     * order (for example "the PC first, then this device"). A type missing from the list ranks after the listed ones.
     */
    val typeOrder: List<ProviderType>? = null,
)

/**
 * What [DefaultAiProviderSelector.explain] decided about one registered provider. [excludedBecause] is null for an
 * eligible provider, otherwise the first requirement it failed, in words a person can act on.
 */
data class ProviderDecision(val registered: RegisteredProvider, val excludedBecause: String?) {
    val eligible: Boolean get() = excludedBecause == null
}

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

    override fun selectProvider(task: Task, preferences: ProviderPreferences): LlmProvider? =
        explain(task, preferences).firstOrNull { it.eligible }?.registered?.provider

    /**
     * Every registered provider with the reason it was left out, eligible ones first in the order they would be
     * tried: [ProviderPreferences.preferredProviderId] if eligible, then by [ProviderPreferences.typeOrder] (or
     * local-first), ties in registration order. [selectProvider] returns the first eligible entry of this list, so a
     * caller that needs a fallback chain, or has to say why a provider was skipped, reads the same decision.
     */
    fun explain(task: Task, preferences: ProviderPreferences = ProviderPreferences()): List<ProviderDecision> {
        val minContext = preferences.minContextTokens
            ?: tokenBudgetManager?.selectBudget(task)?.tokens

        val decisions = providers.map { registered ->
            val info = registered.info
            val missing = preferences.requiredCapabilities - info.capabilities
            val reason = when {
                preferences.allowedTypes != null && info.type !in preferences.allowedTypes -> "its location (${info.type}) is not allowed by the selected policy"
                preferences.requireLocal && info.type == ProviderType.CLOUD -> "it is a cloud provider and a local one is required"
                !info.available -> "it is not available"
                missing.isNotEmpty() -> "it does not support ${missing.joinToString(", ")}"
                minContext != null && info.maxContextTokens < minContext ->
                    "its context window (${info.maxContextTokens} tokens) is smaller than the request needs ($minContext)"
                preferences.maxCostPerMillionInputTokens != null &&
                    (info.cost == null || info.cost.inputPerMillionTokens > preferences.maxCostPerMillionInputTokens) ->
                    "its cost is unknown or above the limit"
                preferences.minTokensPerSecond != null &&
                    (info.measuredTokensPerSecond == null || info.measuredTokensPerSecond < preferences.minTokensPerSecond) ->
                    "its speed is unmeasured or below the minimum"
                else -> null
            }
            ProviderDecision(registered, reason)
        }
        val order = preferences.typeOrder
        val rank: (ProviderDecision) -> Int = { decision ->
            val type = decision.registered.info.type
            when {
                decision.registered.info.id == preferences.preferredProviderId -> -1
                order == null -> type.ordinal
                else -> order.indexOf(type).let { if (it < 0) order.size + type.ordinal else it }
            }
        }
        val (eligible, excluded) = decisions.partition { it.eligible }
        return eligible.sortedBy(rank) + excluded
    }
}
