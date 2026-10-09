package ai.droidcommand.rag.android

import ai.droidcommand.rag.Chunk
import ai.droidcommand.rag.DocumentInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Real Room over Robolectric's SQLite, on the JVM: the DAO queries, blob round-trip, transactional delete
 * and reopen-from-disk actually execute. NOT an on-device test (no real Android SQLite, no process restart).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomVectorStoreTest {
    private lateinit var dbFile: File
    private var db: RagDatabase? = null

    private fun open(): RoomVectorStore {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db?.close()
        db = Room.databaseBuilder(ctx, RagDatabase::class.java, dbFile.path).allowMainThreadQueries().build()
        return RoomVectorStore(db!!.ragDao())
    }

    @Before
    fun setUp() {
        dbFile = File.createTempFile("rag", ".db").also { it.delete() }
    }

    @After
    fun tearDown() {
        db?.close()
        dbFile.delete()
    }

    private fun chunks(doc: String, vararg texts: String) = texts.mapIndexed { i, t -> Chunk(doc, i, t) }

    @Test
    fun `vector blob round trips exactly`() {
        val v = floatArrayOf(0f, -1.5f, 3.25f, Float.MIN_VALUE, 1e30f)
        assertContentEquals(v, RoomVectorStore.decode(RoomVectorStore.encode(v)))
        assertFailsWith<IllegalStateException> { RoomVectorStore.decode(ByteArray(7)) }
    }

    @Test
    fun `search ranks by cosine and survives reopening the database`() {
        val store = open()
        store.add(chunks("a", "about cats", "about dogs"), listOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)))
        assertEquals("about cats", store.search(floatArrayOf(1f, 0.1f), 1).single().chunk.text)

        val reopened = open()
        assertEquals(setOf("a"), reopened.documentIds())
        val hit = reopened.search(floatArrayOf(0.1f, 1f), 1).single()
        assertEquals("about dogs", hit.chunk.text)
        assertEquals(1, hit.chunk.index)
    }

    @Test
    fun `remove drops chunks but keeps the listing, delete drops both`() {
        val store = open()
        store.add(chunks("a", "x"), listOf(floatArrayOf(1f)))
        store.save(DocumentInfo("a", "a", 1, 5L))
        store.remove("a")
        assertTrue(store.documentIds().isEmpty())
        assertEquals(1, store.list().size)

        store.add(chunks("a", "x"), listOf(floatArrayOf(1f)))
        store.delete("a")
        assertTrue(store.list().isEmpty())
        assertTrue(open().documentIds().isEmpty(), "chunks must be gone from disk too")
    }

    @Test
    fun `listing is ordered by time and save replaces an existing entry`() {
        val store = open()
        store.save(DocumentInfo("b", "b", 1, 20L))
        store.save(DocumentInfo("a", "a", 1, 10L))
        store.save(DocumentInfo("a", "a", 9, 30L))
        assertEquals(listOf("b", "a"), store.list().map { it.docId })
        assertEquals(9, store.list().last().chunkCount)
    }

    @Test
    fun `a failed insert leaves memory and disk unchanged`() {
        val store = open()
        store.add(chunks("a", "x"), listOf(floatArrayOf(1f)))
        // Same (doc, index) violates the unique index: the database rejects it.
        assertFailsWith<Exception> { store.add(chunks("a", "dup"), listOf(floatArrayOf(0f, 1f))) }
        assertEquals("x", store.search(floatArrayOf(1f), 5).single().chunk.text)
        assertEquals(1, open().search(floatArrayOf(1f), 5).size)
    }

    @Test
    fun `mismatched chunk and vector counts are rejected`() {
        assertFailsWith<IllegalArgumentException> { open().add(chunks("a", "x", "y"), listOf(floatArrayOf(1f))) }
    }
}
