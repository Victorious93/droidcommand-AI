package ai.droidcommand.app.ui.tools.metasploit

import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolRunner
import ai.droidcommand.agent.describe
import ai.droidcommand.app.ui.tools.ToolRunUiState
import ai.droidcommand.metasploit.MetasploitModuleType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class MetasploitFormState(
    val moduleType: MetasploitModuleType = MetasploitModuleType.AUXILIARY,
    val modulePath: String = "",
    val targetHost: String = "",
    val targetPort: String = "",
    val payload: String = "",
    val options: List<Pair<String, String>> = emptyList(),
)

/**
 * Drives [ai.droidcommand.metasploit.MetasploitTool] through the exact same
 * [ToolRunner] the CLI uses ([ai.droidcommand.app.di.AppModule] wires it as a
 * real `SecureToolExecutor`). Every field on [MetasploitFormState] becomes a
 * structured `input` map entry — never a raw command string an operator
 * types — and [runModule] always goes through the real security/approval
 * gate before anything executes. Dispatches on `Dispatchers.IO` because
 * [ToolRunner.run] can block this coroutine's thread on
 * [ai.droidcommand.app.approval.ComposeApprovalPrompt]'s confirmation queue
 * until the user taps Approve/Deny in [ai.droidcommand.app.approval.ApprovalHost].
 */
@HiltViewModel
class MetasploitViewModel @Inject constructor(
    private val toolRunner: ToolRunner,
) : ViewModel() {
    private val _form = MutableStateFlow(MetasploitFormState())
    val form: StateFlow<MetasploitFormState> = _form

    private val _uiState = MutableStateFlow<ToolRunUiState>(ToolRunUiState.Idle)
    val uiState: StateFlow<ToolRunUiState> = _uiState

    fun updateForm(update: (MetasploitFormState) -> MetasploitFormState) {
        _form.value = update(_form.value)
    }

    fun runModule() {
        val current = _form.value
        _uiState.value = ToolRunUiState.Running
        viewModelScope.launch(Dispatchers.IO) {
            val input = buildMap {
                put("moduleType", current.moduleType.name)
                put("modulePath", current.modulePath)
                put("targetHost", current.targetHost)
                if (current.targetPort.isNotBlank()) put("targetPort", current.targetPort)
                if (current.payload.isNotBlank()) put("payload", current.payload)
                current.options.forEach { (key, value) -> if (key.isNotBlank()) put("option.$key", value) }
            }
            val result = toolRunner.run("run_metasploit_module", input)
            _uiState.value = when (result) {
                is ToolResult.Success -> ToolRunUiState.Success(result.output)
                else -> ToolRunUiState.Failed(result.describe())
            }
        }
    }
}
