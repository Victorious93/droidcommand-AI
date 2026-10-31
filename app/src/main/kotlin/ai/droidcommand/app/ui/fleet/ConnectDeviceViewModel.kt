package ai.droidcommand.app.ui.fleet

import ai.droidcommand.app.deviceconnect.DeviceConnectRuntime
import ai.droidcommand.deviceconnect.DeviceConnectDecision
import ai.droidcommand.deviceconnect.DeviceKind
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConnectDeviceViewModel @Inject constructor(
    private val runtime: DeviceConnectRuntime,
) : ViewModel() {
    private val mutable = MutableStateFlow(ConnectDeviceUi(status = runtime.status()))
    val state: StateFlow<ConnectDeviceUi> = mutable

    init {
        viewModelScope.launch {
            runtime.liveStatus.collect { status ->
                mutable.update { it.copy(status = status) }
            }
        }
    }

    fun onScreenOpen() {
        runtime.startIfKeptOn()
        refresh()
    }

    fun onScreenClose() {
        if (!runtime.service.keepListening) {
            runtime.stopWaiting()
        }
        refresh()
    }

    fun setKind(kind: DeviceKind) {
        mutable.update { it.copy(kind = kind) }
    }

    fun setPassword(password: String) {
        mutable.update { it.copy(password = password) }
    }

    fun generatePassword() {
        mutable.update { it.copy(password = runtime.service.generatePassword()) }
    }

    fun setAddress(address: String) {
        mutable.update { it.copy(address = address) }
    }

    fun setNote(note: String) {
        mutable.update { it.copy(note = note) }
    }

    fun setKeepListening(on: Boolean) {
        runtime.service.setKeepListening(on)
        refresh()
    }

    fun waitHere() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runtime.waitHere(mutable.value.password)
            publish(result)
        }
    }

    fun stopWaiting() {
        runtime.stopWaiting()
        refresh("Stopped waiting.")
    }

    fun join() {
        viewModelScope.launch(Dispatchers.IO) {
            val current = mutable.value
            val result = runtime.join(current.address, current.password)
            publish(result)
        }
    }

    fun confirmCode() {
        runtime.confirmCode(true)
        refresh("Paired. You can now check the connection or send a note to Chat.")
    }

    fun rejectCode() {
        runtime.confirmCode(false)
        refresh("Pairing cancelled.")
    }

    fun ping(deviceId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            publish(runtime.transport.ping(deviceId))
        }
    }

    fun sendNote(deviceId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            publish(runtime.transport.sendChat(deviceId, mutable.value.note))
        }
    }

    fun revoke(deviceId: String) {
        runtime.service.revoke(deviceId)
        refresh("Forgot that device.")
    }

    private fun publish(result: DeviceConnectDecision) {
        val message = when (result) {
            is DeviceConnectDecision.Ok -> result.message
            is DeviceConnectDecision.Refused -> result.reason
        }
        mutable.update { it.copy(message = message, status = runtime.status()) }
    }

    private fun refresh(message: String? = mutable.value.message) {
        mutable.update { it.copy(message = message, status = runtime.status()) }
    }
}
