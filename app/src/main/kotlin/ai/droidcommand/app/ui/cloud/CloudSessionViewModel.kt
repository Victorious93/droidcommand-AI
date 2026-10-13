package ai.droidcommand.app.ui.cloud

import ai.droidcommand.cloudtunnel.CodingSession
import ai.droidcommand.cloudtunnel.FileCodingSessionStore
import ai.droidcommand.cloudtunnel.RemoteCodingTunnelTransport
import ai.droidcommand.cloudtunnel.SharedCodingTunnel
import ai.droidcommand.cloudtunnel.StubCodingTunnelTransport
import ai.droidcommand.cloudtunnel.TunnelBackendKind
import ai.droidcommand.remote.HttpTransport
import ai.droidcommand.remote.RemoteClient
import ai.droidcommand.remote.RemoteEndpoint
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CloudUiState(
    val statusNote: String = "",
    val backend: TunnelBackendKind = TunnelBackendKind.STUB,
    val endpoint: String = "",
    val sessions: List<CodingSession> = emptyList(),
    val selectedId: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * Shared coding-session tunnel. The default transport is the local stub. An HTTPS endpoint switches
 * in [RemoteCodingTunnelTransport], which posts JSON and does not claim a live Cursor or Codex session.
 * Compiles; the screen has not been run on a device.
 */
@HiltViewModel
class CloudSessionViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val http: HttpTransport,
) : ViewModel() {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val store = FileCodingSessionStore(context.filesDir.toPath().resolve("cloud-tunnel").resolve("sessions.json"))
    private var tunnel = tunnelFor(prefs.getString(KEY_ENDPOINT, null).orEmpty())
    private val mutableState = MutableStateFlow(snapshot(selectedId = null))
    val state: StateFlow<CloudUiState> = mutableState.asStateFlow()

    fun select(id: String) = mutableState.update { it.copy(selectedId = id, error = null) }

    fun setEndpoint(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            prefs.edit { remove(KEY_ENDPOINT) }
            tunnel = tunnelFor("")
            mutableState.value = snapshot()
            return
        }
        try {
            RemoteEndpoint(trimmed)
        } catch (e: IllegalArgumentException) {
            mutableState.update { it.copy(error = e.message ?: "That endpoint is not allowed.") }
            return
        }
        prefs.edit { putString(KEY_ENDPOINT, trimmed) }
        tunnel = tunnelFor(trimmed)
        mutableState.value = snapshot()
    }

    fun open(title: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val session = tunnel.open(title)
            mutableState.value = snapshot(session.id)
        }
    }

    fun submit(instruction: String) {
        val id = mutableState.value.selectedId ?: return
        if (instruction.isBlank() || mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val failure = runCatching { tunnel.submit(id, instruction) }.exceptionOrNull()
            mutableState.value = snapshot(id).copy(
                busy = false,
                error = failure?.let { it.message ?: it.javaClass.simpleName },
            )
        }
    }

    fun closeSelected() {
        val id = mutableState.value.selectedId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { tunnel.close(id) }
            mutableState.value = snapshot(id)
        }
    }

    private fun tunnelFor(endpoint: String): SharedCodingTunnel {
        val transport = if (endpoint.isBlank()) {
            StubCodingTunnelTransport()
        } else {
            try {
                RemoteCodingTunnelTransport(RemoteClient(RemoteEndpoint(endpoint), http))
            } catch (_: IllegalArgumentException) {
                StubCodingTunnelTransport()
            }
        }
        return SharedCodingTunnel(transport, store)
    }

    private fun snapshot(selectedId: String? = mutableState.value.selectedId): CloudUiState {
        val sessions = try {
            tunnel.sessions()
        } catch (e: Exception) {
            return CloudUiState(
                statusNote = tunnel.statusNote,
                backend = tunnel.backend,
                endpoint = prefs.getString(KEY_ENDPOINT, null).orEmpty(),
                error = "Could not read saved sessions: ${e.message}",
            )
        }
        val selected = selectedId?.takeIf { id -> sessions.any { it.id == id } } ?: sessions.firstOrNull()?.id
        return CloudUiState(
            statusNote = tunnel.statusNote,
            backend = tunnel.backend,
            endpoint = prefs.getString(KEY_ENDPOINT, null).orEmpty(),
            sessions = sessions,
            selectedId = selected,
        )
    }

    private companion object {
        const val FILE = "cloud_tunnel"
        const val KEY_ENDPOINT = "endpoint"
    }
}
