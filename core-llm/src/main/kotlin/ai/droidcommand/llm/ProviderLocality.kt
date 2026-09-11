package ai.droidcommand.llm

/**
 * Whether an [LlmProvider] is a configured local/self-hosted model or a
 * remote cloud API — the last two links of P0.3's Local-Context-First
 * preference chain (`docs/CAPABILITY_ROADMAP_PROMPT.md`): "Configured
 * Local/Self-Hosted AI" before "Remote AI Provider (Cloud API)". Two
 * values, not the four-way `ProviderType` the roadmap prompt sketches for
 * the separate, larger P0.4 `AiProviderSelector` (CAP-004) — that richer
 * capability/latency/cost/privacy-aware selection stays its own future
 * slice; this type only ever answers "local or not."
 */
enum class ProviderLocality { LOCAL, REMOTE }

/**
 * Pairs an [LlmProvider] with a caller-declared [locality]. Deliberately
 * never inferred from [LlmConfig.endpoint] — a hostname/URL is not a
 * reliable locality signal (a self-hosted server can live on a LAN IP or a
 * VPN, not just `localhost`), and guessing would be exactly the kind of
 * fabricated-classification shortcut `DefaultTokenBudgetManager.selectBudget`'s
 * own "heuristic, not a classifier" restraint already avoids elsewhere in
 * this codebase. The caller who configured the provider already knows
 * where it runs; this type just carries that declaration through to
 * [LocalFirstOrdering].
 */
data class LocatedProvider(val locality: ProviderLocality, val provider: LlmProvider)

/**
 * Orders [LocatedProvider]s local-first (CAP-003, P0.3): every [ProviderLocality.LOCAL]
 * provider precedes every [ProviderLocality.REMOTE] one, with relative
 * input order preserved within each tier — the same "stable, ties broken by
 * input order" determinism [ContextKind]'s declaration order and
 * [TaskGraph.executionOrder][ai.droidcommand.agent.TaskGraph] already apply
 * elsewhere in this codebase.
 *
 * Produces exactly the `List<LlmProvider>` [ModelRouter]'s constructor
 * already accepts — a caller does `ModelRouter(LocalFirstOrdering.order(located))`.
 * [ModelRouter] itself needs no change: its own doc comment already
 * describes composing with an ordered provider list ("e.g. a local model
 * first, then a remote/cloud one"); this is the piece that actually builds
 * that order rather than requiring every caller to hand-sort it.
 */
object LocalFirstOrdering {
    fun order(providers: List<LocatedProvider>): List<LlmProvider> =
        providers.sortedBy { it.locality.ordinal }.map { it.provider }
}
