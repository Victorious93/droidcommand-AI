package ai.droidcommand.remote

import ai.droidcommand.agent.RetryPolicy
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [JdkHttpTransport.sendStreaming] against a real loopback server, and
 * [RemoteClient.sendStreaming]'s retry rules against scripted transports.
 */
class StreamingTransportTest {
    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop(0)
    }

    private fun start(configure: (HttpServer) -> Unit): HttpServer {
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = httpServer
        configure(httpServer)
        httpServer.start()
        return httpServer
    }

    @Test
    fun `lines arrive before the response body is finished`() {
        val firstLineSeen = CountDownLatch(1)
        val httpServer = start { s ->
            s.createContext("/stream") { exchange ->
                exchange.sendResponseHeaders(200, 0) // chunked
                exchange.responseBody.use { out ->
                    out.write("data: one\n\n".toByteArray())
                    out.flush()
                    // Only finish the body once the client has already handled the first line.
                    firstLineSeen.await(5, TimeUnit.SECONDS)
                    out.write("data: two\n\n".toByteArray())
                }
            }
        }
        val lines = mutableListOf<String>()

        val response = JdkHttpTransport().sendStreaming(
            HttpRequestSpec(method = "GET", url = "http://127.0.0.1:${httpServer.address.port}/stream"),
        ) { line ->
            lines += line
            firstLineSeen.countDown()
        }

        assertEquals(200, response.statusCode)
        assertEquals("", response.body)
        assertEquals(listOf("data: one", "", "data: two", ""), lines)
    }

    @Test
    fun `a non-2xx body is collected, not streamed`() {
        val httpServer = start { s ->
            s.createContext("/stream") { exchange ->
                val bytes = "bad key\nsecond line".toByteArray()
                exchange.sendResponseHeaders(401, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        val lines = mutableListOf<String>()

        val response = JdkHttpTransport().sendStreaming(
            HttpRequestSpec(method = "GET", url = "http://127.0.0.1:${httpServer.address.port}/stream"),
        ) { lines += it }

        assertEquals(401, response.statusCode)
        assertEquals("bad key\nsecond line", response.body)
        assertTrue(lines.isEmpty())
    }

    private class ScriptedStreamingTransport(private val script: MutableList<(onLine: (String) -> Unit) -> HttpResponseSpec>) :
        StreamingHttpTransport {
        var calls = 0

        override fun send(request: HttpRequestSpec): HttpResponseSpec = error("not used")

        override fun sendStreaming(request: HttpRequestSpec, onLine: (String) -> Unit): HttpResponseSpec {
            calls++
            return script.removeAt(0)(onLine)
        }
    }

    private val endpoint = RemoteEndpoint("https://api.example.invalid")

    @Test
    fun `a failure before any line is retried`() {
        val transport = ScriptedStreamingTransport(
            mutableListOf(
                { _ -> throw IOException("connection reset") },
                { onLine ->
                    onLine("data: ok")
                    HttpResponseSpec(200, emptyMap(), "")
                },
            ),
        )
        val lines = mutableListOf<String>()

        val result = RemoteClient(endpoint, transport, sleep = {}).sendStreaming("x", retryPolicy = RetryPolicy(maxAttempts = 2)) { lines += it }

        assertIs<RemoteResult.Success>(result)
        assertEquals(2, transport.calls)
        assertEquals(listOf("data: ok"), lines)
    }

    @Test
    fun `a failure after lines were delivered is not retried`() {
        val transport = ScriptedStreamingTransport(
            mutableListOf(
                { onLine ->
                    onLine("data: partial")
                    throw IOException("connection reset")
                },
                { _ -> HttpResponseSpec(200, emptyMap(), "") },
            ),
        )

        val result = RemoteClient(endpoint, transport, sleep = {}).sendStreaming("x", retryPolicy = RetryPolicy(maxAttempts = 3)) { }

        assertIs<RemoteResult.Failure>(result)
        assertEquals(1, transport.calls)
    }

    @Test
    fun `a 5xx before streaming is retried and a 4xx is not`() {
        val retried = ScriptedStreamingTransport(
            mutableListOf({ _ -> HttpResponseSpec(503, emptyMap(), "busy") }, { _ -> HttpResponseSpec(200, emptyMap(), "") }),
        )
        assertIs<RemoteResult.Success>(RemoteClient(endpoint, retried, sleep = {}).sendStreaming("x", retryPolicy = RetryPolicy(2)) { })
        assertEquals(2, retried.calls)

        val terminal = ScriptedStreamingTransport(mutableListOf({ _ -> HttpResponseSpec(400, emptyMap(), "bad") }))
        val failure = assertIs<RemoteResult.Failure>(RemoteClient(endpoint, terminal, sleep = {}).sendStreaming("x", retryPolicy = RetryPolicy(2)) { })
        assertEquals(400, failure.statusCode)
        assertEquals(1, terminal.calls)
    }

    @Test
    fun `a transport without streaming support fails without sending`() {
        val plain = object : HttpTransport {
            override fun send(request: HttpRequestSpec): HttpResponseSpec = error("must not be called")
        }

        val failure = assertIs<RemoteResult.Failure>(RemoteClient(endpoint, plain).sendStreaming("x") { })
        assertTrue(failure.reason.contains("does not support streaming"))
    }
}
