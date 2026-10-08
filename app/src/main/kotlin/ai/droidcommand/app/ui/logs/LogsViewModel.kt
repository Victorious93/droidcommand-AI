package ai.droidcommand.app.ui.logs

import ai.droidcommand.security.AuditEvent
import ai.droidcommand.security.JsonFileAuditLog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [events] is newest first. */
data class LogsUiState(
    val events: List<AuditEvent> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

/**
 * Reads the on-device audit trail (`JsonFileAuditLog`) — the security decisions `SecureToolExecutor`
 * records: every denial, approval prompts and their outcome, and authorization of sensitive/root tools.
 * Plain (non-sensitive) tool runs that were allowed are not recorded. The log is append-only by
 * design, so there is deliberately no clear/delete here. It is a file, not a flow: the screen calls [refresh] each time it opens.
 */
@HiltViewModel
class LogsViewModel @Inject constructor(private val log: JsonFileAuditLog) : ViewModel() {
    private val mutableState = MutableStateFlow(LogsUiState())
    val state: StateFlow<LogsUiState> = mutableState.asStateFlow()

    fun refresh() {
        mutableState.update { it.copy(loading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val next = try {
                LogsUiState(events = log.all().asReversed(), loading = false)
            } catch (e: Exception) {
                // A corrupt line makes JsonFileAuditLog.all() throw; say so rather than showing an empty log.
                LogsUiState(loading = false, error = "Could not read the audit log: ${e.message ?: e.javaClass.simpleName}")
            }
            mutableState.value = next
        }
    }
}
