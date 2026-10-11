package ai.droidcommand.llm.local

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val GOOD = "pretend gguf bytes".toByteArray()
private val GOOD_SHA = ModelRepository.sha256Hex(ByteArrayInputStream(GOOD))

private class FakeDownloader(var body: ByteArray = GOOD, var failWith: IOException? = null) : ModelDownloader {
    var calls = 0
    var lastMax = -1L

    override fun download(url: String, dest: File, maxBytes: Long, onBytes: (Long) -> Unit, cancelled: () -> Boolean) {
        calls++
        lastMax = maxBytes
        dest.writeBytes(body)
        onBytes(body.size.toLong())
        failWith?.let { throw it }
    }
}

class ModelInstallTest {
    private fun dir(): File = Files.createTempDirectory("models").toFile().also { it.deleteOnExit() }

    private fun meta(sha: String = GOOD_SHA, size: Long? = GOOD.size.toLong(), url: String? = "https://example.test/m.gguf") =
        ModelMetadata("m", "M", "m.gguf", sha, 2048, size, url)

    private fun repo(d: File, m: ModelMetadata = meta()) = ModelRepository(d).also { it.register(m) }

    @Test
    fun `installs a file whose size and digest match and reports progress`() {
        val d = dir()
        val dl = FakeDownloader()
        var progress = 0L
        val r = repo(d).install("m", dl, { progress = it })
        assertIs<ModelInstallResult.Installed>(r)
        assertEquals(GOOD.size.toLong(), progress)
        assertEquals(GOOD.size.toLong(), dl.lastMax, "download is capped at the expected size")
        assertTrue(repo(d).verify("m") is ModelCheck.Verified)
        assertFalse(File(d, "m.gguf.part").exists())
    }

    @Test
    fun `a digest mismatch is discarded and never becomes loadable`() {
        val d = dir()
        val r = repo(d).install("m", FakeDownloader(body = "tampered!!!!!!!!!!!".toByteArray().copyOf(GOOD.size)))
        assertIs<ModelInstallResult.Failed>(r)
        assertTrue(r.reason.contains("SHA-256 mismatch"))
        assertFalse(File(d, "m.gguf").exists())
        assertFalse(File(d, "m.gguf.part").exists())
    }

    @Test
    fun `a wrong size is rejected before hashing`() {
        val d = dir()
        val r = repo(d).install("m", FakeDownloader(body = GOOD + 1))
        assertIs<ModelInstallResult.Failed>(r)
        assertTrue(r.reason.contains("expected ${GOOD.size}"))
        assertFalse(File(d, "m.gguf").exists())
    }

    @Test
    fun `an already verified model is not downloaded again`() {
        val d = dir()
        File(d, "m.gguf").writeBytes(GOOD)
        val dl = FakeDownloader()
        assertIs<ModelInstallResult.Installed>(repo(d).install("m", dl))
        assertEquals(0, dl.calls)
    }

    @Test
    fun `a corrupt existing file is replaced only by a verified download`() {
        val d = dir()
        File(d, "m.gguf").writeBytes("corrupt".toByteArray())
        assertIs<ModelInstallResult.Installed>(repo(d).install("m", FakeDownloader()))
        assertTrue(repo(d).verify("m") is ModelCheck.Verified)
    }

    @Test
    fun `a failed download leaves the previous file and no part file`() {
        val d = dir()
        File(d, "m.gguf").writeBytes("corrupt".toByteArray())
        val r = repo(d).install("m", FakeDownloader(failWith = IOException("connection reset")))
        assertEquals(ModelInstallResult.Failed("connection reset"), r)
        assertEquals("corrupt", File(d, "m.gguf").readText())
        assertFalse(File(d, "m.gguf.part").exists())
    }

    @Test
    fun `cancellation before the download starts fetches nothing`() {
        val dl = FakeDownloader()
        val r = repo(dir()).install("m", dl, cancelled = { true })
        assertEquals(ModelInstallResult.Failed("Cancelled"), r)
        assertEquals(0, dl.calls)
    }

    @Test
    fun `unknown id and missing url fail without a download`() {
        val dl = FakeDownloader()
        assertIs<ModelInstallResult.Failed>(repo(dir()).install("nope", dl))
        assertIs<ModelInstallResult.Failed>(repo(dir(), meta(url = null)).install("m", dl))
        assertEquals(0, dl.calls)
    }

    @Test
    fun `metadata rejects a non-https url and a non-positive size`() {
        assertFailsWith<IllegalArgumentException> { meta(url = "http://example.test/m.gguf") }
        assertFailsWith<IllegalArgumentException> { meta(size = 0) }
    }

    @Test
    fun `the https downloader refuses a non-https url before connecting`() {
        assertFailsWith<IllegalArgumentException> {
            HttpsModelDownloader().download("http://example.test/x", File.createTempFile("p", ".part"), 10, {}, { false })
        }
    }

    @Test
    fun `the nomic catalog entry is self-consistent`() {
        val s = EmbeddingModelCatalog.NOMIC_EMBED_TEXT_V1_5_Q4_K_M
        assertEquals("Apache-2.0", s.license)
        assertEquals("search_document: ", s.documentPrefix)
        assertEquals("search_query: ", s.queryPrefix)
        assertTrue(s.metadata.url!!.contains("0188c9bf409793f810680a5a431e7b899c46104c"), "URL is pinned to a revision, not a branch")
    }

    /**
     * Real network + real 84 MB file. Skipped unless DCA_NETWORK_TESTS=1, so CI and offline runs never hit the Hub.
     * It is the only check that the pinned URL, size and digest are still true, and that redirects work.
     */
    @Test
    fun `the real catalog model downloads and verifies from the Hub`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("DCA_NETWORK_TESTS") == "1")
        val d = dir()
        val spec = EmbeddingModelCatalog.NOMIC_EMBED_TEXT_V1_5_Q4_K_M
        val repo = ModelRepository(d).also { it.register(spec.metadata) }
        val r = repo.install(spec.metadata.id, HttpsModelDownloader())
        assertIs<ModelInstallResult.Installed>(r, r.toString())
        assertTrue(repo.verify(spec.metadata.id) is ModelCheck.Verified)
        d.deleteRecursively()
    }
}
