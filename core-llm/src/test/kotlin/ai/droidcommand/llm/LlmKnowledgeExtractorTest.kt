package ai.droidcommand.llm

import ai.droidcommand.agent.ConversationContext
import ai.droidcommand.agent.JsonFileKnowledgeStore
import ai.droidcommand.agent.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class FakeExtractorLlmProvider(private val response: LlmResponse) : LlmProvider {
    override val config = LlmConfig(provider = "fake", model = "fake-1")
    var lastRequest: LlmRequest? = null

    override fun complete(request: LlmRequest): LlmResponse {
        lastRequest = request
        return response
    }
}

class LlmKnowledgeExtractorTest {
    @Test
    fun `parses a valid JSON array into KnowledgeEntry objects`() {
        val provider = FakeExtractorLlmProvider(
            LlmResponse.Text(
                """[{"content":"The user prefers dark mode","tags":["preferences","ui"]},{"content":"The user is on Kotlin 2.4","tags":[]}]""",
            ),
        )

        val result = LlmKnowledgeExtractor(provider).extract(ConversationContext(), source = "conversation:chat-1")

        val success = assertIs<KnowledgeExtractionResult.Success>(result)
        assertEquals(2, success.entries.size)
        assertEquals("The user prefers dark mode", success.entries[0].content)
        assertEquals(setOf("preferences", "ui"), success.entries[0].tags)
        assertEquals("conversation:chat-1", success.entries[0].source)
        assertEquals(emptySet(), success.entries[1].tags)
    }

    @Test
    fun `an empty JSON array means nothing worth remembering`() {
        val provider = FakeExtractorLlmProvider(LlmResponse.Text("[]"))

        val result = LlmKnowledgeExtractor(provider).extract(ConversationContext(), source = "manual")

        assertEquals(KnowledgeExtractionResult.Success(emptyList()), result)
    }

    @Test
    fun `generated ids satisfy JsonFileKnowledgeStore's id pattern`() {
        val provider = FakeExtractorLlmProvider(LlmResponse.Text("""[{"content":"fact"}]"""))

        val result = LlmKnowledgeExtractor(provider).extract(ConversationContext(), source = "manual")

        val id = (result as KnowledgeExtractionResult.Success).entries.single().id
        assertTrue(JsonFileKnowledgeStore.ID_PATTERN.matches(id))
    }

    @Test
    fun `a custom idGenerator is honored instead of the default UUID one`() {
        var counter = 0
        val provider = FakeExtractorLlmProvider(LlmResponse.Text("""[{"content":"a"},{"content":"b"}]"""))

        val result = LlmKnowledgeExtractor(provider, idGenerator = { "id-${counter++}" }).extract(ConversationContext(), source = "manual")

        val ids = (result as KnowledgeExtractionResult.Success).entries.map { it.id }
        assertEquals(listOf("id-0", "id-1"), ids)
    }

    @Test
    fun `malformed JSON text is reported, not thrown`() {
        val provider = FakeExtractorLlmProvider(LlmResponse.Text("not json at all"))

        val result = LlmKnowledgeExtractor(provider).extract(ConversationContext(), source = "manual")

        val malformed = assertIs<KnowledgeExtractionResult.Malformed>(result)
        assertEquals("not json at all", malformed.raw)
    }

    @Test
    fun `a provider error becomes ProviderFailed`() {
        val provider = FakeExtractorLlmProvider(LlmResponse.Error(LlmError.Authentication("invalid API key")))

        val result = LlmKnowledgeExtractor(provider).extract(ConversationContext(), source = "manual")

        val failed = assertIs<KnowledgeExtractionResult.ProviderFailed>(result)
        assertEquals("invalid API key", failed.error.message)
    }

    @Test
    fun `an unexpected tool call is reported as Malformed instead of crashing`() {
        val provider = FakeExtractorLlmProvider(LlmResponse.ToolCall("some_tool", mapOf("x" to "1")))

        val result = LlmKnowledgeExtractor(provider).extract(ConversationContext(), source = "manual")

        assertIs<KnowledgeExtractionResult.Malformed>(result)
    }

    @Test
    fun `the request carries the conversation's messages and no tools`() {
        val provider = FakeExtractorLlmProvider(LlmResponse.Text("[]"))
        val context = ConversationContext().apply {
            append(Role.USER, "I prefer dark mode")
            append(Role.ASSISTANT, "Noted")
        }

        LlmKnowledgeExtractor(provider).extract(context, source = "manual")

        val request = provider.lastRequest!!
        assertEquals(listOf(Role.USER to "I prefer dark mode", Role.ASSISTANT to "Noted"), request.messages.map { it.role to it.content })
        assertTrue(request.tools.isEmpty())
    }
}
