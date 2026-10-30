package ai.droidcommand.app.ui.fleet

import ai.droidcommand.app.fleet.FleetCatalog
import ai.droidcommand.app.fleet.FleetMember
import ai.droidcommand.app.fleet.FleetTrustedLivePublisher
import ai.droidcommand.fleet.messenger.FleetMessengerController
import ai.droidcommand.fleet.messenger.MutablePairedNodeStore
import ai.droidcommand.fleet.messenger.PackageIdentityResolver
import ai.droidcommand.fleet.messenger.PairedNodeGateway
import ai.droidcommand.remotecontrol.FleetDashboardCatalog
import ai.droidcommand.remotecontrol.FleetDashboardMember
import ai.droidcommand.remotecontrol.FleetDashboardPairing
import ai.droidcommand.remotecontrol.FleetDashboardPresenter
import ai.droidcommand.remotecontrol.FleetDashboardUi
import ai.droidcommand.remotecontrol.RemoteControlStore
import ai.droidcommand.remotecontrol.RemoteStatusTray
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Thin Hilt wrapper over [FleetDashboardPresenter]. Refresh re-reads saved
 * pairings, runs a live status/capabilities pass for TRUSTED EXTERNAL_NODEs
 * through [FleetMessengerController] (off the main thread, with per-call
 * timeouts), publishes into [RemoteStatusTray], and clears the "not wired"
 * note once that pass completes.
 */
@HiltViewModel
class FleetDashboardViewModel @Inject constructor(
    private val statusTray: RemoteStatusTray,
    pairedStore: MutablePairedNodeStore,
    remoteStore: RemoteControlStore,
    resolver: PackageIdentityResolver,
    gateway: PairedNodeGateway,
) : ViewModel() {
    private val controller = FleetMessengerController(pairedStore, resolver, gateway)

    private val presenter = FleetDashboardPresenter(
        members = FleetCatalog.all.map(::toDashboardMember),
        statusTray = statusTray,
        pairings = {
            pairedStore.all().map { FleetDashboardPairing(it.nodeId, it.packageName) } +
                remoteStore.load().pairings.map { FleetDashboardPairing(it.sourceNodeRef, it.packageName) }
        },
    )

    private val mutableState = MutableStateFlow(presenter.snapshot())
    val state: StateFlow<FleetDashboardUi> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val baseReasons = FleetTrustedLivePublisher.reasonsFromSnapshot(controller)
            presenter.setReasonOverrides(baseReasons)
            val live = FleetTrustedLivePublisher.refresh(controller, statusTray)
            presenter.setReasonOverrides(baseReasons + live.reasonByCatalogId)
            presenter.markLiveBindReady(true)
            mutableState.value = presenter.snapshot()
        }
    }

    companion object {
        fun toDashboardMember(member: FleetMember): FleetDashboardMember {
            val hintId = if (member.id == "termux") "victorsuite" else member.id
            return FleetDashboardMember(
                id = member.id,
                name = member.name,
                role = FleetDashboardCatalog.roleFor(member.id),
                packageNames = member.androidPackages,
                nodeIdHints = (FleetDashboardCatalog.nodeIdHints(hintId) + member.id).distinct(),
                highlighted = member.id == "rootforge",
                opensPairing = member.id in FleetDashboardCatalog.PAIRING_FLOW_IDS,
                pendingDeviceTest = member.id == "termux" || member.id == "victorsuite",
                pendingRepackage = member.id == "termux" || member.id == "victorsuite",
            )
        }
    }
}
