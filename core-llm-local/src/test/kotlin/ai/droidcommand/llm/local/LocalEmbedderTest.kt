package ai.droidcommand.llm.local

import ai.droidcommand.rag.DocumentRetriever
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

private class FakeEmbeddingBackend(private val f: (String) -> FloatArray) : EmbeddingBackend {
    override fun load(modelPath: String) = Unit

    override fun embed(text: String) = f(text)

    override fun unload() = Unit
}

class LocalEmbedderTest {
    @Test
    fun `vectors are L2-normalised`() {
        val e = LocalEmbedder(FakeEmbeddingBackend { floatArrayOf(3f, 4f) })
        assertContentEquals(floatArrayOf(0.6f, 0.8f), e.embed(listOf("x")).single())
    }

    @Test
    fun `zero vector is passed through unchanged`() {
        val e = LocalEmbedder(FakeEmbeddingBackend { floatArrayOf(0f, 0f) })
        assertContentEquals(floatArrayOf(0f, 0f), e.embed(listOf("x")).single())
    }

    @Test
    fun `empty, non-finite and dimension-changing vectors throw`() {
        assertFailsWith<InferenceException> { LocalEmbedder(FakeEmbeddingBackend { floatArrayOf() }).embed(listOf("x")) }
        assertFailsWith<InferenceException> { LocalEmbedder(FakeEmbeddingBackend { floatArrayOf(Float.NaN) }).embed(listOf("x")) }
        val flip = LocalEmbedder(FakeEmbeddingBackend { if (it == "a") floatArrayOf(1f, 0f) else floatArrayOf(1f) })
        assertFailsWith<InferenceException> { flip.embed(listOf("a", "b")) }
    }

    @Test
    fun `plugs into DocumentRetriever`() {
        val backend = FakeEmbeddingBackend { t -> floatArrayOf(if ("cat" in t) 5f else 0f, if ("dog" in t) 5f else 0f) }
        val r = DocumentRetriever(LocalEmbedder(backend))
        r.index("d", "the cat sleeps")
        assertNotNull(r.retrieveContext("cat?"))
        assertEquals(null, r.retrieveContext("dog?"))
    }
}
