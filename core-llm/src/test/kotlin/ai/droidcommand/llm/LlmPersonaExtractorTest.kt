package ai.droidcommand.llm

import ai.droidcommand.agent.ConversationImportFormat
import ai.droidcommand.agent.Formality
import ai.droidcommand.agent.ImportedConversation
import ai.droidcommand.agent.JsonFilePersonaStore
import ai.droidcommand.agent.Message
import ai.droidcommand.agent.PersonaCategory
import ai.droidcommand.agent.Role
import ai.droidcommand.agent.Verbosity
import ai.droidcommand.agent.formatStyleProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class FakePersonaLlmProvider(private val response: LlmResponse) : LlmProvider {
    override val config = LlmConfig(provider = "fake", model = "fake-1")
    var lastRequest: LlmRequest? = null

    override fun complete(request: LlmRequest): LlmResponse {
        lastRequest = request
        return response
    }
}

private val VALID_STYLE_JSON = """
    {
      "tone": "warm and direct",
      "vocabulary": {"complexity_level": "moderate", "notable_vocabulary": ["honestly"], "jargon_domains": []},
      "sentence_structure": {"typical_sentence_length": "short", "rhythm_description": "punchy", "punctuation_habits": ["em-dashes"]},
      "formality": "CASUAL",
      "verbosity": "CONCISE",
      "humor": {"present": true, "style": "dry", "examples": ["deadpan asides"]},
      "response_structure": "answer first, then explain",
      "common_expressions": ["to be fair"]
    }
""".trimIndent()

private fun conversation(vararg messages: Message) = ImportedConversation(
    messages = messages.toList(),
    sourceFormat = ConversationImportFormat.PLAIN_TEXT_TRANSCRIPT,
    sourceFileNameHint = "chat.txt",
)

class LlmPersonaExtractorTest {
    @Test
    fun `parses a valid JSON object into a Persona with every field mapped correctly`() {
        val provider = FakePersonaLlmProvider(LlmResponse.Text(VALID_STYLE_JSON))

        val result = LlmPersonaExtractor(provider).extract(
            conversation(Message(Role.USER, "hi")),
            name = "Alex",
            category = PersonaCategory.CODING,
            sourceConversationId = "conv-1",
        )

        val success = assertIs<PersonaExtractionResult.Success>(result)
        val persona = success.persona
        assertEquals("Alex", persona.name)
        assertEquals(PersonaCategory.CODING, persona.category)
        assertEquals(listOf("conv-1"), persona.sourceConversations)
        assertEquals("warm and direct", persona.styleCharacteristics.tone)
        assertEquals("moderate", persona.styleCharacteristics.vocabulary.complexityLevel)
        assertEquals(listOf("honestly"), persona.styleCharacteristics.vocabulary.notableVocabulary)
        assertEquals(Formality.CASUAL, persona.styleCharacteristics.formality)
        assertEquals(Verbosity.CONCISE, persona.styleCharacteristics.verbosity)
        assertTrue(persona.styleCharacteristics.humor.present)
        assertEquals("dry", persona.styleCharacteristics.humor.style)
        assertEquals(listOf("to be fair"), persona.styleCharacteristics.commonExpressions)
        assertEquals("1", persona.version)
    }

    @Test
    fun `a freshly extracted persona is never enabled by default`() {
        val provider = FakePersonaLlmProvider(LlmResponse.Text(VALID_STYLE_JSON))

        val result = LlmPersonaExtractor(provider).extract(conversation(), "Alex", PersonaCategory.PERSONAL, "conv-1")

        assertFalse((result as PersonaExtractionResult.Success).persona.enabled)
    }

    @Test
    fun `contextContribution equals formatStyleProfile and never equals raw source message content`() {
        val provider = FakePersonaLlmProvider(LlmResponse.Text(VALID_STYLE_JSON))
        val rawMessage = "this is the raw conversation content, not a style summary"

        val result = LlmPersonaExtractor(provider).extract(
            conversation(Message(Role.USER, rawMessage)),
            "Alex",
            PersonaCategory.PERSONAL,
            "conv-1",
        )

        val persona = (result as PersonaExtractionResult.Success).persona
        assertEquals(formatStyleProfile(persona.styleCharacteristics), persona.contextContribution)
        assertTrue(persona.contextContribution != rawMessage)
    }

    @Test
    fun `generated ids satisfy JsonFilePersonaStore's id pattern`() {
        val provider = FakePersonaLlmProvider(LlmResponse.Text(VALID_STYLE_JSON))

        val result = LlmPersonaExtractor(provider).extract(conversation(), "Alex", PersonaCategory.PERSONAL, "conv-1")

        val id = (result as PersonaExtractionResult.Success).persona.id
        assertTrue(JsonFilePersonaStore.ID_PATTERN.matches(id))
    }

    @Test
    fun `a custom idGenerator is honored instead of the default UUID one`() {
        val provider = FakePersonaLlmProvider(LlmResponse.Text(VALID_STYLE_JSON))

        val result = LlmPersonaExtractor(provider, idGenerator = { "fixed-id" })
            .extract(conversation(), "Alex", PersonaCategory.PERSONAL, "conv-1")

        assertEquals("fixed-id", (result as PersonaExtractionResult.Success).persona.id)
    }

    @Test
    fun `malformed JSON text is reported, not thrown`() {
        val provider = FakePersonaLlmProvider(LlmResponse.Text("not json at all"))

        val result = LlmPersonaExtractor(provider).extract(conversation(), "Alex", PersonaCategory.PERSONAL, "conv-1")

        val malformed = assertIs<PersonaExtractionResult.Malformed>(result)
        assertEquals("not json at all", malformed.raw)
    }

    @Test
    fun `an invalid formality enum value is reported as Malformed, not a crash`() {
        val badJson = VALID_STYLE_JSON.replace("\"CASUAL\"", "\"SUPER_DUPER_CASUAL\"")
        val provider = FakePersonaLlmProvider(LlmResponse.Text(badJson))

        val result = LlmPersonaExtractor(provider).extract(conversation(), "Alex", PersonaCategory.PERSONAL, "conv-1")

        assertIs<PersonaExtractionResult.Malformed>(result)
    }

    @Test
    fun `a provider error becomes ProviderFailed`() {
        val provider = FakePersonaLlmProvider(LlmResponse.Error(LlmError.Authentication("invalid API key")))

        val result = LlmPersonaExtractor(provider).extract(conversation(), "Alex", PersonaCategory.PERSONAL, "conv-1")

        val failed = assertIs<PersonaExtractionResult.ProviderFailed>(result)
        assertEquals("invalid API key", failed.error.message)
    }

    @Test
    fun `an unexpected tool call is reported as Malformed instead of crashing`() {
        val provider = FakePersonaLlmProvider(LlmResponse.ToolCall("some_tool", mapOf("x" to "1")))

        val result = LlmPersonaExtractor(provider).extract(conversation(), "Alex", PersonaCategory.PERSONAL, "conv-1")

        assertIs<PersonaExtractionResult.Malformed>(result)
    }

    @Test
    fun `the request carries the conversation's messages and no tools`() {
        val provider = FakePersonaLlmProvider(LlmResponse.Text(VALID_STYLE_JSON))
        val convo = conversation(Message(Role.USER, "hi there"), Message(Role.ASSISTANT, "hello"))

        LlmPersonaExtractor(provider).extract(convo, "Alex", PersonaCategory.PERSONAL, "conv-1")

        val request = provider.lastRequest!!
        assertEquals(listOf(Role.USER to "hi there", Role.ASSISTANT to "hello"), request.messages.map { it.role to it.content })
        assertTrue(request.tools.isEmpty())
    }
}
