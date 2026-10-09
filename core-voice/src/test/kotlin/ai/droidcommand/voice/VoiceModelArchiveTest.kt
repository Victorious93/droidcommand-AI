package ai.droidcommand.voice

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VoiceModelArchiveTest {
    private val root: File = Files.createTempDirectory("voicearchive").toFile().also { it.deleteOnExit() }
    private val url = "https://example.test/model.tar.bz2"

    private fun sha(bytes: ByteArray) = VoiceModelRepository.sha256Hex(bytes.inputStream())

    private fun tarBz2(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(out)).use { tar ->
            for ((name, body) in entries) {
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = body.size.toLong() })
                tar.write(body)
                tar.closeArchiveEntry()
            }
        }
        return out.toByteArray()
    }

    private val model = "onnx-bytes".toByteArray()
    private val tokens = "tokens-bytes".toByteArray()
    private val extra = "not wanted".toByteArray()

    private fun archive() = tarBz2(listOf("pkg/model.onnx" to model, "pkg/README.md" to extra, "pkg/tokens.txt" to tokens))

    private fun spec(archive: ByteArray = archive(), modelSha: String = sha(model)) = VoiceModel(
        "kws",
        "Test model",
        listOf(
            VoiceFile("model.onnx", url, modelSha, archiveEntry = "pkg/model.onnx"),
            VoiceFile("tokens.txt", url, sha(tokens), archiveEntry = "pkg/tokens.txt"),
        ),
        license = "test-only",
        archiveSha256 = sha(archive),
    )

    private class Serving(val body: ByteArray) : FileDownloader {
        var calls = 0

        override fun download(url: String, dest: File, onBytes: (Long) -> Unit, cancelled: () -> Boolean) {
            calls++
            dest.writeBytes(body)
            onBytes(body.size.toLong())
        }
    }

    private fun repo(served: ByteArray, m: VoiceModel) = VoiceModelRepository(root, Serving(served)).apply { register(m) }

    @Test
    fun extracts_only_the_named_entries_and_verifies_them() {
        val a = archive()
        val r = repo(a, spec(a))
        assertIs<InstallResult.Installed>(r.install("kws"))
        assertIs<VoiceModelStatus.Verified>(r.status("kws"))
        assertEquals(setOf("model.onnx", "tokens.txt"), File(root, "kws").list()!!.toSet())
    }

    @Test
    fun a_tampered_archive_is_rejected_before_it_is_opened() {
        val good = archive()
        val r = repo(good + 1, spec(good))
        val result = assertIs<InstallResult.Failed>(r.install("kws"))
        assertTrue("archive: SHA-256 mismatch" in result.reason)
        assertEquals(emptyList(), File(root, "kws").list()!!.toList())
    }

    @Test
    fun an_entry_whose_digest_differs_is_discarded() {
        val a = archive()
        val r = repo(a, spec(a, modelSha = sha("something else".toByteArray())))
        assertIs<InstallResult.Failed>(r.install("kws"))
        assertFalse(File(root, "kws/model.onnx").exists())
        assertFalse(File(root, "kws/model.onnx.part").exists())
    }

    @Test
    fun a_missing_entry_is_a_failure() {
        val a = tarBz2(listOf("pkg/model.onnx" to model))
        val result = assertIs<InstallResult.Failed>(repo(a, spec(a)).install("kws"))
        assertTrue("tokens.txt" in result.reason)
    }

    @Test
    fun entry_paths_cannot_choose_where_files_land() {
        val outside = File(root.parentFile, "escaped.txt").also { it.delete() }
        val a = tarBz2(listOf("../escaped.txt" to extra, "pkg/model.onnx" to model, "pkg/tokens.txt" to tokens))
        assertIs<InstallResult.Installed>(repo(a, spec(a)).install("kws"))
        assertFalse(outside.exists())
    }

    @Test
    fun an_already_verified_model_is_not_downloaded_again() {
        val a = archive()
        val d = Serving(a)
        val r = VoiceModelRepository(root, d).apply { register(spec(a)) }
        r.install("kws")
        r.install("kws")
        assertEquals(1, d.calls)
    }

    @Test
    fun mixed_or_incomplete_archive_specs_are_rejected() {
        val a = archive()
        val entry = VoiceFile("model.onnx", url, sha(model), archiveEntry = "pkg/model.onnx")
        val plain = VoiceFile("tokens.txt", url, sha(tokens))
        assertFailsWith<IllegalArgumentException> { VoiceModel("m", "M", listOf(entry, plain), "x", sha(a)) }
        assertFailsWith<IllegalArgumentException> { VoiceModel("m", "M", listOf(entry), "x", archiveSha256 = null) }
        assertFailsWith<IllegalArgumentException> { VoiceModel("m", "M", listOf(plain), "x", archiveSha256 = sha(a)) }
    }
}
