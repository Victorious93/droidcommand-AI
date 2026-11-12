package ai.droidcommand.app.mcp

import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.config.AssistantCapabilitySettingsStore
import ai.droidcommand.config.EncryptedSecretsVault
import ai.droidcommand.remote.HttpTransport
import ai.droidcommand.security.ApprovalPrompt
import ai.droidcommand.skills.mcp.HttpMcpToolSource
import ai.droidcommand.skills.mcp.McpLoadedTool
import ai.droidcommand.skills.mcp.McpResolution
import ai.droidcommand.skills.mcp.McpSecrets
import ai.droidcommand.skills.mcp.McpSelections
import ai.droidcommand.skills.mcp.McpServerConfig
import ai.droidcommand.skills.mcp.McpServerStatus
import ai.droidcommand.skills.mcp.McpSettings
import ai.droidcommand.skills.mcp.McpToolBinding
import ai.droidcommand.skills.mcp.McpToolCatalog
import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The phone side of MCP: the owner's server list, and loading those servers' tools into the app's one
 * [ToolRegistry] through core-skills' [McpToolCatalog].
 *
 * Off by default: nothing is contacted unless the `mcpClientEnabled` Kai capability is on (Advanced → Extra AI
 * tools). Loaded tools reach nothing by themselves — a helper bot must list them, and every call goes through the
 * app's `SecureToolExecutor` and approval dialog, plus the extra ask for tools that change something.
 *
 * [refresh] blocks on the network: call it off the main thread. [setEnabled] and [saveServers] never wait for the
 * network: turning MCP or one server off, or removing a server, takes its tools away at once, even while a
 * [refresh] is still waiting for a server. That refresh then starts over with the new settings, so an old answer
 * can never bring the tools or a "ready" status back.
 */
@Singleton
class McpToolsRuntime @Inject constructor(
    @ApplicationContext context: Context,
    registry: ToolRegistry,
    approval: ApprovalPrompt,
    transport: HttpTransport,
    private val vault: EncryptedSecretsVault,
    private val capabilities: AssistantCapabilitySettingsStore,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val catalog = McpToolCatalog(registry, approval) { server ->
        HttpMcpToolSource(transport, server.url, server.label, token = { token(server.id) })
    }
    private val mutableStatuses = MutableStateFlow<List<McpServerStatus>>(emptyList())
    val statuses: StateFlow<List<McpServerStatus>> = mutableStatuses.asStateFlow()

    private val publishLock = Any()

    @Volatile
    private var loaded = false

    fun enabled(): Boolean = capabilities.load().mcpClientEnabled

    fun setEnabled(on: Boolean) {
        capabilities.save(capabilities.load().copy(mcpClientEnabled = on))
        if (!on) {
            catalog.clear()
            loaded = false
            publish()
        }
    }

    fun servers(): List<McpServerConfig> = McpServerCodec.decode(prefs.getString(KEY_SERVERS, null))

    fun saveServers(servers: List<McpServerConfig>) {
        prefs.edit { putString(KEY_SERVERS, McpServerCodec.encode(servers)) }
        // Takes away the tools of a server that was just turned off or removed, without waiting for a refresh.
        catalog.settingsChanged(McpSettings(enabled(), servers))
        publish()
    }

    /** Reconnects to every turned-on server and reloads its tools. Never throws. */
    fun refresh(): List<McpServerStatus> {
        val statuses = catalog.refresh { McpSettings(enabled(), servers()) }
        loaded = enabled()
        publish()
        return statuses
    }

    /** Shows the catalog's current statuses. Always the latest, whichever thread gets here last. */
    private fun publish() = synchronized(publishLock) { mutableStatuses.value = catalog.lastStatuses }

    /** Loads the tools once if MCP is on and they have not been loaded since the app started. */
    fun refreshIfNeeded() {
        if (!loaded && enabled()) refresh()
    }

    fun loadedToolNames(): List<String> = catalog.toolNames()

    fun loadedTools(): List<McpLoadedTool> = catalog.loadedTools()

    /**
     * What a helper bot's saved tools mean right now. An MCP tool counts only while the very server entry and
     * remote tool the owner ticked are loaded; a replacement server or an unclear name is never allowed.
     */
    fun resolve(allowedTools: Set<String>, bindings: Map<String, McpToolBinding>): McpResolution = McpSelections.resolve(
        allowedTools = allowedTools,
        bindings = bindings,
        loaded = if (enabled()) catalog.loadedTools() else emptyList(),
        servers = servers(),
        answered = catalog.lastStatuses.filterIsInstance<McpServerStatus.Loaded>().map { it.server.instanceId }.toSet(),
    )

    private fun token(serverId: String): String? = try {
        vault.getSecret(McpSecrets.tokenId(serverId))
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val PREFS = "mcp_servers"
        const val KEY_SERVERS = "servers.v1"
    }
}
