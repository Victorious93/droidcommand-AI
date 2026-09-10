package ai.droidcommand.llm

import ai.droidcommand.agent.ConversationContext
import ai.droidcommand.agent.KnowledgeEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * The outcome of [LlmKnowledgeExtractor.extract]: a typed result instead of
 * a thrown exception, mirroring how [LlmPlanner] turns [LlmResponse.Error]
 * into [ai.droidcommand.agent.PlannerDecision.Abort] rather than throwing.
 */
sealed class KnowledgeExtractionResult {
    data class Success(val entries: List<KnowledgeEntry>) : KnowledgeExtractionResult()

    /** The provider's response text wasn't the expected JSON array shape. */
    data class Malformed(val raw: String, val reason: String) : KnowledgeExtractionResult()

    data class ProviderFailed(val error: LlmError) : KnowledgeExtractionResult()
}

@Serializable
private data class ExtractedFactDto(val content: String, val tags: List<String> = emptyList())

/**
 * A prompt asking the model to name durable facts/preferences/decisions
 * worth remembering beyond the conversation that produced them, as a JSON
 * array. Like `core-llm-anthropic`'s `AnthropicLlmProvider` and
 * `core-llm-openai`'s `OpenAiLlmProvider`, this has never been exercised
 * against a real provider — this environment has no LLM credentials —
 * only against a scripted [LlmProvider] in tests.
 */
private val EXTRACTION_SYSTEM_PROMPT = """
    You extract durable facts, preferences, or decisions from a
    conversation that are worth remembering beyond it. Respond with ONLY
    a JSON array, nothing else. Each element must have the shape
    {"content": string, "tags": string[]}. If nothing in the conversation
    is worth remembering long-term, respond with an empty array: [].
""".trimIndent()

/**
 * Bridges [ai.droidcommand.agent.KnowledgeStore] (real storage, no LLM
 * dependency) to an [LlmProvider]: turns a conversation's message history
 * into [KnowledgeEntry] candidates via a real model call, the same adapter
 * role [LlmPlanner] already plays for [ai.droidcommand.agent.Planner].
 *
 * Deliberately does **not** call [ai.droidcommand.agent.KnowledgeStore.save]
 * itself, and is **not** invoked automatically from
 * [ai.droidcommand.agent.ObjectiveEngine]/[ai.droidcommand.agent.DroidCommandSession]:
 * deciding when to extract (every turn? end of conversation? on a timer?)
 * and whether/how to persist the result is a caller policy decision, the
 * same restraint [ai.droidcommand.agent.formatKnowledgeContext] already
 * applies on the retrieval side.
 *
 * [idGenerator] defaults to a random UUID's string form, which already
 * satisfies [ai.droidcommand.agent.JsonFileKnowledgeStore.ID_PATTERN]
 * (`[A-Za-z0-9_-]+`) — the model is never trusted to invent a valid,
 * unique id itself.
 */
class LlmKnowledgeExtractor(
    private val provider: LlmProvider,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun extract(context: ConversationContext, source: String): KnowledgeExtractionResult {
        val request = LlmRequest(
            systemPrompt = EXTRACTION_SYSTEM_PROMPT,
            messages = context.messages,
        )
        return when (val response = provider.complete(request)) {
            is LlmResponse.Error -> KnowledgeExtractionResult.ProviderFailed(response.error)
            is LlmResponse.ToolCall -> KnowledgeExtractionResult.Malformed(
                raw = "tool_call:${response.toolName}",
                reason = "Provider returned a tool call, but no tools were offered for extraction",
            )
            is LlmResponse.Text -> parse(response.content, source)
        }
    }

    private fun parse(text: String, source: String): KnowledgeExtractionResult {
        val dtos = try {
            json.decodeFromString(ListSerializer(ExtractedFactDto.serializer()), text)
        } catch (e: SerializationException) {
            return KnowledgeExtractionResult.Malformed(text, e.message ?: "invalid JSON")
        }
        val entries = dtos.map { KnowledgeEntry(id = idGenerator(), content = it.content, source = source, tags = it.tags.toSet()) }
        return KnowledgeExtractionResult.Success(entries)
    }
}
