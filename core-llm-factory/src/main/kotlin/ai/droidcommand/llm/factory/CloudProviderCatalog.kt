package ai.droidcommand.llm.factory

/**
 * One BYOK cloud provider as the app presents it. [id] is the [LlmProviderFactory] provider name;
 * [secretId] is the `SecretsVault` id its API key is stored under (shared by Settings and chat so
 * they cannot drift apart); [defaultModel] is only a SUGGESTION — model ids change, so callers must
 * let the user edit it, and none of these were verified against a live API.
 */
data class CloudProviderSpec(val id: String, val label: String, val secretId: String, val defaultModel: String)

object CloudProviderCatalog {
    val all: List<CloudProviderSpec> = listOf(
        CloudProviderSpec("anthropic", "Anthropic (Claude)", "llm.anthropic.api_key", "claude-sonnet-5-5"),
        CloudProviderSpec("openai", "OpenAI", "llm.openai.api_key", "gpt-4o-mini"),
        CloudProviderSpec("google", "Google (Gemini)", "llm.google.api_key", "gemini-2.5-flash"),
        CloudProviderSpec("groq", "Groq", "llm.groq.api_key", "llama-3.3-70b-versatile"),
    )

    fun byId(id: String): CloudProviderSpec? = all.firstOrNull { it.id == id }
}
