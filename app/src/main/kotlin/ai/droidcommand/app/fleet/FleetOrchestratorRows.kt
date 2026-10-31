package ai.droidcommand.app.fleet

import ai.droidcommand.security.FleetCommandRecord
import ai.droidcommand.security.FleetCommandStatus
import ai.droidcommand.security.FleetCommandTier
import ai.droidcommand.security.FleetControl
import ai.droidcommand.security.FleetControlPolicy
import ai.droidcommand.security.FleetIntegrationClass
import ai.droidcommand.security.FleetNode
import ai.droidcommand.security.FleetNodeState
import ai.droidcommand.security.FleetReachability

/** What the Fleet Orchestrator screen draws for one node. Built from core-security state; holds no data of its own. */
data class FleetRowUi(
    val node: FleetNode,
    val control: FleetControl,
    val state: FleetNodeState,
    val busy: Boolean,
    /** Null when "Send command" is enabled. Otherwise the plain reason it is disabled. */
    val sendDisabledReason: String?,
) {
    val canCheckStatus: Boolean get() = control.canCheckStatus && !busy
    val canSend: Boolean get() = sendDisabledReason == null && !busy
    val classLabel: String get() = connectionClassLabel(node.integrationClass)
    val controlLabel: String get() = friendlyControlLabel(control.label)
    val reachabilityLabel: String get() = reachabilityLabel(state.reachability)
}

fun connectionClassLabel(integrationClass: FleetIntegrationClass): String = when (integrationClass) {
    FleetIntegrationClass.BOUND_COMPANION -> "Signed companion app"
    FleetIntegrationClass.EXTERNAL_NODE -> "Separate app"
    FleetIntegrationClass.TERMUX_IPC -> "Linux tools on this phone"
    FleetIntegrationClass.HOST_PROCESS_TOOL -> "Built-in tool"
}

fun friendlyControlLabel(raw: String): String = when (raw) {
    "bound companion" -> "Signed companion app"
    "external node" -> "Separate app"
    "no runner yet" -> "Not connected yet"
    else -> raw
}

fun reachabilityLabel(reachability: FleetReachability): String = when (reachability) {
    FleetReachability.REACHABLE -> "Reachable"
    FleetReachability.OFFLINE -> "Offline"
    FleetReachability.UNKNOWN -> "Unknown"
}

fun lastCommandLine(record: FleetCommandRecord?, formatTime: (Long) -> String): String {
    if (record == null) return "No command sent yet"
    val tier = if (record.tier == FleetCommandTier.READ_ONLY_STATUS) "read-only" else "action"
    val status = when (record.status) {
        FleetCommandStatus.SUCCEEDED -> "succeeded"
        FleetCommandStatus.FAILED -> "failed"
        FleetCommandStatus.DENIED -> "denied"
        FleetCommandStatus.REFUSED -> "refused"
    }
    return "Last ($tier): ${record.command} → $status at ${formatTime(record.atMs)}"
}

object FleetRows {
    fun build(
        nodes: List<FleetNode>,
        states: List<FleetNodeState>,
        control: FleetControlPolicy,
        busy: Set<String>,
    ): List<FleetRowUi> {
        val byId = states.associateBy { it.nodeId }
        return nodes.map { node ->
            val state = byId.getValue(node.id)
            FleetRowUi(node, control.controlFor(node), state, node.id in busy, control.commandRefusal(node, state))
        }
    }
}
