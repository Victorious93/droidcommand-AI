package ai.droidcommand.llm

import ai.droidcommand.agent.ConversationContext
import ai.droidcommand.agent.InMemoryKnowledgeGraph
import ai.droidcommand.agent.memory.MemoryMetadata
import ai.droidcommand.agent.memory.MemoryClass
import ai.droidcommand.agent.memory.MemoryWriter
import ai.droidcommand.agent.memory.Origin
import ai.droidcommand.agent.memory.Verification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

private class MemoryFakeProvider(private val response: LlmResponse) : LlmProvider {
    override val config = LlmConfig(provider = "fake", model = "fake-1")

    override fun complete(request: LlmRequest): LlmResponse = response
}

class MemoryExtractionServiceTest {
    private val graph = InMemoryKnowledgeGraph()

    private fun service(json: String, scope: String = "project:dca") = MemoryExtractionService(
        LlmGraphExtractor(MemoryFakeProvider(LlmResponse.Text(json))),
        MemoryWriter(graph),
        scope,
    )

    private fun records() = graph.searchEntities("").mapNotNull { MemoryMetadata.from(it) }

    @Test
    fun `extracted entities are stored as unverified model memories in the given scope`() {
        val (result, outcome) = service(
            """{"entities":[{"key":"a","type":"DEVICE","label":"Pixel 9","properties":{"os":"Android 15"}}],"relationships":[]}""",
        ).extractAndStore(ConversationContext(), "conversation:c1")

        assertIs<GraphExtractionResult.Success>(result)
        assertEquals(MemoryExtractionOutcome(1, 0, 0, 0, 0), outcome)
        val meta = records().single()
        assertEquals(MemoryClass.DEVICE, meta.memoryClass)
        assertEquals("project:dca", meta.scope)
        assertEquals(Origin.MODEL, meta.origin)
        assertEquals(Verification.MODEL_INFERRED, meta.verification)
        assertEquals("conversation:c1", meta.sourceRef)
        assertEquals(true, meta.content.contains("Android 15"))
    }

    @Test
    fun `credential-like content is rejected and never stored`() {
        val (_, outcome) = service(
            """{"entities":[{"key":"a","type":"NOTE","label":"api key","properties":{"value":"sk-ant-api03-ABCDEFGHIJKLMNOPQRSTUV"}}],"relationships":[]}""",
        ).extractAndStore(ConversationContext(), "s")

        assertEquals(MemoryExtractionOutcome(0, 0, 0, 1, 0), outcome)
        assertEquals(emptyList(), records())
    }

    @Test
    fun `re-extracting the same fact is a duplicate, not a second record`() {
        val json = """{"entities":[{"key":"a","type":"PROJECT","label":"DCA","properties":{"lang":"Kotlin"}}],"relationships":[]}"""
        service(json).extractAndStore(ConversationContext(), "s")
        val (_, second) = service(json).extractAndStore(ConversationContext(), "s")

        assertEquals(1, second?.duplicates)
        assertEquals(1, records().size)
    }

    @Test
    fun `relationships are counted as dropped and not written`() {
        val (_, outcome) = service(
            """{"entities":[{"key":"a","type":"DEVICE","label":"Pixel 9"},{"key":"b","type":"COMMAND","label":"reboot"}],""" +
                """"relationships":[{"fromKey":"b","toKey":"a","type":"targets"}]}""",
        ).extractAndStore(ConversationContext(), "s")

        assertEquals(2, outcome?.stored)
        assertEquals(1, outcome?.relationshipsDropped)
        assertEquals(0, graph.searchEntities("").sumOf { graph.relationshipsFrom(it.id).size })
    }

    @Test
    fun `malformed output stores nothing and has no outcome`() {
        val (result, outcome) = service("not json").extractAndStore(ConversationContext(), "s")
        assertIs<GraphExtractionResult.Malformed>(result)
        assertNull(outcome)
        assertEquals(emptyList(), records())
    }

    @Test
    fun `blank scope is refused`() {
        assertFailsWith<IllegalArgumentException> { service("""{"entities":[],"relationships":[]}""", scope = " ") }
    }
}
