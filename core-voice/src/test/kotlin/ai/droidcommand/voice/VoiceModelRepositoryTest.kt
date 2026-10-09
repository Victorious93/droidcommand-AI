package ai.droidcommand.voice

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VoiceModelRepositoryTest {
    private val dir: File = Files.createTempDirectory("voicemodels").toFile().also { it.deleteOnExit() }

    private fun sha(bytes: ByteArray) = VoiceModelRepository.sha256Hex(bytes.inputStream())

    private val a = "model-bytes".toByteArray()
    private val b = "tokens-bytes".toByteArray()

    private fun model() = VoiceModel(
        "v1",
        "Voice 1",
        listOf(VoiceFile("m.onnx", "https://example.test/m", sha(a)), VoiceFile("tokens.txt", "https://example.test/t", sha(b))),
        license = "test-only",
    )

    /** Serves bytes by URL; can be told to corrupt, fail, or cancel. */
    private class FakeDownloader(val bodies: Map<String, ByteArray>) : FileDownloader {
        val requested = mutableListOf<String>()
        var failOn: String? = null
        var corrupt = false

        override fun download(url: String, dest: File, onBytes: (Long) -> Unit, cancelled: () -> Boolean) {
            requested += url
            if (url == failOn) throw IOException("network down")
            val body = bodies.getValue(url).let { if (corrupt) it + 1 else it }
            dest.writeBytes(body)
            onBytes(body.size.toLong())
        }
    }

    private fun downloader() = FakeDownloader(mapOf("https://example.test/m" to a, "https://example.test/t" to b))

    @Test
    fun install_downloads_verifies_and_reports_verified() {
        val repo = VoiceModelRepository(dir, downloader()).apply { register(model()) }
        assertEquals(VoiceModelStatus.NotInstalled, repo.status("v1"))
        val r = repo.install("v1")
        assertIs<InstallResult.Installed>(r)
        assertIs<VoiceModelStatus.Verified>(repo.status("v1"))
        assertTrue(File(dir, "v1").listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test
    fun a_digest_mismatch_is_discarded_and_never_loadable() {
        val d = downloader().apply { corrupt = true }
        val repo = VoiceModelRepository(dir, d).apply { register(model()) }
        val r = repo.install("v1")
        assertIs<InstallResult.Failed>(r)
        assertFalse(File(dir, "v1/m.onnx").exists())
        assertEquals(VoiceModelStatus.NotInstalled, repo.status("v1"))
    }

    @Test
    fun a_corrupted_installed_file_is_reported_mismatch() {
        val repo = VoiceModelRepository(dir, downloader()).apply { register(model()) }
        repo.install("v1")
        File(dir, "v1/tokens.txt").writeText("tampered")
        assertIs<VoiceModelStatus.Mismatch>(repo.status("v1"))
    }

    @Test
    fun a_network_failure_keeps_earlier_files_and_resumes_only_what_is_missing() {
        val d = downloader().apply { failOn = "https://example.test/t" }
        val repo = VoiceModelRepository(dir, d).apply { register(model()) }
        assertIs<InstallResult.Failed>(repo.install("v1"))
        assertEquals(VoiceModelStatus.Partial(listOf("tokens.txt")), repo.status("v1"))
        d.failOn = null
        d.requested.clear()
        assertIs<InstallResult.Installed>(repo.install("v1"))
        assertEquals(listOf("https://example.test/t"), d.requested, "the verified file must not be re-downloaded")
    }

    @Test
    fun cancel_before_download_fails_cleanly() {
        val d = downloader()
        val repo = VoiceModelRepository(dir, d).apply { register(model()) }
        val r = repo.install("v1", cancelled = { true })
        assertEquals(InstallResult.Failed("Cancelled"), r)
        assertTrue(d.requested.isEmpty())
    }

    @Test
    fun delete_removes_the_model() {
        val repo = VoiceModelRepository(dir, downloader()).apply { register(model()) }
        repo.install("v1")
        assertTrue(repo.delete("v1"))
        assertEquals(VoiceModelStatus.NotInstalled, repo.status("v1"))
    }

    @Test
    fun unknown_model_install_fails_without_throwing() {
        val repo = VoiceModelRepository(dir, downloader())
        assertIs<InstallResult.Failed>(repo.install("nope"))
        assertFailsWith<NoSuchElementException> { repo.status("nope") }
    }

    @Test
    fun entries_are_validated() {
        assertFailsWith<IllegalArgumentException> { VoiceFile("../evil", "https://x.test/a", sha(a)) }
        assertFailsWith<IllegalArgumentException> { VoiceFile("ok", "http://x.test/a", sha(a)) }
        assertFailsWith<IllegalArgumentException> { VoiceFile("ok", "https://x.test/a", "ABC") }
        assertFailsWith<IllegalArgumentException> { VoiceModel("../x", "n", listOf(VoiceFile("f", "https://x.test/a", sha(a))), "l") }
        assertFailsWith<IllegalArgumentException> { VoiceModel("x", "n", listOf(VoiceFile("f", "https://x.test/a", sha(a))), " ") }
    }
}
