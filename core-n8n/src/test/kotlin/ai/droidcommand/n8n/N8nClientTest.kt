package ai.droidcommand.n8n

import ai.droidcommand.remote.JdkHttpTransport
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class N8nClientTest {
    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop(0)
    }

    @Test
    fun `test connection lists workflows with the n8n API key header`() {
        val key = AtomicReference<String?>()
        val path = AtomicReference<String?>()
        start { exchange ->
            key.set(exchange.requestHeaders.getFirst("X-N8N-API-KEY"))
            path.set(exchange.requestURI.toString())
            respond(exchange, 200, WORKFLOWS_JSON)
        }
        val outcome = assertIs<N8nOutcome.Ok<Int>>(client().testConnection(url()))
        assertEquals(1, outcome.value)
        assertEquals("n8n-test-key", key.get())
        assertTrue(path.get()!!.startsWith("/api/v1/workflows"))
        assertTrue(outcome.words.contains("1 workflow"))
    }

    @Test
    fun `missing key never opens a socket`() {
        val hits = AtomicInteger()
        start {
            hits.incrementAndGet()
            respond(it, 200, WORKFLOWS_JSON)
        }
        val outcome = client(key = "").testConnection(url())
        assertIs<N8nOutcome.Failed>(outcome)
        assertEquals(0, hits.get())
    }

    @Test
    fun `401 is a rejected-key sentence`() {
        start { respond(it, 401, """{"message":"unauthorized"}""") }
        val outcome = assertIs<N8nOutcome.Failed>(client().testConnection(url()))
        assertTrue(outcome.words.contains("API key") || outcome.words.contains("unauthorized"))
    }

    @Test
    fun `list parses a webhook path and skips respondToWebhook`() {
        start { respond(it, 200, WORKFLOWS_JSON) }
        val listed = assertIs<N8nOutcome.Ok<List<N8nWorkflow>>>(client().listWorkflows(url()))
        assertEquals(1, listed.value.size)
        assertEquals("Lights", listed.value.single().name)
        assertEquals("doorbell", listed.value.single().webhook?.path)
        assertEquals("POST", listed.value.single().webhook?.httpMethod)
    }

    @Test
    fun `trigger posts JSON to the webhook and does not send the API key`() {
        val key = AtomicReference<String?>()
        val method = AtomicReference<String?>()
        val path = AtomicReference<String?>()
        val body = AtomicReference<String?>()
        start { exchange ->
            if (exchange.requestURI.path.startsWith("/api/")) {
                respond(exchange, 200, WORKFLOWS_JSON)
                return@start
            }
            key.set(exchange.requestHeaders.getFirst("X-N8N-API-KEY"))
            method.set(exchange.requestMethod)
            path.set(exchange.requestURI.path)
            body.set(exchange.requestBody.bufferedReader().readText())
            respond(exchange, 200, """{"ok":true,"message":"porch light on"}""")
        }
        val workflow = N8nWorkflow("1", "Lights", active = true, webhook = N8nWebhook("doorbell", "POST"))
        val outcome = assertIs<N8nOutcome.Ok<String>>(client().triggerWebhook(url(), workflow, """{"room":"porch"}"""))
        assertNull(key.get())
        assertEquals("POST", method.get())
        assertEquals("/webhook/doorbell", path.get())
        assertEquals("""{"room":"porch"}""", body.get())
        assertTrue(outcome.words.contains("porch light on"))
    }

    @Test
    fun `inactive and non-webhook workflows are refused without a webhook call`() {
        val hits = AtomicInteger()
        start {
            hits.incrementAndGet()
            respond(it, 200, "{}")
        }
        val off = N8nWorkflow("1", "Lights", active = false, webhook = N8nWebhook("doorbell", "POST"))
        assertIs<N8nOutcome.Failed>(client().triggerWebhook(url(), off, "{}"))
        val noHook = N8nWorkflow("1", "Cron", active = true, webhook = null)
        assertIs<N8nOutcome.Failed>(client().triggerWebhook(url(), noHook, "{}"))
        assertEquals(0, hits.get())
    }

    @Test
    fun `non-object JSON is refused`() {
        val outcome = client().triggerWebhook(
            (N8nServerUrl.parse("http://127.0.0.1:9") as N8nUrlParse.Ok).url,
            N8nWorkflow("1", "Lights", true, N8nWebhook("doorbell", "POST")),
            """["nope"]""",
        )
        assertIs<N8nOutcome.Failed>(outcome)
    }

    private fun start(handler: (HttpExchange) -> Unit) {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = s
        s.createContext("/") { handler(it) }
        s.start()
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun client(key: String? = "n8n-test-key") = N8nClient(JdkHttpTransport(), { key })

    private fun url(): N8nServerUrl = (N8nServerUrl.parse("http://127.0.0.1:${server!!.address.port}") as N8nUrlParse.Ok).url
}

private const val WORKFLOWS_JSON = """
{
  "data": [
    {
      "id": "1",
      "name": "Lights",
      "active": true,
      "nodes": [
        {
          "type": "n8n-nodes-base.webhook",
          "parameters": { "path": "doorbell", "httpMethod": "POST" }
        },
        {
          "type": "n8n-nodes-base.respondToWebhook",
          "parameters": { "path": "ignored" }
        }
      ]
    }
  ]
}
"""
