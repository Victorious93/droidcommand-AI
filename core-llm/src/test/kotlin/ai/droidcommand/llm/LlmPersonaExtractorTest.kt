package ai.droidcommand.llm

import ai.droidcommand.agent.ConversationContext
import ai.droidcommand.agent.Formality
import ai.droidcommand.agent.PersonaCategory
import ai.droidcommand.agent.Role
import ai.droidcommand.agent.Verbosity
import kotlin.test.Test
import kotlin.test.assertEquals
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

private const val VALID_STYLE_JSON = """{
    "tone": "warm",
    "vocabularyComplexity": "simple",
    "notableWords": ["neat", "cheers"],
    "typicalSentenceLength": "short",
    "punctuationHabits": "minimal, occasional ellipses",
    "formality": "CASUAL",
    "verbosity": "CONCISE",
    "humorPresent": true,
    "humorStyle": "dry",
    "responseStructure": "bullet points, then a short summary",
    "commonExpressions": ["cool", "no worries"],
    "contextContribution": "Write warmly and concisely, in a casual tone."
}"""

class LlmPersonaExtractorTest {
    @Test
    fun `parses a valid JSON object into a Persona with every field mapped`() {
        val provider = FakePersonaLlmProvider(LlmResponse.Text(VALID_STYLE_JSON))

        val result = LlmPersonaExtractor(provider).extract(
            sourceConversations = listOf(ConversationContext()),
            sourceConversationIds = listOf("chat-1"),
            name = "My Style",
            category = PersonaCategory.PERSONAL,
        )

        val success = assertIs<PersonaExtractionResult.Success>(result)
        val persona = success.persona
        assertEquals("My Style", persona.name)
        assertEquals(PersonaCategory.PERSONAL, persona.category)
        assertEquals(listOf("chat-1"), persona.sourceConversations)
        assertEquals("1", persona.version)
        assertEquals(false, persona.enabled)
        assertEquals("Write warmly and concisely, in a casual tone.", persona.contextContribution)

        val style = persona.styleCharacteristics
        assertEquals("warm", style.tone)
        assertEquals("simple", style.vocabulary.complexity)
        assertEquals(listOf("neat", "cheers"), style.vocabulary.notableWords)
        assertEquals("short", style.sentenceStructure.typicalSentenceLength)
        assertEquals("minimal, occasional ellipses", style.sentenceStructure.punctuationHabits)
        assertEquals(Formality.CASUAL, style.formality)
        assertEquals(Verbosity.CONCISE, style.verbosity)
        assertEquals(true, style.humor.present)
        assertEquals("dry", style.humor.style)
        assertEquals("bullet points, then a short summary", style.responseStructure)
        assertEquals(listOf("cool", "no worries"), style.commonExpressions)
    }

    @Test
    fun `multiple source conversations are concatenated in order for the request`() {
        val provider = FakePersonaLlmProvider(LlmResponse.Text(VALID_STYLE_JSON))
        val first = ConversationContext().apply { append(Role.USER, "hi") }
        val second = ConversationContext().apply { append(Role.USER, "bye") }

        LlmPersonaExtractor(provider).extract(
            sourceConversations = listOf(first, second),
            sourceConversationIds = listOf("chat-1", "chat-2"),
            name = "My Style",
            category = PersonaCategory.PERSONAL,
        )

        val messages = provider.lastRequest!!.messages
        assertEquals(listOf("hi", "bye"), messages.map { it.content })
    }

    @Test
    fun `malformed JSON is reported, not thrown`() {
        val provider = FakePersonaLlmProvider(LlmResponse.Text("not json at all"))

        val result = extract(provider)

        val malformed = assertIs<PersonaExtractionResult.Malformed>(result)
        assertEquals("not json at all", malformed.raw)
    }

    @Test
    fun `an unrecognized formality value is Malformed`() {
        val json = VALID_STYLE_JSON.replace(""""formality": "CASUAL"""", """"formality": "CHILL"""")
        val provider = FakePersonaLlmProvider(LlmResponse.Text(json))

        assertIs<PersonaExtractionResult.Malformed>(extract(provider))
    }

    @Test
    fun `an unrecognized verbosity value is Malformed`() {
        val json = VALID_STYLE_JSON.replace(""""verbosity": "CONCISE"""", """"verbosity": "CHATTY"""")
        val provider = FakePersonaLlmProvider(LlmResponse.Text(json))

        assertIs<PersonaExtractionResult.Malformed>(extract(provider))
    }

    @Test
    fun `a blank tone is Malformed`() {
        val json = VALID_STYLE_JSON.replace(""""tone": "warm"""", """"tone": "   """")
        val provider = FakePersonaLlmProvider(LlmResponse.Text(json))

        assertIs<PersonaExtractionResult.Malformed>(extract(provider))
    }

    @Test
    fun `a blank contextContribution is Malformed`() {
        val json = VALID_STYLE_JSON.replace(
            """"contextContribution": "Write warmly and concisely, in a casual tone."""",
            """"contextContribution": "  """",
        )
        val provider = FakePersonaLlmProvider(LlmResponse.Text(json))

        assertIs<PersonaExtractionResult.Malformed>(extract(provider))
    }

    @Test
    fun `a provider error becomes ProviderFailed`() {
        val provider = FakePersonaLlmProvider(LlmResponse.Error(LlmError.Authentication("invalid API key")))

        val result = extract(provider)

        val failed = assertIs<PersonaExtractionResult.ProviderFailed>(result)
        assertEquals("invalid API key", failed.error.message)
    }

    @Test
    fun `an unexpected tool call is reported as Malformed instead of crashing`() {
        val provider = FakePersonaLlmProvider(LlmResponse.ToolCall("some_tool", mapOf("x" to "1")))

        assertIs<PersonaExtractionResult.Malformed>(extract(provider))
    }

    @Test
    fun `a custom idGenerator is honored instead of the default UUID one`() {
        val provider = FakePersonaLlmProvider(LlmResponse.Text(VALID_STYLE_JSON))

        val result = LlmPersonaExtractor(provider, idGenerator = { "persona-42" }).extract(
            sourceConversations = listOf(ConversationContext()),
            sourceConversationIds = listOf("chat-1"),
            name = "My Style",
            category = PersonaCategory.PERSONAL,
        )

        assertEquals("persona-42", (result as PersonaExtractionResult.Success).persona.id)
    }

    @Test
    fun `the request carries no tools`() {
        val provider = FakePersonaLlmProvider(LlmResponse.Text(VALID_STYLE_JSON))

        extract(provider)

        assertTrue(provider.lastRequest!!.tools.isEmpty())
    }

    private fun extract(provider: FakePersonaLlmProvider): PersonaExtractionResult =
        LlmPersonaExtractor(provider).extract(
            sourceConversations = listOf(ConversationContext()),
            sourceConversationIds = listOf("chat-1"),
            name = "My Style",
            category = PersonaCategory.PERSONAL,
        )
}
