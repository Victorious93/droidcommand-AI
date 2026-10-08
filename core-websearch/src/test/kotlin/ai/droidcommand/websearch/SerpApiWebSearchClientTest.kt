package ai.droidcommand.websearch

import ai.droidcommand.remote.JdkHttpTransport
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** [SerpApiWebSearchClient] against a local mock server only — never a live API or real key. */
class SerpApiWebSearchClientTest {
    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop(0)
    }

    private fun start(status: Int = 200, path: AtomicReference<String>? = null, body: String): HttpServer {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = s
        s.createContext("/search.json") { ex ->
            path?.set(ex.requestURI.toString())
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.start()
        return s
    }

    private fun client(s: HttpServer, key: String? = "serp-test") = SerpApiWebSearchClient(
        apiKey = { key },
        transport = JdkHttpTransport(),
        endpoint = "http://127.0.0.1:${s.address.port}",
        requireHttps = false,
    )

    @Test
    fun `search parses organic_results and sends the api key as a query param`() {
        val path = AtomicReference<String>()
        val s = start(
            path = path,
            body = """{"organic_results":[{"title":"Kotlin","link":"https://kotlinlang.org","snippet":"The language"}]}""",
        )

        val result = assertIs<WebSearchOutcome.Success>(client(s).search("kotlin"))
        assertEquals(listOf(WebSearchResult("Kotlin", "https://kotlinlang.org", "The language")), result.results)
        assertTrue(path.get().contains("api_key=serp-test"))
        assertTrue(path.get().contains("engine=google"))
    }

    @Test
    fun `missing api key fails without a network call`() {
        val s = start(body = "unused")
        assertEquals(
            WebSearchOutcome.Failure("No SerpAPI key configured"),
            client(s, key = null).search("kotlin"),
        )
    }

    @Test
    fun `error status surfaces as a Failure with the status code`() {
        val s = start(status = 403, body = """{"error":"forbidden"}""")

        val result = assertIs<WebSearchOutcome.Failure>(client(s).search("kotlin"))
        assertEquals(403, result.statusCode)
    }
}
