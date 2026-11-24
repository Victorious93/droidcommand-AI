package ai.droidcommand.app.ui.fleet

import ai.droidcommand.activity.ActivityActor
import ai.droidcommand.activity.ActivityLog
import ai.droidcommand.activity.ActivityRun
import ai.droidcommand.app.fleet.FleetRowUi
import ai.droidcommand.app.fleet.FleetRows
import ai.droidcommand.security.FleetCommandStatus
import ai.droidcommand.security.FleetControlPolicy
import ai.droidcommand.security.FleetNodeBoard
import ai.droidcommand.security.FleetOrchestrator
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Both orchestrator calls block (a transport wait, or the approval dialog), so every call runs on `Dispatchers.IO`,
 * like the other tool-invoking view models. Opening the screen runs one read-only status check of every node that
 * can be checked; that binds the bound companions, and changes nothing on any node.
 */
@HiltViewModel
class FleetOrchestratorViewModel @Inject constructor(
    private val orchestrator: FleetOrchestrator,
    private val board: FleetNodeBoard,
    private val control: FleetControlPolicy,
    private val activityLog: ActivityLog,
) : ViewModel() {
    private val busy = MutableStateFlow<Set<String>>(emptySet())
    private val _rows = MutableStateFlow(rows())
    val rows: StateFlow<List<FleetRowUi>> = _rows

    init {
        refreshAll()
    }

    fun refreshAll() {
        val targets = board.nodes.filter { control.controlFor(it).canCheckStatus }.map { it.id }
        viewModelScope.launch(Dispatchers.IO) {
            targets.forEach { id -> withBusy(id) { orchestrator.checkStatus(id) } }
        }
    }

    fun checkStatus(nodeId: String) {
        viewModelScope.launch(Dispatchers.IO) { withBusy(nodeId) { orchestrator.checkStatus(nodeId) } }
    }

    /** Always goes through the approval dialog: [FleetOrchestrator.sendCommand] has no other path. */
    fun sendCommand(nodeId: String, capabilityId: String, inputJson: String) {
        viewModelScope.launch(Dispatchers.IO) { withBusy(nodeId) { recordedSend(nodeId, capabilityId, inputJson) } }
    }

    /**
     * Shows the command on the Activity screen under the fleet app it goes to. Only action commands are recorded:
     * the read-only status checks run for every node each time this screen opens, and would bury everything else.
     * The capability id is recorded, not the input, which can hold anything the owner typed.
     */
    private fun recordedSend(nodeId: String, capabilityId: String, inputJson: String) {
        val name = board.node(nodeId)?.displayName ?: nodeId
        val run = ActivityRun.start(ActivityActor("node:$nodeId", name), "Sending to $name: $capabilityId", activityLog)
        val state = try {
            run.inScope { orchestrator.sendCommand(nodeId, capabilityId, inputJson) }
        } catch (t: Throwable) {
            run.fail("${t.javaClass.simpleName}: ${t.message.orEmpty()}")
            throw t
        }
        val last = state.lastCommand
        if (last == null) {
            run.fail("No result was recorded")
        } else {
            when (last.status) {
                FleetCommandStatus.SUCCEEDED -> run.finish("Sent to $name")
                FleetCommandStatus.DENIED -> run.cancel("You declined it")
                else -> run.fail(last.result.ifBlank { "The command did not go through" })
            }
        }
    }

    private inline fun withBusy(nodeId: String, block: () -> Unit) {
        busy.update { it + nodeId }
        publish()
        try {
            block()
        } finally {
            busy.update { it - nodeId }
            publish()
        }
    }

    private fun publish() {
        _rows.value = rows()
    }

    private fun rows(): List<FleetRowUi> = FleetRows.build(board.nodes, board.snapshot(), control, busy.value)
}
