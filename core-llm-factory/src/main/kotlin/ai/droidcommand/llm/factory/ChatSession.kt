package ai.droidcommand.llm.factory

import ai.droidcommand.agent.ConversationContext
import ai.droidcommand.agent.ConversationStore
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
import ai.droidcommand.llm.completeStreaming
import ai.droidcommand.remote.HttpTransport

/** Outcome of one chat turn. [Failure.message] never contains the API key. */
sealed class ChatResult {
    data class Reply(val text: String) : ChatResult()

    data class Failure(val message: String) : ChatResult()
}

/**
 * One persisted chat conversation against a BYOK cloud provider — the testable core of the Phase 1
 * chat screen, kept free of Android types so it runs (and is tested) on the JVM.
 *
 * Per turn: reads the key fresh from [vault] (never cached), builds the provider, streams the reply
 * through `onDelta`, and only on success appends the user message and the reply to the conversation
 * and saves it to [store]. A failed turn changes nothing, so the caller can retry with the same text.
 * Errors are scrubbed of the API key before being returned, because a provider's error body is
 * untrusted text that could echo a credential.
 *
 * [providerFor] is injectable so tests can point at a local `http://` server (the default factory
 * enforces https). [history], [send] and [reset] block on storage and the network: call them off the main thread. Not safe for concurrent
 * sends (calls are serialized).
 */
class ChatSession(
    private val vault: SecretsVault,
    private val store: ConversationStore,
    private val conversationId: String = DEFAULT_CONVERSATION_ID,
    private val systemPrompt: String? = null,
    private val transport: HttpTransport,
    private val providerFor: (CloudProviderSpec, LlmConfig) -> LlmProvider = { spec, config ->
        LlmProviderFactory.build(
            ConfiguredLlmProvider(config, AiProviderInfo(spec.id, spec.label, ProviderType.CLOUD, METADATA_ONLY_CONTEXT_TOKENS)),
            transport,
        ).provider
    },
) {
    // Loaded on first use, not at construction: a Room-backed store throws on the main thread, and a
    // caller (an Android ViewModel) constructs this there. Every public method may touch storage.
    private var loaded: ConversationContext? = null
    private val context: ConversationContext
        get() = loaded ?: (store.load(conversationId) ?: ConversationContext(systemPrompt)).also { loaded = it }

    @Synchronized
    fun history(): List<Message> = context.messages

    @Synchronized
    fun send(spec: CloudProviderSpec, model: String, userText: String, onDelta: (String) -> Unit = {}): ChatResult {
        val text = userText.trim()
        if (text.isEmpty()) return ChatResult.Failure("Message is empty.")
        val key = vault.getSecret(spec.secretId)
        if (key.isNullOrBlank()) return ChatResult.Failure("No API key for ${spec.label}. Add one in Settings.")

        val config = LlmConfig(provider = spec.id, model = model.trim().ifEmpty { spec.defaultModel }, authToken = { vault.getSecret(spec.secretId) })
        val request = LlmRequest(systemPrompt = context.systemPrompt, messages = context.messages + Message(Role.USER, text))

        val response = try {
            providerFor(spec, config).completeStreaming(request, onDelta)
        } catch (e: Exception) {
            return ChatResult.Failure(scrub("Request failed: ${e.message ?: e.javaClass.simpleName}", key))
        }

        return when (response) {
            is LlmResponse.Text -> {
                if (response.content.isBlank()) return ChatResult.Failure("The model returned an empty reply.")
                context.append(Role.USER, text).append(Role.ASSISTANT, response.content)
                store.save(conversationId, context)
                ChatResult.Reply(response.content)
            }
            is LlmResponse.ToolCall -> ChatResult.Failure("The model asked to call a tool, which chat does not support.")
            is LlmResponse.Error -> ChatResult.Failure(scrub(response.error.message, key))
        }
    }

    /** Deletes the stored conversation and starts an empty one. */
    @Synchronized
    fun reset() {
        store.delete(conversationId)
        loaded = ConversationContext(systemPrompt)
    }

    private fun scrub(message: String, key: String): String =
        if (key.length >= MIN_REDACTABLE_KEY_LENGTH) message.replace(key, "[redacted]") else message

    companion object {
        const val DEFAULT_CONVERSATION_ID = "default"

        /** Only fills AiProviderInfo, which chat does not use for selection. Not a claim about any model's real window. */
        private const val METADATA_ONLY_CONTEXT_TOKENS = 8_000
        private const val MIN_REDACTABLE_KEY_LENGTH = 6
    }
}
