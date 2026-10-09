package ai.droidcommand.app.ui.settings

import ai.droidcommand.app.voice.VoiceRuntime
import ai.droidcommand.voice.InstallResult
import ai.droidcommand.voice.TtsEngineChoice
import ai.droidcommand.voice.VoiceApprovalSettings
import ai.droidcommand.voice.VoiceFeatures
import ai.droidcommand.voice.VoiceSettings
import ai.droidcommand.voice.WakeWordSettings
import ai.droidcommand.voice.android.SharedPreferencesVoiceSettingsStore
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VoiceModelRow(
    val id: String,
    val name: String,
    val license: String,
    val installed: Boolean,
    val busy: Boolean = false,
    val message: String? = null,
)

data class VoiceSettingsUi(
    val settings: VoiceSettings = VoiceSettings(),
    val features: VoiceFeatures = VoiceFeatures(),
    val models: List<VoiceModelRow> = emptyList(),
)

/**
 * Compiles; never run on a device. Persists voice choices and manages downloadable voice models.
 * [VoiceSettingsUi.features] come from [VoiceRuntime]: neural voice and wake word are offered exactly when
 * their model is installed and SHA-256-verified, and [VoiceSettings.normalized] keeps them off otherwise.
 * Verification hashes the model files, so it runs on the IO dispatcher; until it finishes everything neural
 * shows as unavailable rather than guessing.
 */
@HiltViewModel
class VoiceSettingsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val runtime: VoiceRuntime,
) : ViewModel() {
    private val store = SharedPreferencesVoiceSettingsStore(context)
    private val repository = runtime.repository

    private val mutableState = MutableStateFlow(
        VoiceSettingsUi(store.load().normalized(runtime.features()), runtime.features(), rows()),
    )
    val state: StateFlow<VoiceSettingsUi> = mutableState.asStateFlow()

    init {
        reverify()
    }

    fun setEngine(choice: TtsEngineChoice) = change { it.copy(ttsEngine = choice) }

    fun setWakeWord(enabled: Boolean) = change { it.copy(wakeWord = it.wakeWord.copy(enabled = enabled)) }

    fun setWakeWordScreenOff(enabled: Boolean) = change { it.copy(wakeWord = WakeWordSettings(it.wakeWord.enabled, enabled)) }

    fun setApprovals(enabled: Boolean) = change { it.copy(approval = VoiceApprovalSettings(enabled, it.approval.allowApproveByVoice && enabled)) }

    fun setApproveByVoice(enabled: Boolean) = change { it.copy(approval = it.approval.copy(allowApproveByVoice = enabled)) }

    fun install(id: String) {
        update(id) { it.copy(busy = true, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.install(id)
            val features = runtime.refresh()
            update(id) {
                when (result) {
                    is InstallResult.Installed -> it.copy(busy = false, installed = true)
                    is InstallResult.Failed -> it.copy(busy = false, message = result.reason)
                }
            }
            applyFeatures(features)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = repository.delete(id)
            val features = runtime.refresh()
            update(id) { it.copy(installed = runtime.isInstalled(id), message = if (ok) null else "Could not delete all files") }
            applyFeatures(features)
        }
    }

    private fun reverify() {
        viewModelScope.launch(Dispatchers.IO) {
            val features = runtime.refresh()
            mutableState.update { ui -> ui.copy(models = ui.models.map { it.copy(installed = runtime.isInstalled(it.id)) }) }
            applyFeatures(features)
        }
    }

    /** Shows (does not persist) the settings as they will actually take effect now that the models are known. */
    private fun applyFeatures(features: ai.droidcommand.voice.VoiceFeatures) =
        mutableState.update { it.copy(features = features, settings = store.load().normalized(features)) }

    private fun change(edit: (VoiceSettings) -> VoiceSettings) {
        val next = edit(mutableState.value.settings).normalized(mutableState.value.features)
        store.save(next)
        mutableState.update { it.copy(settings = next) }
    }

    private fun update(id: String, edit: (VoiceModelRow) -> VoiceModelRow) =
        mutableState.update { ui -> ui.copy(models = ui.models.map { if (it.id == id) edit(it) else it }) }

    private fun rows(): List<VoiceModelRow> = repository.list().map { VoiceModelRow(it.id, it.displayName, it.license, installed = runtime.isInstalled(it.id)) }
}
