package ai.droidcommand.app.ui.tools.metasploit

import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolRunner
import ai.droidcommand.agent.describe
import ai.droidcommand.app.ui.tools.ToolRunUiState
import ai.droidcommand.metasploit.MetasploitModuleType
import ai.droidcommand.pentestswarm.KaliAuthorizedScanTool
import ai.droidcommand.pentestswarm.LocalKaliPlaybookRunner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class MetasploitFormState(
    val moduleType: MetasploitModuleType = MetasploitModuleType.AUXILIARY,
    val modulePath: String = "",
    val targetHost: String = "",
    val targetPort: String = "",
    val payload: String = "",
    val options: List<Pair<String, String>> = emptyList(),
    val scopeFilePath: String = "",
    val targetsAuthorized: Boolean = false,
)

/**
 * Drives [ai.droidcommand.metasploit.MetasploitTool] and
 * [KaliAuthorizedScanTool] through the same [ToolRunner]
 * (`SecureToolExecutor`). Playbook confirmation is the exact phrase in
 * [LocalKaliPlaybookRunner.AUTHORIZED_CONFIRMATION_PHRASE]; an unchecked
 * box sends an empty string so the runner refuses before any Termux call.
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

    fun writeScopeFileForHost(directory: File) {
        val host = _form.value.targetHost.trim()
        val file = File(directory, "kali-authorized-scope.yaml")
        file.writeText("authorized_targets:\n  - $host\n")
        _form.value = _form.value.copy(scopeFilePath = file.absolutePath)
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

    fun runAuthorizedKaliPlaybook() {
        val current = _form.value
        _uiState.value = ToolRunUiState.Running
        viewModelScope.launch(Dispatchers.IO) {
            val confirmation = if (current.targetsAuthorized) {
                LocalKaliPlaybookRunner.AUTHORIZED_CONFIRMATION_PHRASE
            } else {
                ""
            }
            val result = toolRunner.run(
                KaliAuthorizedScanTool.NAME,
                mapOf(
                    "targetHost" to current.targetHost,
                    "scopeFile" to current.scopeFilePath,
                    "authorizedConfirmation" to confirmation,
                ),
            )
            _uiState.value = when (result) {
                is ToolResult.Success -> ToolRunUiState.Success(result.output)
                else -> ToolRunUiState.Failed(result.describe())
            }
        }
    }
}
