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
    private val gguf = ggufFixture()

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

    private fun importer(dir: File, free: Long = Long.MAX_VALUE / 2) = LocalModelImporter(dir, usableBytes = { free })

    @Test
    fun `an unsupported GGUF version is rejected from its header, before anything is copied`() {
        val dir = newDir()
        try {
            var read = 0
            val counting = object : ByteArrayInputStream(ggufFixture(version = 1, payload = ByteArray(1_000_000))) {
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) read += it }
            }
            val rejected = assertIs<ImportOutcome.Rejected>(importer(dir).importFrom("old.gguf", counting, null, emptySet()))
            assertEquals("This file cannot be used: Unsupported GGUF version 1 (supported: 2 to 3).", rejected.reason)
            assertEquals(24, read, "only the header may be read")
            assertEquals(emptyList(), entries(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a copy that arrives shorter than the picker said is not registered`() {
        val dir = newDir()
        try {
            // The source went away part-way (or the file was still being copied from a PC): the stream just ends.
            val whole = ggufFixture(payload = ByteArray(5 * 1024 * 1024))
            val cut = whole.copyOf(3 * 1024 * 1024)
            val failed = assertIs<ImportOutcome.Failed>(
                importer(dir).importFrom("model.gguf", ByteArrayInputStream(cut), whole.size.toLong(), emptySet()),
            )
            assertEquals(true, failed.reason.startsWith("The import was interrupted: only 3 of 5 MB arrived. Nothing was kept."), failed.reason)
            assertEquals(emptyList(), entries(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a file cut off inside its own metadata is rejected as incomplete even when no size is known`() {
        val dir = newDir()
        try {
            val cut = ggufFixture().copyOf(60)
            val rejected = assertIs<ImportOutcome.Rejected>(importer(dir).importFrom("model.gguf", ByteArrayInputStream(cut), null, emptySet()))
            assertEquals(true, rejected.reason.contains("incomplete"), rejected.reason)
            assertEquals(emptyList(), entries(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `cancelling stops the copy and leaves nothing behind`() {
        val dir = newDir()
        try {
            var copied = 0L
            val outcome = importer(dir).importFrom(
                "model.gguf",
                ByteArrayInputStream(ggufFixture(payload = ByteArray(4 * 1024 * 1024))),
                null,
                emptySet(),
                onProgress = { copied = it },
                cancelled = { copied >= 1024 * 1024 },
            )
            assertEquals(ImportOutcome.Failed("Import cancelled. Nothing was kept."), outcome)
            assertEquals(true, copied < 2 * 1024 * 1024, "the copy must stop soon after the cancel, got $copied")
            assertEquals(emptyList(), entries(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `files that are not chat models are rejected with what to do instead`() {
        val dir = newDir()
        try {
            fun reason(bytes: ByteArray) =
                assertIs<ImportOutcome.Rejected>(importer(dir).importFrom("x.gguf", ByteArrayInputStream(bytes), null, emptySet())).reason
            assertEquals(true, reason(ggufFixture(splitCount = 3)).contains("split into 3 files"))
            assertEquals(true, reason(ggufFixture(type = "mmproj")).contains("vision projector"))
            assertEquals(true, reason(ggufFixture(type = "adapter")).contains("LoRA adapter"))
            assertEquals(true, reason(ggufFixture(architecture = "bert")).contains("embedding model"))
            assertEquals(emptyList(), entries(dir))

            val outside = File(dir, "part-00001-of-00003.gguf").also { it.writeBytes(ggufFixture(splitCount = 3)) }
            val inPlace = assertIs<ImportOutcome.Rejected>(importer(dir).importExisting(outside, emptySet()))
            assertEquals(true, inPlace.reason.contains("split into 3 files"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the recorded window is the model's trained one when that is smaller, and a missing chat template is noted`() {
        val dir = newDir()
        try {
            val small = assertIs<ImportOutcome.Imported>(
                importer(dir).importFrom("small.gguf", ByteArrayInputStream(ggufFixture(contextLength = 512, chatTemplate = false)), null, emptySet()),
            )
            assertEquals(512, small.model.metadata.contextTokens)
            assertEquals(true, small.note!!.contains("chat template"))

            val large = assertIs<ImportOutcome.Imported>(
                importer(dir).importFrom("large.gguf", ByteArrayInputStream(ggufFixture(contextLength = 131072)), null, emptySet()),
            )
            assertEquals(LocalModelImporter.DEFAULT_CONTEXT_TOKENS, large.model.metadata.contextTokens)
            assertEquals(null, large.note)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `storage refusals say how much is needed and free, and a disk that fills mid-copy is named as such`() {
        val dir = newDir()
        try {
            val mb = 1024L * 1024L
            val before = assertIs<ImportOutcome.Failed>(
                LocalModelImporter(dir, usableBytes = { 500 * mb }).importFrom("big.gguf", ByteArrayInputStream(gguf), 2000 * mb, emptySet()),
            )
            assertEquals(true, before.reason.startsWith(LocalModelImporter.NOT_ENOUGH_STORAGE), before.reason)
            assertEquals(true, before.reason.contains("about 2000 MB and 500 MB is free"), before.reason)

            val full = object : InputStream() {
                private val head = ByteArrayInputStream(gguf.copyOf(24))

                override fun read(): Int = head.read()

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = head.read(b, off, len)
                    if (n > 0) return n
                    throw IOException("write failed: ENOSPC (No space left on device)")
                }
            }
            val during = assertIs<ImportOutcome.Failed>(importer(dir).importFrom("big.gguf", full, null, emptySet()))
            assertEquals(true, during.reason.startsWith(LocalModelImporter.NOT_ENOUGH_STORAGE), during.reason)
            assertEquals(emptyList(), entries(dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}
