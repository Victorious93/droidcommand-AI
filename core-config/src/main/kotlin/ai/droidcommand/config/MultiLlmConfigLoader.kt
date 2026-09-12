package ai.droidcommand.config

import ai.droidcommand.llm.AiProviderInfo
import ai.droidcommand.llm.Cost
import ai.droidcommand.llm.LlmConfig
import ai.droidcommand.llm.ProviderCapability
import ai.droidcommand.llm.ProviderType

/**
 * A present-but-invalid per-provider configuration value — distinct from
 * [MissingConfigException] (a required key was absent entirely): an
 * unrecognized `_TYPE`/`_CAPABILITIES` entry, a non-numeric
 * `_MAX_CONTEXT_TOKENS`, or exactly one of the two `_COST_*` keys present
 * without the other.
 */
class InvalidLlmProviderConfigException(message: String) : IllegalStateException(message)

/**
 * Pairs a per-provider [LlmConfig] (connection settings — everything an
 * [ai.droidcommand.llm.LlmProvider] needs to actually run) with its declared
 * [AiProviderInfo] (selector metadata — everything
 * [ai.droidcommand.llm.AiProviderSelector] needs to choose between
 * providers). Everything one configured provider entry needs to become a
 * real `ai.droidcommand.llm.RegisteredProvider`, once a caller constructs
 * the actual [ai.droidcommand.llm.LlmProvider] instance this module cannot
 * (see [MultiLlmConfigLoader]'s own doc comment for why that construction
 * step stays the caller's responsibility).
 */
data class ConfiguredLlmProvider(val config: LlmConfig, val info: AiProviderInfo)

/**
 * Reads zero or more provider configurations from a [ConfigSource], closing
 * the "config-driven multi-provider construction" gap named repeatedly in
 * `docs/AUDIT_2026-09-05.md`'s CAP-004/005/006/007 addenda: [LlmConfigLoader]
 * builds exactly one [LlmConfig]; this builds a list, each entry paired with
 * the [AiProviderInfo] `ai.droidcommand.llm.AiProviderSelector`/
 * `RegisteredProvider` need to select between them.
 *
 * **Only the config-loading half of that gap — stated plainly, not silently
 * narrowed.** The other half those same addenda name — reading
 * [LlmConfig.provider]'s string to auto-construct a concrete
 * `AnthropicLlmProvider`/`OpenAiLlmProvider` — cannot live here:
 * constructing either needs `core-llm-anthropic`/`core-llm-openai` (in turn
 * needing a real `core-remote.HttpTransport`), and `core-config` depends on
 * none of them. Giving `core-config` those dependencies would invert this
 * repository's existing shape, where `core-config` is a leaf module several
 * things depend on, not one that reaches into concrete provider
 * implementations. That factory step is real, separate future work needing
 * an assembly-root module this repository does not yet have (confirmed:
 * no `fun main()` exists anywhere in it) — named here, not built.
 *
 * Additive throughout: [LlmConfigLoader]/[ConfigKeys]'s existing
 * single-provider keys and behavior are completely unchanged.
 * [ConfigKeys.LLM_PROVIDER_IDS] (new) is a comma-separated list of provider
 * ids (e.g. `"anthropic-primary,openai-fallback"`) — deliberately distinct
 * from [LlmConfig.provider] (`"anthropic"`/`"openai"`, the SDK/implementation
 * selector): one caller can declare two different `anthropic` configs (say,
 * a primary and a cheaper fallback model) under two different ids. Absent
 * or blank yields an empty list, so an existing single-provider deployment
 * that never sets [ConfigKeys.LLM_PROVIDER_IDS] is completely unaffected.
 *
 * Each id's own settings live under `DROIDCOMMAND_LLM_PROVIDER_<ID>_*` (id
 * upper-cased, `-` replaced with `_`). Required: `_PROVIDER`, `_MODEL`,
 * `_TYPE` ([ProviderType.LOCAL]/[ProviderType.SELF_HOSTED]/
 * [ProviderType.CLOUD] — never inferred from `_ENDPOINT`, matching
 * [ProviderType]'s own established "never inferred, only declared" rule),
 * `_MAX_CONTEXT_TOKENS`. Optional: `_NAME` (a human-readable display name,
 * defaulting to the id itself rather than fabricating one), `_ENDPOINT`,
 * `_TEMPERATURE`, `_MAX_OUTPUT_TOKENS`, `_API_KEY` (re-read on every
 * `authToken()` call, exactly like [LlmConfigLoader] — never captured as a
 * plain field), `_AVAILABLE` (default `true`), `_CAPABILITIES` (comma-separated
 * [ProviderCapability] names), `_COST_INPUT_PER_MILLION`/
 * `_COST_OUTPUT_PER_MILLION` (both present or both absent — one without the
 * other is a declared-config error, never silently coerced into a
 * half-known [Cost]).
 */
object MultiLlmConfigLoader {
    fun load(source: ConfigSource): List<ConfiguredLlmProvider> {
        val reader = ConfigReader(source)
        val ids = reader.optional(ConfigKeys.LLM_PROVIDER_IDS)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        return ids.map { id -> loadOne(source, reader, id) }
    }

    private fun loadOne(source: ConfigSource, reader: ConfigReader, id: String): ConfiguredLlmProvider {
        val prefix = keyPrefix(id)

        val config = LlmConfig(
            provider = reader.require(prefix + "PROVIDER"),
            model = reader.require(prefix + "MODEL"),
            endpoint = reader.optional(prefix + "ENDPOINT"),
            temperature = reader.optionalDouble(prefix + "TEMPERATURE"),
            maxOutputTokens = reader.optionalInt(prefix + "MAX_OUTPUT_TOKENS"),
            authToken = { source.get(prefix + "API_KEY") },
        )

        val typeRaw = reader.require(prefix + "TYPE")
        val type = ProviderType.entries.firstOrNull { it.name.equals(typeRaw, ignoreCase = true) }
            ?: throw InvalidLlmProviderConfigException(
                "Unknown provider type '$typeRaw' for provider '$id'; expected one of ${ProviderType.entries.joinToString { it.name }}",
            )

        val maxContextTokensRaw = reader.require(prefix + "MAX_CONTEXT_TOKENS")
        val maxContextTokens = maxContextTokensRaw.toIntOrNull()
            ?: throw InvalidLlmProviderConfigException(
                "Invalid ${prefix}MAX_CONTEXT_TOKENS value '$maxContextTokensRaw' for provider '$id'; expected an integer",
            )

        val available = reader.optionalBoolean(prefix + "AVAILABLE", default = true)

        val capabilitiesRaw = reader.optional(prefix + "CAPABILITIES")
        val capabilities = capabilitiesRaw
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.map { raw ->
                ProviderCapability.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                    ?: throw InvalidLlmProviderConfigException(
                        "Unknown capability '$raw' for provider '$id'; expected one of ${ProviderCapability.entries.joinToString { it.name }}",
                    )
            }
            ?.toSet()
            ?: emptySet()

        val costInputRaw = reader.optional(prefix + "COST_INPUT_PER_MILLION")
        val costOutputRaw = reader.optional(prefix + "COST_OUTPUT_PER_MILLION")
        val cost = when {
            costInputRaw == null && costOutputRaw == null -> null
            costInputRaw != null && costOutputRaw != null -> Cost(
                inputPerMillionTokens = costInputRaw.toDoubleOrNull()
                    ?: throw InvalidLlmProviderConfigException(
                        "Invalid ${prefix}COST_INPUT_PER_MILLION value '$costInputRaw' for provider '$id'; expected a number",
                    ),
                outputPerMillionTokens = costOutputRaw.toDoubleOrNull()
                    ?: throw InvalidLlmProviderConfigException(
                        "Invalid ${prefix}COST_OUTPUT_PER_MILLION value '$costOutputRaw' for provider '$id'; expected a number",
                    ),
            )
            else -> throw InvalidLlmProviderConfigException(
                "Provider '$id' declares only one of ${prefix}COST_INPUT_PER_MILLION/${prefix}COST_OUTPUT_PER_MILLION; both or neither are required",
            )
        }

        val info = AiProviderInfo(
            id = id,
            name = reader.optional(prefix + "NAME", default = id)!!,
            type = type,
            maxContextTokens = maxContextTokens,
            available = available,
            cost = cost,
            capabilities = capabilities,
        )

        return ConfiguredLlmProvider(config, info)
    }

    private fun keyPrefix(id: String): String = "DROIDCOMMAND_LLM_PROVIDER_${id.uppercase().replace('-', '_')}_"
}
