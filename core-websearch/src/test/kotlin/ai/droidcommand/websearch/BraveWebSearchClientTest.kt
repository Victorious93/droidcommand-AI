package ai.droidcommand.websearch

import ai.droidcommand.remote.InsecureEndpointRejected
import ai.droidcommand.remote.JdkHttpTransport
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** [BraveWebSearchClient] against a local mock server only — never a live API or real key. */
class BraveWebSearchClientTest {
    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop(0)
    }

    private fun start(status: Int = 200, path: AtomicReference<String>? = null, token: AtomicReference<String?>? = null, body: String): HttpServer {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = s
        s.createContext("/res/v1/web/search") { ex ->
            path?.set(ex.requestURI.toString())
            token?.set(ex.requestHeaders.getFirst("X-Subscription-Token"))
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.start()
        return s
    }

    private fun client(s: HttpServer, key: String? = "brave-test") = BraveWebSearchClient(
        apiKey = { key },
        transport = JdkHttpTransport(),
        endpoint = "http://127.0.0.1:${s.address.port}",
        requireHttps = false,
    )

    @Test
    fun `search parses results and sends the subscription token header`() {
        val token = AtomicReference<String?>()
        val s = start(
            token = token,
            body = """{"web":{"results":[{"title":"Kotlin","url":"https://kotlinlang.org","description":"The language"}]}}""",
        )

        val result = assertIs<WebSearchOutcome.Success>(client(s).search("kotlin"))
        assertEquals(listOf(WebSearchResult("Kotlin", "https://kotlinlang.org", "The language")), result.results)
        assertEquals("brave-test", token.get())
    }

    @Test
    fun `query is URL-encoded and count is passed through`() {
        val path = AtomicReference<String>()
        val s = start(path = path, body = """{"web":{"results":[]}}""")

        client(s).search("hello world", count = 3)
        assertTrue(path.get().contains("q=hello+world"))
        assertTrue(path.get().contains("count=3"))
    }

    @Test
    fun `missing api key fails without a network call`() {
        val s = start(body = "unused")
        assertEquals(
            WebSearchOutcome.Failure("No Brave Search API key configured"),
            client(s, key = null).search("kotlin"),
        )
    }

    @Test
    fun `401 surfaces as a Failure with the status code`() {
        val s = start(status = 401, body = """{"error":"bad key"}""")

        val result = assertIs<WebSearchOutcome.Failure>(client(s).search("kotlin"))
        assertEquals(401, result.statusCode)
    }

    @Test
    fun `malformed json surfaces as a Failure, not a thrown exception`() {
        val s = start(body = "not json")
        assertIs<WebSearchOutcome.Failure>(client(s).search("kotlin"))
    }

    @Test
    fun `https is required by default`() {
        assertFailsWith<InsecureEndpointRejected> {
            BraveWebSearchClient(apiKey = { "k" }, transport = JdkHttpTransport(), endpoint = "http://example.com")
        }
    }
}
