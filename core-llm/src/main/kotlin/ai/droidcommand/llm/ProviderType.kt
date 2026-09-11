package ai.droidcommand.llm

/**
 * Where an [LlmProvider] actually runs — CAP-003/P0.3's "Configured
 * Local/Self-Hosted AI" vs. "Remote AI Provider (Cloud API)" distinction,
 * and P0.4's own `ProviderType` sketch (`docs/CAPABILITY_ROADMAP_PROMPT.md`
 * line 286: `LOCAL, SELF_HOSTED, CLOUD, etc.`). Declaration order doubles as
 * local-first priority order for [LocalFirstOrdering], the same
 * "declaration order is priority order" discipline [ContextKind] already
 * documents for its own determinism.
 */
enum class ProviderType { LOCAL, SELF_HOSTED, CLOUD }

/**
 * A capability an [LlmProvider] can be declared to support (CAP-004,
 * P0.4). [TOOL_CALLING] is the only one anything in this codebase currently
 * reasons about — both real providers parse tool-call response shapes into
 * [LlmResponse.ToolCall] (`ROADMAP-059`, VERIFIED IMPLEMENTED). The rest are
 * reserved, not-yet-real values — the same "kind exists, no adapter yet"
 * pattern [ContextKind]'s `USER`/`PROJECT`/`PERSONA`/`SUMMARY` already
 * establish — naming them now costs nothing and avoids a breaking enum
 * change once streaming/structured output (`ROADMAP-058`/`-060`, both
 * MISSING today) land.
 */
enum class ProviderCapability { TOOL_CALLING, STREAMING, STRUCTURED_OUTPUT, VISION }

/**
 * A caller-declared price, never computed or looked up by this codebase —
 * no pricing data exists anywhere here. Shaped after how Anthropic/OpenAI
 * actually price real requests (separate input/output per-million-token
 * rates), not a fabricated schema.
 */
data class Cost(val inputPerMillionTokens: Double, val outputPerMillionTokens: Double)

/**
 * Everything [AiProviderSelector]/[LocalFirstOrdering] know about one
 * [LlmProvider] — entirely caller-declared (P0.4's `AiProviderInfo`), never
 * inferred: an endpoint hostname isn't a reliable [type] signal (a
 * self-hosted server can live on a LAN IP or a VPN, not just `localhost`),
 * and no other field here has a real, introspectable source anywhere in
 * this codebase either. The caller who configured a provider already knows
 * its real metadata; this type just carries that declaration through.
 */
data class AiProviderInfo(
    val id: String,
    val name: String,
    val type: ProviderType,
    val maxContextTokens: Int,
    val available: Boolean = true,
    val cost: Cost? = null,
    val capabilities: Set<ProviderCapability> = emptySet(),
)

/** Pairs a real [LlmProvider] with its declared [info]. */
data class RegisteredProvider(val info: AiProviderInfo, val provider: LlmProvider)

/**
 * Orders [RegisteredProvider]s local-first (CAP-003, P0.3): every
 * [ProviderType.LOCAL] provider precedes every [ProviderType.SELF_HOSTED]
 * one, which precedes every [ProviderType.CLOUD] one — stable within each
 * tier, ties broken by input order, the same determinism discipline
 * [ContextKind]'s declaration order and
 * [TaskGraph.executionOrder][ai.droidcommand.agent.TaskGraph] already apply
 * elsewhere in this codebase.
 *
 * Produces exactly the `List<LlmProvider>` [ModelRouter]'s constructor
 * already accepts — a caller does `ModelRouter(LocalFirstOrdering.order(registered))`.
 * [ModelRouter] itself needs no change: its own doc comment already
 * describes composing with an ordered provider list ("e.g. a local model
 * first, then a remote/cloud one").
 */
object LocalFirstOrdering {
    fun order(providers: List<RegisteredProvider>): List<LlmProvider> =
        providers.sortedBy { it.info.type.ordinal }.map { it.provider }
}
