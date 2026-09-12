package ai.droidcommand.config

/** Names of the configuration keys this module knows how to read. Values only — never hard-code a secret here. */
object ConfigKeys {
    const val LLM_PROVIDER = "DROIDCOMMAND_LLM_PROVIDER"
    const val LLM_MODEL = "DROIDCOMMAND_LLM_MODEL"
    const val LLM_ENDPOINT = "DROIDCOMMAND_LLM_ENDPOINT"
    const val LLM_TEMPERATURE = "DROIDCOMMAND_LLM_TEMPERATURE"
    const val LLM_MAX_OUTPUT_TOKENS = "DROIDCOMMAND_LLM_MAX_OUTPUT_TOKENS"
    const val LLM_API_KEY = "DROIDCOMMAND_LLM_API_KEY"

    /**
     * Comma-separated list of provider ids for [MultiLlmConfigLoader]
     * (e.g. `"anthropic-primary,openai-fallback"`) — deliberately separate
     * from [LLM_PROVIDER], which names a single-provider deployment's SDK
     * selector, not an id. Absent or blank means no additional providers are
     * configured; [LlmConfigLoader]'s single-provider keys above are
     * completely independent of this and need no change for either to work.
     */
    const val LLM_PROVIDER_IDS = "DROIDCOMMAND_LLM_PROVIDER_IDS"

    const val ROOT_ENABLED = "DROIDCOMMAND_ROOT_ENABLED"
    const val GRANTED_PERMISSIONS = "DROIDCOMMAND_GRANTED_PERMISSIONS"
    const val AUTO_APPROVE_LEVELS = "DROIDCOMMAND_AUTO_APPROVE_LEVELS"
}
