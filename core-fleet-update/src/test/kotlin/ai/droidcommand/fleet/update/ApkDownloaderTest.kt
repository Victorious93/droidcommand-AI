package ai.droidcommand.fleet.update

import com.sun.net.httpserver.HttpServer
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
import kotlin.test.assertTrue

/** A stand-in APK: the zip signature, then a pattern. */
private val APK = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(180_000) { (it * 37 % 251).toByte() }
private val APK_SHA = ApkVerifier.sha256Hex(APK)

class ApkDownloaderTest {
    private val servers = mutableListOf<HttpServer>()
    private val dir: File = Files.createTempDirectory("apkdl").toFile().also { it.deleteOnExit() }

    @AfterTest fun stop() = servers.forEach { it.stop(0) }

    private class Served(val base: String, val ranges: MutableList<String?>)

    /** Serves [body] at /apk. [ranges]: honour Range. [status416]: answer 416 to any Range. Also /moved redirects to /apk. */
    private fun serve(body: ByteArray = APK, ranges: Boolean = true, status416: Boolean = false, wrongRangeStart: Boolean = false): Served {
        val seen = mutableListOf<String?>()
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/apk") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            seen += range
            val start = range?.removePrefix("bytes=")?.removeSuffix("-")?.toIntOrNull()
            if (range != null && ranges && status416) {
                ex.sendResponseHeaders(416, -1)
            } else if (range != null && ranges && start != null) {
                // [wrongRangeStart] claims to continue from byte 0 whatever was asked, as a broken proxy might.
                ex.responseHeaders.add("Content-Range", "bytes ${if (wrongRangeStart) 0 else start}-${body.size - 1}/${body.size}")
                ex.sendResponseHeaders(206, (body.size - start).toLong())
                ex.responseBody.use { it.write(body, start, body.size - start) }
            } else {
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            ex.close()
        }
        s.createContext("/moved") { ex ->
            ex.responseHeaders.add("Location", "/apk")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        s.start()
        servers += s
        return Served("http://127.0.0.1:${s.address.port}", seen)
    }

    private fun downloader(
        maxBytes: Long = ApkDownloader.DEFAULT_MAX_BYTES,
        allowRedirect: (String, String) -> Boolean = { _, _ -> true },
    ) = ApkDownloader(dir, maxBytes = maxBytes, allowAsset = { _, _ -> true }, allowRedirect = allowRedirect, httpsOnly = false)

    private fun asset(url: String, sha: String? = APK_SHA, size: Long = APK.size.toLong()) = ReleaseAsset("app.apk", url, size, sha)

    /** What an earlier stopped download of [url] leaves behind: the prefix and the record of which asset it came from. */
    private fun keepPartial(prefix: ByteArray, url: String) {
        File(dir, "pkg.apk.part").writeBytes(prefix)
        File(dir, "pkg.apk.part.src").writeText(url)
    }

    @Test
    fun a_full_download_is_verified_moved_into_place_and_reports_progress_to_the_total() {
        val s = serve()
        val progress = mutableListOf<Pair<Long, Long>>()
        val result = downloader().download("o/r", "pkg", asset("${s.base}/apk"), { r, t -> progress += r to t })
        assertContentEquals(APK, result.file.readBytes())
        assertEquals(APK_SHA, result.sha256)
        assertTrue(result.digestChecked)
        assertFalse(File(dir, "pkg.apk.part").exists())
        assertEquals(APK.size.toLong() to APK.size.toLong(), progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> a.first <= b.first })
    }

    @Test
    fun stopping_keeps_the_prefix_and_the_next_call_resumes_it_and_still_verifies_the_whole_file() {
        val s = serve()
        val d = downloader()
        var received = 0L
        assertFailsWith<ApkDownloadStopped> {
            d.download("o/r", "pkg", asset("${s.base}/apk"), { r, _ -> received = r }, cancelled = { received > 0 })
        }
        val kept = d.partialBytes("pkg")
        assertTrue(kept > 0 && kept < APK.size, "a prefix is kept")
        assertFalse(File(dir, "pkg.apk").exists())

        val result = d.download("o/r", "pkg", asset("${s.base}/apk"))
        assertEquals(listOf<String?>(null, "bytes=$kept-"), s.ranges, "the second call asked only for the rest")
        assertContentEquals(APK, result.file.readBytes())
        assertTrue(result.digestChecked, "the published checksum covered the whole file, resumed prefix included")
        assertEquals(0L, d.partialBytes("pkg"))
    }

    @Test
    fun discardPartial_throws_the_prefix_away_and_the_next_download_starts_from_zero() {
        val s = serve()
        val d = downloader()
        var received = 0L
        assertFailsWith<ApkDownloadStopped> {
            d.download("o/r", "pkg", asset("${s.base}/apk"), { r, _ -> received = r }, cancelled = { received > 0 })
        }
        assertTrue(d.discardPartial("pkg"))
        assertEquals(0L, d.partialBytes("pkg"))
        assertFalse(d.discardPartial("pkg"), "nothing left")
        d.download("o/r", "pkg", asset("${s.base}/apk"))
        assertEquals(listOf<String?>(null, null), s.ranges, "no range was asked for after a discard")
    }

    @Test
    fun a_partial_of_one_release_is_never_resumed_into_another_release() {
        // Two different APKs for the same package, as two releases of one app are.
        val other = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(180_000) { ((it * 41 + 7) % 251).toByte() }
        val a = serve(body = APK)
        val b = serve(body = other)
        val d = downloader()
        var received = 0L
        assertFailsWith<ApkDownloadStopped> {
            d.download("o/r", "pkg", asset("${a.base}/apk"), { r, _ -> received = r }, cancelled = { received > 0 })
        }
        assertTrue(d.partialBytes("pkg") > 0)
        assertEquals("${a.base}/apk", d.partialSource("pkg"))

        // Picking the other release must start from zero (no Range request), not append its bytes to the first one's prefix.
        val result = d.download("o/r", "pkg", asset("${b.base}/apk", sha = ApkVerifier.sha256Hex(other), size = other.size.toLong()))
        assertEquals(listOf<String?>(null), b.ranges, "no range was asked for: the other release's partial was discarded")
        assertContentEquals(other, result.file.readBytes())
        assertTrue(result.digestChecked)
        assertEquals(null, d.partialSource("pkg"))
    }

    @Test
    fun a_partial_with_no_record_of_its_asset_is_not_resumed() {
        val s = serve()
        val d = downloader()
        File(dir, "pkg.apk.part").writeBytes(APK.copyOfRange(0, 5_000)) // as an older build left it: no .src record
        assertEquals(null, d.partialSource("pkg"))
        val result = d.download("o/r", "pkg", asset("${s.base}/apk"))
        assertEquals(listOf<String?>(null), s.ranges)
        assertContentEquals(APK, result.file.readBytes())
    }

    @Test
    fun partialSource_names_the_asset_of_a_kept_prefix_and_is_gone_after_a_discard() {
        val s = serve()
        val d = downloader()
        var received = 0L
        assertFailsWith<ApkDownloadStopped> {
            d.download("o/r", "pkg", asset("${s.base}/apk"), { r, _ -> received = r }, cancelled = { received > 0 })
        }
        assertEquals("${s.base}/apk", d.partialSource("pkg"))
        d.discardPartial("pkg")
        assertEquals(null, d.partialSource("pkg"))
        assertFalse(File(dir, "pkg.apk.part.src").exists())
    }

    @Test
    fun a_server_that_ignores_the_range_restarts_the_file_and_the_result_is_still_correct() {
        val s = serve(ranges = false)
        val d = downloader()
        keepPartial(ByteArray(5_000) { 9 }, "${s.base}/apk")
        val result = d.download("o/r", "pkg", asset("${s.base}/apk"))
        assertContentEquals(APK, result.file.readBytes())
    }

    @Test
    fun a_rejected_resume_deletes_the_partial_and_fails() {
        val s = serve(status416 = true)
        val d = downloader()
        keepPartial(APK.copyOfRange(0, 5_000), "${s.base}/apk")
        assertFailsWith<IOException> { d.download("o/r", "pkg", asset("${s.base}/apk")) }
        assertEquals(0L, d.partialBytes("pkg"))
    }

    @Test
    fun a_206_that_does_not_continue_where_we_left_off_discards_the_partial_instead_of_failing_every_retry() {
        val s = serve(wrongRangeStart = true)
        val d = downloader()
        keepPartial(APK.copyOfRange(0, 5_000), "${s.base}/apk")
        assertFailsWith<IOException> { d.download("o/r", "pkg", asset("${s.base}/apk")) }
        assertEquals(0L, d.partialBytes("pkg"))
        // The retry starts clean and succeeds.
        val result = d.download("o/r", "pkg", asset("${s.base}/apk"))
        assertContentEquals(APK, result.file.readBytes())
    }

    @Test
    fun a_download_that_does_not_match_the_published_checksum_is_deleted_not_kept() {
        val s = serve()
        val d = downloader()
        val e = assertFailsWith<IllegalStateException> { d.download("o/r", "pkg", asset("${s.base}/apk", sha = "0".repeat(64))) }
        assertTrue("does not match the SHA-256" in e.message.orEmpty())
        assertEquals(0L, d.partialBytes("pkg"))
        assertFalse(File(dir, "pkg.apk").exists())
    }

    @Test
    fun an_html_page_saved_as_apk_is_refused_and_deleted() {
        val page = "<html>rate limited</html>".toByteArray()
        val s = serve(body = page)
        val d = downloader()
        val e = assertFailsWith<IllegalStateException> { d.download("o/r", "pkg", asset("${s.base}/apk", sha = null, size = page.size.toLong())) }
        assertTrue("not an APK" in e.message.orEmpty())
        assertEquals(0L, d.partialBytes("pkg"))
    }

    @Test
    fun a_file_over_the_limit_is_refused() {
        val s = serve()
        val d = downloader(maxBytes = 50_000)
        val e = assertFailsWith<IllegalStateException> { d.download("o/r", "pkg", asset("${s.base}/apk")) }
        assertTrue("limit" in e.message.orEmpty())
        assertEquals(0L, d.partialBytes("pkg"))
    }

    @Test
    fun redirects_are_followed_only_where_the_policy_allows() {
        val s = serve()
        val ok = downloader().download("o/r", "pkg", asset("${s.base}/moved"))
        assertContentEquals(APK, ok.file.readBytes())
        val e = assertFailsWith<IllegalStateException> {
            downloader(allowRedirect = { _, _ -> false }).download("o/r", "pkg", asset("${s.base}/moved"))
        }
        assertTrue("not allowed" in e.message.orEmpty())
    }

    @Test
    fun an_address_the_policy_refuses_is_never_fetched() {
        val d = ApkDownloader(dir, httpsOnly = false)
        val e = assertFailsWith<IllegalArgumentException> { d.download("o/r", "pkg", asset("http://127.0.0.1:1/apk")) }
        assertTrue("not allowed" in e.message.orEmpty())
    }
}
