package ai.droidforge.config

/** Names of the configuration keys this module knows how to read. Values only — never hard-code a secret here. */
object ConfigKeys {
    const val LLM_PROVIDER = "DROIDFORGE_LLM_PROVIDER"
    const val LLM_MODEL = "DROIDFORGE_LLM_MODEL"
    const val LLM_ENDPOINT = "DROIDFORGE_LLM_ENDPOINT"
    const val LLM_TEMPERATURE = "DROIDFORGE_LLM_TEMPERATURE"
    const val LLM_MAX_OUTPUT_TOKENS = "DROIDFORGE_LLM_MAX_OUTPUT_TOKENS"
    const val LLM_API_KEY = "DROIDFORGE_LLM_API_KEY"

    const val ROOT_ENABLED = "DROIDFORGE_ROOT_ENABLED"
    const val GRANTED_PERMISSIONS = "DROIDFORGE_GRANTED_PERMISSIONS"
    const val AUTO_APPROVE_LEVELS = "DROIDFORGE_AUTO_APPROVE_LEVELS"
}
