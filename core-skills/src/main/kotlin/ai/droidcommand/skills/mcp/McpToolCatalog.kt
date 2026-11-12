package ai.droidcommand.skills.mcp

import ai.droidcommand.agent.DuplicateToolException
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.security.ApprovalPrompt

/** What happened when a server's tools were loaded, in plain words for the settings screen. */
sealed class McpServerStatus {
    abstract val server: McpServerConfig
    abstract val words: String

    /**
     * [longNamed] tools got a longer name with a code on the end, because their usual name could be mixed up with
     * another tool. [leftOut] tools could not be told apart at all (the server listed the same name twice).
     */
    data class Loaded(
        override val server: McpServerConfig,
        val toolNames: List<String>,
        val longNamed: Int = 0,
        val leftOut: Int = 0,
    ) : McpServerStatus() {
        override val words: String = buildString {
            append(if (toolNames.isEmpty()) "${server.label} is connected but offers no tools." else "${server.label}: ${toolNames.size} tool(s) ready.")
            if (longNamed > 0) append(" $longNamed got a longer name so they cannot be mixed up with another tool.")
            if (leftOut > 0) append(" $leftOut left out: the server lists the same name twice.")
        }
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
 *
 * A tool name never changes its meaning while the app runs: once a name stood for one server entry's one remote
 * tool ([McpToolBinding]), no other tool is registered under it. Another server added under the same label, or
 * a second remote name that cleans up to the same local name, gets [McpBridgedTool.longName] instead. So a bot
 * that is already open can never reach a different tool through a name it was allowed earlier. Across restarts
 * the saved bot's own binding does that job (see [McpSelections]).
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

    /** Every name this catalog has registered since the app started, and the one tool it stands for. */
    private val meaning = mutableMapOf<String, McpToolBinding>()

    /** Bumped whenever the owner's settings change or a refresh starts; a refresh that sees a newer value is stale. */
    private var generation = 0L

    @Volatile
    var lastStatuses: List<McpServerStatus> = emptyList()
        private set

    /** Names of the MCP tools currently in the registry. */
    fun toolNames(): List<String> = synchronized(lock) { registered.map { it.spec.name } }

    /** The MCP tools currently in the registry, each with the server entry and remote tool it stands for. */
    fun loadedTools(): List<McpLoadedTool> =
        synchronized(lock) { registered.map { McpLoadedTool(it.spec.name, it.binding, it.server.label) } }

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
        val localNames = namesLocked(found.server, found.tools.map { it.name })
        val usable = found.tools.filter { it.name in localNames }
        val names = mutableListOf<String>()
        var longNamed = 0
        usable.take(MAX_TOOLS_PER_SERVER).forEach { info ->
            val name = localNames.getValue(info.name)
            val tool = McpBridgedTool(found.server, info, found.source, approval, name)
            try {
                registry.register(tool)
                registered += tool
                meaning[name] = tool.binding
                names += name
                if (name != McpBridgedTool.toolName(found.server.id, info.name)) longNamed++
            } catch (_: DuplicateToolException) {
                // The name is taken by a tool that is not ours. Leave it out.
            }
        }
        return McpServerStatus.Loaded(found.server, names, longNamed, leftOut = found.tools.map { it.name }.distinct().size - localNames.size)
    }

    /**
     * The local name for each of [server]'s remote tool names; a remote name that gets none is left out.
     *
     * The answer does not depend on the order of [remoteNames]. A remote name gets its usual name only when no
     * other remote name of this server cleans up to the same one and that name has not stood for another tool.
     * Otherwise it gets [McpBridgedTool.longName]. A remote name listed twice is left out: which of the two
     * descriptions (and "read only" hints) is the real one cannot be known.
     */
    private fun namesLocked(server: McpServerConfig, remoteNames: List<String>): Map<String, String> {
        val listedTwice = remoteNames.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        val distinct = remoteNames.distinct()
        val sharing = distinct.groupingBy { McpBridgedTool.toolName(server.id, it) }.eachCount()
        val proposed = distinct.filter { it !in listedTwice }.mapNotNull { remote ->
            val binding = McpToolBinding(server.instanceId, remote)
            val free = { name: String -> meaning[name].let { it == null || it == binding } }
            val usual = McpBridgedTool.toolName(server.id, remote)
            val name = if (sharing.getValue(usual) == 1 && free(usual)) usual else McpBridgedTool.longName(server, remote).takeIf(free)
            name?.let { remote to it }
        }
        val clashing = proposed.groupingBy { it.second }.eachCount().filterValues { it > 1 }.keys
        return proposed.filter { it.second !in clashing }.toMap()
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
