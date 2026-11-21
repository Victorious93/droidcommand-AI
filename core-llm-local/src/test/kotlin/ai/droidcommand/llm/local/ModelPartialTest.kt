package ai.droidcommand.llm.local

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val DATA = ByteArray(100_000) { (it * 17 % 253).toByte() }
private val DATA_SHA = ModelRepository.sha256Hex(ByteArrayInputStream(DATA))

/** Writes the next [chunk] bytes after what is already in `dest`, then fails (a drop) or stops when [cancelAfterChunk]. */
private class ChunkedFake(private val chunk: Int, private val cancelAfterChunk: Boolean = false) : ModelDownloader {
    override fun download(url: String, dest: File, maxBytes: Long, onBytes: (Long) -> Unit, cancelled: () -> Boolean) {
        val from = if (dest.isFile) dest.length().toInt() else 0
        val to = minOf(DATA.size.toLong(), from.toLong() + chunk).toInt()
        dest.appendBytes(DATA.copyOfRange(from, to))
        onBytes((to - from).toLong())
        if (to < DATA.size) throw IOException(if (cancelAfterChunk) "Cancelled" else "connection reset")
    }
}

class ModelPartialTest {
    private fun repo(): Pair<ModelRepository, File> {
        val dir = Files.createTempDirectory("partial").toFile().also { it.deleteOnExit() }
        val repo = ModelRepository(dir).also {
            it.register(ModelMetadata("m", "M", "m.gguf", DATA_SHA, 2048, DATA.size.toLong(), "https://example.test/m.gguf"))
        }
        return repo to dir
    }

    @Test
    fun `a dropped download keeps its prefix and partialBytes reports it`() {
        val (repo, _) = repo()
        assertEquals(0L, repo.partialBytes("m"))
        val result = repo.install("m", ChunkedFake(30_000))
        assertIs<ModelInstallResult.Failed>(result)
        assertEquals(30_000L, repo.partialBytes("m"))
    }

    @Test
    fun `a stopped download keeps the prefix too, and the next call resumes and installs`() {
        val (repo, _) = repo()
        repo.install("m", ChunkedFake(40_000, cancelAfterChunk = true), cancelled = { false })
        assertEquals(40_000L, repo.partialBytes("m"))
        // Resume: the fake appends after the 40,000 bytes already there and finishes.
        val result = repo.install("m", ChunkedFake(Int.MAX_VALUE))
        assertIs<ModelInstallResult.Installed>(result)
        assertEquals(0L, repo.partialBytes("m"), "the partial is gone once the model is installed")
    }

    @Test
    fun `discardPartial removes the prefix, once, and a second install starts from zero`() {
        val (repo, dir) = repo()
        repo.install("m", ChunkedFake(30_000))
        assertTrue(repo.discardPartial("m"))
        assertEquals(0L, repo.partialBytes("m"))
        assertFalse(File(dir, "m.gguf.part").exists())
        assertFalse(repo.discardPartial("m"), "nothing left to remove")
        val result = repo.install("m", ChunkedFake(Int.MAX_VALUE))
        assertIs<ModelInstallResult.Installed>(result)
    }

    @Test
    fun `discardPartial never touches an installed model, and unknown ids are harmless`() {
        val (repo, dir) = repo()
        repo.install("m", ChunkedFake(Int.MAX_VALUE))
        assertTrue(File(dir, "m.gguf").isFile)
        assertFalse(repo.discardPartial("m"))
        assertTrue(File(dir, "m.gguf").isFile)
        assertFalse(repo.discardPartial("nope"))
        assertEquals(0L, repo.partialBytes("nope"))
    }
}
