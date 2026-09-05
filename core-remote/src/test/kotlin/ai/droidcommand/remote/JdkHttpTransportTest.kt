package ai.droidcommand.remote

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Exercises [JdkHttpTransport] against a real HTTP server bound to
 * loopback (127.0.0.1) — this is a genuine network round trip, not a
 * mock, but it never leaves the local machine, so it needs no external
 * network access and no Android SDK.
 */
class JdkHttpTransportTest {
    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop(0)
    }

    @Test
    fun `round-trips a real request through a local HTTP server`() {
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = httpServer
        httpServer.createContext("/echo") { exchange ->
            val responseBody = "hello from server".toByteArray()
            exchange.responseHeaders.add("X-Test-Header", "test-value")
            exchange.sendResponseHeaders(200, responseBody.size.toLong())
            exchange.responseBody.use { it.write(responseBody) }
        }
        httpServer.start()

        val transport = JdkHttpTransport()
        val response = transport.send(
            HttpRequestSpec(method = "GET", url = "http://127.0.0.1:${httpServer.address.port}/echo"),
        )

        assertEquals(200, response.statusCode)
        assertEquals("hello from server", response.body)
        assertEquals("test-value", response.headers["X-Test-Header"])
    }

    @Test
    fun `a redirect response is returned as-is, never followed, so a credential header can never be resent cross-origin`() {
        // OD-011 (docs/AUDIT_2026-09-05.md): OpenDroid's OkHttp-based client has an
        // explicit test asserting a cross-origin redirect drops the Authorization
        // header. This transport's guarantee is stronger and simpler to verify: it
        // never follows a redirect at all (java.net.http.HttpClient defaults to
        // Redirect.NEVER when a builder never calls followRedirects), so the
        // Authorization header this test sends is never resent anywhere — the
        // caller gets the raw 3xx back and decides what to do next.
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = httpServer
        var redirectTargetRequests = 0
        httpServer.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "http://attacker.invalid/steal-token")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        httpServer.createContext("/steal-token") { exchange ->
            redirectTargetRequests++
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        httpServer.start()

        val transport = JdkHttpTransport()
        val response = transport.send(
            HttpRequestSpec(
                method = "GET",
                url = "http://127.0.0.1:${httpServer.address.port}/redirect",
                headers = mapOf("Authorization" to "Bearer super-secret-token"),
            ),
        )

        assertEquals(302, response.statusCode)
        assertEquals(0, redirectTargetRequests)
    }

    @Test
    fun `a request that never gets a response times out within the configured window`() {
        // A raw ServerSocket that accepts the TCP connection but never writes an
        // HTTP response, so the client's own request timeout is what fires.
        val serverSocket = ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))
        val acceptThread = Thread {
            try {
                serverSocket.accept()
                // Deliberately never write a response and never close the socket.
            } catch (e: IOException) {
                // Expected once the socket is closed in tearDown-equivalent below.
            }
        }
        acceptThread.isDaemon = true
        acceptThread.start()

        try {
            val transport = JdkHttpTransport()
            assertFailsWith<IOException> {
                transport.send(
                    HttpRequestSpec(
                        method = "GET",
                        url = "http://127.0.0.1:${serverSocket.localPort}/never-responds",
                        requestTimeoutMillis = 300,
                    ),
                )
            }
        } finally {
            serverSocket.close()
        }
    }
}
