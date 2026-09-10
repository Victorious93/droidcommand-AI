package ai.droidcommand.mcp

import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FixedResultTool(
    name: String,
    private val result: ToolResult,
    requiredInitiator: Set<Initiator>? = null,
) : Tool {
    override val spec = ToolSpec(name = name, description = "test tool '$name'", requiredInitiator = requiredInitiator)
    override fun execute(input: Map<String, String>): ToolResult = result
}

private class EchoInputTool : Tool {
    override val spec = ToolSpec(name = "echo", description = "echoes received input back as its Success output")
    override fun execute(input: Map<String, String>): ToolResult =
        ToolResult.Success(input.entries.sortedBy { it.key }.joinToString { (k, v) -> "$k=$v" })
}

/**
 * Connects a real [Client] to [mcpServer] over a real [StdioServerTransport] /
 * [StdioClientTransport] pair wired through in-process pipes — the same
 * transport class [McpToolServer.runStdio] uses against real process
 * stdin/stdout, just swapping "the OS's real stdio streams" for
 * `PipedInputStream`/`PipedOutputStream` so the test never spawns a real
 * subprocess. This proves the real, non-experimental transport path (unlike
 * `kotlin-sdk-testing`'s `ChannelTransport`, which is `@ExperimentalMcpApi`
 * and — verified directly — races on a `tools/call` round trip under this
 * SDK version). Runs [block], then unconditionally closes both ends so no
 * background receive-loop coroutine leaks into a later test.
 */
private fun withMcpClient(mcpServer: McpToolServer, block: suspend (Client) -> Unit) = runBlocking {
    val clientToServer = PipedOutputStream()
    val serverReadsClient = PipedInputStream(clientToServer)
    val serverToClient = PipedOutputStream()
    val clientReadsServer = PipedInputStream(serverToClient)

    val serverTransport = StdioServerTransport(serverReadsClient.asSource().buffered(), serverToClient.asSink().buffered()) { }
    val clientTransport = StdioClientTransport(clientReadsServer.asSource().buffered(), clientToServer.asSink().buffered())

    val client = Client(clientInfo = Implementation(name = "test-client", version = "1.0"), options = ClientOptions())
    val server = mcpServer.buildServer()
    joinAll(
        launch { client.connect(clientTransport) },
        launch { server.createSession(serverTransport) },
    )
    try {
        block(client)
    } finally {
        client.close()
        server.close()
    }
}

class McpToolServerTest {
    @Test
    fun `lists a registered tool with its name and description`() {
        val registry = ToolRegistry().apply { register(FixedResultTool("greet", ToolResult.Success("hi"))) }
        val executor = ToolExecutor(registry, AgentStateMachine())
        val mcpServer = McpToolServer(registry, executor)

        withMcpClient(mcpServer) { client ->
            val tools = client.listTools().tools
            assertEquals(1, tools.size)
            assertEquals("greet", tools.single().name)
            assertEquals("test tool 'greet'", tools.single().description)
        }
    }

    @Test
    fun `a tool restricted to an initiator other than REMOTE is not listed`() {
        val registry = ToolRegistry().apply {
            register(FixedResultTool("owner-only", ToolResult.Success("ran"), requiredInitiator = setOf(Initiator.DEVICE_OWNER)))
        }
        val executor = ToolExecutor(registry, AgentStateMachine())
        val mcpServer = McpToolServer(registry, executor) // default initiator = REMOTE

        withMcpClient(mcpServer) { client ->
            assertTrue(client.listTools().tools.isEmpty())
        }
    }

    @Test
    fun `calling a tool that returns Success maps to non-error text content`() {
        val registry = ToolRegistry().apply { register(FixedResultTool("greet", ToolResult.Success("hello there"))) }
        val executor = ToolExecutor(registry, AgentStateMachine())
        val mcpServer = McpToolServer(registry, executor)

        withMcpClient(mcpServer) { client ->
            val result = client.callTool("greet", emptyMap())
            assertEquals(false, result.isError)
            val content = assertIs<TextContent>(result.content.single())
            assertEquals("hello there", content.text)
        }
    }

    @Test
    fun `calling a tool that returns Failure maps to error text content`() {
        val registry = ToolRegistry().apply { register(FixedResultTool("broken", ToolResult.Failure("nope"))) }
        val executor = ToolExecutor(registry, AgentStateMachine())
        val mcpServer = McpToolServer(registry, executor)

        withMcpClient(mcpServer) { client ->
            val result = client.callTool("broken", emptyMap())
            assertEquals(true, result.isError)
            val content = assertIs<TextContent>(result.content.single())
            assertEquals("ERROR: nope", content.text)
        }
    }

    @Test
    fun `calling a tool that returns Partial maps to non-error text content with the PARTIAL prefix`() {
        val registry = ToolRegistry().apply {
            register(FixedResultTool("half-done", ToolResult.Partial("3 of 5 done", "ran out of time")))
        }
        val executor = ToolExecutor(registry, AgentStateMachine())
        val mcpServer = McpToolServer(registry, executor)

        withMcpClient(mcpServer) { client ->
            val result = client.callTool("half-done", emptyMap())
            assertEquals(false, result.isError)
            val content = assertIs<TextContent>(result.content.single())
            assertEquals("PARTIAL: 3 of 5 done (ran out of time)", content.text)
        }
    }

    @Test
    fun `calling a tool that returns Unexpected maps to non-error text content with the UNEXPECTED prefix`() {
        val registry = ToolRegistry().apply {
            register(FixedResultTool("odd", ToolResult.Unexpected("device reported an unrecognized state")))
        }
        val executor = ToolExecutor(registry, AgentStateMachine())
        val mcpServer = McpToolServer(registry, executor)

        withMcpClient(mcpServer) { client ->
            val result = client.callTool("odd", emptyMap())
            assertEquals(false, result.isError)
            val content = assertIs<TextContent>(result.content.single())
            assertEquals("UNEXPECTED: device reported an unrecognized state", content.text)
        }
    }

    @Test
    fun `string and number arguments reach the tool as strings, a nested array argument is dropped`() {
        val tool = EchoInputTool()
        val registry = ToolRegistry().apply { register(tool) }
        val executor = ToolExecutor(registry, AgentStateMachine())
        val mcpServer = McpToolServer(registry, executor)

        withMcpClient(mcpServer) { client ->
            val result = client.callTool("echo", mapOf("text" to "hi", "count" to 3, "tags" to listOf("a", "b")))
            val content = assertIs<TextContent>(result.content.single())
            assertEquals("count=3, text=hi", content.text)
        }
    }

    @Test
    fun `every tool is advertised with a permissive input schema, since ToolSpec has no schema of its own`() {
        val registry = ToolRegistry().apply { register(FixedResultTool("greet", ToolResult.Success("hi"))) }
        val executor = ToolExecutor(registry, AgentStateMachine())
        val mcpServer = McpToolServer(registry, executor)

        withMcpClient(mcpServer) { client ->
            val schema = client.listTools().tools.single().inputSchema
            assertEquals("object", schema.type)
            assertNull(schema.required)
        }
    }
}
