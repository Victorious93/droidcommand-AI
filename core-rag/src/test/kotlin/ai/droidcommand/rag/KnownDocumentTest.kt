package ai.droidcommand.rag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Roadmap acceptance shape: a known multi-section document with known question → expected-passage pairs.
 * Uses the keyword fake embedder, so this proves chunk → embed → store → retrieve plumbing picks the right
 * section; it says nothing about retrieval quality with a real embedding model.
 */
class KnownDocumentTest {
    private val sections = mapOf(
        "mars" to "Mars has two moons named Phobos and Deimos and a thin carbon dioxide atmosphere",
        "bread" to "Sourdough bread needs flour water salt and a fermented starter left overnight",
        "rust" to "Rust ownership rules guarantee memory safety through borrowing and lifetimes",
    )
    private val doc = sections.values.joinToString("\n\n")
    private val vocab = listOf("moons", "phobos", "starter", "flour", "borrowing", "ownership", "atmosphere", "sourdough")

    private fun retriever() = DocumentRetriever(
        KeywordEmbedder(vocab),
        chunker = TextChunker(size = 12, overlap = 2),
        topK = 1,
    ).also { assertTrue(it.index("manual.txt", doc) > 3) }

    @Test
    fun `each known question retrieves the section that answers it`() {
        val r = retriever()
        val qa = mapOf(
            "What are the moons called, Phobos?" to "Deimos",
            "What does sourdough need, flour?" to "Sourdough bread needs flour",
            "How does ownership and borrowing work?" to "memory safety",
        )
        qa.forEach { (q, expected) ->
            val ctx = r.retrieveContext(q)
            assertTrue(ctx != null && ctx.contains(expected), "question '$q' should surface '$expected', got: $ctx")
        }
    }

    @Test
    fun `an unrelated question gets no excerpts`() {
        assertEquals(null, retriever().retrieveContext("Who won the 1998 world cup?"))
    }
}
