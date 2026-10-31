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
    fun `a GGUF whose picker name has no gguf suffix is still imported`() {
        val dir = newDir()
        try {
            // Storage Access Framework often reports DISPLAY_NAME as "msf:123" or "model.bin".
            val imported = assertIs<ImportOutcome.Imported>(
                LocalModelImporter(dir).importFrom("msf:123", ByteArrayInputStream(gguf), null, emptySet()),
            ).model
            assertEquals(gguf.size.toLong(), imported.metadata.sizeBytes)
            assertEquals(listOf(imported.metadata.fileName), entries(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a non-GGUF whose name has no gguf suffix is rejected`() {
        val dir = newDir()
        try {
            val rejected = assertIs<ImportOutcome.Rejected>(
                LocalModelImporter(dir).importFrom("model.bin", ByteArrayInputStream("NOPE payload".toByteArray()), null, emptySet()),
            )
            assertEquals("Pick a file whose name ends in .gguf.", rejected.reason)
            assertEquals(emptyList(), entries(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `importExisting registers a file in place without copying it`() {
        val dir = newDir()
        val outside = newDir()
        try {
            val source = File(outside, "tiny.Q4_K_M.gguf")
            source.writeBytes(gguf)
            val imported = assertIs<ImportOutcome.Imported>(
                LocalModelImporter(dir, nowMillis = { 7L }).importExisting(source, emptySet()),
            ).model
            assertEquals(source.canonicalPath, imported.externalPath)
            assertEquals("tiny.Q4_K_M", imported.label)
            assertEquals(7L, imported.importedAtMillis)
            assertEquals(emptyList(), entries(dir), "the file stays where it was")
            assertContentEquals(gguf, source.readBytes())
        } finally {
            dir.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun `importExisting rejects a missing GGUF header`() {
        val dir = newDir()
        try {
            val source = File(dir, "fake.gguf")
            source.writeBytes("NOPE".toByteArray())
            assertIs<ImportOutcome.Rejected>(LocalModelImporter(dir).importExisting(source, emptySet()))
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

    @Test
    fun `discards the partial copies an interrupted import left behind, and nothing else`() {
        val dir = newDir()
        try {
            File(dir, "import-123.part").writeText("half a model")
            File(dir, "import-456.part").writeText("another one")
            File(dir, "user-abc.gguf").writeText("a model that was kept")
            File(dir, "registry.txt").writeText("records")
            File(dir, "notes.part").writeText("not an import temp file")

            assertEquals(2, LocalModelImporter(dir).discardAbandonedImports())
            assertEquals(listOf("notes.part", "registry.txt", "user-abc.gguf"), entries(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `discarding before the directory exists removes nothing and does not fail`() {
        val parent = newDir()
        try {
            assertEquals(0, LocalModelImporter(File(parent, "not-created-yet")).discardAbandonedImports())
        } finally {
            parent.deleteRecursively()
        }
    }
}
