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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

data class VoiceModelRow(
    val id: String,
    val name: String,
    val license: String,
    val installed: Boolean,
    val busy: Boolean = false,
    /** Bytes on disk so far while a download runs (including a resumed prefix); the total is not known up front. */
    val downloadedBytes: Long? = null,
    /** Bytes a stopped or interrupted download kept on disk. More than 0 means Resume instead of Download. */
    val partialBytes: Long = 0L,
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

    /** Per running download: set to true to stop it. Stop keeps what is downloaded; Cancel also deletes it. */
    private val stopFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private val discardRequests = ConcurrentHashMap.newKeySet<String>()

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

    /** Start, or Resume when a stopped download left bytes behind. */
    fun install(id: String) {
        val stop = AtomicBoolean(false)
        if (stopFlags.putIfAbsent(id, stop) != null) return
        update(id) { it.copy(busy = true, message = null, downloadedBytes = it.partialBytes) }
        viewModelScope.launch(Dispatchers.IO) {
            var lastUpdateNanos = 0L
            val result = repository.install(
                id,
                // Progress arrives on every read; the screen only needs it a few times a second.
                onProgress = { bytes ->
                    val now = System.nanoTime()
                    if (now - lastUpdateNanos >= PROGRESS_INTERVAL_NANOS) {
                        lastUpdateNanos = now
                        update(id) { it.copy(downloadedBytes = bytes) }
                    }
                },
                cancelled = stop::get,
            )
            val features = runtime.refresh()
            // The transfer has returned, so nothing is writing the partial files: a Cancel can delete them now.
            val discard = discardRequests.remove(id)
            if (discard) repository.discardPartial(id)
            stopFlags.remove(id)
            val kept = repository.partialBytes(id)
            update(id) {
                when {
                    result is InstallResult.Installed -> it.copy(busy = false, installed = true, downloadedBytes = null, partialBytes = 0L)
                    discard -> it.copy(busy = false, downloadedBytes = null, partialBytes = 0L, message = "Cancelled. What was downloaded was deleted.")
                    stop.get() -> it.copy(busy = false, downloadedBytes = null, partialBytes = kept, message = "Stopped. Resume continues from where it stopped.")
                    else -> it.copy(busy = false, downloadedBytes = null, partialBytes = kept, message = (result as? InstallResult.Failed)?.reason)
                }
            }
            applyFeatures(features)
        }
    }

    /** Stop: ends the transfer and keeps what is downloaded, so Resume continues from it. Takes effect within a moment. */
    fun stop(id: String) {
        stopFlags[id]?.set(true)
    }

    /** Cancel: ends the transfer and deletes what was downloaded. */
    fun cancel(id: String) {
        val running = stopFlags[id]
        if (running != null) {
            // The download deletes its own partial files once it has stopped, so nothing is deleted under a writer.
            discardRequests += id
            running.set(true)
        } else {
            viewModelScope.launch(Dispatchers.IO) {
                repository.discardPartial(id)
                update(id) { it.copy(partialBytes = 0L, message = "Cancelled. What was downloaded was deleted.") }
            }
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
            mutableState.update { ui -> ui.copy(models = ui.models.map { it.copy(installed = runtime.isInstalled(it.id), partialBytes = repository.partialBytes(it.id)) }) }
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

    private companion object {
        const val PROGRESS_INTERVAL_NANOS = 250_000_000L
    }

    private fun rows(): List<VoiceModelRow> =
        repository.list().map { VoiceModelRow(it.id, it.displayName, it.license, installed = runtime.isInstalled(it.id), partialBytes = repository.partialBytes(it.id)) }
}
