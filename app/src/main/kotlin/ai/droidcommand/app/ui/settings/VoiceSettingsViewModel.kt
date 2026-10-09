package ai.droidcommand.app.ui.settings

import ai.droidcommand.voice.HttpsFileDownloader
import ai.droidcommand.voice.InstallResult
import ai.droidcommand.voice.TtsEngineChoice
import ai.droidcommand.voice.VoiceApprovalSettings
import ai.droidcommand.voice.VoiceFeatures
import ai.droidcommand.voice.VoiceModelRepository
import ai.droidcommand.voice.VoiceModelStatus
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
import java.io.File
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
 * UNBUILT/UNTESTED (no Android SDK here). Persists voice choices and manages downloadable voice models.
 * [features] is all-false because `:core-voice-neural-android` is NOT linked into this app yet — the screen
 * therefore shows neural voice and wake word as unavailable, and [VoiceSettings.normalized] keeps them off.
 * No voice-model catalog is registered either: adding one is a deliberate, license-checked step
 * (docs/VOICE_PHASE_SCOPE.md). Voice approvals are persisted but NOT yet connected to the security gate.
 */
@HiltViewModel
class VoiceSettingsViewModel @Inject constructor(
    @ApplicationContext context: Context,
) : ViewModel() {
    private val features = VoiceFeatures()
    private val store = SharedPreferencesVoiceSettingsStore(context)
    private val repository = VoiceModelRepository(File(context.filesDir, "voice-models"), HttpsFileDownloader())

    private val mutableState = MutableStateFlow(VoiceSettingsUi(store.load().normalized(features), features, rows()))
    val state: StateFlow<VoiceSettingsUi> = mutableState.asStateFlow()

    fun setEngine(choice: TtsEngineChoice) = change { it.copy(ttsEngine = choice) }

    fun setWakeWord(enabled: Boolean) = change { it.copy(wakeWord = it.wakeWord.copy(enabled = enabled)) }

    fun setWakeWordScreenOff(enabled: Boolean) = change { it.copy(wakeWord = WakeWordSettings(it.wakeWord.enabled, enabled)) }

    fun setApprovals(enabled: Boolean) = change { it.copy(approval = VoiceApprovalSettings(enabled, it.approval.allowApproveByVoice && enabled)) }

    fun setApproveByVoice(enabled: Boolean) = change { it.copy(approval = it.approval.copy(allowApproveByVoice = enabled)) }

    fun install(id: String) {
        update(id) { it.copy(busy = true, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.install(id)
            update(id) {
                when (result) {
                    is InstallResult.Installed -> it.copy(busy = false, installed = true)
                    is InstallResult.Failed -> it.copy(busy = false, message = result.reason)
                }
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = repository.delete(id)
            update(id) { it.copy(installed = !ok && it.installed, message = if (ok) null else "Could not delete all files") }
        }
    }

    private fun change(edit: (VoiceSettings) -> VoiceSettings) {
        val next = edit(mutableState.value.settings).normalized(features)
        store.save(next)
        mutableState.update { it.copy(settings = next) }
    }

    private fun update(id: String, edit: (VoiceModelRow) -> VoiceModelRow) =
        mutableState.update { ui -> ui.copy(models = ui.models.map { if (it.id == id) edit(it) else it }) }

    private fun rows(): List<VoiceModelRow> = repository.list().map {
        VoiceModelRow(it.id, it.displayName, it.license, repository.status(it.id) is VoiceModelStatus.Verified)
    }
}
