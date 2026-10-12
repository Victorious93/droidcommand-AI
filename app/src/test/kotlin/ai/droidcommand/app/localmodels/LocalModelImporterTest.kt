package ai.droidcommand.app.localmodels

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LocalModelImporterTest {
    private val gguf = "GGUF".toByteArray() + "model-payload".toByteArray()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun newDir(): File = Files.createTempDirectory("import-test").toFile()

    /** Names of everything left in [dir]. After a rejected or failed import this must be empty. */
    private fun entries(dir: File): List<String> = dir.listFiles().orEmpty().map { it.name }.sorted()

    @Test
    fun `accepts a GGUF file, records its digest and size, and stores it under its id`() {
        val dir = newDir()
        try {
            val imported = assertIs<ImportOutcome.Imported>(
                LocalModelImporter(dir, nowMillis = { 42L })
                    .importFrom("My Model.gguf", ByteArrayInputStream(gguf), gguf.size.toLong(), emptySet()),
            ).model
            assertEquals(sha256(gguf), imported.metadata.sha256)
            assertEquals(gguf.size.toLong(), imported.metadata.sizeBytes)
            assertEquals("user-" + sha256(gguf).take(16), imported.id)
            assertEquals("My Model", imported.label)
            assertEquals(42L, imported.importedAtMillis)
            assertContentEquals(gguf, File(dir, imported.metadata.fileName).readBytes())
            assertEquals(listOf(imported.metadata.fileName), entries(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `rejects content without the GGUF header and leaves nothing behind`() {
        val dir = newDir()
        try {
            assertIs<ImportOutcome.Rejected>(
                LocalModelImporter(dir).importFrom("fake.gguf", ByteArrayInputStream("NOPE payload".toByteArray()), null, emptySet()),
            )
            assertEquals(emptyList(), entries(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a file shorter than the header is rejected`() {
        val dir = newDir()
        try {
            assertIs<ImportOutcome.Rejected>(LocalModelImporter(dir).importFrom("tiny.gguf", ByteArrayInputStream("GG".toByteArray()), null, emptySet()))
            assertEquals(emptyList(), entries(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a name that does not end in gguf is rejected before anything is copied`() {
        val dir = newDir()
        try {
            assertIs<ImportOutcome.Rejected>(LocalModelImporter(dir).importFrom("model.bin", ByteArrayInputStream(gguf), null, emptySet()))
            assertEquals(emptyList(), entries(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `importing the same bytes again reports the id already in use`() {
        val dir = newDir()
        try {
            val importer = LocalModelImporter(dir)
            val first = assertIs<ImportOutcome.Imported>(importer.importFrom("a.gguf", ByteArrayInputStream(gguf), null, emptySet())).model
            val second = importer.importFrom("b.gguf", ByteArrayInputStream(gguf), null, setOf(first.id))
            assertEquals(ImportOutcome.Duplicate(first.id), second)
            assertEquals(listOf(first.metadata.fileName), entries(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a read failure part-way deletes the partial copy and reports a failure`() {
        val dir = newDir()
        try {
            // Serves the first eight bytes, then fails like a dropped connection.
            val broken = object : InputStream() {
                private val head = gguf.copyOf(8)
                private var position = 0

                override fun read(): Int {
                    if (position < head.size) return head[position++].toInt() and 0xff
                    throw IOException("connection reset")
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (position >= head.size) throw IOException("connection reset")
                    val n = minOf(len, head.size - position)
                    head.copyInto(b, off, position, position + n)
                    position += n
                    return n
                }
            }
            assertIs<ImportOutcome.Failed>(LocalModelImporter(dir).importFrom("broken.gguf", broken, null, emptySet()))
            assertEquals(emptyList(), entries(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `refuses before copying when the free-space check fails`() {
        val dir = newDir()
        try {
            val importer = LocalModelImporter(dir, usableBytes = { 10L })
            assertIs<ImportOutcome.Failed>(importer.importFrom("big.gguf", ByteArrayInputStream(gguf), 1_000_000_000L, emptySet()))
            assertEquals(emptyList(), entries(dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}
