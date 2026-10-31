package ai.droidcommand.app.ui.fleet

import ai.droidcommand.app.fleet.FleetCatalog
import ai.droidcommand.app.fleet.FleetRow
import ai.droidcommand.app.fleet.FleetStatus
import ai.droidcommand.app.fleet.FleetTrustedLivePublisher
import ai.droidcommand.app.fleet.PackageFleetProbe
import ai.droidcommand.fleet.messenger.ExternalNodeRow
import ai.droidcommand.fleet.messenger.FleetMessengerController
import ai.droidcommand.fleet.messenger.LiveCallResult
import ai.droidcommand.fleet.messenger.MutablePairedNodeStore
import ai.droidcommand.fleet.messenger.PackageIdentityResolver
import ai.droidcommand.fleet.messenger.PairedNodeGateway
import ai.droidcommand.fleet.messenger.PairingResult
import ai.droidcommand.remotecontrol.RemoteStatusTray
import ai.droidcommand.security.PasswordPairingDecision
import ai.droidcommand.security.PasswordPairingService
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

data class FleetUiState(
    val externalNodes: List<ExternalNodeRow> = emptyList(),
    val otherRows: List<FleetRow> = emptyList(),
    val liveById: Map<String, LiveCallResult> = emptyMap(),
    val busyIds: Set<String> = emptySet(),
    val pairingMessage: String? = null,
    val loading: Boolean = true,
)

/**
 * Thin Hilt wrapper. Pairing, trust, and live Messenger calls live in
 * [FleetMessengerController] (plain JUnit in `core-fleet-messenger`). Messenger
 * exchanges must not run on the main thread ([ai.droidcommand.app.fleet.AndroidMessengerChannel]).
 */
@HiltViewModel
class FleetViewModel @Inject constructor(
    @ApplicationContext context: Context,
    store: MutablePairedNodeStore,
    resolver: PackageIdentityResolver,
    gateway: PairedNodeGateway,
    private val statusTray: RemoteStatusTray,
    private val pairing: PasswordPairingService,
) : ViewModel() {
    private val probe = PackageFleetProbe(context)
    private val controller = FleetMessengerController(store, resolver, gateway)

    private val mutableState = MutableStateFlow(FleetUiState())
    val state: StateFlow<FleetUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        mutableState.update { it.copy(loading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val nodes = controller.snapshot()
            val others = FleetStatus.resolve(
                FleetCatalog.all.filter { member -> nodes.none { it.spec.id == member.id } },
                probe::isInstalled,
            )
            mutableState.update { it.copy(externalNodes = nodes, otherRows = others, loading = false) }
        }
    }

    fun pair(catalogId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = controller.pair(catalogId)
            val message = when (result) {
                is PairingResult.Paired -> {
                    // Right after pairing, pull live status/capabilities into the shared tray.
                    FleetTrustedLivePublisher.refresh(controller, statusTray)
                    "Paired ${result.node.packageName} with cert ${result.node.expectedCertSha256}"
                }
                is PairingResult.Refused -> result.reason
            }
            publish(pairingMessage = message)
        }
    }

    fun pairByPassword(catalogId: String, password: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val row = controller.row(catalogId)
            val packageName = row?.installedPackage
            val cert = row?.observedCertSha256
            if (row == null || packageName == null || cert == null) {
                publish(pairingMessage = "$catalogId: not installed or no single observed cert")
                return@launch
            }
            val decision = pairing.confirmWithPassword(
                packageName,
                cert,
                password,
                screenConfirmed = true,
            )
            val message = when (decision) {
                is PasswordPairingDecision.Refused -> decision.reason
                is PasswordPairingDecision.Allowed -> {
                    when (val result = controller.pair(catalogId)) {
                        is PairingResult.Paired -> {
                            FleetTrustedLivePublisher.refresh(controller, statusTray)
                            decision.message + " Paired ${result.node.packageName}."
                        }
                        is PairingResult.Refused -> decision.message + " Messenger pair refused: ${result.reason}"
                    }
                }
            }
            publish(pairingMessage = message)
        }
    }

    fun unpair(catalogId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val removed = controller.unpair(catalogId)
            publish(pairingMessage = if (removed) "Unpaired $catalogId" else "$catalogId was not paired")
        }
    }

    fun refreshCapabilities(catalogId: String) = live(catalogId) { controller.refreshCapabilities(catalogId) }

    fun refreshStatus(catalogId: String) = live(catalogId) { controller.refreshStatus(catalogId) }

    fun bringUpAndPoll(catalogId: String) = live(catalogId) { controller.bringUpAndPoll(catalogId) }

    private fun live(catalogId: String, call: () -> LiveCallResult) {
        mutableState.update { it.copy(busyIds = it.busyIds + catalogId, pairingMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = call()
            val nodes = controller.snapshot()
            mutableState.update {
                it.copy(
                    externalNodes = nodes,
                    liveById = it.liveById + (catalogId to result),
                    busyIds = it.busyIds - catalogId,
                    loading = false,
                )
            }
        }
    }

    private fun publish(pairingMessage: String) {
        val nodes = controller.snapshot()
        mutableState.update {
            it.copy(externalNodes = nodes, pairingMessage = pairingMessage, loading = false)
        }
    }
}
