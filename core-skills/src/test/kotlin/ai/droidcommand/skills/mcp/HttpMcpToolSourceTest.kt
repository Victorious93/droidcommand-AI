package ai.droidcommand.skills.mcp

import ai.droidcommand.agent.ToolJsonType
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.remote.HttpRequestSpec
import ai.droidcommand.remote.HttpResponseSpec
import ai.droidcommand.remote.HttpTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A fake MCP server behind a fake [HttpTransport]: answers JSON-RPC by method, records every request. */
private class FakeMcpServer(
    var tools: String = """[{"name":"search","description":"Finds","annotations":{"readOnlyHint":true},
        "inputSchema":{"type":"object","properties":{"q":{"type":"string"},"limit":{"type":"integer"},"all":{"type":"boolean"}},"required":["q"]}}]""",
    var sse: Boolean = false,
) : HttpTransport {
    val requests = mutableListOf<HttpRequestSpec>()
    var failWith: Exception? = null
    var status: Int = 200
    var rawBody: String? = null
    var callResult: String = """{"content":[{"type":"text","text":"found 2"}]}"""
    var expireSessionOnce = false
    var callError: String? = null

    override fun send(request: HttpRequestSpec): HttpResponseSpec {
        requests += request
        failWith?.let { throw it }
        if (request.method == "DELETE") return HttpResponseSpec(200, emptyMap(), "")
        val message = Json.parseToJsonElement(request.body!!).jsonObject
        val method = message["method"]!!.jsonPrimitive.content
        if (method == "notifications/initialized") return HttpResponseSpec(202, emptyMap(), "")
        if (expireSessionOnce && method != "initialize") {
            expireSessionOnce = false
            return HttpResponseSpec(404, emptyMap(), "")
        }
        if (status != 200) return HttpResponseSpec(status, emptyMap(), "nope")
        rawBody?.let { return HttpResponseSpec(200, mapOf("content-type" to "application/json"), it) }
        val id = message["id"]!!.jsonPrimitive.int
        val result = when (method) {
            "initialize" -> """{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"fake","version":"1"}}"""
            "tools/list" -> """{"tools":$tools}"""
            "tools/call" -> callError?.let { reason ->
                return reply(id, """{"jsonrpc":"2.0","id":$id,"error":{"code":-32602,"message":"$reason"}}""", method)
            } ?: callResult
            else -> return reply(id, """{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"no such method"}}""", method)
        }
        return reply(id, """{"jsonrpc":"2.0","id":$id,"result":$result}""", method)
    }

    private fun reply(id: Int, json: String, method: String): HttpResponseSpec {
        val headers = buildMap {
            if (method == "initialize") put("Mcp-Session-Id", "sess-1")
            put("Content-Type", if (sse) "text/event-stream" else "application/json")
        }
        val body = if (sse) "event: message\ndata: ${json.replace("\n", "")}\n\n" else json
        return HttpResponseSpec(200, headers, body)
    }

    fun bodies(method: String): List<JsonObject> = requests.filter { it.body != null }
        .map { Json.parseToJsonElement(it.body!!).jsonObject }
        .filter { it["method"]?.jsonPrimitive?.content == method }
}

class HttpMcpToolSourceTest {
    private fun source(server: FakeMcpServer, token: String? = null) =
        HttpMcpToolSource(server, "https://home.local/mcp", "Home PC", token = { token })

    @Test
    fun `lists tools with hints and input schema`() {
        val server = FakeMcpServer()
        val tool = source(server).listTools().single()
        assertEquals("search", tool.name)
        assertEquals(true, tool.readOnlyHint)
        assertNull(tool.destructiveHint)
        val params = tool.parameters!!.parameters.associateBy { it.name }
        assertEquals(ToolJsonType.STRING, params["q"]!!.type)
        assertTrue(params["q"]!!.required)
        assertEquals(ToolJsonType.NUMBER, params["limit"]!!.type)
        assertEquals(McpToolKind.READ_ONLY, McpToolKind.of(tool))
    }

    @Test
    fun `follows the MCP handshake and keeps the session id`() {
        val server = FakeMcpServer()
        source(server).listTools()
        val methods = server.requests.map { Json.parseToJsonElement(it.body!!).jsonObject["method"]!!.jsonPrimitive.content }
        assertEquals(listOf("initialize", "notifications/initialized", "tools/list"), methods)
        assertNull(server.requests[0].headers["Mcp-Session-Id"])
        assertEquals("sess-1", server.requests[2].headers["Mcp-Session-Id"])
        assertEquals("2025-06-18", server.requests[2].headers["MCP-Protocol-Version"])
        assertTrue(server.requests[0].headers["Accept"]!!.contains("text/event-stream"))
    }

    @Test
    fun `reads answers sent as a server-sent event stream`() {
        val server = FakeMcpServer(sse = true)
        val mcp = source(server)
        assertEquals("search", mcp.listTools().single().name)
        assertEquals(ToolResult.Success("found 2"), mcp.call("search", mapOf("q" to "x")))
    }

    @Test
    fun `sends the token as a bearer header only when one is saved`() {
        val withToken = FakeMcpServer()
        source(withToken, token = "abc123").listTools()
        assertTrue(withToken.requests.all { it.headers["Authorization"] == "Bearer abc123" })

        val without = FakeMcpServer()
        source(without).listTools()
        assertTrue(without.requests.none { it.headers.containsKey("Authorization") })
    }

    @Test
    fun `sends numbers and booleans as the types the server asked for`() {
        val server = FakeMcpServer()
        val mcp = source(server)
        mcp.listTools()
        mcp.call("search", mapOf("q" to "milk", "limit" to "5", "all" to "true"))
        val args = server.bodies("tools/call").single()["params"]!!.jsonObject["arguments"]!!.jsonObject
        assertEquals("\"milk\"", args["q"].toString())
        assertEquals("5", args["limit"].toString())
        assertEquals("true", args["all"].toString())
    }

    @Test
    fun `a tool error result becomes a failure`() {
        val server = FakeMcpServer().apply { callResult = """{"isError":true,"content":[{"type":"text","text":"disk full"}]}""" }
        assertEquals(ToolResult.Failure("disk full"), source(server).call("search", emptyMap()))
    }

    @Test
    fun `a server that cannot be reached gives a plain message`() {
        val server = FakeMcpServer().apply { failWith = IOException("Connection refused") }
        val e = assertFailsWith<McpException> { source(server).listTools() }
        assertEquals("Could not reach Home PC. Check the address and that it is running.", e.message)
    }

    @Test
    fun `a timeout gives a plain message`() {
        val server = FakeMcpServer().apply { failWith = SocketTimeoutException("Read timed out") }
        val e = assertFailsWith<McpException> { source(server).listTools() }
        assertEquals("Home PC did not answer in time. Check that it is running.", e.message)
    }

    @Test
    fun `a missing or wrong token gives a plain message`() {
        val server = FakeMcpServer().apply { status = 401 }
        val e = assertFailsWith<McpException> { source(server).listTools() }
        assertEquals("Home PC needs a valid token. Add or update it in Settings.", e.message)
    }

    @Test
    fun `a server error code gives a plain message`() {
        val server = FakeMcpServer().apply { status = 500 }
        assertEquals("Home PC answered with error 500.", assertFailsWith<McpException> { source(server).listTools() }.message)
    }

    @Test
    fun `an unreadable answer gives a plain message`() {
        val server = FakeMcpServer().apply { rawBody = "<html>hello</html>" }
        assertEquals(
            "Home PC sent an answer this app could not read.",
            assertFailsWith<McpException> { source(server).listTools() }.message,
        )
    }

    @Test
    fun `a malformed tool list gives a plain message`() {
        val server = FakeMcpServer(tools = "\"not a list\"")
        assertEquals(
            "Home PC sent an answer this app could not read.",
            assertFailsWith<McpException> { source(server).listTools() }.message,
        )
    }

    @Test
    fun `a JSON-RPC error gives the server's reason`() {
        val server = FakeMcpServer().apply { callError = "Unknown tool: search" }
        val e = assertFailsWith<McpException> { source(server).call("search", emptyMap()) }
        assertEquals("Home PC refused: Unknown tool: search", e.message)
    }

    @Test
    fun `an expired session is restarted once`() {
        val server = FakeMcpServer()
        val mcp = source(server)
        mcp.listTools()
        server.expireSessionOnce = true
        assertEquals(ToolResult.Success("found 2"), mcp.call("search", mapOf("q" to "x")))
        assertEquals(2, server.bodies("initialize").size)
    }

    @Test
    fun `non-text content is described instead of dropped silently`() {
        val text = HttpMcpToolSource.textOf(
            Json.parseToJsonElement("""{"content":[{"type":"image","data":"..."},{"type":"text","text":"caption"}]}""").jsonObject,
        )
        assertEquals("[image]\ncaption", text)
    }

    @Test
    fun `a bridged tool over HTTP turns a dead server into a plain failure`() {
        val server = FakeMcpServer()
        val mcp = source(server)
        val info = mcp.listTools().single()
        server.failWith = IOException("gone")
        val tool = McpBridgedTool(HOME, info, mcp, RecordingApproval())
        val result = assertIs<ToolResult.Failure>(tool.execute(mapOf("q" to "x")))
        assertEquals("Could not reach Home PC. Check the address and that it is running.", result.reason)
    }
}
