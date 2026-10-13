package ai.droidcommand.app.cloud

import ai.droidcommand.cloudsession.CloudCodingSession
import ai.droidcommand.cloudsession.CloudSessionTunnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class CloudUiState(
    val sessions: List<CloudCodingSession> = emptyList(),
    val selectedId: String? = null,
    val error: String? = null,
    val backendStatus: String = "",
)

/**
 * App-lifetime owner of the cloud coding tunnel. The tunnel's session model (plan → edit → report)
 * is real; [backendStatus] is whatever the bound transport reports. The binding in this app is the
 * in-memory stub, so the status stays PLANNED.
 */
@Singleton
class CloudSessionController @Inject constructor(
    private val tunnel: CloudSessionTunnel,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(CloudUiState(backendStatus = tunnel.backendStatus))
    val state: StateFlow<CloudUiState> = mutable

    init {
        refresh()
    }

    fun refresh() {
        scope.launch { mutable.update { it.copy(sessions = tunnel.list(), backendStatus = tunnel.backendStatus) } }
    }

    fun select(id: String) = mutable.update { it.copy(selectedId = id, error = null) }

    fun open(goal: String) {
        scope.launch {
            try {
                val session = tunnel.open(goal)
                mutable.update { it.copy(sessions = tunnel.list(), selectedId = session.id, error = null) }
            } catch (e: IllegalArgumentException) {
                mutable.update { it.copy(error = e.message) }
            }
        }
    }

    fun advance(id: String) = mutate(id) { tunnel.advance(id) }

    fun cancel(id: String) = mutate(id) { tunnel.cancel(id) }

    private fun mutate(id: String, block: () -> CloudCodingSession) {
        scope.launch {
            try {
                val session = block()
                mutable.update { it.copy(sessions = tunnel.list(), selectedId = session.id, error = null) }
            } catch (e: IllegalArgumentException) {
                mutable.update { it.copy(error = e.message) }
            }
        }
    }
}
