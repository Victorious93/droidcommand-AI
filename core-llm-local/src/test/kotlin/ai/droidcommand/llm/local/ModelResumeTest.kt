package ai.droidcommand.llm.local

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
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

private val BODY = ByteArray(200_000) { (it * 31 % 251).toByte() }
private val BODY_SHA = ModelRepository.sha256Hex(ByteArrayInputStream(BODY))

/** Appends the missing tail like a real resuming downloader; optionally dies after [dieAfter] bytes. */
private class ResumingFake(var dieAfter: Int = Int.MAX_VALUE) : ModelDownloader {
    var resumedFrom = -1L

    override fun download(url: String, dest: File, maxBytes: Long, onBytes: (Long) -> Unit, cancelled: () -> Boolean) {
        resumedFrom = if (dest.isFile) dest.length() else 0L
        val chunk = BODY.copyOfRange(resumedFrom.toInt(), minOf(BODY.size.toLong(), resumedFrom + dieAfter).toInt())
        dest.appendBytes(chunk)
        onBytes(chunk.size.toLong())
        if (resumedFrom + chunk.size < BODY.size) throw IOException("connection reset")
    }
}

class ModelResumeTest {
    private val servers = mutableListOf<HttpServer>()

    @AfterTest fun stop() = servers.forEach { it.stop(0) }

    private fun dir(): File = Files.createTempDirectory("models").toFile().also { it.deleteOnExit() }

    private fun repo(d: File) = ModelRepository(d).also {
        it.register(ModelMetadata("m", "M", "m.gguf", BODY_SHA, 2048, BODY.size.toLong(), "https://example.test/m.gguf"))
    }

    /** Local HTTP server: honours Range when [ranges], else always answers 200 with the whole body. */
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

    @Test
    fun `an interrupted install keeps the prefix and the next call finishes it`() {
        val d = dir()
        val first = repo(d).install("m", ResumingFake(dieAfter = 70_000))
        assertEquals(ModelInstallResult.Failed("connection reset", retryable = true), first)
        assertEquals(70_000L, File(d, "m.gguf.part").length())

        val fake = ResumingFake()
        var last = 0L
        val second = repo(d).install("m", fake, { last = it })
        assertIs<ModelInstallResult.Installed>(second)
        assertEquals(70_000L, fake.resumedFrom)
        assertEquals(BODY.size.toLong(), last, "progress includes the resumed prefix")
        assertTrue(repo(d).verify("m") is ModelCheck.Verified)
        assertFalse(File(d, "m.gguf.part").exists())
    }

    @Test
    fun `a resumed prefix that is wrong fails the digest and is discarded`() {
        val d = dir()
        File(d, "m.gguf.part").writeBytes(ByteArray(1000) { 7 })
        val r = repo(d).install("m", ResumingFake())
        assertIs<ModelInstallResult.Failed>(r)
        assertFalse(File(d, "m.gguf.part").exists(), "a bad file is not resumed again")
        assertIs<ModelInstallResult.Installed>(repo(d).install("m", ResumingFake()))
    }

    @Test
    fun `an oversized part file is discarded and a complete one is verified without downloading`() {
        val d = dir()
        File(d, "m.gguf.part").writeBytes(ByteArray(BODY.size + 5))
        val fake = ResumingFake()
        assertIs<ModelInstallResult.Installed>(repo(d).install("m", fake))
        assertEquals(0L, fake.resumedFrom)

        val d2 = dir()
        File(d2, "m.gguf.part").writeBytes(BODY)
        val never = ModelDownloader { _, _, _, _, _ -> error("must not download") }
        assertIs<ModelInstallResult.Installed>(repo(d2).install("m", never))
    }

    @Test
    fun `http resume sends Range and appends the 206 tail`() {
        val (url, seen) = server()
        val dest = File(dir(), "x.part").also { it.writeBytes(BODY.copyOf(50_000)) }
        var n = 0L
        HttpsModelDownloader(httpsOnly = false).download(url, dest, BODY.size.toLong(), { n += it }, { false })
        assertEquals(listOf<String?>("bytes=50000-"), seen)
        assertContentEquals(BODY, dest.readBytes())
        assertEquals(BODY.size - 50_000L, n, "onBytes counts only this call")
    }

    @Test
    fun `a server that ignores Range restarts from zero instead of corrupting the file`() {
        val (url, _) = server(ranges = false)
        val dest = File(dir(), "x.part").also { it.writeBytes(ByteArray(50_000) { 9 }) }
        HttpsModelDownloader(httpsOnly = false).download(url, dest, BODY.size.toLong(), {}, { false })
        assertContentEquals(BODY, dest.readBytes())
    }

    @Test
    fun `a 416 discards the partial file and fails so the next try starts fresh`() {
        val (url, _) = server(rangeStatus = 416)
        val dest = File(dir(), "x.part").also { it.writeBytes(ByteArray(10)) }
        assertFailsWith<IOException> { HttpsModelDownloader(httpsOnly = false).download(url, dest, BODY.size.toLong(), {}, { false }) }
        assertFalse(dest.exists())
    }

    @Test
    fun `the byte cap counts a resumed prefix`() {
        val (url, _) = server()
        val dest = File(dir(), "x.part").also { it.writeBytes(BODY.copyOf(50_000)) }
        assertFailsWith<IOException> { HttpsModelDownloader(httpsOnly = false).download(url, dest, 100_000, {}, { false }) }
    }
}
