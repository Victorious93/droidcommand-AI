package ai.droidcommand.llm.factory

import ai.droidcommand.agent.ConversationContext
import ai.droidcommand.agent.ConversationStore
import ai.droidcommand.agent.GraphRetriever
import ai.droidcommand.agent.KnowledgeGraph
import ai.droidcommand.agent.Message
import ai.droidcommand.agent.Role
import ai.droidcommand.config.ConfiguredLlmProvider
import ai.droidcommand.config.SecretsVault
import ai.droidcommand.llm.AiProviderInfo
import ai.droidcommand.llm.GraphExtractionResult
import ai.droidcommand.llm.GraphExtractionService
import ai.droidcommand.llm.LlmConfig
import ai.droidcommand.llm.LlmGraphExtractor
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
 * Outcome of [ChatSession.rememberConversation]. [Saved.entityCount] is how many entities were written
 * to the knowledge graph. [Failure.message] never contains the API key.
 */
sealed class MemoryResult {
    data class Saved(val entityCount: Int, val relationshipCount: Int) : MemoryResult()

    /** The model found nothing worth remembering, or there was no conversation yet. */
    data object NothingToRemember : MemoryResult()

    data class Failure(val message: String) : MemoryResult()
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
 * enforces https).
 *
 * [knowledgeGraph] (off when `null`, the default) is the store [rememberConversation] writes to — see
 * that method. It is separate from [knowledge] (which reads a graph into each message): a caller may wire
 * reading, writing, both, or neither.
 *
 * [knowledge] (off when `null`, the default) adds saved knowledge-graph notes relevant to each message.
 * They go into that request's final user message, not the system prompt: they are derived from the
 * user's own conversations, so they get user-level trust, not system authority. They are added per
 * request only and never saved into the conversation, so history holds exactly what the user typed. If
 * retrieval throws, the turn proceeds without notes rather than failing.
 *
 * [history], [send] and [reset] block on storage and the network: call them off the main thread. Not safe for concurrent
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
    private val knowledge: GraphRetriever? = null,
    private val knowledgeGraph: KnowledgeGraph? = null,
) {
    // Loaded on first use, not at construction: a Room-backed store throws on the main thread, and a
    // caller (an Android ViewModel) constructs this there. Every public method may touch storage.
    private var loaded: ConversationContext? = null

    // The provider+model of the most recent successful turn. rememberConversation() extracts with the
    // SAME provider, which already received the full conversation in that turn, so remembering adds no
    // new recipient of the user's text. null until the first successful send.
    private var lastSpec: CloudProviderSpec? = null
    private var lastModel: String? = null
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
        // Exception, not Throwable: an Error (e.g. OutOfMemoryError) should not be silently swallowed.
        val notes = try {
            knowledge?.retrieveContext(text)
        } catch (e: Exception) {
            null
        }
        val outgoing = if (notes == null) text else "$notes\n\n$text"
        val request = LlmRequest(systemPrompt = context.systemPrompt, messages = context.messages + Message(Role.USER, outgoing))

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
                lastSpec = spec
                lastModel = config.model
                ChatResult.Reply(response.content)
            }
            is LlmResponse.ToolCall -> ChatResult.Failure("The model asked to call a tool, which chat does not support.")
            is LlmResponse.Error -> ChatResult.Failure(scrub(response.error.message, key))
        }
    }

    /**
     * Extracts knowledge-graph entities/relationships from the current conversation and saves them to
     * [knowledgeGraph]. User-triggered only: nothing calls this automatically. Uses the provider+model of
     * the most recent successful [send] — that provider already saw the whole conversation in that turn,
     * so remembering sends the user's text to no new party. Returns [MemoryResult.NothingToRemember] when
     * no graph is configured, no successful turn has happened yet, the conversation is empty, or the model
     * finds nothing worth keeping. A provider or parse failure is a [MemoryResult.Failure] with the key
     * scrubbed; the conversation and the graph are unchanged on failure.
     *
     * Blocks on the network and storage: call it off the main thread.
     */
    @Synchronized
    fun rememberConversation(): MemoryResult {
        val graph = knowledgeGraph ?: return MemoryResult.NothingToRemember
        val spec = lastSpec
        val model = lastModel
        if (spec == null || model == null || context.messages.isEmpty()) return MemoryResult.NothingToRemember
        val key = vault.getSecret(spec.secretId)
        if (key.isNullOrBlank()) return MemoryResult.Failure("No API key for ${spec.label}. Add one in Settings.")

        val config = LlmConfig(provider = spec.id, model = model, authToken = { vault.getSecret(spec.secretId) })
        val service = GraphExtractionService(LlmGraphExtractor(providerFor(spec, config)), graph)
        val result = try {
            service.extractAndSave(context, source = conversationId)
        } catch (e: Exception) {
            return MemoryResult.Failure(scrub("Could not save memory: ${e.message ?: e.javaClass.simpleName}", key))
        }
        return when (result) {
            is GraphExtractionResult.Success ->
                if (result.entities.isEmpty()) {
                    MemoryResult.NothingToRemember
                } else {
                    MemoryResult.Saved(result.entities.size, result.relationships.size)
                }
            is GraphExtractionResult.Malformed -> MemoryResult.Failure("The model's reply could not be read as memory.")
            is GraphExtractionResult.ProviderFailed -> MemoryResult.Failure(scrub(result.error.message, key))
        }
    }

    /** Deletes the stored conversation and starts an empty one. */
    @Synchronized
    fun reset() {
        store.delete(conversationId)
        loaded = ConversationContext(systemPrompt)
        lastSpec = null
        lastModel = null
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
