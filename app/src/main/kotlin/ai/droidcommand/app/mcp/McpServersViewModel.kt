package ai.droidcommand.app.mcp

import ai.droidcommand.config.EncryptedSecretsVault
import ai.droidcommand.config.SecretState
import ai.droidcommand.skills.mcp.McpSecrets
import ai.droidcommand.skills.mcp.McpServerConfig
import ai.droidcommand.skills.mcp.McpServerStatus
import ai.droidcommand.skills.mcp.McpServerUrl
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class McpServerRow(val server: McpServerConfig, val hasToken: Boolean, val status: String?)

data class McpServersUi(
    val enabled: Boolean = false,
    val servers: List<McpServerRow> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    val urlProblem: String? = null,
    val urlWarning: String? = null,
    val confirmEnable: Boolean = false,
    val pendingRemove: McpServerConfig? = null,
)

/** Advanced → Extra AI tools (MCP). Off by default; turning it on asks first. */
@HiltViewModel
class McpServersViewModel @Inject constructor(
    private val runtime: McpToolsRuntime,
    private val vault: EncryptedSecretsVault,
) : ViewModel() {
    private val mutable = MutableStateFlow(McpServersUi(enabled = runtime.enabled()))
    val state: StateFlow<McpServersUi> = mutable.asStateFlow()

    init {
        reloadRows()
    }

    fun requestEnabled(on: Boolean) {
        if (on && !runtime.enabled()) {
            mutable.update { it.copy(confirmEnable = true) }
            return
        }
        runtime.setEnabled(on)
        mutable.update { it.copy(enabled = on, confirmEnable = false, message = if (on) null else "Extra AI tools are off. No server is contacted.") }
        reloadRows()
    }

    fun confirmEnable() {
        runtime.setEnabled(true)
        mutable.update { it.copy(enabled = true, confirmEnable = false) }
        refresh()
    }

    fun cancelEnable() = mutable.update { it.copy(confirmEnable = false) }

    fun checkUrl(raw: String) {
        when (val check = McpServerUrl.check(raw)) {
            is McpServerUrl.Check.Ok -> mutable.update { it.copy(urlProblem = null, urlWarning = check.warning) }
            is McpServerUrl.Check.Rejected -> mutable.update { it.copy(urlProblem = if (raw.isBlank()) null else check.reason, urlWarning = null) }
        }
    }

    /** Adds a server. Returns true when it was saved, so the screen can clear the boxes. */
    fun addServer(label: String, url: String, token: String): Boolean {
        val check = McpServerUrl.check(url)
        if (check is McpServerUrl.Check.Rejected) {
            mutable.update { it.copy(urlProblem = check.reason) }
            return false
        }
        val ok = check as McpServerUrl.Check.Ok
        val servers = runtime.servers()
        if (servers.size >= MAX_SERVERS) {
            mutable.update { it.copy(message = "You can add up to $MAX_SERVERS servers.") }
            return false
        }
        val name = label.trim().ifBlank { "MCP server" }.take(40)
        val server = McpServerConfig(McpServerConfig.newId(name, servers.map { it.id }.toSet()), name, ok.url)
        if (token.isNotBlank() && !saveTokenFor(server.id, token)) return false
        runtime.saveServers(servers + server)
        mutable.update { it.copy(urlProblem = null, urlWarning = null, message = "Added ${server.label}.") }
        if (runtime.enabled()) refresh() else reloadRows()
        return true
    }

    fun setServerOn(id: String, on: Boolean) {
        runtime.saveServers(runtime.servers().map { if (it.id == id) it.copy(enabled = on) else it })
        if (runtime.enabled()) refresh() else reloadRows()
    }

    fun saveToken(id: String, token: String) {
        if (token.isBlank()) return
        if (saveTokenFor(id, token)) {
            mutable.update { it.copy(message = "Token saved on this phone. It will not be shown again.") }
            if (runtime.enabled()) refresh() else reloadRows()
        }
    }

    fun clearToken(id: String) {
        try {
            vault.revokeSecret(McpSecrets.tokenId(id))
            mutable.update { it.copy(message = "Token removed.") }
        } catch (_: Exception) {
            mutable.update { it.copy(message = "Could not reach the phone's key storage.") }
        }
        reloadRows()
    }

    fun askRemove(server: McpServerConfig) = mutable.update { it.copy(pendingRemove = server) }

    fun cancelRemove() = mutable.update { it.copy(pendingRemove = null) }

    fun confirmRemove() {
        val server = mutable.value.pendingRemove ?: return
        runtime.saveServers(runtime.servers().filterNot { it.id == server.id })
        try {
            vault.revokeSecret(McpSecrets.tokenId(server.id))
        } catch (_: Exception) {
        }
        mutable.update { it.copy(pendingRemove = null, message = "Removed ${server.label}.") }
        if (runtime.enabled()) refresh() else reloadRows()
    }

    /** Reconnects and reloads tools. Server trouble shows as a plain message on that server's row. */
    fun refresh() {
        if (mutable.value.busy) return
        if (!runtime.enabled()) {
            mutable.update { it.copy(message = "Turn on Extra AI tools first.") }
            return
        }
        mutable.update { it.copy(busy = true, message = "Checking servers…") }
        viewModelScope.launch {
            val statuses = withContext(Dispatchers.IO) { runtime.refresh() }
            val count = statuses.filterIsInstance<McpServerStatus.Loaded>().sumOf { it.toolNames.size }
            mutable.update {
                it.copy(
                    busy = false,
                    message = if (statuses.isEmpty()) "Add a server first." else "$count tool(s) ready. Pick them for a helper bot in Bot maker.",
                )
            }
            reloadRows()
        }
    }

    private fun saveTokenFor(id: String, token: String): Boolean = try {
        vault.putSecret(McpSecrets.tokenId(id), token.trim())
        true
    } catch (_: Exception) {
        mutable.update { it.copy(message = "Could not save the token in the phone's key storage.") }
        false
    }

    private fun reloadRows() {
        val statuses = runtime.statuses.value.associateBy { it.server.id }
        val rows = runtime.servers().map { server ->
            McpServerRow(
                server = server,
                hasToken = hasToken(server.id),
                status = statuses[server.id]?.words,
            )
        }
        mutable.update { it.copy(servers = rows, enabled = runtime.enabled()) }
    }

    private fun hasToken(id: String): Boolean = try {
        vault.inspect(McpSecrets.tokenId(id)) == SecretState.Present
    } catch (_: Exception) {
        false
    }

    private companion object {
        const val MAX_SERVERS = 8
    }
}
