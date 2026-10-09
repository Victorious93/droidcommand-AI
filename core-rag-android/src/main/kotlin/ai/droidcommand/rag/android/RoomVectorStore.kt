package ai.droidcommand.rag.android

import ai.droidcommand.rag.Chunk
import ai.droidcommand.rag.DocumentInfo
import ai.droidcommand.rag.DocumentLibrary
import ai.droidcommand.rag.InMemoryVectorStore
import ai.droidcommand.rag.ScoredChunk
import ai.droidcommand.rag.VectorStore
import android.content.Context
import androidx.room.Room
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Room-backed [VectorStore] and [DocumentLibrary] — the persistent counterpart to `core-rag.FileVectorStore`
 * for Android. Rows are loaded into an [InMemoryVectorStore] at construction and searched there (brute-force
 * cosine, the same single-document-scale design as the file store); Room is the durable copy.
 *
 * Every write hits the database first and updates memory only if that succeeded, so memory never holds
 * something the database lacks. [add] inserts all chunks in one transaction. The database is the unit of
 * truth for what survives a restart; a corrupt vector blob (length not a multiple of 4) fails construction
 * loudly rather than being skipped.
 *
 * Methods block on SQLite and Room rejects main-thread queries by default: call off the main thread,
 * including construction (it reads every row).
 */
class RoomVectorStore(private val dao: RagDao) : VectorStore, DocumentLibrary {
    private val index = InMemoryVectorStore()

    init {
        val rows = dao.allChunks()
        if (rows.isNotEmpty()) {
            index.add(rows.map { Chunk(it.docId, it.chunkIndex, it.text) }, rows.map { decode(it.vector) })
        }
    }

    @Synchronized
    override fun add(chunks: List<Chunk>, vectors: List<FloatArray>) {
        require(chunks.size == vectors.size) { "one vector per chunk" }
        dao.insertChunks(chunks.zip(vectors).map { (c, v) -> ChunkEntity(docId = c.docId, chunkIndex = c.index, text = c.text, vector = encode(v)) })
        index.add(chunks, vectors)
    }

    @Synchronized
    override fun search(query: FloatArray, topK: Int): List<ScoredChunk> = index.search(query, topK)

    /** Removes the chunks of [docId] only; the listing entry stays until [delete]. */
    @Synchronized
    override fun remove(docId: String) {
        dao.deleteChunks(docId)
        index.remove(docId)
    }

    @Synchronized
    override fun documentIds(): Set<String> = index.documentIds()

    @Synchronized
    override fun list(): List<DocumentInfo> = dao.documents().map { DocumentInfo(it.docId, it.name, it.chunkCount, it.addedAt) }

    @Synchronized
    override fun save(info: DocumentInfo) = dao.upsertDocument(DocumentEntity(info.docId, info.name, info.chunkCount, info.addedAtMillis))

    @Synchronized
    override fun delete(docId: String) {
        dao.deleteAll(docId)
        index.remove(docId)
    }

    companion object {
        fun encode(v: FloatArray): ByteArray {
            val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            v.forEach { buf.putFloat(it) }
            return buf.array()
        }

        fun decode(bytes: ByteArray): FloatArray {
            check(bytes.size % 4 == 0) { "Corrupt vector blob: ${bytes.size} bytes is not a whole number of floats" }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(bytes.size / 4) { buf.getFloat() }
        }

        /** Opens (creating if needed) the app's document index. Call off the main thread. */
        fun open(context: Context): RoomVectorStore =
            RoomVectorStore(Room.databaseBuilder(context.applicationContext, RagDatabase::class.java, "rag.db").build().ragDao())
    }
}
