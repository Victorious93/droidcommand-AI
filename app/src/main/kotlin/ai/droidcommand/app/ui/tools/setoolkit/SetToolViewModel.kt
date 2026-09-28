package ai.droidcommand.app.ui.tools.setoolkit

import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolRunner
import ai.droidcommand.agent.describe
import ai.droidcommand.app.ui.tools.ToolRunUiState
import ai.droidcommand.setoolkit.SetAttackVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class SetToolFormState(
    val attackVector: SetAttackVector = SetAttackVector.SPEAR_PHISHING,
    val targetHost: String = "",
    val targetEmail: String = "",
    val payload: String = "",
    val options: List<Pair<String, String>> = emptyList(),
)

/**
 * Drives [ai.droidcommand.setoolkit.SetTool] through the same [ToolRunner]
 * [ai.droidcommand.app.ui.tools.metasploit.MetasploitViewModel] uses — same
 * structured-input, same real confirmation gate. See that class's doc
 * comment for the full rationale (dispatch on IO, block on the approval
 * queue, never a raw command string).
 */
@HiltViewModel
class SetToolViewModel @Inject constructor(
    private val toolRunner: ToolRunner,
) : ViewModel() {
    private val _form = MutableStateFlow(SetToolFormState())
    val form: StateFlow<SetToolFormState> = _form

    private val _uiState = MutableStateFlow<ToolRunUiState>(ToolRunUiState.Idle)
    val uiState: StateFlow<ToolRunUiState> = _uiState

    fun updateForm(update: (SetToolFormState) -> SetToolFormState) {
        _form.value = update(_form.value)
    }

    fun runAttack() {
        val current = _form.value
        _uiState.value = ToolRunUiState.Running
        viewModelScope.launch(Dispatchers.IO) {
            val input = buildMap {
                put("attackVector", current.attackVector.name)
                if (current.targetHost.isNotBlank()) put("targetHost", current.targetHost)
                if (current.targetEmail.isNotBlank()) put("targetEmail", current.targetEmail)
                if (current.payload.isNotBlank()) put("payload", current.payload)
                current.options.forEach { (key, value) -> if (key.isNotBlank()) put("option.$key", value) }
            }
            val result = toolRunner.run("run_setoolkit_attack", input)
            _uiState.value = when (result) {
                is ToolResult.Success -> ToolRunUiState.Success(result.output)
                else -> ToolRunUiState.Failed(result.describe())
            }
        }
    }
}
