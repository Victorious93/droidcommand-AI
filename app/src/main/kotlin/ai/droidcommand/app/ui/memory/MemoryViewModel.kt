package ai.droidcommand.app.ui.memory

import ai.droidcommand.agent.Entity
import ai.droidcommand.agent.KnowledgeGraph
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MemoryUiState(
    val entities: List<Entity> = emptyList(),
    val loading: Boolean = true,
)

/**
 * Compiles; never run on a device. All graph work is the already-tested [MemoryController] over a
 * Room-backed [KnowledgeGraph]; this class only owns UI state and keeps storage off the main thread
 * (Room refuses main-thread access).
 */
@HiltViewModel
class MemoryViewModel
    @Inject
    constructor(graph: KnowledgeGraph) : ViewModel() {
        private val controller = MemoryController(graph)
        private val mutableState = MutableStateFlow(MemoryUiState())
        val state: StateFlow<MemoryUiState> = mutableState.asStateFlow()

        init {
            refresh()
        }

        fun refresh() {
            mutableState.update { it.copy(loading = true) }
            viewModelScope.launch(Dispatchers.IO) {
                val all = runCatching { controller.all() }.getOrDefault(emptyList())
                mutableState.update { it.copy(entities = all, loading = false) }
            }
        }

        fun delete(id: String) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { controller.delete(id) }
                val all = runCatching { controller.all() }.getOrDefault(emptyList())
                mutableState.update { it.copy(entities = all) }
            }
        }

        fun clearAll() {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { controller.clearAll() }
                mutableState.update { it.copy(entities = emptyList()) }
            }
        }
    }
