package ai.droidcommand.app.ui.fleet

import ai.droidcommand.app.fleet.FleetCatalog
import ai.droidcommand.app.fleet.FleetMember
import ai.droidcommand.fleet.messenger.MutablePairedNodeStore
import ai.droidcommand.remotecontrol.FleetDashboardCatalog
import ai.droidcommand.remotecontrol.FleetDashboardMember
import ai.droidcommand.remotecontrol.FleetDashboardPairing
import ai.droidcommand.remotecontrol.FleetDashboardPresenter
import ai.droidcommand.remotecontrol.FleetDashboardUi
import ai.droidcommand.remotecontrol.RemoteControlStore
import ai.droidcommand.remotecontrol.RemoteStatusTray
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * Thin Hilt wrapper over [FleetDashboardPresenter]. Refresh only re-reads
 * saved pairings and republishes the in-memory status tray. It does not
 * bind Messenger or call live `node.status.get`.
 */
@HiltViewModel
class FleetDashboardViewModel @Inject constructor(
    statusTray: RemoteStatusTray,
    pairedStore: MutablePairedNodeStore,
    remoteStore: RemoteControlStore,
) : ViewModel() {
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

    fun refresh() {
        mutableState.value = presenter.snapshot()
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
