package ai.droidcommand.skills.mcp

import ai.droidcommand.agent.ToolParameterSchema
import ai.droidcommand.agent.ToolResult

/**
 * One tool an MCP server offers, as that server describes it.
 *
 * [readOnlyHint] and [destructiveHint] are the MCP `ToolAnnotations` hints. They come from the server, so they
 * are only ever used to ask *more* (see [McpToolKind.of]); a missing hint never makes a tool look safer.
 */
data class McpToolInfo(
    val name: String,
    val description: String = "",
    val readOnlyHint: Boolean? = null,
    val destructiveHint: Boolean? = null,
    val parameters: ToolParameterSchema? = null,
)

/** How much an MCP tool can change, from its hints. Decides how many times the owner is asked. */
enum class McpToolKind {
    /** The server says it only reads. Still asks once through the normal approval dialog. */
    READ_ONLY,

    /** Changes something, but the server says nothing is deleted or overwritten. Asks before every call. */
    WRITE,

    /** Can delete or overwrite. Asks before every call with a stronger warning. */
    DESTRUCTIVE,
    ;

    companion object {
        /**
         * MCP's own defaults are `readOnlyHint = false` and `destructiveHint = true`, so a tool with no hints is
         * treated as [DESTRUCTIVE]. Only an explicit `readOnlyHint = true` (and no `destructiveHint = true`) is
         * [READ_ONLY]; only an explicit `destructiveHint = false` on a non-read-only tool is [WRITE].
         */
        fun of(info: McpToolInfo): McpToolKind = when {
            info.destructiveHint == true -> DESTRUCTIVE
            info.readOnlyHint == true -> READ_ONLY
            info.destructiveHint == false -> WRITE
            else -> DESTRUCTIVE
        }
    }
}

/** A failure talking to an MCP server. [message] is plain words the owner can read. */
class McpException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A connection to one MCP server that can list its tools and call one.
 *
 * Implementations: [HttpMcpToolSource] (MCP over HTTP, used by the phone app) and `core-mcp`'s `McpToolClient`
 * (the official MCP Kotlin SDK client, used on the JVM). Both may throw [McpException] or any other exception;
 * [McpBridgedTool] and [McpToolCatalog] turn every throw into a plain message, never a crash.
 */
interface McpToolSource : AutoCloseable {
    fun listTools(): List<McpToolInfo>

    fun call(name: String, arguments: Map<String, String>): ToolResult

    override fun close() {}
}
