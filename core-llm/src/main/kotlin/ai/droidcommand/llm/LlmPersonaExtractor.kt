package ai.droidcommand.llm

import ai.droidcommand.agent.ConversationContext
import ai.droidcommand.agent.Formality
import ai.droidcommand.agent.HumorProfile
import ai.droidcommand.agent.Persona
import ai.droidcommand.agent.PersonaCategory
import ai.droidcommand.agent.StructureProfile
import ai.droidcommand.agent.StyleProfile
import ai.droidcommand.agent.Verbosity
import ai.droidcommand.agent.VocabProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * The outcome of [LlmPersonaExtractor.extract] and
 * [DefaultPersonaManager.createPersonaFromConversations] — a typed result
 * instead of a thrown exception, the same discipline [KnowledgeExtractionResult]
 * already applies.
 */
sealed class PersonaExtractionResult {
    data class Success(val persona: Persona) : PersonaExtractionResult()

    /** The provider's response wasn't the expected JSON object shape, or a required field was blank/unrecognized. */
    data class Malformed(val raw: String, val reason: String) : PersonaExtractionResult()

    data class ProviderFailed(val error: LlmError) : PersonaExtractionResult()

    /**
     * Producible only by [DefaultPersonaManager] (never by
     * [LlmPersonaExtractor] itself, which never touches
     * [ai.droidcommand.agent.ConversationStore]) — none of the requested
     * source conversation ids resolved to a real, storable conversation.
     */
    data class NoSourceConversations(val requestedIds: List<String>) : PersonaExtractionResult()
}

@Serializable
private data class StyleProfileDto(
    val tone: String,
    val vocabularyComplexity: String,
    val notableWords: List<String> = emptyList(),
    val typicalSentenceLength: String,
    val punctuationHabits: String,
    val formality: String,
    val verbosity: String,
    val humorPresent: Boolean,
    val humorStyle: String? = null,
    val responseStructure: String,
    val commonExpressions: List<String> = emptyList(),
    val contextContribution: String,
)

/**
 * A prompt asking the model to describe *how* the participant in a
 * conversation writes, never *what* they said, as a single JSON object.
 * Like every other `core-llm` extraction prompt, this has never been
 * exercised against a real provider — this environment has no LLM
 * credentials — only against a scripted [LlmProvider] in tests.
 */
private val PERSONA_EXTRACTION_SYSTEM_PROMPT = """
    You are a communication-style analyst. Given a conversation transcript,
    extract a compact style profile describing HOW the participant writes,
    never WHAT they said. Respond with ONLY a single JSON object, nothing
    else, in exactly this shape:
    {"tone": string, "vocabularyComplexity": string, "notableWords": string[],
    "typicalSentenceLength": string, "punctuationHabits": string,
    "formality": one of "VERY_CASUAL"|"CASUAL"|"NEUTRAL"|"FORMAL"|"VERY_FORMAL",
    "verbosity": one of "CONCISE"|"MODERATE"|"DETAILED"|"VERBOSE",
    "humorPresent": boolean, "humorStyle": string or null,
    "responseStructure": string, "commonExpressions": string[],
    "contextContribution": string}. "contextContribution" must be a short,
    compact instruction (2-4 sentences) a future assistant could use as extra
    system-prompt guidance to write in this same style — never a copy of the
    transcript itself.
""".trimIndent()

/**
 * Bridges [ai.droidcommand.agent.PersonaStore] (real storage, no LLM
 * dependency) to an [LlmProvider]: turns a set of source conversations'
 * message history into a [Persona] via a real model call, the same adapter
 * role [LlmKnowledgeExtractor] already plays for
 * [ai.droidcommand.agent.KnowledgeStore].
 *
 * Deliberately does **not** call [ai.droidcommand.agent.PersonaStore.save]
 * itself — that composition is [DefaultPersonaManager]'s job, the same
 * extractor/service split [LlmKnowledgeExtractor]/[KnowledgeExtractionService]
 * already establish.
 *
 * [idGenerator] defaults to a random UUID's string form — the model is
 * never trusted to invent a valid, unique id itself, matching
 * [LlmKnowledgeExtractor]'s own precedent.
 */
class LlmPersonaExtractor(
    private val provider: LlmProvider,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * [sourceConversations]' messages (in order) become the outgoing
     * request's message history; [sourceConversationIds] (same order, same
     * size) is never sent to the model — it only labels the resulting
     * [Persona.sourceConversations].
     */
    fun extract(
        sourceConversations: List<ConversationContext>,
        sourceConversationIds: List<String>,
        name: String,
        category: PersonaCategory,
    ): PersonaExtractionResult {
        val request = LlmRequest(
            systemPrompt = PERSONA_EXTRACTION_SYSTEM_PROMPT,
            messages = sourceConversations.flatMap { it.messages },
        )
        return when (val response = provider.complete(request)) {
            is LlmResponse.Error -> PersonaExtractionResult.ProviderFailed(response.error)
            is LlmResponse.ToolCall -> PersonaExtractionResult.Malformed(
                raw = "tool_call:${response.toolName}",
                reason = "Provider returned a tool call, but no tools were offered for extraction",
            )
            is LlmResponse.Text -> parse(response.content, sourceConversationIds, name, category)
        }
    }

    private fun parse(
        text: String,
        sourceConversationIds: List<String>,
        name: String,
        category: PersonaCategory,
    ): PersonaExtractionResult {
        val dto = try {
            json.decodeFromString(StyleProfileDto.serializer(), text)
        } catch (e: SerializationException) {
            return PersonaExtractionResult.Malformed(text, e.message ?: "invalid JSON")
        }

        if (dto.tone.isBlank() || dto.responseStructure.isBlank() || dto.contextContribution.isBlank()) {
            return PersonaExtractionResult.Malformed(text, "tone, responseStructure and contextContribution must all be non-blank")
        }

        val formality = try {
            Formality.valueOf(dto.formality.uppercase())
        } catch (e: IllegalArgumentException) {
            return PersonaExtractionResult.Malformed(text, "unrecognized formality '${dto.formality}'")
        }
        val verbosity = try {
            Verbosity.valueOf(dto.verbosity.uppercase())
        } catch (e: IllegalArgumentException) {
            return PersonaExtractionResult.Malformed(text, "unrecognized verbosity '${dto.verbosity}'")
        }

        val persona = Persona(
            id = idGenerator(),
            name = name,
            category = category,
            sourceConversations = sourceConversationIds,
            styleCharacteristics = StyleProfile(
                tone = dto.tone,
                vocabulary = VocabProfile(dto.vocabularyComplexity, dto.notableWords),
                sentenceStructure = StructureProfile(dto.typicalSentenceLength, dto.punctuationHabits),
                formality = formality,
                verbosity = verbosity,
                humor = HumorProfile(dto.humorPresent, dto.humorStyle),
                responseStructure = dto.responseStructure,
                commonExpressions = dto.commonExpressions,
            ),
            contextContribution = dto.contextContribution,
            version = "1",
            // A freshly created persona is never enabled by default — activation is
            // its own explicit step (DefaultPersonaManager.setActivePersona), matching
            // PersonaActivationStatus's own PROFILE_CREATED-then-ACTIVATED ordering.
            enabled = false,
        )
        return PersonaExtractionResult.Success(persona)
    }
}
