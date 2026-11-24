package ai.droidcommand.app.ui.settings

import ai.droidcommand.app.fleet.FleetCatalog
import ai.droidcommand.app.fleet.PackageFleetProbe
import ai.droidcommand.fleet.messenger.ConnectedAppFacts
import ai.droidcommand.fleet.messenger.ConnectedAppPurposes
import ai.droidcommand.fleet.messenger.ConnectedAppRow
import ai.droidcommand.fleet.messenger.ConnectedApps
import ai.droidcommand.fleet.messenger.FleetMessengerController
import ai.droidcommand.fleet.messenger.LiveCallResult
import ai.droidcommand.fleet.messenger.MutablePairedNodeStore
import ai.droidcommand.fleet.messenger.NodePairingState
import ai.droidcommand.fleet.messenger.PackageIdentityResolver
import ai.droidcommand.fleet.messenger.PairedNodeGateway
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

data class ConnectedAppsUi(
    val rows: List<ConnectedAppRow> = emptyList(),
    val checking: Boolean = false,
)

/**
 * Feeds the Connected apps list. The status rules live in `core-fleet-messenger` ([ConnectedApps]); this class
 * only gathers the facts: package probes, the pairing state, and the result of the last live check.
 * A node counts as active only after a check in this session succeeded.
 */
@HiltViewModel
class ConnectedAppsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    store: MutablePairedNodeStore,
    resolver: PackageIdentityResolver,
    gateway: PairedNodeGateway,
) : ViewModel() {
    private val probe = PackageFleetProbe(context)
    private val controller = FleetMessengerController(store, resolver, gateway)
    private val lastCheck = mutableMapOf<String, Boolean>()

    private val mutable = MutableStateFlow(ConnectedAppsUi())
    val state: StateFlow<ConnectedAppsUi> = mutable.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            mutable.update { it.copy(rows = ConnectedApps.rows(collectFacts())) }
        }
    }

    /** Asks every trusted app for its status once, then redraws. Never pairs, never sends a command. */
    fun checkPaired() {
        mutable.update { it.copy(checking = true) }
        viewModelScope.launch(Dispatchers.IO) {
            ConnectedAppPurposes.all.filter { it.pairs }.forEach { purpose ->
                val row = controller.row(purpose.catalogId)
                if (row?.state == NodePairingState.TRUSTED) {
                    lastCheck[purpose.catalogId] = controller.refreshStatus(purpose.catalogId) is LiveCallResult.Success
                }
            }
            mutable.update { it.copy(rows = ConnectedApps.rows(collectFacts()), checking = false) }
        }
    }

    private fun collectFacts(): Map<String, ConnectedAppFacts> =
        ConnectedAppPurposes.all.associate { purpose ->
            purpose.catalogId to if (purpose.pairs) {
                val state = controller.row(purpose.catalogId)?.state ?: NodePairingState.NOT_INSTALLED
                ConnectedAppFacts(
                    installed = state != NodePairingState.NOT_INSTALLED,
                    pairing = state,
                    lastLiveCallOk = lastCheck[purpose.catalogId],
                )
            } else {
                ConnectedAppFacts(
                    installed = probe.isInstalled(FleetCatalog.TERMUX_PACKAGE),
                    pairing = null,
                    lastLiveCallOk = null,
                )
            }
        }
}
