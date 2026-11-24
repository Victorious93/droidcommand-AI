package ai.droidcommand.voice

import com.sun.net.httpserver.HttpServer
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val BODY = ByteArray(150_000) { (it * 29 % 251).toByte() }

/** Stop, resume and discard on the voice downloader, tested against a real local HTTP server. */
class HttpsFileDownloaderResumeTest {
    private val servers = mutableListOf<HttpServer>()

    @AfterTest fun stop() = servers.forEach { it.stop(0) }

    private fun newDest(): File = Files.createTempFile("voice", ".part").toFile().also {
        it.delete()
        it.deleteOnExit()
    }

    /** Honours Range when [ranges] (answering [rangeStatus] for the 416 case), otherwise always answers 200 with everything. */
    private fun server(ranges: Boolean = true, rangeStatus: Int = 206): Pair<String, MutableList<String?>> {
        val seen = mutableListOf<String?>()
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/f") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            seen += range
            val start = range?.removePrefix("bytes=")?.removeSuffix("-")?.toIntOrNull()
            if (range != null && ranges && rangeStatus == 416) {
                ex.sendResponseHeaders(416, -1)
            } else if (range != null && ranges && start != null) {
                ex.responseHeaders.add("Content-Range", "bytes $start-${BODY.size - 1}/${BODY.size}")
                ex.sendResponseHeaders(206, (BODY.size - start).toLong())
                ex.responseBody.use { it.write(BODY, start, BODY.size - start) }
            } else {
                ex.sendResponseHeaders(200, BODY.size.toLong())
                ex.responseBody.use { it.write(BODY) }
            }
            ex.close()
        }
        s.start()
        servers += s
        return "http://127.0.0.1:${s.address.port}/f" to seen
    }

    private fun downloader() = HttpsFileDownloader(maxBytes = 10_000_000L, httpsOnly = false)

    @Test
    fun a_partial_file_is_resumed_with_a_range_request_and_only_the_tail_is_fetched() {
        val (url, seen) = server()
        val dest = newDest().also { it.writeBytes(BODY.copyOfRange(0, 40_000)) }
        var fetched = 0L
        downloader().download(url, dest, { fetched += it }, { false })
        assertContentEquals(BODY, dest.readBytes())
        assertEquals(BODY.size - 40_000L, fetched)
        assertEquals(listOf<String?>("bytes=40000-"), seen)
    }

    @Test
    fun a_server_that_ignores_the_range_restarts_the_file_from_zero() {
        val (url, _) = server(ranges = false)
        val dest = newDest().also { it.writeBytes(ByteArray(40_000) { 7 }) }
        var fetched = 0L
        downloader().download(url, dest, { fetched += it }, { false })
        assertContentEquals(BODY, dest.readBytes())
        assertEquals(BODY.size.toLong(), fetched)
    }

    @Test
    fun a_rejected_resume_deletes_the_partial_and_fails_so_the_next_try_starts_clean() {
        val (url, _) = server(rangeStatus = 416)
        val dest = newDest().also { it.writeBytes(BODY.copyOfRange(0, 40_000)) }
        assertFailsWith<IOException> { downloader().download(url, dest, {}, { false }) }
        assertFalse(dest.exists())
    }

    @Test
    fun stopping_midway_keeps_what_was_written_so_far() {
        val (url, _) = server()
        val dest = newDest()
        var fetched = 0L
        assertFailsWith<IOException> {
            downloader().download(url, dest, { fetched += it }, { fetched > 0 })
        }
        assertTrue(dest.isFile && dest.length() > 0 && dest.length() < BODY.size, "a prefix is kept for the next call")
        // And that prefix resumes to the full file.
        downloader().download(url, dest, {}, { false })
        assertContentEquals(BODY, dest.readBytes())
    }

    @Test
    fun the_default_downloader_still_refuses_plain_http() {
        assertFailsWith<IllegalArgumentException> { HttpsFileDownloader().download("http://example.test/x", newDest(), {}, { false }) }
    }
}

/** Stop keeps the partial, resume continues it, Cancel discards it, and a bad finished file is never kept. */
class VoiceDownloadControlTest {
    private val root: File = Files.createTempDirectory("voicestop").toFile().also { it.deleteOnExit() }

    private fun sha(bytes: ByteArray) = VoiceModelRepository.sha256Hex(bytes.inputStream())

    private val body = ByteArray(10_000) { (it * 13 % 253).toByte() }

    private fun model() = VoiceModel("v1", "Voice", listOf(VoiceFile("m.onnx", "https://example.test/m", sha(body))), license = "test-only")

    /** Appends the next [chunk] bytes after what `dest` already holds, then reports a stop until [chunk] covers the rest. */
    private class Slicing(val body: ByteArray, var chunk: Long) : FileDownloader {
        var calls = 0
        var resumedFrom = -1L

        override fun download(url: String, dest: File, onBytes: (Long) -> Unit, cancelled: () -> Boolean) {
            calls++
            resumedFrom = if (dest.isFile) dest.length() else 0L
            val to = minOf(body.size.toLong(), resumedFrom + chunk).toInt()
            dest.appendBytes(body.copyOfRange(resumedFrom.toInt(), to))
            onBytes(to - resumedFrom)
            if (to < body.size) throw IOException("Cancelled")
        }
    }

    private fun repo(d: FileDownloader) = VoiceModelRepository(root, d).apply { register(model()) }

    @Test
    fun a_stopped_download_keeps_its_prefix_and_reports_it() {
        val repo = repo(Slicing(body, chunk = 4_000))
        assertEquals(0L, repo.partialBytes("v1"))
        assertIs<InstallResult.Failed>(repo.install("v1"))
        assertEquals(4_000L, repo.partialBytes("v1"))
        assertTrue(File(root, "v1/m.onnx.part").isFile)
        assertFalse(File(root, "v1/m.onnx").exists())
    }

    @Test
    fun the_next_install_resumes_from_the_prefix_and_reports_progress_including_it() {
        val d = Slicing(body, chunk = 4_000)
        val repo = repo(d)
        repo.install("v1")
        d.chunk = Long.MAX_VALUE / 2
        val progress = mutableListOf<Long>()
        assertIs<InstallResult.Installed>(repo.install("v1", onProgress = { progress += it }))
        assertEquals(4_000L, d.resumedFrom, "continued after the kept bytes")
        assertEquals(4_000L, progress.first(), "progress starts at the resumed prefix")
        assertEquals(body.size.toLong(), progress.last())
        assertEquals(0L, repo.partialBytes("v1"))
    }

    @Test
    fun discardPartial_removes_the_prefix_and_the_next_install_starts_from_zero() {
        val d = Slicing(body, chunk = 4_000)
        val repo = repo(d)
        repo.install("v1")
        assertTrue(repo.discardPartial("v1"))
        assertEquals(0L, repo.partialBytes("v1"))
        assertFalse(repo.discardPartial("v1"), "nothing left to remove")
        d.chunk = Long.MAX_VALUE / 2
        repo.install("v1")
        assertEquals(0L, d.resumedFrom)
    }

    @Test
    fun discardPartial_never_touches_an_installed_model_and_unknown_ids_are_harmless() {
        val repo = repo(Slicing(body, chunk = Long.MAX_VALUE / 2))
        assertIs<InstallResult.Installed>(repo.install("v1"))
        assertFalse(repo.discardPartial("v1"))
        assertTrue(File(root, "v1/m.onnx").isFile)
        assertFalse(repo.discardPartial("nope"))
        assertEquals(0L, repo.partialBytes("nope"))
    }

    @Test
    fun a_finished_file_with_the_wrong_digest_is_discarded_not_kept_for_resume() {
        // Serves one byte too many, so the finished file does not match the pinned digest.
        val repo = repo(Slicing(body + 1, chunk = Long.MAX_VALUE / 2))
        val result = assertIs<InstallResult.Failed>(repo.install("v1"))
        assertTrue("SHA-256 mismatch" in result.reason)
        assertEquals(0L, repo.partialBytes("v1"))
    }

    @Test
    fun a_stopped_archive_download_keeps_archive_part_and_resumes() {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(out)).use { tar ->
            tar.putArchiveEntry(TarArchiveEntry("pkg/model.onnx").apply { size = body.size.toLong() })
            tar.write(body)
            tar.closeArchiveEntry()
        }
        val archive = out.toByteArray()
        val spec = VoiceModel(
            "kws",
            "Archive model",
            listOf(VoiceFile("model.onnx", "https://example.test/a.tar.bz2", sha(body), archiveEntry = "pkg/model.onnx")),
            license = "test-only",
            archiveSha256 = sha(archive),
        )
        val d = Slicing(archive, chunk = 100)
        val repo = VoiceModelRepository(root, d).apply { register(spec) }
        assertIs<InstallResult.Failed>(repo.install("kws"))
        assertEquals(100L, repo.partialBytes("kws"))
        assertTrue(File(root, "kws/archive.part").isFile)
        d.chunk = Long.MAX_VALUE / 2
        assertIs<InstallResult.Installed>(repo.install("kws"))
        assertEquals(100L, d.resumedFrom)
        assertEquals(0L, repo.partialBytes("kws"))
        assertFalse(File(root, "kws/archive.part").exists())
    }
}
