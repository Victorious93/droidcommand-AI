package ai.droidcommand.rag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Test-only bag-of-keywords embedder: one dimension per keyword. Not a real embedding model. */
internal class KeywordEmbedder(private val vocab: List<String>) : Embedder {
    var calls = 0

    override fun embed(texts: List<String>): List<FloatArray> {
        calls++
        return texts.map { t ->
            val words = t.lowercase().split(Regex("\\W+"))
            FloatArray(vocab.size) { i -> words.count { it == vocab[i] }.toFloat() }
        }
    }
}

class RagTest {
    @Test
    fun `chunker windows with overlap and never loses words`() {
        val text = (1..10).joinToString(" ") { "w$it" }
        val chunks = TextChunker(size = 4, overlap = 1).chunk("d", text)
        assertEquals(listOf("w1 w2 w3 w4", "w4 w5 w6 w7", "w7 w8 w9 w10"), chunks.map { it.text })
        assertEquals(listOf(0, 1, 2), chunks.map { it.index })
    }

    @Test
    fun `chunker handles short, blank and exact-fit input`() {
        val c = TextChunker(size = 3, overlap = 1)
        assertEquals(emptyList(), c.chunk("d", "  \n "))
        assertEquals(listOf("a b"), c.chunk("d", "a b").map { it.text })
        assertEquals(listOf("a b c"), c.chunk("d", "a b c").map { it.text })
    }

    @Test
    fun `chunker rejects bad sizes`() {
        assertFailsWith<IllegalArgumentException> { TextChunker(size = 0) }
        assertFailsWith<IllegalArgumentException> { TextChunker(size = 4, overlap = 4) }
    }

    @Test
    fun `store ranks by cosine and ignores zero vectors and dimension mismatches`() {
        val s = InMemoryVectorStore()
        val a = Chunk("d", 0, "a")
        val b = Chunk("d", 1, "b")
        val z = Chunk("d", 2, "z")
        val m = Chunk("d", 3, "m")
        s.add(listOf(a, b, z, m), listOf(floatArrayOf(1f, 0f), floatArrayOf(1f, 1f), floatArrayOf(0f, 0f), floatArrayOf(1f, 0f, 0f)))
        val hits = s.search(floatArrayOf(1f, 0f), 5)
        assertEquals(listOf(a, b), hits.map { it.chunk })
        assertEquals(1f, hits[0].score, 1e-6f)
        assertEquals(emptyList(), s.search(floatArrayOf(0f, 0f), 5))
    }

    @Test
    fun `retriever finds the grounded passage and frames it as untrusted`() {
        val e = KeywordEmbedder(listOf("battery", "camera", "price"))
        val r = DocumentRetriever(e, chunker = TextChunker(size = 4, overlap = 0), topK = 1)
        r.index("phone.txt", "the battery lasts two days. the camera is sharp and fast. the price is low today.")
        val ctx = assertNotNull(r.retrieveContext("how long does the battery last"))
        assertTrue(ctx.startsWith(DocumentRetriever.HEADING))
        assertTrue("battery lasts" in ctx)
        assertFalse("camera" in ctx)
    }

    @Test
    fun `retriever returns null when empty, blank, or nothing relevant`() {
        val r = DocumentRetriever(KeywordEmbedder(listOf("battery")))
        assertNull(r.retrieveContext("battery"))
        r.index("d", "battery notes")
        assertNull(r.retrieveContext("   "))
        assertNull(r.retrieveContext("completely unrelated question"))
    }

    @Test
    fun `reindexing replaces and remove clears`() {
        val r = DocumentRetriever(KeywordEmbedder(listOf("alpha", "beta")))
        r.index("d", "alpha alpha")
        r.index("d", "beta beta")
        assertNull(r.retrieveContext("alpha"))
        assertNotNull(r.retrieveContext("beta"))
        r.remove("d")
        assertFalse(r.hasDocuments())
    }

    @Test
    fun `a misbehaving embedder is rejected`() {
        val bad = object : Embedder {
            override fun embed(texts: List<String>) = emptyList<FloatArray>()
        }
        assertFailsWith<IllegalArgumentException> { DocumentRetriever(bad).index("d", "some text") }
    }
}
