package ai.droidcommand.llm.factory

import ai.droidcommand.agent.Message
import ai.droidcommand.agent.Role
import ai.droidcommand.config.ConfiguredLlmProvider
import ai.droidcommand.config.SecretsVault
import ai.droidcommand.llm.AiProviderInfo
import ai.droidcommand.llm.LlmConfig
import ai.droidcommand.llm.LlmProvider
import ai.droidcommand.llm.LlmRequest
import ai.droidcommand.llm.LlmResponse
import ai.droidcommand.llm.ProviderType
import ai.droidcommand.remote.HttpTransport

/**
 * One prompt, one reply, to a BYOK cloud provider, for automation keys. Unlike [ChatSession.send] it reads no
 * conversation and writes nothing: the prompt is the only message sent, and nothing is stored. The vault key and the
 * provider construction are the same as chat's. [ChatResult.Failure.message] never contains the API key.
 */
class CloudPromptClient(
    private val vault: SecretsVault,
    private val transport: HttpTransport,
    private val providerFor: (CloudProviderSpec, LlmConfig) -> LlmProvider = { spec, config ->
        LlmProviderFactory.build(
            ConfiguredLlmProvider(config, AiProviderInfo(spec.id, spec.label, ProviderType.CLOUD, METADATA_ONLY_CONTEXT_TOKENS)),
            transport,
        ).provider
    },
) {
    /** Blocks on the network: call it off the main thread. An empty [model] uses the catalog's suggestion. */
    fun ask(spec: CloudProviderSpec, model: String, prompt: String): ChatResult {
        if (prompt.isBlank()) return ChatResult.Failure("Prompt is empty.")
        val key = vault.getSecret(spec.secretId)
        if (key.isNullOrBlank()) return ChatResult.Failure("No API key for ${spec.label}. Add one in Settings.")

        val config = LlmConfig(
            provider = spec.id,
            model = model.trim().ifEmpty { spec.defaultModel },
            authToken = { vault.getSecret(spec.secretId) },
        )
        val request = LlmRequest(systemPrompt = null, messages = listOf(Message(Role.USER, prompt.trim())))
        val response = try {
            providerFor(spec, config).complete(request)
        } catch (e: Exception) {
            return ChatResult.Failure(scrub("Request failed: ${e.message ?: e.javaClass.simpleName}", key))
        }

        return when (response) {
            is LlmResponse.Text -> if (response.content.isBlank()) {
                ChatResult.Failure("The model returned an empty reply.")
            } else {
                ChatResult.Reply(response.content)
            }
            is LlmResponse.ToolCall -> ChatResult.Failure("The model asked to call a tool, which this request does not support.")
            is LlmResponse.Error -> ChatResult.Failure(scrub(response.error.message, key))
        }
    }

    private fun scrub(message: String, key: String?): String =
        if (key != null && key.length >= MIN_REDACTABLE_KEY_LENGTH) message.replace(key, "[redacted]") else message

    private companion object {
        /** The same metadata-only budget [ChatSession] uses for its providers. */
        const val METADATA_ONLY_CONTEXT_TOKENS = 8_000

        /** The same threshold [ChatSession] uses before it redacts a key from an error message. */
        const val MIN_REDACTABLE_KEY_LENGTH = 6
    }
}
