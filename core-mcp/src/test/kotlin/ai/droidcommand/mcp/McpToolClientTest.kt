package ai.droidcommand.mcp

import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class FixedTool(name: String, private val result: ToolResult) : Tool {
    override val spec = ToolSpec(name = name, description = "fixture '$name'")
    override fun execute(input: Map<String, String>): ToolResult = result
}

private class EchoTool : Tool {
    override val spec = ToolSpec(name = "echo", description = "echoes received input")
    override fun execute(input: Map<String, String>): ToolResult =
        ToolResult.Success(input.entries.sortedBy { it.key }.joinToString { (k, v) -> "$k=$v" })
}

class McpToolClientTest {
    /**
     * Stands up an in-process [McpToolServer] exposing [registry] and connects a real [McpToolClient]
     * to it over a piped `StdioServerTransport`/`StdioClientTransport` pair — the same real transport
     * path a cross-process deployment uses, with pipes standing in for process stdio so no subprocess
     * is spawned. The server session runs on its own thread (kept alive by a [Job] until teardown) so
     * the synchronous client's cross-thread request/response bridge is exercised for real.
     */
    private fun withConnectedClient(registry: ToolRegistry, block: (McpToolClient) -> Unit) {
        val clientToServer = PipedOutputStream()
        val serverReadsClient = PipedInputStream(clientToServer)
        val serverToClient = PipedOutputStream()
        val clientReadsServer = PipedInputStream(serverToClient)

        val serverTransport =
            StdioServerTransport(serverReadsClient.asSource().buffered(), serverToClient.asSink().buffered()) { }
        val clientTransport =
            StdioClientTransport(clientReadsServer.asSource().buffered(), clientToServer.asSink().buffered())

        val server = McpToolServer(registry, ToolExecutor(registry, AgentStateMachine())).buildServer()
        val serverClose = Job()
        val serverThread = thread(name = "mcp-test-server", isDaemon = true) {
            runBlocking {
                server.createSession(serverTransport)
                serverClose.join()
            }
        }

        val client = McpToolClient()
        try {
            client.connect(clientTransport)
            block(client)
        } finally {
            client.close()
            serverClose.complete()
            runBlocking { server.close() }
            serverThread.join(2_000)
        }
    }

    @Test
    fun `surfaces each remote tool as a namespaced SENSITIVE tool`() {
        val registry = ToolRegistry().apply {
            register(FixedTool("greet", ToolResult.Success("hi")))
            register(FixedTool("count", ToolResult.Success("1")))
        }
        withConnectedClient(registry) { client ->
            val tools = client.asTools().sortedBy { it.spec.name }
            assertEquals(listOf("mcp_count", "mcp_greet"), tools.map { it.spec.name })
            tools.forEach {
                assertEquals(SecurityLevel.SENSITIVE, it.spec.securityLevel)
                assertTrue(it.spec.requiresConfirmation, "a remote MCP tool must require confirmation")
            }
        }
    }

    @Test
    fun `lists remote tool names and descriptions`() {
        val registry = ToolRegistry().apply { register(FixedTool("greet", ToolResult.Success("hi"))) }
        withConnectedClient(registry) { client ->
            val remote = client.listRemoteTools().single()
            assertEquals("greet", remote.name)
            assertEquals("fixture 'greet'", remote.description)
        }
    }

    @Test
    fun `a remote tool execute returns the server Success output`() {
        val registry = ToolRegistry().apply { register(FixedTool("greet", ToolResult.Success("hello there"))) }
        withConnectedClient(registry) { client ->
            val tool = client.asTools().single { it.spec.name == "mcp_greet" }
            val result = assertIs<ToolResult.Success>(tool.execute(emptyMap()))
            assertEquals("hello there", result.output)
        }
    }

    @Test
    fun `a remote tool failure maps to ToolResult Failure`() {
        val registry = ToolRegistry().apply { register(FixedTool("broken", ToolResult.Failure("nope"))) }
        withConnectedClient(registry) { client ->
            val tool = client.asTools().single()
            val result = assertIs<ToolResult.Failure>(tool.execute(emptyMap()))
            // McpToolServer renders a Failure as "ERROR: <reason>" text with isError=true; the client maps
            // isError back to Failure, carrying that rendered text.
            assertEquals("ERROR: nope", result.reason)
        }
    }

    @Test
    fun `arguments are forwarded to the remote tool`() {
        val registry = ToolRegistry().apply { register(EchoTool()) }
        withConnectedClient(registry) { client ->
            val tool = client.asTools().single()
            val result = assertIs<ToolResult.Success>(tool.execute(mapOf("b" to "2", "a" to "1")))
            assertEquals("a=1, b=2", result.output)
        }
    }
}
