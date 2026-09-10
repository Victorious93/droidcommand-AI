package ai.droidcommand.llm

import ai.droidcommand.agent.ConversationImportFormat
import ai.droidcommand.agent.ImportedConversation
import ai.droidcommand.agent.InMemoryPersonaStore
import ai.droidcommand.agent.LogEvent
import ai.droidcommand.agent.LogLevel
import ai.droidcommand.agent.Logger
import ai.droidcommand.agent.PersonaCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private class RecordingPersonaServiceLogger : Logger {
    val events = mutableListOf<LogEvent>()

    override fun log(event: LogEvent) {
        events.add(event)
    }
}

private class PersonaServiceFakeLlmProvider(private val response: LlmResponse) : LlmProvider {
    override val config = LlmConfig(provider = "fake", model = "fake-1")

    override fun complete(request: LlmRequest): LlmResponse = response
}

private val VALID_STYLE_JSON = """
    {
      "tone": "warm",
      "vocabulary": {"complexity_level": "simple"},
      "sentence_structure": {"typical_sentence_length": "short", "rhythm_description": "even"},
      "formality": "CASUAL",
      "verbosity": "CONCISE",
      "humor": {"present": false},
      "response_structure": "direct"
    }
""".trimIndent()

private val conversation = ImportedConversation(
    messages = emptyList(),
    sourceFormat = ConversationImportFormat.PLAIN_TEXT_TRANSCRIPT,
    sourceFileNameHint = "chat.txt",
)

class PersonaExtractionServiceTest {
    @Test
    fun `a successful extraction saves the persona into the store`() {
        val provider = PersonaServiceFakeLlmProvider(LlmResponse.Text(VALID_STYLE_JSON))
        val store = InMemoryPersonaStore()
        val service = PersonaExtractionService(LlmPersonaExtractor(provider), store)

        val result = service.extractAndSave(conversation, "Alex", PersonaCategory.PERSONAL, "conv-1")

        val success = assertIs<PersonaExtractionResult.Success>(result)
        assertEquals(success.persona, store.load(success.persona.id))
    }

    @Test
    fun `malformed extraction saves nothing and logs a warning, never throws`() {
        val provider = PersonaServiceFakeLlmProvider(LlmResponse.Text("not json"))
        val store = InMemoryPersonaStore()
        val logger = RecordingPersonaServiceLogger()
        val service = PersonaExtractionService(LlmPersonaExtractor(provider), store, logger)

        val result = service.extractAndSave(conversation, "Alex", PersonaCategory.PERSONAL, "conv-1")

        assertIs<PersonaExtractionResult.Malformed>(result)
        assertEquals(emptyList(), store.list())
        assertEquals(1, logger.events.size)
        assertEquals(LogLevel.WARN, logger.events.single().level)
        assertEquals("persona_extraction_malformed", logger.events.single().message)
    }

    @Test
    fun `a provider failure saves nothing and logs a warning`() {
        val provider = PersonaServiceFakeLlmProvider(LlmResponse.Error(LlmError.Authentication("invalid API key")))
        val store = InMemoryPersonaStore()
        val logger = RecordingPersonaServiceLogger()
        val service = PersonaExtractionService(LlmPersonaExtractor(provider), store, logger)

        val result = service.extractAndSave(conversation, "Alex", PersonaCategory.PERSONAL, "conv-1")

        assertIs<PersonaExtractionResult.ProviderFailed>(result)
        assertEquals(emptyList(), store.list())
        assertEquals("persona_extraction_provider_failed", logger.events.single().message)
        assertEquals("invalid API key", logger.events.single().fields["error"])
    }

    @Test
    fun `a successful extraction with no logger given does not throw`() {
        val provider = PersonaServiceFakeLlmProvider(LlmResponse.Text(VALID_STYLE_JSON))
        val store = InMemoryPersonaStore()
        val service = PersonaExtractionService(LlmPersonaExtractor(provider), store)

        service.extractAndSave(conversation, "Alex", PersonaCategory.PERSONAL, "conv-1")

        assertEquals(1, store.list().size)
    }
}
