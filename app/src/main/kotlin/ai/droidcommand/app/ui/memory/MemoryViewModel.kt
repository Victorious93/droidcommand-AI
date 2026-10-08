package ai.droidcommand.app.ui.memory

import ai.droidcommand.agent.Entity
import ai.droidcommand.agent.KnowledgeGraph
import ai.droidcommand.app.memory.MemoryController
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
 * Compiles; never run on a device. All logic lives in the plain-JUnit-tested [MemoryController]; this
 * class only owns UI state and keeps Room off the main thread, the same split [ChatViewModel] already
 * uses for [KnowledgeGraph]'s sibling store.
 */
@HiltViewModel
class MemoryViewModel @Inject constructor(graph: KnowledgeGraph) : ViewModel() {
    private val controller = MemoryController(graph)

    private val mutableState = MutableStateFlow(MemoryUiState())
    val state: StateFlow<MemoryUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        mutableState.update { it.copy(loading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val entities = controller.all()
            mutableState.update { it.copy(entities = entities, loading = false) }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            controller.delete(id)
            refresh()
        }
    }

    fun clearAll() {
        viewModelScope.launch(Dispatchers.IO) {
            controller.clearAll()
            refresh()
        }
    }
}
