package ai.droidcommand.app.ui.remotepc

import ai.droidcommand.app.remotepc.AndroidPcProfileReader
import ai.droidcommand.app.remotepc.RemotePcPrefs
import ai.droidcommand.remotepc.CommandConfirmFlow
import ai.droidcommand.remotepc.CommandConfirmState
import ai.droidcommand.remotepc.OpenPcResultCopy
import ai.droidcommand.remotepc.PcListState
import ai.droidcommand.remotepc.PcOnlineProbe
import ai.droidcommand.remotepc.PcOnlineState
import ai.droidcommand.remotepc.PcProfile
import ai.droidcommand.remotepc.PcProfileLoader
import ai.droidcommand.remotepc.SaveCheck
import ai.droidcommand.remotepc.SavedPcCommand
import ai.droidcommand.remotepc.SavedPcCommands
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

data class RemotePcUi(
    val enabled: Boolean = false,
    val list: PcListState = PcListState.Loading,
    val online: Map<String, PcOnlineState> = emptyMap(),
    val commands: List<SavedPcCommand> = emptyList(),
    val confirm: CommandConfirmState = CommandConfirmState.Idle,
    val notice: String? = null,
)

/**
 * My PC hub tile. Reads VictorSuite's PC list, checks each PC's sshd port, and hands sessions to VictorSuite
 * through `OPEN_PC`. It never connects over SSH itself and holds no key.
 */
@HiltViewModel
class RemotePcViewModel @Inject constructor(
    private val reader: AndroidPcProfileReader,
    private val prefs: RemotePcPrefs,
) : ViewModel() {
    private val probe = PcOnlineProbe()
    private val flow = CommandConfirmFlow()
    private var refreshJob: Job? = null

    private val mutable = MutableStateFlow(RemotePcUi(enabled = prefs.enabled(), commands = prefs.savedCommands()))
    val state: StateFlow<RemotePcUi> = mutable.asStateFlow()

    init {
        if (mutable.value.enabled) refresh()
    }

    fun setEnabled(enabled: Boolean) {
        prefs.setEnabled(enabled)
        mutable.update { it.copy(enabled = enabled, notice = null) }
        if (enabled) refresh() else refreshJob?.cancel()
    }

    fun refresh() {
        if (!mutable.value.enabled) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            mutable.update { it.copy(list = PcListState.Loading, online = emptyMap()) }
            val list = withContext(Dispatchers.IO) { PcProfileLoader.evaluate(reader.read()) }
            mutable.update { it.copy(list = list) }
            val profiles = (list as? PcListState.Ready)?.profiles.orEmpty()
            mutable.update { ui -> ui.copy(online = profiles.associate { it.profileId to PcOnlineState.Checking }) }
            profiles.map { profile ->
                async(Dispatchers.IO) {
                    val result = probe.check(profile)
                    mutable.update { ui -> ui.copy(online = ui.online + (profile.profileId to result)) }
                }
            }.awaitAll()
        }
    }

    /** Returns null when saved, or a plain reason it was refused. */
    fun addCommand(label: String, command: String): String? {
        val trimmedLabel = label.trim()
        val trimmedCommand = command.trim()
        when (val check = SavedPcCommands.check(trimmedLabel, trimmedCommand)) {
            is SaveCheck.Refused -> return check.message
            SaveCheck.Ok -> Unit
        }
        val current = prefs.savedCommands()
        if (current.size >= SavedPcCommands.MAX_COMMANDS) return "You already have ${SavedPcCommands.MAX_COMMANDS} saved commands. Delete one first."
        val next = current + SavedPcCommand(UUID.randomUUID().toString(), trimmedLabel, trimmedCommand)
        prefs.setSavedCommands(next)
        mutable.update { it.copy(commands = next) }
        return null
    }

    /** Called only after the delete confirm dialog. */
    fun deleteCommand(id: String) {
        val next = prefs.savedCommands().filterNot { it.id == id }
        prefs.setSavedCommands(next)
        mutable.update { it.copy(commands = next) }
    }

    fun startRun(profile: PcProfile, command: SavedPcCommand) {
        mutable.update { it.copy(confirm = flow.start(profile, command)) }
    }

    fun confirmStep() {
        mutable.update { it.copy(confirm = flow.confirm()) }
    }

    fun cancelRun() {
        mutable.update { it.copy(confirm = flow.cancel()) }
    }

    /** One-shot: the tile hands the approved command off exactly once. */
    fun consumeApproved(): CommandConfirmState.Approved? {
        val approved = flow.consume()
        mutable.update { it.copy(confirm = flow.state) }
        return approved
    }

    fun onOpenResult(ok: Boolean, error: String?, pcName: String) {
        val message = OpenPcResultCopy.message(ok, error, pcName)
        mutable.update { it.copy(notice = message) }
        if (error == "consent_required" || error == "unknown_profile") refresh()
    }

    fun showNotice(message: String?) {
        mutable.update { it.copy(notice = message) }
    }
}
