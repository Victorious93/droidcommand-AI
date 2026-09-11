package ai.droidcommand.llm

import ai.droidcommand.agent.ConversationContext
import ai.droidcommand.agent.EntityType
import ai.droidcommand.agent.JsonFileKnowledgeGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class FakeGraphLlmProvider(private val response: LlmResponse) : LlmProvider {
    override val config = LlmConfig(provider = "fake", model = "fake-1")
    var lastRequest: LlmRequest? = null

    override fun complete(request: LlmRequest): LlmResponse {
        lastRequest = request
        return response
    }
}

class LlmGraphExtractorTest {
    @Test
    fun `parses entities and relationships, resolving keys to generated ids`() {
        val provider = FakeGraphLlmProvider(
            LlmResponse.Text(
                """{"entities":[{"key":"e1","type":"DEVICE","label":"Pixel 9","properties":{"os":"Android 16"}},""" +
                    """{"key":"e2","type":"COMMAND","label":"reboot"}],""" +
                    """"relationships":[{"fromKey":"e2","toKey":"e1","type":"targets"}]}""",
            ),
        )

        val result = LlmGraphExtractor(provider).extract(ConversationContext(), source = "conversation:chat-1")

        val success = assertIs<GraphExtractionResult.Success>(result)
        assertEquals(2, success.entities.size)
        val device = success.entities.single { it.type == EntityType.DEVICE }
        val command = success.entities.single { it.type == EntityType.COMMAND }
        assertEquals("Pixel 9", device.label)
        assertEquals(mapOf("os" to "Android 16"), device.properties)
        assertEquals(1, success.relationships.size)
        assertEquals(command.id, success.relationships.single().fromId)
        assertEquals(device.id, success.relationships.single().toId)
        assertEquals("targets", success.relationships.single().type)
    }

    @Test
    fun `empty entities and relationships is Success with empty lists`() {
        val provider = FakeGraphLlmProvider(LlmResponse.Text("""{"entities":[],"relationships":[]}"""))

        val result = LlmGraphExtractor(provider).extract(ConversationContext(), source = "manual")

        assertEquals(GraphExtractionResult.Success(emptyList(), emptyList()), result)
    }

    @Test
    fun `generated entity ids satisfy JsonFileKnowledgeGraph's id pattern`() {
        val provider = FakeGraphLlmProvider(LlmResponse.Text("""{"entities":[{"key":"e1","type":"NOTE","label":"n"}],"relationships":[]}"""))

        val result = LlmGraphExtractor(provider).extract(ConversationContext(), source = "manual")

        val id = (result as GraphExtractionResult.Success).entities.single().id
        assertTrue(JsonFileKnowledgeGraph.ID_PATTERN.matches(id))
    }

    @Test
    fun `a custom idGenerator is honored instead of the default UUID one`() {
        var counter = 0
        val provider = FakeGraphLlmProvider(
            LlmResponse.Text("""{"entities":[{"key":"e1","type":"NOTE","label":"a"},{"key":"e2","type":"NOTE","label":"b"}],"relationships":[]}"""),
        )

        val result = LlmGraphExtractor(provider, idGenerator = { "id-${counter++}" }).extract(ConversationContext(), source = "manual")

        val ids = (result as GraphExtractionResult.Success).entities.map { it.id }
        assertEquals(listOf("id-0", "id-1"), ids)
    }

    @Test
    fun `malformed JSON text is reported, not thrown`() {
        val provider = FakeGraphLlmProvider(LlmResponse.Text("not json at all"))

        val result = LlmGraphExtractor(provider).extract(ConversationContext(), source = "manual")

        val malformed = assertIs<GraphExtractionResult.Malformed>(result)
        assertEquals("not json at all", malformed.raw)
    }

    @Test
    fun `an unrecognized entity type is reported as Malformed`() {
        val provider = FakeGraphLlmProvider(LlmResponse.Text("""{"entities":[{"key":"e1","type":"SPACESHIP","label":"a"}],"relationships":[]}"""))

        val result = LlmGraphExtractor(provider).extract(ConversationContext(), source = "manual")

        val malformed = assertIs<GraphExtractionResult.Malformed>(result)
        assertTrue(malformed.reason.contains("SPACESHIP"))
    }

    @Test
    fun `a relationship referencing an unknown key is reported as Malformed`() {
        val provider = FakeGraphLlmProvider(
            LlmResponse.Text("""{"entities":[{"key":"e1","type":"NOTE","label":"a"}],"relationships":[{"fromKey":"e1","toKey":"e99","type":"relates_to"}]}"""),
        )

        val result = LlmGraphExtractor(provider).extract(ConversationContext(), source = "manual")

        val malformed = assertIs<GraphExtractionResult.Malformed>(result)
        assertTrue(malformed.reason.contains("e99"))
    }

    @Test
    fun `a provider error becomes ProviderFailed`() {
        val provider = FakeGraphLlmProvider(LlmResponse.Error(LlmError.Authentication("invalid API key")))

        val result = LlmGraphExtractor(provider).extract(ConversationContext(), source = "manual")

        val failed = assertIs<GraphExtractionResult.ProviderFailed>(result)
        assertEquals("invalid API key", failed.error.message)
    }

    @Test
    fun `an unexpected tool call is reported as Malformed instead of crashing`() {
        val provider = FakeGraphLlmProvider(LlmResponse.ToolCall("some_tool", mapOf("x" to "1")))

        val result = LlmGraphExtractor(provider).extract(ConversationContext(), source = "manual")

        assertIs<GraphExtractionResult.Malformed>(result)
    }

    @Test
    fun `the request carries no tools`() {
        val provider = FakeGraphLlmProvider(LlmResponse.Text("""{"entities":[],"relationships":[]}"""))

        LlmGraphExtractor(provider).extract(ConversationContext(), source = "manual")

        assertTrue(provider.lastRequest!!.tools.isEmpty())
    }
}
