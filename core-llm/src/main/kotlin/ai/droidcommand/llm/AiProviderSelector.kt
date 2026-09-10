package ai.droidcommand.llm

import ai.droidcommand.agent.DefaultTokenBudgetManager
import ai.droidcommand.agent.Task
import ai.droidcommand.agent.TokenBudgetManager

/**
 * The three provider locality categories the roadmap prompt's own comment
 * names (`// LOCAL, SELF_HOSTED, CLOUD, etc.`) — no unnamed "etc." category
 * is guessed at here.
 */
enum class ProviderType { LOCAL, SELF_HOSTED, CLOUD }

/** Maps [ProviderType] onto CAP-003's [Locality] axis rather than re-declaring a second one: `LOCAL`/`SELF_HOSTED` are local, `CLOUD` is remote. */
fun ProviderType.toLocality(): Locality = when (this) {
    ProviderType.LOCAL, ProviderType.SELF_HOSTED -> Locality.LOCAL
    ProviderType.CLOUD -> Locality.REMOTE
}

/**
 * The two capability axes this codebase's own `LlmRequest`/`LlmResponse`
 * shape actually distinguishes today: every [LlmProvider] handles plain
 * completion, and tool-calling is the one real, checkable split
 * (`LlmRequest.tools`/`LlmResponse.ToolCall`) already represents.
 * Caller-declared per provider, the same as [ProviderType] — nothing here
 * safely probes "does this provider support tools" without making a real
 * call.
 */
enum class ProviderCapability { TEXT_COMPLETION, TOOL_CALLING }

/**
 * Per-token pricing. Undefined in the roadmap prompt (only referenced as
 * [AiProviderInfo.cost]'s type) — designed to match how LLM pricing
 * actually works (input/output rates differ). Both fields are nullable,
 * fully caller-supplied: no fabricated default pricing.
 */
data class Cost(val perInputToken: Double? = null, val perOutputToken: Double? = null)

/** A provider descriptor, verbatim from the spec's 7 fields. */
data class AiProviderInfo(
    val id: String,
    val name: String,
    val type: ProviderType,
    val maxContextTokens: Int,
    val available: Boolean,
    val cost: Cost?,
    val capabilities: Set<ProviderCapability>,
)

/**
 * A real, mechanical proxy for "is this provider configured" — **not a
 * live health probe.** An actual "is the provider up" call would cost
 * real tokens/money against a real endpoint, and this environment has no
 * live credentials to test one against anyway; the same passive/active
 * distinction `core-root.MagiskProvider.checkHealth(probeShell)` already
 * draws for root. A caller may use this to compute [AiProviderInfo.available],
 * or declare it some other way.
 */
fun LlmProvider.hasCredential(): Boolean = config.authToken() != null

/**
 * Undefined in the roadmap prompt — designed minimally around the two
 * selection factors that are genuinely caller-declared policy rather than
 * something computable: user configuration ([preferredProviderId]) and
 * privacy ([requireLocal]). Additional preference dimensions (e.g. a
 * max-cost ceiling) are a named future extension, not built here.
 */
data class ProviderPreferences(val preferredProviderId: String? = null, val requireLocal: Boolean = false)

/**
 * Pairs a descriptor with the real, callable [LlmProvider] it describes —
 * mirroring [LocalityAwareProvider]'s exact shape from CAP-003.
 * [AiProviderInfo] itself stays spec-pure with no live reference baked in.
 */
data class RegisteredAiProvider(val info: AiProviderInfo, val provider: LlmProvider)

/**
 * Selects the best [LlmProvider] for a [Task] (CAP-004, P0.4).
 *
 * **Two stated deviations from the literal spec:** (1) not `suspend` —
 * this codebase has no coroutines dependency anywhere, the same
 * deviation `core-agent.ContextManager.buildContext` already took from
 * P0.1's spec; (2) returns `LlmProvider?`, not the undefined `AiProvider?`
 * — `LlmProvider` is this codebase's real, already-existing, callable
 * provider interface; inventing a second parallel type with no defined
 * shape and no real differentiation would be redundant scaffolding.
 */
interface AiProviderSelector {
    fun selectProvider(task: Task, preferences: ProviderPreferences = ProviderPreferences()): LlmProvider?

    fun listProviders(): List<AiProviderInfo>
}

/**
 * The real [AiProviderSelector] implementation.
 *
 * Of P0.4's nine selection factors, this slice honestly implements the
 * ones this repository can actually compute: availability (a hard
 * exclude on [AiProviderInfo.available]), privacy
 * ([ProviderPreferences.requireLocal] via [toLocality]), context length +
 * task complexity (a hard exclude on [AiProviderInfo.maxContextTokens]
 * against [tokenBudgetManager]'s real, already-shipped CAP-002 heuristic
 * — reused rather than re-invented), user configuration
 * ([ProviderPreferences.preferredProviderId]), and token cost (ascending
 * rank among survivors, [Cost]-null ranked last, never assumed cheap).
 * Latency, resource requirements (memory/GPU), and capability-based
 * task-type matching are **not implemented** — no request-timing
 * instrumentation, no resource telemetry, and no field on [Task] encodes
 * what capability it needs; all three are named gaps, not fabricated.
 */
class DefaultAiProviderSelector(
    private val registered: List<RegisteredAiProvider>,
    private val tokenBudgetManager: TokenBudgetManager = DefaultTokenBudgetManager(),
) : AiProviderSelector {
    override fun listProviders(): List<AiProviderInfo> = registered.map { it.info }

    override fun selectProvider(task: Task, preferences: ProviderPreferences): LlmProvider? {
        var candidates = registered.filter { it.info.available }

        if (preferences.requireLocal) {
            candidates = candidates.filter { it.info.type.toLocality() == Locality.LOCAL }
        }

        val requiredTokens = tokenBudgetManager.selectBudget(task).tokens
        candidates = candidates.filter { it.info.maxContextTokens >= requiredTokens }

        preferences.preferredProviderId?.let { preferredId ->
            candidates.firstOrNull { it.info.id == preferredId }?.let { return it.provider }
        }

        // Ranked by perInputToken specifically: it's the one rate comparable across
        // providers without knowing how many output tokens a real call would produce.
        // A missing Cost, or a Cost with perInputToken unspecified, ranks last (Double.MAX_VALUE)
        // — unknown cost is never assumed cheap.
        return candidates
            .sortedWith(
                compareBy(
                    { it.info.type.toLocality().ordinal },
                    { it.info.cost?.perInputToken ?: Double.MAX_VALUE },
                ),
            )
            .firstOrNull()
            ?.provider
    }
}
