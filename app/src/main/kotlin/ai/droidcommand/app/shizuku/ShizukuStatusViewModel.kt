package ai.droidcommand.app.shizuku

import ai.droidcommand.app.prefs.AdvancedPrefs
import ai.droidcommand.app.shizuku.embedded.EmbeddedShizukuStarter
import ai.droidcommand.shizuku.EmbeddedStartMethod
import ai.droidcommand.shizuku.EmbeddedStartPolicy
import ai.droidcommand.shizuku.EmbeddedStartSignals
import ai.droidcommand.shizuku.ShizukuManager
import ai.droidcommand.shizuku.ShizukuState
import ai.droidcommand.shizuku.WirelessPairingInput
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ShizukuScreenState(
    val state: ShizukuState,
    val startOnBoot: Boolean,
    val startMethod: EmbeddedStartMethod,
    val bootMethod: EmbeddedStartMethod,
    val rootButtonVisible: Boolean,
    val writeSecureSettingsGranted: Boolean,
    val pairCode: String = "",
    val pairPort: String = "",
    val connectPort: String = "",
    val message: String? = null,
    val busy: Boolean = false,
    val signals: EmbeddedStartSignals = EmbeddedStartSignals(),
)

@HiltViewModel
class ShizukuStatusViewModel @Inject constructor(
    private val manager: ShizukuManager,
    private val starter: EmbeddedShizukuStarter,
    private val prefs: AdvancedPrefs,
) : ViewModel() {
    private val _state = MutableStateFlow(snapshot(manager.state()))
    val state: StateFlow<ShizukuScreenState> = _state.asStateFlow()

    private val listener: (ShizukuState) -> Unit = { next ->
        _state.value = snapshot(next, _state.value)
    }

    init {
        manager.addListener(listener)
    }

    fun requestPermission() = manager.requestPermission()

    fun setPairCode(value: String) = update { it.copy(pairCode = value) }

    fun setPairPort(value: String) = update { it.copy(pairPort = value) }

    fun setConnectPort(value: String) = update { it.copy(connectPort = value) }

    fun setStartOnBoot(enabled: Boolean) {
        prefs.setStartShizukuOnBoot(enabled)
        _state.value = snapshot(manager.state(), _state.value)
    }

    fun startBuiltIn() {
        val current = _state.value
        runStart {
            when (current.startMethod) {
                EmbeddedStartMethod.WIRELESS_PAIR -> starter.startWireless(
                    WirelessPairingInput(current.pairCode, current.pairPort, current.connectPort),
                )
                EmbeddedStartMethod.WIRELESS_PAIRED -> {
                    if (current.connectPort.isNotBlank() || current.pairCode.isNotBlank()) {
                        starter.startWireless(
                            WirelessPairingInput(current.pairCode, current.pairPort, current.connectPort),
                        )
                    } else {
                        starter.startPaired()
                    }
                }
                EmbeddedStartMethod.ROOT -> starter.startRoot()
                EmbeddedStartMethod.EXTERNAL -> "Shizuku is already running."
                EmbeddedStartMethod.UNAVAILABLE,
                EmbeddedStartMethod.SECURE_SETTINGS_WADB,
                EmbeddedStartMethod.NOTIFICATION,
                -> "This phone cannot start the built-in server from here. Use root or Android 11+ wireless debugging."
            }
        }
    }

    fun startWithRoot() {
        runStart { starter.startRoot() }
    }

    private fun runStart(block: () -> String) {
        if (_state.value.busy) return
        update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val message = withContext(Dispatchers.IO) {
                try {
                    block()
                } catch (t: Throwable) {
                    t.message?.takeIf { it.isNotBlank() } ?: "Start failed. The rest of the app still works."
                }
            }
            _state.value = snapshot(manager.state(), _state.value).copy(busy = false, message = message)
        }
    }

    private fun update(edit: (ShizukuScreenState) -> ShizukuScreenState) {
        _state.value = edit(_state.value)
    }

    private fun snapshot(state: ShizukuState, previous: ShizukuScreenState? = null): ShizukuScreenState {
        val signals = starter.signals()
        return ShizukuScreenState(
            state = state,
            startOnBoot = prefs.startShizukuOnBoot(),
            startMethod = EmbeddedStartPolicy.startButtonMethod(signals),
            bootMethod = EmbeddedStartPolicy.bootMethodLabel(signals),
            rootButtonVisible = EmbeddedStartPolicy.rootButtonVisible(signals),
            writeSecureSettingsGranted = signals.writeSecureSettingsGranted,
            pairCode = previous?.pairCode.orEmpty(),
            pairPort = previous?.pairPort.orEmpty(),
            connectPort = previous?.connectPort.orEmpty(),
            message = previous?.message,
            busy = previous?.busy == true,
            signals = signals,
        )
    }

    override fun onCleared() {
        manager.removeListener(listener)
        super.onCleared()
    }
}
