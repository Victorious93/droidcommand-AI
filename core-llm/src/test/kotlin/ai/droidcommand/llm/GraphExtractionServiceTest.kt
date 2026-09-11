package ai.droidcommand.llm

import ai.droidcommand.agent.ConversationContext
import ai.droidcommand.agent.EntityType
import ai.droidcommand.agent.InMemoryKnowledgeGraph
import ai.droidcommand.agent.LogEvent
import ai.droidcommand.agent.LogLevel
import ai.droidcommand.agent.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private class RecordingGraphServiceLogger : Logger {
    val events = mutableListOf<LogEvent>()

    override fun log(event: LogEvent) {
        events.add(event)
    }
}

private class GraphServiceFakeLlmProvider(private val response: LlmResponse) : LlmProvider {
    override val config = LlmConfig(provider = "fake", model = "fake-1")

    override fun complete(request: LlmRequest): LlmResponse = response
}

class GraphExtractionServiceTest {
    @Test
    fun `a successful extraction saves every entity, then every relationship`() {
        val provider = GraphServiceFakeLlmProvider(
            LlmResponse.Text(
                """{"entities":[{"key":"e1","type":"DEVICE","label":"Pixel 9"},{"key":"e2","type":"COMMAND","label":"reboot"}],""" +
                    """"relationships":[{"fromKey":"e2","toKey":"e1","type":"targets"}]}""",
            ),
        )
        val graph = InMemoryKnowledgeGraph()
        val service = GraphExtractionService(LlmGraphExtractor(provider), graph)

        val result = service.extractAndSave(ConversationContext(), source = "conversation:chat-1")

        assertIs<GraphExtractionResult.Success>(result)
        assertEquals(1, graph.entitiesByType(EntityType.DEVICE).size)
        assertEquals(1, graph.entitiesByType(EntityType.COMMAND).size)
        val command = graph.entitiesByType(EntityType.COMMAND).single()
        assertEquals(1, graph.relationshipsFrom(command.id).size)
    }

    @Test
    fun `an empty extraction saves nothing`() {
        val provider = GraphServiceFakeLlmProvider(LlmResponse.Text("""{"entities":[],"relationships":[]}"""))
        val graph = InMemoryKnowledgeGraph()
        val service = GraphExtractionService(LlmGraphExtractor(provider), graph)

        service.extractAndSave(ConversationContext(), source = "manual")

        assertEquals(emptyList(), graph.entitiesByType(EntityType.NOTE))
    }

    @Test
    fun `malformed extraction saves nothing and logs a warning, never throws`() {
        val provider = GraphServiceFakeLlmProvider(LlmResponse.Text("not json"))
        val graph = InMemoryKnowledgeGraph()
        val logger = RecordingGraphServiceLogger()
        val service = GraphExtractionService(LlmGraphExtractor(provider), graph, logger)

        val result = service.extractAndSave(ConversationContext(), source = "manual")

        assertIs<GraphExtractionResult.Malformed>(result)
        assertEquals(1, logger.events.size)
        assertEquals(LogLevel.WARN, logger.events.single().level)
        assertEquals("graph_extraction_malformed", logger.events.single().message)
    }

    @Test
    fun `a provider failure saves nothing and logs a warning`() {
        val provider = GraphServiceFakeLlmProvider(LlmResponse.Error(LlmError.Authentication("invalid API key")))
        val graph = InMemoryKnowledgeGraph()
        val logger = RecordingGraphServiceLogger()
        val service = GraphExtractionService(LlmGraphExtractor(provider), graph, logger)

        val result = service.extractAndSave(ConversationContext(), source = "manual")

        assertIs<GraphExtractionResult.ProviderFailed>(result)
        assertEquals("graph_extraction_provider_failed", logger.events.single().message)
        assertEquals("invalid API key", logger.events.single().fields["error"])
    }

    @Test
    fun `a successful extraction with no logger given does not throw`() {
        val provider = GraphServiceFakeLlmProvider(LlmResponse.Text("""{"entities":[{"key":"e1","type":"NOTE","label":"n"}],"relationships":[]}"""))
        val graph = InMemoryKnowledgeGraph()
        val service = GraphExtractionService(LlmGraphExtractor(provider), graph)

        service.extractAndSave(ConversationContext(), source = "manual")

        assertEquals(1, graph.entitiesByType(EntityType.NOTE).size)
    }
}
