package ai.droidcommand.llm.local

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModelRepositoryGgufTest {
    private val gguf = ggufBytes()
    private val html = "<html>Access denied</html>".toByteArray()

    private fun sha(b: ByteArray) = ModelRepository.sha256Hex(ByteArrayInputStream(b))

    private fun dir(): File = Files.createTempDirectory("gguf-repo").toFile().also { it.deleteOnExit() }

    private fun repo(d: File, expected: ByteArray) = ModelRepository(d, requireGguf = true).also {
        it.register(ModelMetadata("m", "M", "m.gguf", sha(expected), 2048, expected.size.toLong(), "https://example.test/m.gguf"))
    }

    private class Serving(private val body: ByteArray) : ModelDownloader {
        override fun download(url: String, dest: File, maxBytes: Long, onBytes: (Long) -> Unit, cancelled: () -> Boolean) {
            dest.writeBytes(body)
            onBytes(body.size.toLong())
        }
    }

    @Test
    fun `a downloaded GGUF is installed and then verifies`() {
        val d = dir()
        val r = repo(d, gguf)
        assertIs<ModelInstallResult.Installed>(r.install("m", Serving(gguf)))
        assertIs<ModelCheck.Verified>(r.verify("m"))
    }

    @Test
    fun `a non-GGUF download is rejected even when the publisher digest was recorded from those same bytes`() {
        val d = dir()
        val r = repo(d, html)
        val result = assertIs<ModelInstallResult.Failed>(r.install("m", Serving(html)))
        assertTrue(result.reason.contains("GGUF"), result.reason)
        assertTrue(result.reason.endsWith("file discarded"))
        assertFalse(result.retryable)
        assertFalse(File(d, "m.gguf").exists(), "a rejected file is never moved into place")
        assertFalse(File(d, "m.gguf.part").exists())
        assertIs<ModelCheck.Missing>(r.verify("m"))
    }

    @Test
    fun `a hand-placed non-GGUF file is rejected by verify, not reported usable`() {
        val d = dir()
        File(d, "m.gguf").writeBytes(html)
        val check = assertIs<ModelCheck.Rejected>(repo(d, html).verify("m"))
        assertEquals(IntegrityFailure.FORMAT_INVALID, check.failure)
    }

    @Test
    fun `a wrong digest on a valid GGUF is still a mismatch`() {
        val d = dir()
        File(d, "m.gguf").writeBytes(ggufBytes(payload = "other".toByteArray()))
        val r = ModelRepository(d, requireGguf = true).also {
            it.register(ModelMetadata("m", "M", "m.gguf", sha(gguf), 2048))
        }
        assertIs<ModelCheck.Mismatch>(r.verify("m"))
    }

    @Test
    fun `without requireGguf stand-in bytes still work, as the existing tests rely on`() {
        val d = dir()
        val bytes = "pretend".toByteArray()
        File(d, "m.gguf").writeBytes(bytes)
        val r = ModelRepository(d).also { it.register(ModelMetadata("m", "M", "m.gguf", sha(bytes), 2048)) }
        assertIs<ModelCheck.Verified>(r.verify("m"))
    }
}
