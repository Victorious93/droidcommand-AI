package ai.droidcommand.mcp

import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

/** A tool a connected external MCP server offers: its remote name and description, as the server reports them. */
data class RemoteToolDescriptor(val name: String, val description: String)

/**
 * The reverse of [McpToolServer]: connects **this** process (as an MCP client) to an external MCP
 * server over the official Kotlin MCP SDK (`io.modelcontextprotocol:kotlin-sdk-client`), lists the
 * tools that server offers, and surfaces each as an ordinary DroidCommand [Tool] (a [RemoteMcpTool])
 * so the agent can call it exactly like a local one. This is the "MCP client" Phase 5 named as the
 * counterpart to the already-shipped server.
 *
 * **Security posture — external tools are untrusted and fail-closed.** Every surfaced [RemoteMcpTool]
 * is `SecurityLevel.SENSITIVE`, so `ToolSpec.requiresConfirmation` defaults true: a remote MCP tool
 * reaches nothing except through the **same** `core-security.SecureToolExecutor` policy / approval /
 * grant / audit gate as every other tool — it is not a parallel, trusted path. A remote server's own
 * tool names never silently shadow a built-in: each is namespaced with [namePrefix] (default `mcp_`)
 * when registered. Connecting to a server, and registering its tools, is an explicit opt-in a caller
 * performs; nothing here auto-discovers or auto-registers.
 *
 * **Connection lifecycle (coroutine ↔ synchronous bridge).** The SDK is coroutine-based and keeps a
 * live connection (its transport runs a receive loop), while a DroidCommand [Tool.execute] is
 * synchronous. [connect] therefore starts one dedicated thread that holds the connection open inside a
 * single `runBlocking` scope (suspending on [closeSignal], never blocking the thread, so the SDK's
 * receive loop keeps running); [listRemoteTools] and [call] issue their requests with their own
 * `runBlocking` from the caller thread, and the shared [Client]'s internal request/response state
 * resolves them across threads. [close] ends the held scope and joins the thread. This mirrors the
 * real deployment topology (client here, server in another process) rather than a test-only shortcut.
 *
 * First-slice scope, stated honestly: the transport is injected ([connect] takes any SDK [Transport]),
 * so this works today against an in-process server (the tests) or any transport a caller constructs
 * (e.g. an SDK stdio transport over a spawned process). It does **not** itself spawn or manage external
 * server **processes**, and it exposes only MCP *tools* (not prompts/resources) and maps arguments as a
 * flat string map — process launching, richer typed arguments, and prompts/resources are deliberate
 * follow-ups. It is not designed for concurrent [call]s on one client.
 */
class McpToolClient(
    clientName: String = "droidcommand-ai",
    clientVersion: String = "0.1.0",
) : AutoCloseable {
    private val client = Client(Implementation(name = clientName, version = clientVersion), ClientOptions())
    private val ready = CompletableDeferred<Unit>()
    private val closeSignal = Job()

    @Volatile
    private var connectFailure: Throwable? = null

    @Volatile
    private var worker: Thread? = null

    /**
     * Connects to the server reachable over [transport] and blocks until the MCP handshake completes
     * (or rethrows the failure). The connection stays open until [close]. Call this once per client.
     */
    fun connect(transport: Transport) {
        check(worker == null) { "McpToolClient is already connected" }
        worker = thread(name = "mcp-client", isDaemon = true) {
            try {
                runBlocking {
                    client.connect(transport)
                    ready.complete(Unit)
                    // Suspend (do not block the thread) so the SDK transport's receive loop keeps
                    // running on this scope until close() completes the signal.
                    closeSignal.join()
                    client.close()
                }
            } catch (e: Throwable) {
                connectFailure = e
                ready.complete(Unit)
            }
        }
        runBlocking { ready.await() }
        connectFailure?.let { throw IllegalStateException("MCP client failed to connect: ${it.message}", it) }
    }

    /** The tools the connected server offers. */
    fun listRemoteTools(): List<RemoteToolDescriptor> = runBlocking {
        client.listTools().tools.map { RemoteToolDescriptor(it.name, it.description ?: "") }
    }

    /**
     * Calls the remote tool [remoteName] with [arguments] and maps the MCP result to a [ToolResult]:
     * an `isError` result becomes [ToolResult.Failure], otherwise [ToolResult.Success], with the text
     * of all text-content blocks joined. (The MCP shape has no partial/unexpected distinction, so those
     * `ToolResult` variants are intentionally never fabricated here.)
     */
    fun call(remoteName: String, arguments: Map<String, String>): ToolResult = runBlocking {
        mapResult(client.callTool(remoteName, arguments))
    }

    /**
     * Each remote tool as a DroidCommand [Tool], namespaced with [namePrefix] and gated
     * `SENSITIVE`/confirm. Register the returned tools in a `ToolRegistry` to let the agent use them.
     */
    fun asTools(namePrefix: String = DEFAULT_TOOL_PREFIX): List<Tool> =
        listRemoteTools().map { RemoteMcpTool(this, it.name, it.description, namePrefix) }

    override fun close() {
        closeSignal.complete()
        worker?.join(CLOSE_JOIN_TIMEOUT_MS)
        worker = null
    }

    private fun mapResult(result: CallToolResult): ToolResult {
        val text = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
        return if (result.isError == true) ToolResult.Failure(text) else ToolResult.Success(text)
    }

    companion object {
        const val DEFAULT_TOOL_PREFIX = "mcp_"
        private const val CLOSE_JOIN_TIMEOUT_MS = 2_000L
    }
}

/**
 * A single tool offered by a connected external MCP server, adapted to the DroidCommand [Tool]
 * contract. Its [ToolSpec] is `SENSITIVE` (so `requiresConfirmation` defaults true) because the tool
 * runs on an external, untrusted server; it is reached only through the normal
 * `SecureToolExecutor` gate, never a separate trusted path. [execute] forwards to [McpToolClient.call].
 */
class RemoteMcpTool internal constructor(
    private val client: McpToolClient,
    private val remoteName: String,
    description: String,
    namePrefix: String,
) : Tool {
    override val spec = ToolSpec(
        name = namePrefix + remoteName,
        description = description.ifBlank { "Remote MCP tool '$remoteName' (external server)" },
        securityLevel = SecurityLevel.SENSITIVE,
    )

    override fun execute(input: Map<String, String>): ToolResult = client.call(remoteName, input)
}
