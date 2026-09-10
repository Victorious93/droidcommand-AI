package ai.droidcommand.llm

import ai.droidcommand.agent.ConversationContext
import ai.droidcommand.agent.InMemoryKnowledgeStore
import ai.droidcommand.agent.LogEvent
import ai.droidcommand.agent.LogLevel
import ai.droidcommand.agent.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class RecordingServiceLogger : Logger {
    val events = mutableListOf<LogEvent>()

    override fun log(event: LogEvent) {
        events.add(event)
    }
}

private class ServiceFakeLlmProvider(private val response: LlmResponse) : LlmProvider {
    override val config = LlmConfig(provider = "fake", model = "fake-1")

    override fun complete(request: LlmRequest): LlmResponse = response
}

class KnowledgeExtractionServiceTest {
    @Test
    fun `a successful extraction saves every entry into the store`() {
        val provider = ServiceFakeLlmProvider(
            LlmResponse.Text("""[{"content":"fact one"},{"content":"fact two"}]"""),
        )
        val store = InMemoryKnowledgeStore()
        val service = KnowledgeExtractionService(LlmKnowledgeExtractor(provider), store)

        val result = service.extractAndSave(ConversationContext(), source = "conversation:chat-1")

        assertIs<KnowledgeExtractionResult.Success>(result)
        val saved = store.list().mapNotNull(store::load)
        assertEquals(setOf("fact one", "fact two"), saved.map { it.content }.toSet())
        assertTrue(saved.all { it.source == "conversation:chat-1" })
    }

    @Test
    fun `an empty extraction saves nothing`() {
        val provider = ServiceFakeLlmProvider(LlmResponse.Text("[]"))
        val store = InMemoryKnowledgeStore()
        val service = KnowledgeExtractionService(LlmKnowledgeExtractor(provider), store)

        service.extractAndSave(ConversationContext(), source = "manual")

        assertEquals(emptyList(), store.list())
    }

    @Test
    fun `malformed extraction saves nothing and logs a warning, never throws`() {
        val provider = ServiceFakeLlmProvider(LlmResponse.Text("not json"))
        val store = InMemoryKnowledgeStore()
        val logger = RecordingServiceLogger()
        val service = KnowledgeExtractionService(LlmKnowledgeExtractor(provider), store, logger)

        val result = service.extractAndSave(ConversationContext(), source = "manual")

        assertIs<KnowledgeExtractionResult.Malformed>(result)
        assertEquals(emptyList(), store.list())
        assertEquals(1, logger.events.size)
        assertEquals(LogLevel.WARN, logger.events.single().level)
        assertEquals("knowledge_extraction_malformed", logger.events.single().message)
    }

    @Test
    fun `a provider failure saves nothing and logs a warning`() {
        val provider = ServiceFakeLlmProvider(LlmResponse.Error(LlmError.Authentication("invalid API key")))
        val store = InMemoryKnowledgeStore()
        val logger = RecordingServiceLogger()
        val service = KnowledgeExtractionService(LlmKnowledgeExtractor(provider), store, logger)

        val result = service.extractAndSave(ConversationContext(), source = "manual")

        assertIs<KnowledgeExtractionResult.ProviderFailed>(result)
        assertEquals(emptyList(), store.list())
        assertEquals("knowledge_extraction_provider_failed", logger.events.single().message)
        assertEquals("invalid API key", logger.events.single().fields["error"])
    }

    @Test
    fun `a successful extraction with no logger given does not throw`() {
        val provider = ServiceFakeLlmProvider(LlmResponse.Text("""[{"content":"fact"}]"""))
        val store = InMemoryKnowledgeStore()
        val service = KnowledgeExtractionService(LlmKnowledgeExtractor(provider), store)

        service.extractAndSave(ConversationContext(), source = "manual")

        assertEquals(1, store.list().size)
    }
}
