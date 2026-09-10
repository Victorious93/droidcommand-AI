package ai.droidcommand.llm

import ai.droidcommand.agent.Formality
import ai.droidcommand.agent.HumorProfile
import ai.droidcommand.agent.ImportedConversation
import ai.droidcommand.agent.Persona
import ai.droidcommand.agent.PersonaCategory
import ai.droidcommand.agent.StructureProfile
import ai.droidcommand.agent.StyleProfile
import ai.droidcommand.agent.Verbosity
import ai.droidcommand.agent.VocabProfile
import ai.droidcommand.agent.formatStyleProfile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * The outcome of [LlmPersonaExtractor.extract]: a typed result instead of
 * a thrown exception, matching [KnowledgeExtractionResult]'s exact shape.
 */
sealed class PersonaExtractionResult {
    data class Success(val persona: Persona) : PersonaExtractionResult()

    /** The provider's response text wasn't the expected JSON object shape, or named an enum value that isn't real. */
    data class Malformed(val raw: String, val reason: String) : PersonaExtractionResult()

    data class ProviderFailed(val error: LlmError) : PersonaExtractionResult()
}

@Serializable
private data class VocabProfileDto(
    @SerialName("complexity_level") val complexityLevel: String,
    @SerialName("notable_vocabulary") val notableVocabulary: List<String> = emptyList(),
    @SerialName("jargon_domains") val jargonDomains: List<String> = emptyList(),
)

@Serializable
private data class StructureProfileDto(
    @SerialName("typical_sentence_length") val typicalSentenceLength: String,
    @SerialName("rhythm_description") val rhythmDescription: String,
    @SerialName("punctuation_habits") val punctuationHabits: List<String> = emptyList(),
)

@Serializable
private data class HumorProfileDto(
    val present: Boolean,
    val style: String? = null,
    val examples: List<String> = emptyList(),
)

@Serializable
private data class StyleProfileDto(
    val tone: String,
    val vocabulary: VocabProfileDto,
    @SerialName("sentence_structure") val sentenceStructure: StructureProfileDto,
    val formality: String,
    val verbosity: String,
    val humor: HumorProfileDto,
    @SerialName("response_structure") val responseStructure: String,
    @SerialName("common_expressions") val commonExpressions: List<String> = emptyList(),
)

/**
 * A prompt asking the model to analyze a conversation's communication
 * style along P0.5's own named axes, as one JSON object. Like every other
 * `core-llm-*` prompt, this has never been exercised against a real
 * provider — this environment has no LLM credentials — only against a
 * scripted [LlmProvider] in tests.
 */
private val PERSONA_EXTRACTION_SYSTEM_PROMPT = """
    You analyze a conversation's communication style — tone, vocabulary,
    sentence structure, formality, verbosity, humor, response structure,
    and common expressions — so it can be mimicked later without copying
    the conversation itself. Respond with ONLY a JSON object, nothing
    else, matching exactly this shape:
    {
      "tone": string,
      "vocabulary": {"complexity_level": string, "notable_vocabulary": string[], "jargon_domains": string[]},
      "sentence_structure": {"typical_sentence_length": string, "rhythm_description": string, "punctuation_habits": string[]},
      "formality": one of "VERY_CASUAL" | "CASUAL" | "NEUTRAL" | "FORMAL" | "VERY_FORMAL",
      "verbosity": one of "TERSE" | "CONCISE" | "MODERATE" | "DETAILED" | "VERBOSE",
      "humor": {"present": boolean, "style": string or null, "examples": string[]},
      "response_structure": string,
      "common_expressions": string[]
    }
""".trimIndent()

/**
 * Bridges [ai.droidcommand.agent.PersonaStore] (real storage, no LLM
 * dependency) to an [LlmProvider]: turns a conversation's communication
 * style into a [Persona] via a real model call — the same adapter role
 * [LlmKnowledgeExtractor]/[ObjectiveAnalyzer] already play.
 *
 * Deliberately does **not** call [ai.droidcommand.agent.PersonaStore.save]
 * itself and is **not** invoked automatically from anywhere — the same
 * caller-policy restraint every prior extractor in this module already
 * applies.
 *
 * [idGenerator] defaults to a random UUID's string form, which already
 * satisfies [ai.droidcommand.agent.JsonFilePersonaStore.ID_PATTERN] — the
 * model is never trusted to invent a valid, unique id itself.
 *
 * [Persona.enabled] is always `false` on a freshly extracted persona — it
 * is never silently made active. [Persona.sourceConversations] is scoped
 * to the single [sourceConversationId] this call was given; merging
 * multiple source conversations into one persona is a named future
 * enhancement, not built here.
 */
class LlmPersonaExtractor(
    private val provider: LlmProvider,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun extract(
        conversation: ImportedConversation,
        name: String,
        category: PersonaCategory,
        sourceConversationId: String,
    ): PersonaExtractionResult {
        val request = LlmRequest(
            systemPrompt = PERSONA_EXTRACTION_SYSTEM_PROMPT,
            messages = conversation.messages,
        )
        return when (val response = provider.complete(request)) {
            is LlmResponse.Error -> PersonaExtractionResult.ProviderFailed(response.error)
            is LlmResponse.ToolCall -> PersonaExtractionResult.Malformed(
                raw = "tool_call:${response.toolName}",
                reason = "Provider returned a tool call, but no tools were offered for extraction",
            )
            is LlmResponse.Text -> parse(response.content, name, category, sourceConversationId)
        }
    }

    private fun parse(text: String, name: String, category: PersonaCategory, sourceConversationId: String): PersonaExtractionResult {
        val dto = try {
            json.decodeFromString(StyleProfileDto.serializer(), text)
        } catch (e: SerializationException) {
            return PersonaExtractionResult.Malformed(text, e.message ?: "invalid JSON")
        }

        val profile = try {
            dto.toDomain()
        } catch (e: IllegalArgumentException) {
            return PersonaExtractionResult.Malformed(text, e.message ?: "unknown enum value")
        }

        val persona = Persona(
            id = idGenerator(),
            name = name,
            category = category,
            sourceConversations = listOf(sourceConversationId),
            styleCharacteristics = profile,
            contextContribution = formatStyleProfile(profile),
            version = "1",
            enabled = false,
        )
        return PersonaExtractionResult.Success(persona)
    }

    private fun StyleProfileDto.toDomain(): StyleProfile =
        StyleProfile(
            tone = tone,
            vocabulary = VocabProfile(vocabulary.complexityLevel, vocabulary.notableVocabulary, vocabulary.jargonDomains),
            sentenceStructure = StructureProfile(
                sentenceStructure.typicalSentenceLength,
                sentenceStructure.rhythmDescription,
                sentenceStructure.punctuationHabits,
            ),
            formality = Formality.valueOf(formality),
            verbosity = Verbosity.valueOf(verbosity),
            humor = HumorProfile(humor.present, humor.style, humor.examples),
            responseStructure = responseStructure,
            commonExpressions = commonExpressions,
        )
}
