package ai.droidcommand.skills.mcp

import ai.droidcommand.agent.DuplicateToolException
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.security.ApprovalPrompt

/** What happened when a server's tools were loaded, in plain words for the settings screen. */
sealed class McpServerStatus {
    abstract val server: McpServerConfig
    abstract val words: String

    data class Loaded(override val server: McpServerConfig, val toolNames: List<String>) : McpServerStatus() {
        override val words: String =
            if (toolNames.isEmpty()) "${server.label} is connected but offers no tools." else "${server.label}: ${toolNames.size} tool(s) ready."
    }

    data class Failed(override val server: McpServerConfig, override val words: String) : McpServerStatus()

    data class Skipped(override val server: McpServerConfig, override val words: String) : McpServerStatus()
}

/**
 * Loads the tools of the owner's MCP servers into the app's [ToolRegistry], and takes them out again.
 *
 * Off by default: [refresh] with `enabled = false` (the `mcpClientEnabled` Kai capability, default false)
 * removes every MCP tool and connects to nothing. Registering a tool grants nothing by itself — it only makes
 * the tool *pickable* in Bot Maker. A helper bot must list it (its `ScopedToolRunner` allow-list), and every
 * call still goes through `SecureToolExecutor` and the approval dialog (see [McpBridgedTool]). A skill file's
 * declared `tools` list never adds one either.
 *
 * A server that is down, slow, or sends something unreadable gives a [McpServerStatus.Failed] with a plain
 * message; it never throws and never stops other servers from loading.
 */
class McpToolCatalog(
    private val registry: ToolRegistry,
    private val approval: ApprovalPrompt,
    private val sourceFor: (McpServerConfig) -> McpToolSource,
) {
    private val lock = Any()
    private val registered = mutableListOf<String>()
    private val sources = mutableListOf<McpToolSource>()

    @Volatile
    var lastStatuses: List<McpServerStatus> = emptyList()
        private set

    /** Names of the MCP tools currently in the registry. */
    fun toolNames(): List<String> = synchronized(lock) { registered.toList() }

    fun refresh(enabled: Boolean, servers: List<McpServerConfig>): List<McpServerStatus> = synchronized(lock) {
        clearLocked()
        val statuses = servers.map { server ->
            when {
                !enabled -> McpServerStatus.Skipped(server, "Extra AI tools (MCP) are off. Nothing was contacted.")
                !server.enabled -> McpServerStatus.Skipped(server, "${server.label} is turned off.")
                else -> load(server)
            }
        }
        lastStatuses = statuses
        statuses
    }

    /** Removes every MCP tool from the registry and closes the connections. */
    fun clear() = synchronized(lock) {
        clearLocked()
        lastStatuses = emptyList()
    }

    private fun load(server: McpServerConfig): McpServerStatus {
        val source = try {
            sourceFor(server)
        } catch (e: Exception) {
            return McpServerStatus.Failed(server, "${server.label}: ${e.message ?: "could not be set up."}")
        }
        val infos = try {
            source.listTools()
        } catch (e: McpException) {
            closeQuietly(source)
            return McpServerStatus.Failed(server, e.message ?: "${server.label} did not answer.")
        } catch (e: Exception) {
            closeQuietly(source)
            return McpServerStatus.Failed(server, "${server.label} did not answer properly.")
        }
        sources += source
        val names = mutableListOf<String>()
        infos.take(MAX_TOOLS_PER_SERVER).forEach { info ->
            val tool = McpBridgedTool(server, info, source, approval)
            try {
                registry.register(tool)
                registered += tool.spec.name
                names += tool.spec.name
            } catch (_: DuplicateToolException) {
                // Two remote names that clean up to the same tool name: keep the first, skip the rest.
            }
        }
        return McpServerStatus.Loaded(server, names)
    }

    private fun clearLocked() {
        registered.forEach { registry.unregister(it) }
        registered.clear()
        sources.forEach { closeQuietly(it) }
        sources.clear()
    }

    private fun closeQuietly(source: McpToolSource) {
        try {
            source.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        /** Keeps a huge server from flooding the tool list the model is sent. */
        const val MAX_TOOLS_PER_SERVER = 50
    }
}
