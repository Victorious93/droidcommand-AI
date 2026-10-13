package ai.droidcommand.app.coding

import ai.droidcommand.coding.CodingSession
import ai.droidcommand.coding.FileCodingLog
import ai.droidcommand.coding.LocalCodingTunnel
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

data class CodingUiState(
    val sessions: List<CodingSession> = emptyList(),
    val error: String? = null,
)

/**
 * On-device coding sessions. [LocalCodingTunnel] is the only backend this app runs.
 * A paired socket can carry the same session (see [ai.droidcommand.coding.PairedCodingTunnel]),
 * but this process does not open one, and no vendor cloud is called.
 */
@Singleton
class CodingController @Inject constructor(@ApplicationContext context: Context) {
    private val tunnel = LocalCodingTunnel()
    private val log = FileCodingLog(context.filesDir.toPath().resolve("coding").resolve("sessions.log"))
    private val loaded = runCatching { log.load() }.getOrDefault(emptyList())
    private val mutable = MutableStateFlow(CodingUiState(sessions = loaded))
    val state: StateFlow<CodingUiState> = mutable

    init {
        loaded.forEach(tunnel::remember)
    }

    fun start(goal: String) {
        try {
            val session = tunnel.start(goal)
            publish(listOf(session) + mutable.value.sessions.filterNot { it.id == session.id })
        } catch (e: IllegalArgumentException) {
            mutable.update { it.copy(error = e.message) }
        }
    }

    fun advance(id: String) = update(id) { tunnel.advance(id) }

    fun cancel(id: String) = update(id) { tunnel.cancel(id) }

    private fun update(id: String, block: () -> CodingSession) {
        try {
            val next = block()
            publish(mutable.value.sessions.map { if (it.id == id) next else it })
        } catch (e: Exception) {
            mutable.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
        }
    }

    private fun publish(sessions: List<CodingSession>) {
        val kept = sessions.take(MAX_SESSIONS)
        mutable.value = CodingUiState(sessions = kept)
        runCatching { log.save(kept) }
    }

    private companion object {
        const val MAX_SESSIONS = 20
    }
}
