package ai.droidcommand.llm.factory

import ai.droidcommand.config.ConfigSource
import ai.droidcommand.config.ConfiguredLlmProvider
import ai.droidcommand.config.MultiLlmConfigLoader
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
}
