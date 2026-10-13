package ai.droidcommand.app.ui.mode

import ai.droidcommand.app.mode.EngineModeState
import ai.droidcommand.app.mode.EngineModes
import ai.droidcommand.app.mode.PilotController
import ai.droidcommand.app.mode.PilotUiState
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class PilotViewModel @Inject constructor(
    private val controller: PilotController,
    modes: EngineModes,
) : ViewModel() {
    val state: StateFlow<PilotUiState> = controller.state
    val modeState: StateFlow<EngineModeState> = modes.state

    fun run(toolName: String, argumentText: String) = controller.run(toolName, argumentText)
}
