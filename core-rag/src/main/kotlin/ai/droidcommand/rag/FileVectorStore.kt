package ai.droidcommand.rag

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * A [VectorStore] that survives restarts by rewriting a single binary file on every change. Search is
 * delegated to an [InMemoryVectorStore], so quality and scale limits are the same (single-document scale).
 *
 * Writes go to a temp file that is atomically moved over [file], so a crash mid-write leaves the previous
 * index intact. A file that exists but cannot be parsed makes construction throw [IllegalStateException]
 * rather than silently starting empty and then overwriting the user's index on the next change.
 *
 * Not a Room store: the Android-side Room/blob store is still unbuilt. The record format here is private to
 * this class and carries no compatibility promise beyond its [MAGIC] header.
 */
class FileVectorStore(private val file: Path) : VectorStore {
    private class Record(val chunk: Chunk, val vector: FloatArray)

    private val records = mutableListOf<Record>()
    private val index = InMemoryVectorStore()

    init {
        if (Files.exists(file)) {
            val loaded = try {
                decode(Files.readAllBytes(file))
            } catch (e: Exception) {
                throw IllegalStateException("Unreadable vector index at $file: ${e.message}", e)
            }
            records += loaded
            if (loaded.isNotEmpty()) index.add(loaded.map { it.chunk }, loaded.map { it.vector })
        }
    }

    @Synchronized
    override fun add(chunks: List<Chunk>, vectors: List<FloatArray>) {
        require(chunks.size == vectors.size) { "one vector per chunk" }
        index.add(chunks, vectors)
        val before = records.size
        chunks.zip(vectors).forEach { (c, v) -> records += Record(c, v.copyOf()) }
        persistOrRollback(before)
    }

    @Synchronized
    override fun search(query: FloatArray, topK: Int): List<ScoredChunk> = index.search(query, topK)

    @Synchronized
    override fun remove(docId: String) {
        val removed = records.filter { it.chunk.docId == docId }
        if (removed.isEmpty()) return
        records.removeAll(removed)
        index.remove(docId)
        try {
            save()
        } catch (e: Exception) {
            records += removed
            index.add(removed.map { it.chunk }, removed.map { it.vector })
            throw e
        }
    }

    @Synchronized
    override fun documentIds(): Set<String> = index.documentIds()

    private fun persistOrRollback(sizeBefore: Int) {
        try {
            save()
        } catch (e: Exception) {
            val added = records.subList(sizeBefore, records.size).toList()
            records.subList(sizeBefore, records.size).clear()
            added.map { it.chunk.docId }.toSet().forEach { index.remove(it) }
            // Re-add anything of those docs that predated this call.
            val survivors = records.filter { r -> added.any { it.chunk.docId == r.chunk.docId } }
            if (survivors.isNotEmpty()) index.add(survivors.map { it.chunk }, survivors.map { it.vector })
            throw e
        }
    }

    private fun save() {
        file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.write(tmp, encode(records))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun encode(rs: List<Record>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(rs.size)
            for (r in rs) {
                val doc = r.chunk.docId.toByteArray(Charsets.UTF_8)
                val text = r.chunk.text.toByteArray(Charsets.UTF_8)
                out.writeInt(doc.size)
                out.write(doc)
                out.writeInt(r.chunk.index)
                out.writeInt(text.size)
                out.write(text)
                out.writeInt(r.vector.size)
                r.vector.forEach { out.writeFloat(it) }
            }
        }
        return bytes.toByteArray()
    }

    private fun decode(data: ByteArray): List<Record> {
        DataInputStream(ByteArrayInputStream(data)).use { input ->
            check(input.readInt() == MAGIC) { "not a vector index file" }
            val count = input.readInt()
            check(count >= 0) { "negative record count" }
            val out = ArrayList<Record>()
            repeat(count) {
                val doc = readBytes(input, data.size).toString(Charsets.UTF_8)
                val idx = input.readInt()
                val text = readBytes(input, data.size).toString(Charsets.UTF_8)
                val dim = input.readInt()
                check(dim in 0..data.size / 4) { "implausible vector size $dim" }
                val v = FloatArray(dim) { input.readFloat() }
                out += Record(Chunk(doc, idx, text), v)
            }
            check(input.read() == -1) { "trailing bytes" }
            return out
        }
    }

    private fun readBytes(input: DataInputStream, limit: Int): ByteArray {
        val n = input.readInt()
        if (n < 0 || n > limit) throw EOFException("implausible length $n")
        return ByteArray(n).also { input.readFully(it) }
    }

    private companion object {
        /** "DRG1" */
        const val MAGIC = 0x44524731
    }
}
