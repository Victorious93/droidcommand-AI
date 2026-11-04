package ai.droidcommand.app.fleet

import ai.droidcommand.fleet.messenger.FleetMessengerController
import ai.droidcommand.fleet.messenger.LiveCallResult
import ai.droidcommand.fleet.messenger.TrustedLiveOutcome
import ai.droidcommand.remotecontrol.FleetLiveStatusMapping
import ai.droidcommand.remotecontrol.RemoteStatusTray

/**
 * Runs [FleetMessengerController.refreshTrustedLive] and publishes each
 * outcome into [RemoteStatusTray]. Pure mapping + controller call; the
 * ViewModel launches it on [Dispatchers.IO].
 */
object FleetTrustedLivePublisher {
    data class Result(
        val outcomes: List<TrustedLiveOutcome>,
        val reasonByCatalogId: Map<String, String>,
        val published: Int,
    )

    fun refresh(controller: FleetMessengerController, tray: RemoteStatusTray, nowMs: Long = System.currentTimeMillis()): Result {
        val outcomes = controller.refreshTrustedLive()
        val reasons = linkedMapOf<String, String>()
        var published = 0
        for (outcome in outcomes) {
            val (ok, detail) = interpret(outcome.status)
            val reason = FleetLiveStatusMapping.classifyLiveDetail(detail)
                ?: when (outcome.status) {
                    is LiveCallResult.Success -> FleetLiveStatusMapping.REASON_LIVE
                    is LiveCallResult.Untrusted -> FleetLiveStatusMapping.reasonFor(
                        paired = true,
                        snapshot = null,
                        trustDetail = detail,
                        liveDetail = detail,
                    )
                    else -> FleetLiveStatusMapping.classifyLiveDetail(detail)
                        ?: FleetLiveStatusMapping.REASON_AWAITING
                }
            reasons[outcome.catalogId] = reason
            tray.consume(
                FleetLiveStatusMapping.snapshotFromLive(
                    nodeId = outcome.nodeId,
                    packageName = outcome.packageName,
                    ok = ok,
                    detail = detail,
                    updatedAtMs = nowMs,
                    actionRef = outcome.status.operation,
                ),
            )
            published++
            // Capabilities are a second live read; a success bumps detail but does not
            // invent a second tray row. Failures still refine the reason.
            val capsDetail = interpret(outcome.capabilities).second
            FleetLiveStatusMapping.classifyLiveDetail(capsDetail)?.let { reasons[outcome.catalogId] = it }
        }
        return Result(outcomes, reasons, published)
    }

    /** Also publish reasons for non-TRUSTED rows from the controller snapshot. */
    fun reasonsFromSnapshot(controller: FleetMessengerController): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (row in controller.snapshot()) {
            val reason = when (row.state) {
                ai.droidcommand.fleet.messenger.NodePairingState.NOT_INSTALLED ->
                    FleetLiveStatusMapping.REASON_NOT_INSTALLED
                ai.droidcommand.fleet.messenger.NodePairingState.NOT_PAIRED ->
                    if (row.installedPackage != null) {
                        FleetLiveStatusMapping.REASON_INSTALLED_UNPAIRED
                    } else {
                        FleetLiveStatusMapping.REASON_NOT_PAIRED
                    }
                ai.droidcommand.fleet.messenger.NodePairingState.CERT_MISMATCH ->
                    FleetLiveStatusMapping.REASON_BIND_REFUSED_CERT
                ai.droidcommand.fleet.messenger.NodePairingState.UNTRUSTED ->
                    FleetLiveStatusMapping.reasonFor(
                        paired = true,
                        snapshot = null,
                        trustDetail = row.trustDetail,
                        liveDetail = row.trustDetail,
                    )
                ai.droidcommand.fleet.messenger.NodePairingState.TRUSTED ->
                    FleetLiveStatusMapping.REASON_AWAITING
            }
            out[row.spec.id] = reason
        }
        return out
    }

    private fun interpret(result: LiveCallResult): Pair<Boolean?, String> = when (result) {
        is LiveCallResult.Success -> true to result.summary()
        is LiveCallResult.RemoteError -> {
            val msg = result.summary()
            val ok = if (FleetLiveStatusMapping.looksLikePeerControlOff(msg)) false else false
            ok to msg
        }
        is LiveCallResult.Timeout -> false to result.summary()
        is LiveCallResult.Untrusted -> null to result.summary()
        is LiveCallResult.Gated -> null to result.summary()
        is LiveCallResult.Failed -> {
            val msg = result.summary()
            false to msg
        }
        is LiveCallResult.UpThenStatus -> interpret(result.status ?: result.up)
    }
}
