package ai.droidcommand.remote

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpUrlConnectionTransportTest {
    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop(0)
    }

    private fun start(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = s
        s.createContext("/", handler)
        s.start()
        return "http://127.0.0.1:${s.address.port}"
    }

    private fun reply(ex: com.sun.net.httpserver.HttpExchange, status: Int, body: String, vararg headers: Pair<String, String>) {
        headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
        val bytes = body.toByteArray()
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private val transport = HttpUrlConnectionTransport()

    @Test
    fun `POST sends headers and body and returns status headers and body`() {
        val seenBody = AtomicReference<String>()
        val seenAuth = AtomicReference<String?>()
        val base = start { ex ->
            seenBody.set(ex.requestBody.readBytes().toString(Charsets.UTF_8))
            seenAuth.set(ex.requestHeaders.getFirst("Authorization"))
            reply(ex, 200, """{"ok":true}""", "X-Thing" to "abc")
        }

        val r = transport.send(HttpRequestSpec("POST", "$base/x", mapOf("Authorization" to "Bearer k"), """{"a":"ü"}"""))

        assertEquals(200, r.statusCode)
        assertEquals("""{"ok":true}""", r.body)
        assertEquals("abc", r.headers["x-thing"])
        assertEquals("""{"a":"ü"}""", seenBody.get())
        assertEquals("Bearer k", seenAuth.get())
    }

    @Test
    fun `non-2xx returns the error body instead of throwing`() {
        val base = start { ex -> reply(ex, 429, """{"error":"slow down"}""") }

        val r = transport.send(HttpRequestSpec("GET", "$base/"))

        assertEquals(429, r.statusCode)
        assertEquals("""{"error":"slow down"}""", r.body)
    }

    @Test
    fun `redirects are not followed`() {
        val hits = AtomicReference(0)
        val base = start { ex ->
            if (ex.requestURI.path == "/start") {
                reply(ex, 302, "", "Location" to "/elsewhere")
            } else {
                hits.set(hits.get() + 1)
                reply(ex, 200, "reached")
            }
        }

        val r = transport.send(HttpRequestSpec("GET", "$base/start"))

        assertEquals(302, r.statusCode)
        assertEquals(0, hits.get())
    }

    @Test
    fun `streaming delivers lines in order and leaves body empty`() {
        val base = start { ex -> reply(ex, 200, "data: a\n\ndata: b\n\n") }
        val lines = mutableListOf<String>()

        val r = transport.sendStreaming(HttpRequestSpec("POST", "$base/", body = "{}")) { lines += it }

        assertEquals(200, r.statusCode)
        assertEquals("", r.body)
        assertEquals(listOf("data: a", "", "data: b", ""), lines)
    }

    @Test
    fun `streaming non-2xx collects the body and delivers no lines`() {
        val base = start { ex -> reply(ex, 401, """{"error":"bad key"}""") }
        val lines = mutableListOf<String>()

        val r = transport.sendStreaming(HttpRequestSpec("POST", "$base/", body = "{}")) { lines += it }

        assertEquals(401, r.statusCode)
        assertEquals("""{"error":"bad key"}""", r.body)
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `a stalled server surfaces as a SocketTimeoutException that isTimeoutFailure recognises`() {
        val base = start { ex ->
            Thread.sleep(1500)
            reply(ex, 200, "late")
        }

        val e = assertFailsWith<SocketTimeoutException> {
            transport.send(HttpRequestSpec("GET", "$base/", requestTimeoutMillis = 200))
        }
        assertTrue(e.isTimeoutFailure())
    }

    @Test
    fun `isTimeoutFailure matches by class name and rejects other failures`() {
        assertTrue(java.net.http.HttpTimeoutException("x").isTimeoutFailure())
        assertFalse(IOException("x").isTimeoutFailure())
        assertFalse((null as Throwable?).isTimeoutFailure())
    }

    @Test
    fun `connection failure is an IOException`() {
        assertFailsWith<IOException> { transport.send(HttpRequestSpec("GET", "http://127.0.0.1:1/", connectTimeoutMillis = 500)) }
    }
}
