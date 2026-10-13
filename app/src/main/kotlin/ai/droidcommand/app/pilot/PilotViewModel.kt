package ai.droidcommand.app.pilot

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

@HiltViewModel
class PilotViewModel @Inject constructor(
    private val controller: PilotController,
) : ViewModel() {
    private val mutable = MutableStateFlow(PilotRunState(tools = controller.toolNames()))
    val state: StateFlow<PilotRunState> = mutable.asStateFlow()

    fun run(toolName: String, rawInput: String) {
        if (mutable.value.running || toolName.isBlank()) return
        val input = try {
            parsePilotInput(rawInput)
        } catch (e: IllegalArgumentException) {
            mutable.update { it.copy(error = e.message) }
            return
        }
        mutable.update { it.copy(running = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { controller.run(toolName, input) }
                .getOrElse { "Pilot run failed: ${it.message ?: it.javaClass.simpleName}" }
            mutable.update { it.copy(running = false, lastTool = toolName, lastResult = result) }
        }
    }
}
