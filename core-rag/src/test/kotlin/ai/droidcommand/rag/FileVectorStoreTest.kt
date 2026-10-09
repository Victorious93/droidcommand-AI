package ai.droidcommand.rag

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileVectorStoreTest {
    private fun tmp() = Files.createTempDirectory("rag").resolve("index.bin")

    private fun chunks(doc: String, n: Int) = (0 until n).map { Chunk(doc, it, "text $it of $doc — ünïcode") }

    @Test
    fun `contents survive a reopen and search still works`() {
        val f = tmp()
        FileVectorStore(f).apply {
            add(chunks("a", 2), listOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)))
            add(chunks("b", 1), listOf(floatArrayOf(1f, 1f)))
        }
        val reopened = FileVectorStore(f)
        assertEquals(setOf("a", "b"), reopened.documentIds())
        val top = reopened.search(floatArrayOf(0f, 1f), 1).single()
        assertEquals(Chunk("a", 1, "text 1 of a — ünïcode"), top.chunk)
    }

    @Test
    fun `remove persists`() {
        val f = tmp()
        FileVectorStore(f).apply {
            add(chunks("a", 1), listOf(floatArrayOf(1f)))
            add(chunks("b", 1), listOf(floatArrayOf(1f)))
            remove("a")
        }
        assertEquals(setOf("b"), FileVectorStore(f).documentIds())
    }

    @Test
    fun `large chunk text over 64k round-trips`() {
        val f = tmp()
        val big = "x".repeat(200_000)
        FileVectorStore(f).add(listOf(Chunk("d", 0, big)), listOf(floatArrayOf(1f)))
        assertEquals(big, FileVectorStore(f).search(floatArrayOf(1f), 1).single().chunk.text)
    }

    @Test
    fun `corrupt file fails loudly and is not overwritten`() {
        val f = tmp()
        Files.write(f, byteArrayOf(1, 2, 3, 4, 5))
        assertFailsWith<IllegalStateException> { FileVectorStore(f) }
        assertEquals(5, Files.size(f).toInt())
    }

    @Test
    fun `truncated file fails loudly`() {
        val f = tmp()
        FileVectorStore(f).add(chunks("a", 3), List(3) { floatArrayOf(1f, 2f) })
        Files.write(f, Files.readAllBytes(f).copyOf(30))
        assertFailsWith<IllegalStateException> { FileVectorStore(f) }
    }

    @Test
    fun `failed write rolls back in-memory state`() {
        val f = tmp()
        val store = FileVectorStore(f)
        store.add(chunks("a", 1), listOf(floatArrayOf(1f)))
        // Make the temp path a directory so the write fails.
        Files.createDirectory(f.resolveSibling("index.bin.tmp"))
        assertFailsWith<Exception> { store.add(chunks("b", 1), listOf(floatArrayOf(1f))) }
        assertEquals(setOf("a"), store.documentIds())
        assertTrue(store.search(floatArrayOf(1f), 5).all { it.chunk.docId == "a" })
    }

    @Test
    fun `retriever over a file store answers after restart`() {
        val f = tmp()
        val embedder = KeywordEmbedder(listOf("apple", "banana"))
        DocumentRetriever(embedder, FileVectorStore(f)).index("doc", "apple apple pie recipe")
        val ctx = DocumentRetriever(embedder, FileVectorStore(f)).retrieveContext("apple?")
        assertTrue(ctx!!.contains("apple apple pie"))
    }
}
