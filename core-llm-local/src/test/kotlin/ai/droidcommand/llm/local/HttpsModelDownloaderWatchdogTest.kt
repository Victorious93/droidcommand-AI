package ai.droidcommand.llm.local

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HttpsModelDownloaderWatchdogTest {
    private val servers = mutableListOf<HttpServer>()
    private val rawSockets = mutableListOf<ServerSocket>()
    private val release = CountDownLatch(1)

    @AfterTest fun stop() {
        release.countDown()
        servers.forEach { it.stop(0) }
        rawSockets.forEach { runCatching { it.close() } }
    }

    /** Accepts the TCP connection but never writes a response, so the client is stuck waiting on headers. */
    private fun silentServer(): String {
        val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        rawSockets += ss
        Thread(
            {
                runCatching {
                    val socket = ss.accept()
                    release.await()
                    socket.close()
                }
            },
            "silent-server",
        ).apply { isDaemon = true }.start()
        return "http://127.0.0.1:${ss.localPort}/f"
    }

    /** Sends [first] bytes then blocks (or drips 1 byte per [dripMs]) until the test ends. */
    private fun server(first: Int, dripMs: Long = 0): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/f") { ex ->
            ex.sendResponseHeaders(200, 10_000_000)
            val out = ex.responseBody
            runCatching {
                out.write(ByteArray(first))
                out.flush()
                if (dripMs == 0L) {
                    release.await()
                } else {
                    while (true) {
                        Thread.sleep(dripMs)
                        out.write(1)
                        out.flush()
                    }
                }
            }
            runCatching { ex.close() }
        }
        s.executor = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        s.start()
        servers += s
        return "http://127.0.0.1:${s.address.port}/f"
    }

    private fun dest() = File(Files.createTempDirectory("dl").toFile(), "m.part").also { it.parentFile.deleteOnExit() }

    // Socket read timeout is far above every test's budget, so only the watchdog can end these downloads.
    private fun downloader() = HttpsModelDownloader(httpsOnly = false, stallMs = 600, minBytesPerWindow = 1000, pollMs = 50)

    @Test
    fun `a stalled read is aborted and the received prefix is kept`() {
        val d = dest()
        val t0 = System.nanoTime()
        val e = assertFailsWith<IOException> { downloader().download(server(5000), d, 1L shl 30, {}, { false }) }
        assertTrue(e.message!!.startsWith("Download stalled"), e.message)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms < 10_000, "took $ms ms")
        assertEquals(5000L, d.length())
    }

    @Test
    fun `a slow drip below the minimum rate is aborted`() {
        val e = assertFailsWith<IOException> {
            downloader().download(server(0, dripMs = 20), dest(), 1L shl 30, {}, { false })
        }
        assertTrue(e.message!!.startsWith("Download stalled"), e.message)
    }

    @Test
    fun `cancellation interrupts a blocked read promptly`() {
        val flag = AtomicBoolean(false)
        Thread {
            Thread.sleep(300)
            flag.set(true)
        }.start()
        val t0 = System.nanoTime()
        val e = assertFailsWith<IOException> { downloader().download(server(100), dest(), 1L shl 30, {}, { flag.get() }) }
        assertEquals("Cancelled", e.message)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms < 5_000, "took $ms ms")
    }

    @Test
    fun `cancellation during the response-header wait is prompt`() {
        val flag = AtomicBoolean(false)
        Thread {
            Thread.sleep(300)
            flag.set(true)
        }.start()
        val t0 = System.nanoTime()
        val e = assertFailsWith<IOException> { downloader().download(silentServer(), dest(), 1L shl 30, {}, { flag.get() }) }
        assertEquals("Cancelled", e.message)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms < 5_000, "took $ms ms")
    }
}
