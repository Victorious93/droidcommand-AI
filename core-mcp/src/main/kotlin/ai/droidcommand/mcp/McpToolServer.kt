package ai.droidcommand.mcp

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.ToolArgumentCodec
import ai.droidcommand.agent.ToolCallEnvelope
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolRunner
import ai.droidcommand.agent.ToolSchemaValidation
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

/**
 * Exposes a [ToolRegistry]'s tools to any MCP client (Claude Desktop/Code,
 * the MCP Inspector, or a custom agent) over the real Kotlin MCP SDK
 * (`io.modelcontextprotocol:kotlin-sdk-server`) — closing ROADMAP-123 /
 * DP-001, which recommended exactly this rather than a hand-rolled
 * protocol implementation.
 *
 * Every exposed tool is dispatched through a [ToolRunner] — satisfied by
 * the plain `core-agent.ToolExecutor` for callers that only need Pilot
 * Mode's mode/initiator gates, and by `core-security.SecureToolExecutor`
 * for callers that also need its policy/approval/grant/audit pipeline. An
 * MCP client is an [Initiator.REMOTE] caller by this server's own default,
 * exactly the kind of caller a production deployment should run behind
 * `SecureToolExecutor` rather than the bare executor — this class accepts
 * either, at a fixed [mode]/[initiator] chosen once for the whole server.
 * This means an MCP-exposed tool is scoped by the same
 * `ToolSpec.allowedModes`/`requiredInitiator` gates as every other caller
 * (and, when [executor] is a `SecureToolExecutor`, the same
 * policy/approval/grant/audit gates too), not a separate, parallel
 * permission path.
 *
 * **Input schema honesty:** [ai.droidcommand.agent.ToolSpec] does not model
 * a tool's parameter shape (documented already in
 * `core-llm-anthropic.AnthropicToolDefinition`), so every tool is advertised
 * with the SDK's permissive default `ToolSchema()` (`{"type":"object"}`,
 * no declared properties) rather than a fabricated one.
 *
 * **Argument mapping:** MCP arguments arrive as JSON of any type and are
 * decoded through [ai.droidcommand.agent.ToolArgumentCodec] into a
 * [ai.droidcommand.agent.ToolCallEnvelope] (new call id per invocation,
 * never reused by this server). JSON primitives become their raw text
 * (`42` → `"42"`); JSON `null` is omitted; arrays/objects become compact
 * JSON text rather than being dropped. The envelope's values are then
 * flattened with [ai.droidcommand.agent.ToolArgumentCodec.toStringMap]
 * into the `Map<String, String>` every existing [ai.droidcommand.agent.Tool]
 * already implements. When [ai.droidcommand.agent.ToolSpec.parameterSchema]
 * is set, the typed arguments are validated before the executor runs; a
 * mismatch is returned as an MCP error and does not invoke the tool.
 * Widening [ai.droidcommand.agent.Tool.execute] itself remains separately
 * scoped.
 */
class McpToolServer(
    private val registry: ToolRegistry,
    private val executor: ToolRunner,
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
                val envelope = ToolCallEnvelope(
                    callId = ToolCallEnvelope.newId(),
                    toolName = spec.name,
                    arguments = ToolArgumentCodec.fromJsonMap(request.arguments),
                )
                val schema = spec.parameterSchema
                if (schema != null) {
                    when (val check = schema.validate(envelope.arguments)) {
                        is ToolSchemaValidation.Invalid -> {
                            val message = "ERROR: tool call ${envelope.callId}: ${check.reason}"
                            return@addTool CallToolResult(
                                content = listOf(TextContent(message)),
                                isError = true,
                            )
                        }
                        ToolSchemaValidation.Valid -> Unit
                    }
                }
                val input = ToolArgumentCodec.toStringMap(envelope.arguments)
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
