package ai.droidcommand.mcp

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.describe
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonPrimitive

/**
 * Exposes a [ToolRegistry]'s tools to any MCP client (Claude Desktop/Code,
 * the MCP Inspector, or a custom agent) over the real Kotlin MCP SDK
 * (`io.modelcontextprotocol:kotlin-sdk-server`) — closing ROADMAP-123 /
 * DP-001, which recommended exactly this rather than a hand-rolled
 * protocol implementation.
 *
 * Every exposed tool is dispatched through the same [ToolExecutor] Pilot
 * Mode and `ObjectiveEngine` already use, at a fixed [mode]/[initiator]
 * chosen once for the whole server (defaulting to [Initiator.REMOTE],
 * `core-security`'s existing category for a caller outside this process —
 * an MCP client is exactly that). This means an MCP-exposed tool is
 * scoped by the same `ToolSpec.allowedModes`/`requiredInitiator` gates as
 * every other caller, not a separate, parallel permission path.
 *
 * **Input schema honesty:** [ai.droidcommand.agent.ToolSpec] does not model
 * a tool's parameter shape (documented already in
 * `core-llm-anthropic.AnthropicToolDefinition`), so every tool is advertised
 * with the SDK's permissive default `ToolSchema()` (`{"type":"object"}`,
 * no declared properties) rather than a fabricated one.
 *
 * **Argument mapping:** [ai.droidcommand.agent.Tool.execute] takes a
 * `Map<String, String>`, but MCP arguments arrive as a JSON object whose
 * values can be any JSON type. Only JSON primitive values are converted
 * (via their raw text form, e.g. `42` -> `"42"`, matching how existing
 * tools like `set_volume` already parse a numeric string themselves); an
 * array or nested-object argument value is dropped rather than guessed at,
 * since [ai.droidcommand.agent.ToolSpec] has no schema to say what shape it
 * should take.
 */
class McpToolServer(
    private val registry: ToolRegistry,
    private val executor: ToolExecutor,
    private val mode: AgentMode? = null,
    private val initiator: Initiator? = Initiator.REMOTE,
    private val serverName: String = "droidcommand-ai",
    private val serverVersion: String = "0.1.0",
) {
    /**
     * Builds a fresh [Server] with every tool [registry] offers for
     * [mode]/[initiator] registered. Building fresh each time (rather than
     * caching one instance) mirrors the SDK's own samples, since a `Server`
     * is bound to exactly one [io.modelcontextprotocol.kotlin.sdk.server.ServerSession]
     * at a time.
     */
    fun buildServer(): Server {
        val server = Server(
            Implementation(name = serverName, version = serverVersion),
            ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
            ),
        )

        for (spec in registry.list(mode, initiator)) {
            server.addTool(name = spec.name, description = spec.description) { request ->
                val input = request.arguments
                    ?.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.content?.let { key to it } }
                    ?.toMap()
                    .orEmpty()
                val result = executor.run(spec.name, input, mode = mode, initiator = initiator)
                CallToolResult(content = listOf(TextContent(result.describe())), isError = result is ToolResult.Failure)
            }
        }

        return server
    }

    /**
     * Runs [buildServer]'s server over real process stdin/stdout until the
     * client disconnects — the entry point a launcher process (an `app`
     * module, or a directly-run JVM process configured as an MCP server
     * command) calls. Blocks the calling thread for the lifetime of the
     * session, matching the SDK's own stdio sample pattern.
     */
    fun runStdio() {
        val transport = StdioServerTransport(
            System.`in`.asSource().buffered(),
            System.out.asSink().buffered(),
        ) { }
        runBlocking {
            val session = buildServer().createSession(transport)
            val done = Job()
            session.onClose { done.complete() }
            done.join()
        }
    }
}
