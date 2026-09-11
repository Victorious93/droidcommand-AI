package ai.droidcommand.llm

import ai.droidcommand.agent.ConversationContext
import ai.droidcommand.agent.Entity
import ai.droidcommand.agent.EntityType
import ai.droidcommand.agent.Relationship
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * The outcome of [LlmGraphExtractor.extract] — a typed result instead of a
 * thrown exception, mirroring [KnowledgeExtractionResult]/[PersonaExtractionResult].
 */
sealed class GraphExtractionResult {
    data class Success(val entities: List<Entity>, val relationships: List<Relationship>) : GraphExtractionResult()

    /** The provider's response text wasn't the expected JSON object shape, named an unrecognized [EntityType], or a relationship referenced a `key` no entity in the same response declared. */
    data class Malformed(val raw: String, val reason: String) : GraphExtractionResult()

    data class ProviderFailed(val error: LlmError) : GraphExtractionResult()
}

@Serializable
private data class ExtractedEntityDto(val key: String, val type: String, val label: String, val properties: Map<String, String> = emptyMap())

@Serializable
private data class ExtractedRelationshipDto(val fromKey: String, val toKey: String, val type: String)

@Serializable
private data class ExtractedGraphDto(
    val entities: List<ExtractedEntityDto> = emptyList(),
    val relationships: List<ExtractedRelationshipDto> = emptyList(),
)

/**
 * A prompt asking the model to name entities and relationships between them
 * worth capturing from a conversation, as one JSON object. Like every other
 * `core-llm` extraction prompt, this has never been exercised against a real
 * provider — this environment has no LLM credentials — only against a
 * scripted [LlmProvider] in tests.
 */
private val GRAPH_EXTRACTION_SYSTEM_PROMPT = """
    You extract structured knowledge-graph entities and relationships from a
    conversation. Respond with ONLY a single JSON object, nothing else, in
    exactly this shape:
    {"entities": [{"key": string, "type": one of
    "NOTE"|"AUTOMATION"|"DEVICE"|"COMMAND"|"LOG"|"PROJECT"|"CONCEPT"|"VARIABLE"|"PLUGIN"|"AI_CONTEXT"|"DOCUMENT"|"CONVERSATION"|"PERSONA"|"TASK"|"EXECUTION_TARGET",
    "label": string, "properties": {string: string}}],
    "relationships": [{"fromKey": string, "toKey": string, "type": string}]}.
    "key" is a short local reference you invent to link entities and
    relationships together within this one response only — it is never
    stored or shown to a user. Every "fromKey"/"toKey" must match a "key"
    declared in "entities" in this same response. If nothing in the
    conversation is worth capturing, respond with {"entities": [],
    "relationships": []}.
""".trimIndent()

/**
 * Bridges [ai.droidcommand.agent.KnowledgeGraph] (real storage, no LLM
 * dependency) to an [LlmProvider]: turns a conversation's message history
 * into [Entity]/[Relationship] candidates via a real model call, the same
 * adapter role [LlmKnowledgeExtractor] already plays for
 * [ai.droidcommand.agent.KnowledgeStore].
 *
 * Deliberately does **not** call [ai.droidcommand.agent.KnowledgeGraph.addEntity]/
 * [ai.droidcommand.agent.KnowledgeGraph.addRelationship] itself — that
 * composition is [GraphExtractionService]'s job, the same extractor/service
 * split [LlmKnowledgeExtractor]/[KnowledgeExtractionService] already
 * establish.
 *
 * [idGenerator] defaults to a random UUID's string form, which already
 * satisfies [ai.droidcommand.agent.JsonFileKnowledgeGraph.ID_PATTERN] — the
 * model is never trusted to invent a valid, unique id itself; the model's
 * own per-response `key`s are resolved to generated ids by [parse] and
 * never leak into the returned [Entity]/[Relationship] values.
 */
class LlmGraphExtractor(
    private val provider: LlmProvider,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun extract(context: ConversationContext, source: String): GraphExtractionResult {
        val request = LlmRequest(
            systemPrompt = GRAPH_EXTRACTION_SYSTEM_PROMPT,
            messages = context.messages,
        )
        return when (val response = provider.complete(request)) {
            is LlmResponse.Error -> GraphExtractionResult.ProviderFailed(response.error)
            is LlmResponse.ToolCall -> GraphExtractionResult.Malformed(
                raw = "tool_call:${response.toolName}",
                reason = "Provider returned a tool call, but no tools were offered for extraction",
            )
            is LlmResponse.Text -> parse(response.content)
        }
    }

    private fun parse(text: String): GraphExtractionResult {
        val dto = try {
            json.decodeFromString(ExtractedGraphDto.serializer(), text)
        } catch (e: SerializationException) {
            return GraphExtractionResult.Malformed(text, e.message ?: "invalid JSON")
        }

        val idsByKey = mutableMapOf<String, String>()
        val entities = mutableListOf<Entity>()
        for (entityDto in dto.entities) {
            val type = try {
                EntityType.valueOf(entityDto.type)
            } catch (e: IllegalArgumentException) {
                return GraphExtractionResult.Malformed(text, "unrecognized entity type '${entityDto.type}'")
            }
            val id = idGenerator()
            idsByKey[entityDto.key] = id
            entities += Entity(id = id, type = type, label = entityDto.label, properties = entityDto.properties)
        }

        val relationships = mutableListOf<Relationship>()
        for (relDto in dto.relationships) {
            val fromId = idsByKey[relDto.fromKey]
                ?: return GraphExtractionResult.Malformed(text, "relationship fromKey '${relDto.fromKey}' does not match any declared entity key")
            val toId = idsByKey[relDto.toKey]
                ?: return GraphExtractionResult.Malformed(text, "relationship toKey '${relDto.toKey}' does not match any declared entity key")
            relationships += Relationship(id = idGenerator(), fromId = fromId, toId = toId, type = relDto.type)
        }

        return GraphExtractionResult.Success(entities, relationships)
    }
}
