package ai.droidcommand.app.ui.mode

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.app.mode.EngineModeState
import ai.droidcommand.app.mode.EngineModes
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Activity-scoped reader of [EngineModes] for the mode switcher in the navigation shell. */
@HiltViewModel
class EngineModeViewModel @Inject constructor(
    private val modes: EngineModes,
) : ViewModel() {
    val state: StateFlow<EngineModeState> = modes.state

    fun select(mode: AgentMode): Boolean = modes.select(mode)
}
