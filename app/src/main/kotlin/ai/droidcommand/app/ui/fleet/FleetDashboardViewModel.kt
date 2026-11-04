package ai.droidcommand.app.ui.fleet

import ai.droidcommand.app.fleet.AndroidCompanionLink
import ai.droidcommand.app.fleet.FleetCatalog
import ai.droidcommand.app.fleet.FleetCompanionLivePublisher
import ai.droidcommand.app.fleet.FleetMember
import ai.droidcommand.app.fleet.FleetTrustedLivePublisher
import ai.droidcommand.app.fleet.PackageFleetProbe
import ai.droidcommand.app.ux.FleetCardUi
import ai.droidcommand.app.ux.FleetUserCopy
import ai.droidcommand.app.ux.FleetUserKind
import ai.droidcommand.app.ux.FriendlyErrors
import ai.droidcommand.companion.KnownCompanions
import ai.droidcommand.fleet.messenger.FleetMessengerController
import ai.droidcommand.fleet.messenger.LiveCallResult
import ai.droidcommand.fleet.messenger.MutablePairedNodeStore
import ai.droidcommand.fleet.messenger.PackageIdentityResolver
import ai.droidcommand.fleet.messenger.PairedNodeGateway
import ai.droidcommand.fleet.messenger.PairingResult
import ai.droidcommand.remotecontrol.FleetDashboardCatalog
import ai.droidcommand.remotecontrol.FleetDashboardMember
import ai.droidcommand.remotecontrol.FleetDashboardPairing
import ai.droidcommand.remotecontrol.FleetDashboardPresenter
import ai.droidcommand.remotecontrol.RemoteControlStore
import ai.droidcommand.remotecontrol.RemoteStatusTray
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

data class FleetHomeUi(
    val cards: List<FleetCardUi> = emptyList(),
    val banner: String = "Checking which of your apps are on this phone…",
    val emptyHint: String? = "None of your other apps are connected yet. Install one, then tap Pair.",
    val notice: String? = null,
    val busyIds: Set<String> = emptySet(),
)

/**
 * Thin Hilt wrapper over [FleetDashboardPresenter]. Refresh re-reads saved
 * pairings, probes bound companions (HackerAI / Pentest-Swarm) through
 * [AndroidCompanionLink], runs a live status/capabilities pass for TRUSTED
 * paired apps through [FleetMessengerController] (off the main thread,
 * with per-call timeouts), and publishes them into [RemoteStatusTray].
 *
 * Companion binds stay same-signing. This does not turn external control
 * on and does not auto-bind EDL.
 */
@HiltViewModel
class FleetDashboardViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val statusTray: RemoteStatusTray,
    pairedStore: MutablePairedNodeStore,
    remoteStore: RemoteControlStore,
    resolver: PackageIdentityResolver,
    gateway: PairedNodeGateway,
) : ViewModel() {
    private val controller = FleetMessengerController(pairedStore, resolver, gateway)
    private val companionLink = AndroidCompanionLink(context)
    private val packageProbe = PackageFleetProbe(context)

    private val presenter = FleetDashboardPresenter(
        members = FleetCatalog.all.map(::toDashboardMember),
        statusTray = statusTray,
        pairings = {
            pairedStore.all().map { FleetDashboardPairing(it.nodeId, it.packageName) } +
                remoteStore.load().pairings.map { FleetDashboardPairing(it.sourceNodeRef, it.packageName) }
        },
    )

    private val mutableState = MutableStateFlow(FleetHomeUi())
    val state: StateFlow<FleetHomeUi> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val baseReasons = FleetTrustedLivePublisher.reasonsFromSnapshot(controller)
            val companionReasons = FleetCompanionLivePublisher.reasons(
                companionByPackage = mapOf(
                    KnownCompanions.HACKERAI_PACKAGE to
                        FleetCompanionLivePublisher.probeLink(companionLink, KnownCompanions.HACKERAI_PACKAGE),
                    KnownCompanions.PENTESTSWARM_PACKAGE to
                        FleetCompanionLivePublisher.probeLink(companionLink, KnownCompanions.PENTESTSWARM_PACKAGE),
                ),
                termuxInstalled = packageProbe.isInstalled(FleetCatalog.TERMUX_PACKAGE),
                edlInstalled = packageProbe.isInstalled(FleetCatalog.EDLCOMMANDER_PACKAGE),
            )
            presenter.setReasonOverrides(companionReasons + baseReasons)
            val live = FleetTrustedLivePublisher.refresh(controller, statusTray)
            presenter.setReasonOverrides(companionReasons + baseReasons + live.reasonByCatalogId)
            presenter.markLiveBindReady(true)
            publish(notice = mutableState.value.notice)
        }
    }

    fun pair(id: String) {
        mutableState.update { it.copy(busyIds = it.busyIds + id, notice = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = controller.pair(id)
            val notice = when (result) {
                is PairingResult.Paired -> "Paired. That install is trusted on this phone. Remote control is still off."
                is PairingResult.Refused -> FriendlyErrors.forUser(result.reason)
            }
            if (result is PairingResult.Paired) {
                FleetTrustedLivePublisher.refresh(controller, statusTray)
            }
            publish(notice, busyRemove = id)
        }
    }

    fun unpair(id: String) {
        mutableState.update { it.copy(busyIds = it.busyIds + id, notice = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val removed = controller.unpair(id)
            publish(
                notice = if (removed) "Stopped pairing. The other app is still on the phone." else "That app was not paired.",
                busyRemove = id,
            )
        }
    }

    fun testConnection(id: String) {
        val card = mutableState.value.cards.firstOrNull { it.id == id }
        when {
            card == null -> return
            card.status.kind == FleetUserKind.NOT_INSTALLED -> {
                mutableState.update { it.copy(notice = FriendlyErrors.testNeedsInstall()) }
                return
            }
            !card.canTest -> {
                mutableState.update { it.copy(notice = FriendlyErrors.testNeedsPair()) }
                return
            }
        }
        mutableState.update { it.copy(busyIds = it.busyIds + id, notice = FriendlyErrors.testBusy()) }
        viewModelScope.launch(Dispatchers.IO) {
            val notice = when (id) {
                "hackerai" -> companionResult(KnownCompanions.HACKERAI_PACKAGE)
                "pentestswarm" -> companionResult(KnownCompanions.PENTESTSWARM_PACKAGE)
                else -> liveResult(id)
            }
            publish(notice, busyRemove = id)
        }
    }

    private fun companionResult(packageName: String): String {
        val result = FleetCompanionLivePublisher.probeLink(companionLink, packageName)
        return if (result.connected) {
            FriendlyErrors.testOk()
        } else {
            FriendlyErrors.testFailed(
                result.signatureRefusal ?: FleetCompanionLivePublisher.companionReason(result),
            )
        }
    }

    private fun liveResult(id: String): String {
        val result = controller.refreshStatus(id)
        return when (result) {
            is LiveCallResult.Success -> FriendlyErrors.testOk()
            is LiveCallResult.Gated -> FriendlyErrors.forUser(result.message)
            else -> FriendlyErrors.testFailed(result.summary())
        }
    }

    private fun publish(notice: String?, busyRemove: String? = null) {
        val snap = presenter.snapshot()
        val cards = snap.rows.map { row ->
            val installed = row.member.packageNames.any { packageProbe.isInstalled(it) }
            FleetUserCopy.fromRow(
                row = row,
                installed = installed,
                opensApprovedCommands = row.member.id in COMPANION_ORCHESTRATOR_IDS,
            )
        }
        val anyConnected = cards.any { it.status.kind == FleetUserKind.CONNECTED }
        mutableState.update {
            it.copy(
                cards = cards,
                banner = FleetUserCopy.banner(snap.liveBindReady, anyConnected),
                emptyHint = if (anyConnected) null else snap.emptyStateCta?.let { FleetUserCopy.banner(true, false) },
                notice = notice,
                busyIds = if (busyRemove == null) it.busyIds else it.busyIds - busyRemove,
            )
        }
    }

    companion object {
        val COMPANION_ORCHESTRATOR_IDS = setOf("hackerai", "pentestswarm")

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
