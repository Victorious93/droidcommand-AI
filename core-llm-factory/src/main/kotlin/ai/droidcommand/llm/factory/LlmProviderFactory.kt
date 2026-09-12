package ai.droidcommand.llm.factory

import ai.droidcommand.agent.TokenBudgetManager
import ai.droidcommand.config.ConfigSource
import ai.droidcommand.config.ConfiguredLlmProvider
import ai.droidcommand.config.MultiLlmConfigLoader
import ai.droidcommand.llm.AiProviderSelector
import ai.droidcommand.llm.DefaultAiProviderSelector
import ai.droidcommand.llm.LocalFirstOrdering
import ai.droidcommand.llm.ModelRouter
import ai.droidcommand.llm.RegisteredProvider
import ai.droidcommand.llm.anthropic.AnthropicLlmProvider
import ai.droidcommand.llm.openai.OpenAiLlmProvider
import ai.droidcommand.remote.HttpTransport

/**
 * A [ConfiguredLlmProvider.config]'s `provider` value that matches neither
 * known implementation — distinct from `core-config`'s own
 * `InvalidLlmProviderConfigException` (a malformed config *value*): this is
 * a config value that parsed fine but names a provider this factory cannot
 * construct.
 */
class UnknownLlmProviderException(message: String) : IllegalStateException(message)

/**
 * Closes the provider-construction half of "config-driven multi-provider
 * construction" — the gap `core-config.MultiLlmConfigLoader`'s own doc
 * comment names and explicitly leaves open, since `core-config` depends on
 * neither `core-llm-anthropic`/`core-llm-openai` (each needing a real
 * `HttpTransport` to construct) nor `core-remote` itself. This module is the
 * first one allowed to see every one of those at once, per the design
 * scoped in `docs/AUDIT_2026-09-05.md`'s "scoping the provider-construction
 * factory module" addendum — read there before changing this file's shape.
 *
 * [transport] is always caller-supplied, never constructed internally:
 * [ai.droidcommand.remote.JdkHttpTransport]'s own doc comment establishes it
 * holds no request-specific state, so one instance is safe to share across
 * every provider [buildAll]/[load] construct, and a caller who wants
 * certificate pinning/mutual TLS/a fake transport for tests supplies it the
 * same way `AnthropicLlmProviderIntegrationTest`/`OpenAiLlmProviderIntegrationTest`
 * already do for a single provider.
 *
 * [createSelector]/[createModelRouter] (added 2026-09-12, closing the
 * "not wired into a real caller yet" gap the "implementing core-llm-factory"
 * addendum named) go one step further than [load]: they compose the
 * constructed providers straight into `core-llm`'s own
 * [ai.droidcommand.llm.AiProviderSelector]/[ai.droidcommand.llm.ModelRouter],
 * the two real consumers of a [ai.droidcommand.llm.RegisteredProvider] list
 * that already existed in this codebase before this module did.
 */
object LlmProviderFactory {
    /**
     * Constructs the real [ai.droidcommand.llm.LlmProvider] named by
     * [configured]'s `config.provider` (case-insensitive, matching
     * [MultiLlmConfigLoader]'s own case-insensitive `_TYPE`/`_CAPABILITIES`
     * parsing), paired with [configured]'s own [ConfiguredLlmProvider.info]
     * into a [RegisteredProvider]. Only `"anthropic"`/`"openai"` are
     * recognized — the only two concrete providers this repository has;
     * anything else throws [UnknownLlmProviderException] naming both the
     * bad value and the known set, rather than silently skipping it.
     */
    fun build(configured: ConfiguredLlmProvider, transport: HttpTransport): RegisteredProvider {
        val provider = when (configured.config.provider.lowercase()) {
            "anthropic" -> AnthropicLlmProvider(configured.config, transport)
            "openai" -> OpenAiLlmProvider(configured.config, transport)
            else -> throw UnknownLlmProviderException(
                "Unknown provider '${configured.config.provider}' for '${configured.info.id}'; expected one of anthropic, openai",
            )
        }
        return RegisteredProvider(configured.info, provider)
    }

    /**
     * [build]s every entry in [configured] over one shared [transport]. A
     * single [UnknownLlmProviderException] fails the whole call rather than
     * silently dropping the offending entry and returning a partial list —
     * the same fail-closed discipline `core-security` already applies
     * elsewhere in this codebase.
     */
    fun buildAll(configured: List<ConfiguredLlmProvider>, transport: HttpTransport): List<RegisteredProvider> =
        configured.map { build(it, transport) }

    /**
     * The one call a real caller actually wants: reads every configured
     * provider from [source] via [MultiLlmConfigLoader] and constructs all
     * of them over [transport] in a single step.
     */
    fun load(source: ConfigSource, transport: HttpTransport): List<RegisteredProvider> =
        buildAll(MultiLlmConfigLoader.load(source), transport)

    /**
     * [load]s [source] and wraps the result in a real [AiProviderSelector] —
     * closing the last named gap from the 2026-09-12 "implementing
     * core-llm-factory" addendum: nothing previously composed this factory
     * with `core-llm`'s own selection/routing layer. [tokenBudgetManager] is
     * optional and passed straight through to [DefaultAiProviderSelector],
     * matching that class's own caller-opt-in precedent for deriving a
     * minimum context requirement from a [ai.droidcommand.agent.Task].
     */
    fun createSelector(
        source: ConfigSource,
        transport: HttpTransport,
        tokenBudgetManager: TokenBudgetManager? = null,
    ): AiProviderSelector = DefaultAiProviderSelector(load(source, transport), tokenBudgetManager)

    /**
     * [load]s [source] and wraps the result in a real [ModelRouter], ordered
     * local-first via [LocalFirstOrdering] — the exact composition
     * [LocalFirstOrdering]'s own doc comment already describes
     * (`ModelRouter(LocalFirstOrdering.order(registered))`), now reachable
     * directly from a [ConfigSource] instead of requiring every caller to
     * assemble it by hand. [ModelRouter]'s own constructor still rejects an
     * empty provider list (`require(providers.isNotEmpty())`) — an
     * unconfigured [source] surfaces as that same `IllegalArgumentException`
     * here, not a silently-empty router.
     */
    fun createModelRouter(source: ConfigSource, transport: HttpTransport): ModelRouter =
        ModelRouter(LocalFirstOrdering.order(load(source, transport)))
}
