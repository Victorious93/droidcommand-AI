package ai.droidcommand.n8n

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.remote.JdkHttpTransport
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class N8nToolTest {
    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop(0)
    }

    @Test
    fun `both tools are SENSITIVE NETWORK and always require confirmation`() {
        val client = N8nClient(JdkHttpTransport()) { "n8n-test-key" }
        for (spec in listOf(
            N8nListWorkflowsTool(client, { "https://n8n.example.com" }, { false }).spec,
            N8nRunWorkflowTool(client, { "https://n8n.example.com" }, { false }).spec,
        )) {
            assertEquals(SecurityLevel.SENSITIVE, spec.securityLevel)
            assertEquals(PermissionCategory.NETWORK, spec.permissionCategory)
            assertTrue(spec.requiresConfirmation)
            assertTrue(spec.parameterSchema != null)
        }
        assertEquals("n8n_list_workflows", N8nTool.LIST)
        assertEquals("n8n_run_workflow", N8nTool.RUN)
    }

    @Test
    fun `gate off refuses without talking to n8n`() {
        val hits = AtomicInteger()
        start {
            hits.incrementAndGet()
            respond(it, 200, """{"data":[]}""")
        }
        val url = "http://127.0.0.1:${server!!.address.port}"
        val client = N8nClient(JdkHttpTransport()) { "n8n-test-key" }
        val list = N8nListWorkflowsTool(client, { url }, { false }).execute(emptyMap())
        val run = N8nRunWorkflowTool(client, { url }, { false }).execute(mapOf("name" to "Lights", "json" to "{}"))
        val listFail = assertIs<ToolResult.Failure>(list)
        assertIs<ToolResult.Failure>(run)
        assertTrue(listFail.reason.contains("off", ignoreCase = true))
        assertEquals(0, hits.get())
    }

    @Test
    fun `list and run work when the gate is on`() {
        start { exchange ->
            if (exchange.requestURI.path.startsWith("/api/")) {
                respond(exchange, 200, LIST_JSON)
            } else {
                respond(exchange, 200, """{"message":"done"}""")
            }
        }
        val url = "http://127.0.0.1:${server!!.address.port}"
        val client = N8nClient(JdkHttpTransport()) { "n8n-test-key" }
        val listed = N8nListWorkflowsTool(client, { url }, { true }).execute(emptyMap())
        val success = assertIs<ToolResult.Success>(listed)
        assertTrue(success.output.contains("Lights"))
        val ran = N8nRunWorkflowTool(client, { url }, { true }).execute(mapOf("name" to "Lights", "json" to """{"ok":true}"""))
        val runOk = assertIs<ToolResult.Success>(ran)
        assertTrue(runOk.output.contains("done"))
    }

    @Test
    fun `run without a workflow name fails closed`() {
        val client = N8nClient(JdkHttpTransport()) { "n8n-test-key" }
        val result = N8nRunWorkflowTool(client, { "https://n8n.example.com" }, { true }).execute(emptyMap())
        assertIs<ToolResult.Failure>(result)
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
}

private const val LIST_JSON = """
{"data":[{"id":"1","name":"Lights","active":true,"nodes":[{"type":"n8n-nodes-base.webhook","parameters":{"path":"doorbell","httpMethod":"POST"}}]}]}
"""
