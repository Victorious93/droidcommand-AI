package ai.droidforge.remote

import ai.droidforge.agent.RetryPolicy
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Proves RemoteClient's retry logic actually works against a real server,
 * not just a scripted fake — the local HTTP server below returns 500 twice
 * before succeeding, and RemoteClient + the real JdkHttpTransport must
 * recover without any test double standing in for the network layer.
 */
class RemoteClientIntegrationTest {
    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop(0)
    }

    @Test
    fun `RemoteClient recovers from real transient 5xx failures against a real local server`() {
        val requestCount = AtomicInteger(0)
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = httpServer
        httpServer.createContext("/flaky") { exchange ->
            val attempt = requestCount.incrementAndGet()
            if (attempt <= 2) {
                exchange.sendResponseHeaders(503, -1)
                exchange.close()
            } else {
                val body = "recovered".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
        httpServer.start()

        val endpoint = RemoteEndpoint("http://127.0.0.1:${httpServer.address.port}", requireHttps = false)
        val client = RemoteClient(endpoint, JdkHttpTransport(), sleep = { Thread.sleep(10) })

        val result = client.send("/flaky", retryPolicy = RetryPolicy(maxAttempts = 5))

        val success = assertIs<RemoteResult.Success>(result)
        assertEquals("recovered", success.body)
        assertEquals(3, requestCount.get())
    }
}
