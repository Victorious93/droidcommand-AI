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

/** The owner's MCP settings at one moment: the master switch and the server list. */
data class McpSettings(val enabled: Boolean, val servers: List<McpServerConfig>)

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
 *
 * Taking access away ([clear], [settingsChanged]) never waits for the network. Servers are asked for their tools
 * outside the lock, and an answer that arrives after the owner turned MCP or that server off is thrown away:
 * it registers nothing and reports nothing. A removed tool is also marked revoked, so a call that was waiting
 * on the owner's approval sends nothing when approved afterwards (see [McpBridgedTool.execute]).
 */
class McpToolCatalog(
    private val registry: ToolRegistry,
    private val approval: ApprovalPrompt,
    private val sourceFor: (McpServerConfig) -> McpToolSource,
) {
    /** Guards the fields below. Never held while talking to a server. */
    private val lock = Any()

    /** One refresh at a time. Held across the network, so nothing that takes access away may wait on it. */
    private val refreshLock = Any()
    private val registered = mutableListOf<McpBridgedTool>()
    private val sources = mutableMapOf<McpServerConfig, McpToolSource>()

    /** Bumped whenever the owner's settings change or a refresh starts; a refresh that sees a newer value is stale. */
    private var generation = 0L

    @Volatile
    var lastStatuses: List<McpServerStatus> = emptyList()
        private set

    /** Names of the MCP tools currently in the registry. */
    fun toolNames(): List<String> = synchronized(lock) { registered.map { it.spec.name } }

    /**
     * Reloads the tools for [servers] once. If the owner changes the settings while a server is still answering,
     * nothing from this refresh is kept and the current (already narrowed) statuses are returned.
     */
    fun refresh(enabled: Boolean, servers: List<McpServerConfig>): List<McpServerStatus> =
        synchronized(refreshLock) { attempt(McpSettings(enabled, servers)) ?: lastStatuses }

    /**
     * Reloads the tools for the owner's current settings. [settings] is read again, and the load started over,
     * whenever the settings change while a server is still answering, so the result always matches the settings
     * as they are when this returns.
     */
    fun refresh(settings: () -> McpSettings): List<McpServerStatus> = synchronized(refreshLock) {
        var statuses = attempt(settings())
        while (statuses == null) statuses = attempt(settings())
        statuses
    }

    /**
     * The owner saved new settings. Right away, without waiting for any server: the tools of every server that is
     * no longer present and turned on (or all of them when MCP is off) are removed and revoked, and a refresh that
     * is still waiting for an answer is made stale. Call [refresh] afterwards to load newly turned-on servers.
     */
    fun settingsChanged(settings: McpSettings) {
        val closing = synchronized(lock) {
            generation++
            val keep = if (settings.enabled) settings.servers.filter { it.enabled }.toSet() else emptySet()
            lastStatuses = lastStatuses.filter { it.server in settings.servers && (it !is McpServerStatus.Loaded || it.server in keep) }
            removeLocked { it !in keep }
        }
        closeInBackground(closing)
    }

    /** Removes every MCP tool from the registry, right away, and closes the connections. */
    fun clear() {
        val closing = synchronized(lock) {
            generation++
            lastStatuses = emptyList()
            removeLocked { true }
        }
        closeInBackground(closing)
    }

    /** What one server gave during a refresh: a finished status, or tools still to be registered. */
    private sealed interface Outcome

    private class Settled(val status: McpServerStatus) : Outcome

    private class Discovered(val server: McpServerConfig, val source: McpToolSource, val tools: List<McpToolInfo>) : Outcome

    /** One load. Null when the settings changed while it ran: nothing was registered and nothing reported. */
    private fun attempt(settings: McpSettings): List<McpServerStatus>? {
        val (ticket, old) = synchronized(lock) {
            lastStatuses = emptyList()
            ++generation to removeLocked { true }
        }
        old.forEach { closeQuietly(it) }

        val outcomes = mutableListOf<Outcome>()
        for (server in settings.servers) {
            outcomes += when {
                !settings.enabled -> Settled(McpServerStatus.Skipped(server, "Extra AI tools (MCP) are off. Nothing was contacted."))
                !server.enabled -> Settled(McpServerStatus.Skipped(server, "${server.label} is turned off."))
                else -> {
                    // Settings changed mid-refresh: do not contact the remaining servers for a result nobody will use.
                    if (synchronized(lock) { generation } != ticket) break
                    discover(server)
                }
            }
        }

        val statuses = synchronized(lock) {
            if (generation != ticket) {
                null
            } else {
                outcomes.map { if (it is Discovered) registerLocked(it) else (it as Settled).status }.also { lastStatuses = it }
            }
        }
        if (statuses == null) outcomes.filterIsInstance<Discovered>().forEach { closeQuietly(it.source) }
        return statuses
    }

    /** Asks [server] for its tools. Talks to the network, so it is never called with [lock] held. */
    private fun discover(server: McpServerConfig): Outcome {
        val source = try {
            sourceFor(server)
        } catch (e: Exception) {
            return Settled(McpServerStatus.Failed(server, "${server.label}: ${e.message ?: "could not be set up."}"))
        }
        val infos = try {
            source.listTools()
        } catch (e: McpException) {
            closeQuietly(source)
            return Settled(McpServerStatus.Failed(server, e.message ?: "${server.label} did not answer."))
        } catch (e: Exception) {
            closeQuietly(source)
            return Settled(McpServerStatus.Failed(server, "${server.label} did not answer properly."))
        }
        return Discovered(server, source, infos)
    }

    private fun registerLocked(found: Discovered): McpServerStatus {
        sources[found.server] = found.source
        val names = mutableListOf<String>()
        found.tools.take(MAX_TOOLS_PER_SERVER).forEach { info ->
            val tool = McpBridgedTool(found.server, info, found.source, approval)
            try {
                registry.register(tool)
                registered += tool
                names += tool.spec.name
            } catch (_: DuplicateToolException) {
                // Two remote names that clean up to the same tool name: keep the first, skip the rest.
            }
        }
        return McpServerStatus.Loaded(found.server, names)
    }

    /** Unregisters and revokes the tools of every server matching [gone]; returns their connections to close. */
    private fun removeLocked(gone: (McpServerConfig) -> Boolean): List<McpToolSource> {
        val removed = registered.filter { gone(it.server) }
        removed.forEach {
            it.revoke()
            registry.unregister(it.spec.name)
        }
        registered.removeAll(removed)
        val closing = sources.filterKeys(gone)
        sources.keys.removeAll(closing.keys)
        return closing.values.toList()
    }

    /**
     * Closing says goodbye to the server and waits for a call that is still running, so it must not hold up the
     * owner's "off" tap (which may come from the screen's own thread).
     */
    private fun closeInBackground(closing: List<McpToolSource>) {
        if (closing.isEmpty()) return
        Thread({ closing.forEach { closeQuietly(it) } }, "mcp-close").apply { isDaemon = true }.start()
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
