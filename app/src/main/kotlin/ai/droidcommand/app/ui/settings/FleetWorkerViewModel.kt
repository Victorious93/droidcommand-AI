package ai.droidcommand.app.ui.settings

import ai.droidcommand.app.fleetworker.FleetWorkerController
import ai.droidcommand.app.fleetworker.FleetWorkerStatus
import ai.droidcommand.app.fleetworker.PairOutcome
import ai.droidcommand.app.fleetworker.PairingInput
import ai.droidcommand.app.fleetworker.PairingParse
import ai.droidcommand.fleet.worker.PairingPayload
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where the pairing flow is. Nothing is sent to the coordinator until the owner confirms the fingerprint ([Confirm]). */
sealed class PairingUi {
    object Idle : PairingUi()

    data class Confirm(val payload: PairingPayload) : PairingUi()

    object Working : PairingUi()

    data class Failed(val why: String) : PairingUi()
}

data class FleetWorkerUi(
    val status: FleetWorkerStatus = FleetWorkerStatus(),
    val pasted: String = "",
    val pairing: PairingUi = PairingUi.Idle,
    /** Set when the owner tried to switch the worker on without a pairing. */
    val note: String? = null,
)

/**
 * Compiles and is JVM-tested at the engine level; never run on a device. The engine is created off the main thread because its
 * first read touches the Keystore-backed vault.
 */
@HiltViewModel
class FleetWorkerViewModel @Inject constructor(private val controller: FleetWorkerController) : ViewModel() {
    private val _state = MutableStateFlow(FleetWorkerUi())
    val state: StateFlow<FleetWorkerUi> = _state.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            controller.status.collect { s -> _state.update { it.copy(status = s) } }
        }
    }

    fun onPasted(text: String) = _state.update { it.copy(pasted = text, pairing = if (it.pairing is PairingUi.Working) it.pairing else PairingUi.Idle) }

    /** Reads the pasted text. On success the owner sees the coordinator's fingerprint to compare; nothing is sent yet. */
    fun check() {
        when (val r = PairingInput.parse(_state.value.pasted)) {
            is PairingParse.Ok -> _state.update { it.copy(pairing = PairingUi.Confirm(r.payload)) }
            is PairingParse.Bad -> _state.update { it.copy(pairing = PairingUi.Failed(r.why)) }
        }
    }

    fun cancelPairing() = _state.update { it.copy(pairing = PairingUi.Idle) }

    fun confirmPairing() {
        val confirm = _state.value.pairing as? PairingUi.Confirm ?: return
        _state.update { it.copy(pairing = PairingUi.Working) }
        viewModelScope.launch(Dispatchers.IO) {
            val outcome = controller.pair(confirm.payload)
            _state.update {
                when (outcome) {
                    is PairOutcome.Paired -> it.copy(pairing = PairingUi.Idle, pasted = "", note = null)
                    is PairOutcome.Failed -> it.copy(pairing = PairingUi.Failed(outcome.why))
                }
            }
        }
    }

    fun setWorkerEnabled(on: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val accepted = controller.setWorkerEnabled(on)
            _state.update { it.copy(note = if (!accepted) "Pair this phone with your coordinator first." else null) }
        }
    }

    fun setBatchApprovalEnabled(on: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { controller.setBatchApprovalEnabled(on) }
    }

    fun unpair() {
        viewModelScope.launch(Dispatchers.IO) { controller.unpair() }
    }
}
