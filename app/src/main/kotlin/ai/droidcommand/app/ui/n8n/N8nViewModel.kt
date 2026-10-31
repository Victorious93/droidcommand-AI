package ai.droidcommand.app.ui.n8n

import ai.droidcommand.app.prefs.AdvancedPrefs
import ai.droidcommand.app.ui.settings.KeyStatus
import ai.droidcommand.config.EncryptedSecretsVault
import ai.droidcommand.config.SecretState
import ai.droidcommand.config.SecretStorageException
import ai.droidcommand.n8n.N8nClient
import ai.droidcommand.n8n.N8nOutcome
import ai.droidcommand.n8n.N8nSecrets
import ai.droidcommand.n8n.N8nServerUrl
import ai.droidcommand.n8n.N8nUrlParse
import ai.droidcommand.n8n.N8nWorkflow
import ai.droidcommand.remote.HttpTransport
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class N8nUiState(
    val serverUrl: String = "",
    val urlWarning: String? = null,
    val urlError: String? = null,
    val keyStatus: KeyStatus = KeyStatus.NOT_SET,
    val busy: Boolean = false,
    val status: String? = null,
    val workflows: List<N8nWorkflow> = emptyList(),
    val selectedId: String? = null,
    val jsonInput: String = "{}",
    val confirmRun: Boolean = false,
    val confirmAiTools: Boolean = false,
    val aiToolsEnabled: Boolean = false,
    val lastResult: String? = null,
)

@HiltViewModel
class N8nViewModel @Inject constructor(
    private val vault: EncryptedSecretsVault,
    private val advanced: AdvancedPrefs,
    transport: HttpTransport,
) : ViewModel() {
    private val client = N8nClient(transport) { vault.getSecret(N8nSecrets.API_KEY_ID) }
    private val mutable = MutableStateFlow(
        N8nUiState(
            serverUrl = advanced.n8nServerUrl(),
            keyStatus = keyStatus(),
            aiToolsEnabled = advanced.n8nAiToolsEnabled(),
        ),
    )
    val state: StateFlow<N8nUiState> = mutable.asStateFlow()

    init {
        applyUrl(advanced.n8nServerUrl())
    }

    fun setServerUrl(raw: String) {
        advanced.setN8nServerUrl(raw)
        applyUrl(raw)
    }

    fun saveApiKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        try {
            vault.putSecret(N8nSecrets.API_KEY_ID, trimmed)
            mutable.update { it.copy(keyStatus = keyStatus(), status = "API key saved on this phone. It will not be shown again.") }
        } catch (_: SecretStorageException) {
            mutable.update { it.copy(keyStatus = KeyStatus.STORAGE_ERROR) }
        }
    }

    fun clearApiKey() {
        try {
            vault.revokeSecret(N8nSecrets.API_KEY_ID)
            mutable.update { it.copy(keyStatus = keyStatus(), status = "API key removed.") }
        } catch (_: SecretStorageException) {
            mutable.update { it.copy(keyStatus = KeyStatus.STORAGE_ERROR) }
        }
    }

    fun testConnection() {
        val url = parsedUrl() ?: return
        runIo("Testing the connection…") { client.testConnection(url) }
    }

    fun refreshWorkflows() {
        val url = parsedUrl() ?: return
        runIo("Loading workflows…") { client.listWorkflows(url) }
    }

    fun selectWorkflow(id: String) {
        mutable.update { it.copy(selectedId = id, lastResult = null) }
    }

    fun setJsonInput(text: String) {
        mutable.update { it.copy(jsonInput = text) }
    }

    fun requestRun() {
        val selected = selectedWorkflow() ?: return
        if (!selected.canTrigger) {
            mutable.update { it.copy(status = "${selected.name} has no webhook, so this app cannot start it.") }
            return
        }
        mutable.update { it.copy(confirmRun = true) }
    }

    fun cancelRun() {
        mutable.update { it.copy(confirmRun = false) }
    }

    fun confirmRun() {
        val url = parsedUrl() ?: return
        val workflow = selectedWorkflow() ?: return
        val json = mutable.value.jsonInput
        mutable.update { it.copy(confirmRun = false) }
        runIo("Running ${workflow.name}…") { client.triggerWebhook(url, workflow, json) }
    }

    fun requestAiToolsOn() {
        mutable.update { it.copy(confirmAiTools = true) }
    }

    fun cancelAiToolsOn() {
        mutable.update { it.copy(confirmAiTools = false) }
    }

    fun setAiToolsEnabled(enabled: Boolean) {
        if (enabled && !advanced.n8nAiToolsEnabled()) {
            requestAiToolsOn()
            return
        }
        advanced.setN8nAiToolsEnabled(enabled)
        mutable.update { it.copy(aiToolsEnabled = enabled, confirmAiTools = false) }
    }

    fun confirmAiToolsOn() {
        advanced.setN8nAiToolsEnabled(true)
        mutable.update { it.copy(aiToolsEnabled = true, confirmAiTools = false) }
    }

    private fun selectedWorkflow(): N8nWorkflow? {
        val id = mutable.value.selectedId
        return mutable.value.workflows.firstOrNull { it.id == id }
    }

    private fun parsedUrl(): N8nServerUrl? {
        return when (val parsed = N8nServerUrl.parse(mutable.value.serverUrl)) {
            is N8nUrlParse.Ok -> parsed.url
            is N8nUrlParse.Rejected -> {
                mutable.update { it.copy(urlError = parsed.reason, status = parsed.reason) }
                null
            }
        }
    }

    private fun applyUrl(raw: String) {
        when (val parsed = N8nServerUrl.parse(raw)) {
            is N8nUrlParse.Ok -> mutable.update {
                it.copy(serverUrl = raw, urlWarning = parsed.url.warning, urlError = null)
            }
            is N8nUrlParse.Rejected -> mutable.update {
                it.copy(serverUrl = raw, urlWarning = null, urlError = if (raw.isBlank()) null else parsed.reason)
            }
        }
    }

    private fun <T> runIo(busyText: String, block: () -> N8nOutcome<T>) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, status = busyText, lastResult = null) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { block() }
            mutable.update { state ->
                when (outcome) {
                    is N8nOutcome.Ok -> {
                        val workflows = (outcome.value as? List<*>)?.filterIsInstance<N8nWorkflow>()
                        state.copy(
                            busy = false,
                            status = outcome.words,
                            lastResult = if (workflows == null) outcome.words else state.lastResult,
                            workflows = workflows ?: state.workflows,
                            selectedId = when {
                                workflows == null -> state.selectedId
                                workflows.any { it.id == state.selectedId } -> state.selectedId
                                else -> workflows.firstOrNull()?.id
                            },
                        )
                    }
                    is N8nOutcome.Failed -> state.copy(busy = false, status = outcome.words, lastResult = outcome.words)
                }
            }
        }
    }

    private fun keyStatus(): KeyStatus = when (vault.inspect(N8nSecrets.API_KEY_ID)) {
        SecretState.Present -> KeyStatus.SAVED
        SecretState.Absent -> KeyStatus.NOT_SET
        SecretState.Unrecoverable -> KeyStatus.NEEDS_REENTRY
        SecretState.StorageUnavailable -> KeyStatus.STORAGE_ERROR
    }
}
