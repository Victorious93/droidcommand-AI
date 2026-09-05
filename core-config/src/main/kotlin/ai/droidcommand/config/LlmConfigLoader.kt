package ai.droidcommand.config

import ai.droidcommand.llm.LlmConfig

/**
 * Builds an [LlmConfig] from a [ConfigSource]. `authToken` is a lambda that
 * re-reads [ConfigKeys.LLM_API_KEY] from [source] on every call rather than
 * capturing it once — the key is never held as a plain field on the
 * returned config, so nothing in this loader itself can serialize or log a
 * credential.
 */
object LlmConfigLoader {
    fun load(source: ConfigSource): LlmConfig {
        val reader = ConfigReader(source)
        return LlmConfig(
            provider = reader.require(ConfigKeys.LLM_PROVIDER),
            model = reader.require(ConfigKeys.LLM_MODEL),
            endpoint = reader.optional(ConfigKeys.LLM_ENDPOINT),
            temperature = reader.optionalDouble(ConfigKeys.LLM_TEMPERATURE),
            maxOutputTokens = reader.optionalInt(ConfigKeys.LLM_MAX_OUTPUT_TOKENS),
            authToken = { source.get(ConfigKeys.LLM_API_KEY) },
        )
    }
}
