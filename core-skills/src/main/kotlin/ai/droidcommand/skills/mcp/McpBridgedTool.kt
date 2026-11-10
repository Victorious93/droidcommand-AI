package ai.droidcommand.skills.mcp

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec
import ai.droidcommand.security.ApprovalPrompt

/**
 * One tool from an opt-in MCP server, as an ordinary DroidCommand [Tool].
 *
 * It is registered in the app's normal `ToolRegistry`, so it is reached only through the normal path:
 * a helper bot's `ScopedToolRunner` allow-list first (a tool the bot did not list is refused before anything
 * else), then `SecureToolExecutor` (policy, the approval dialog, audit). Nothing here is a second, trusted path.
 *
 * Asking the owner:
 * - Every MCP tool is [SecurityLevel.SENSITIVE] with [PermissionCategory.NETWORK], so `SecureToolExecutor` shows
 *   the normal approval dialog before it runs (the read-only auto-approve list never matches a non-VIEW tool).
 * - A tool that can change something ([McpToolKind.WRITE] or [McpToolKind.DESTRUCTIVE]) also asks through
 *   [approval] directly inside [execute], with the exact inputs, before anything is sent. That second ask does not
 *   consult the auto-approve policy, so YOLO mode or a saved grant can never skip it — same pattern as the System
 *   Toolbox tool.
 *
 * It is never offered to the Pilot or Forge planners ([ToolSpec.allowedModes] is empty); only Chat's helper-bot
 * path, which lists tools without a mode, can offer it. Server failures become a plain [ToolResult.Failure].
 */
class McpBridgedTool(
    val server: McpServerConfig,
    val info: McpToolInfo,
    private val source: McpToolSource,
    private val approval: ApprovalPrompt,
) : Tool {
    val kind: McpToolKind = McpToolKind.of(info)

    override val spec: ToolSpec = ToolSpec(
        name = toolName(server.id, info.name),
        description = describe(server, info, kind),
        securityLevel = SecurityLevel.SENSITIVE,
        requiresConfirmation = true,
        permissionCategory = PermissionCategory.NETWORK,
        allowedModes = emptySet(),
        parameterSchema = info.parameters,
    )

    override fun execute(input: Map<String, String>): ToolResult {
        if (kind != McpToolKind.READ_ONLY) {
            val ok = try {
                approval.requestApproval(spec.name, input, confirmReason(server, info, kind))
            } catch (e: Exception) {
                false
            }
            if (!ok) return ToolResult.Failure("You said no. Nothing was sent to ${server.label}.")
        }
        return try {
            when (val result = source.call(info.name, input)) {
                is ToolResult.Success -> ToolResult.Success(clip(result.output))
                is ToolResult.Failure -> ToolResult.Failure(
                    "${server.label} said the tool failed: ${clip(result.reason).ifBlank { "no details" }}",
                )
                else -> result
            }
        } catch (e: McpException) {
            ToolResult.Failure(e.message ?: "${server.label} did not answer.")
        } catch (e: Exception) {
            ToolResult.Failure("${server.label} did not answer properly, so nothing more was done.")
        }
    }

    companion object {
        const val PREFIX = "mcp_"

        /** LLM providers accept tool names of 1-64 letters, digits, '_' or '-'. */
        private const val MAX_NAME = 64
        const val MAX_OUTPUT = 16_000

        fun toolName(serverId: String, remoteName: String): String {
            val safe = remoteName.map { if (it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-') it else '_' }
                .joinToString("")
                .ifEmpty { "tool" }
            return (PREFIX + serverId + "_" + safe).take(MAX_NAME)
        }

        fun isMcpTool(name: String): Boolean = name.startsWith(PREFIX)

        fun confirmReason(server: McpServerConfig, info: McpToolInfo, kind: McpToolKind): String = when (kind) {
            McpToolKind.DESTRUCTIVE ->
                "The assistant wants ${server.label} to run \"${info.name}\". This can delete or overwrite things " +
                    "and may not be undoable. Only say yes if you meant this."
            McpToolKind.WRITE ->
                "The assistant wants ${server.label} to run \"${info.name}\", which changes something. Say yes to send it."
            McpToolKind.READ_ONLY ->
                "The assistant wants ${server.label} to run \"${info.name}\" (read only)."
        }

        private fun describe(server: McpServerConfig, info: McpToolInfo, kind: McpToolKind): String = buildString {
            append(info.description.ifBlank { "Tool \"${info.name}\"" }.take(1_000))
            append(" [From MCP server \"").append(server.label).append("\"")
            when (kind) {
                McpToolKind.READ_ONLY -> append("; read only")
                McpToolKind.WRITE -> append("; changes things, asks the owner first")
                McpToolKind.DESTRUCTIVE -> append("; can delete or overwrite, asks the owner first")
            }
            append(".]")
            val params = info.parameters?.parameters.orEmpty()
            if (params.isNotEmpty()) {
                append(" Inputs: ")
                append(
                    params.joinToString(", ") { p ->
                        p.name + " (" + p.type.name.lowercase() + (if (p.required) ", required" else "") + ")"
                    },
                )
                append(".")
            }
        }

        private fun clip(text: String): String =
            if (text.length <= MAX_OUTPUT) text else text.take(MAX_OUTPUT) + "\n…(cut short)"
    }
}
