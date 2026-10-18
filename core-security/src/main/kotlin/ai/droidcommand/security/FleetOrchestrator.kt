package ai.droidcommand.security

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolRunner
import ai.droidcommand.agent.ToolSpec
import ai.droidcommand.agent.describe
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Hands the real outcome of a [FleetCommandTool] run back to the [FleetOrchestrator] that asked for it. */
class FleetCommandOutcomes {
    private val results = ConcurrentHashMap<String, ToolResult>()

    internal fun put(requestId: String, result: ToolResult) {
        results[requestId] = result
    }

    internal fun take(requestId: String): ToolResult? = results.remove(requestId)
}

/**
 * The only code path that sends an action command to a fleet node. It is a SENSITIVE tool that always
 * requires confirmation, and it has empty `allowedModes` so the Pilot and Forge planners never see it.
 * Register it only in the [ai.droidcommand.agent.ToolRegistry] behind a [SecureToolExecutor], so every
 * call reaches [FleetNodeTransport.sendCommand] only after the approval prompt said yes.
 * It re-checks [FleetControlPolicy] itself, so a read-only node refuses even if a caller skipped the orchestrator.
 */
class FleetCommandTool(
    private val board: FleetNodeBoard,
    private val control: FleetControlPolicy,
    private val transport: FleetNodeTransport,
    private val outcomes: FleetCommandOutcomes,
) : Tool {
    override val spec = ToolSpec(
        name = NAME,
        description = "Sends an action command to a fleet node through its existing transport. Always asks the owner first.",
        securityLevel = SecurityLevel.SENSITIVE,
        requiresConfirmation = true,
        allowedModes = emptySet(),
        permissionCategory = PermissionCategory.NETWORK,
    )

    override fun execute(input: Map<String, String>): ToolResult {
        val requestId = input[REQUEST_ID] ?: return ToolResult.Failure("Missing required input '$REQUEST_ID'")
        val result = run(input)
        outcomes.put(requestId, result)
        return result
    }

    private fun run(input: Map<String, String>): ToolResult {
        val nodeId = input[NODE_ID] ?: return ToolResult.Failure("Missing required input '$NODE_ID'")
        val capabilityId = input[CAPABILITY_ID]?.takeIf { it.isNotBlank() }
            ?: return ToolResult.Failure("Missing required input '$CAPABILITY_ID'")
        val node = board.node(nodeId) ?: return ToolResult.Failure("Unknown fleet node '$nodeId'")
        val state = board.state(nodeId) ?: return ToolResult.Failure("Unknown fleet node '$nodeId'")
        control.commandRefusal(node, state)?.let { return ToolResult.Failure(it) }
        return try {
            transport.sendCommand(node, capabilityId, input[INPUT_JSON] ?: "{}")
        } catch (e: Exception) {
            ToolResult.Failure("${node.displayName} transport failed: ${e.message ?: e::class.simpleName}", e)
        }
    }

    companion object {
        const val NAME = "fleet_send_command"
        const val NODE_ID = "nodeId"
        const val CAPABILITY_ID = "capabilityId"
        const val INPUT_JSON = "input"
        const val REQUEST_ID = "requestId"
    }
}

/**
 * Drives the Fleet Orchestrator screen. Two tiers:
 * - [checkStatus] is read-only and does not ask for approval: [FleetNodeTransport.probe] cannot change anything.
 * - [sendCommand] ALWAYS goes through [runner] (a [SecureToolExecutor] in the app), which asks the approval
 *   prompt before [FleetCommandTool] runs. There is no overload and no flag that skips it.
 *
 * Blocking: [sendCommand] can wait on the human at the approval dialog and [checkStatus] waits on a transport.
 * Call both off the main thread.
 */
class FleetOrchestrator(
    private val board: FleetNodeBoard,
    private val control: FleetControlPolicy,
    private val transport: FleetNodeTransport,
    private val runner: ToolRunner,
    private val outcomes: FleetCommandOutcomes,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val newRequestId: () -> String = { UUID.randomUUID().toString() },
) {
    fun snapshot(): List<FleetNodeState> = board.snapshot()

    fun checkAll(): List<FleetNodeState> {
        board.nodes.filter { control.controlFor(it).canCheckStatus }.forEach { checkStatus(it.id) }
        return board.snapshot()
    }

    fun checkStatus(nodeId: String): FleetNodeState {
        val node = requireNode(nodeId)
        val ctl = control.controlFor(node)
        if (!ctl.canCheckStatus) return board.state(nodeId)!!

        val probe = try {
            transport.probe(node)
        } catch (e: Exception) {
            val failure = FleetProbeResult(FleetReachability.UNKNOWN, "Status check failed: ${e.message ?: e::class.simpleName}")
            board.recordProbe(nodeId, failure, nowMs())
            return board.recordCommand(
                nodeId,
                FleetCommandRecord(FleetCommandTier.READ_ONLY_STATUS, STATUS_COMMAND, FleetCommandStatus.FAILED, failure.detail, nowMs()),
            )
        }
        val at = nowMs()
        board.recordProbe(nodeId, probe, at)
        return board.recordCommand(
            nodeId,
            FleetCommandRecord(FleetCommandTier.READ_ONLY_STATUS, STATUS_COMMAND, FleetCommandStatus.SUCCEEDED, probe.detail, at),
        )
    }

    fun sendCommand(nodeId: String, capabilityId: String, inputJson: String): FleetNodeState {
        val node = requireNode(nodeId)
        val summary = commandSummary(capabilityId, inputJson)

        control.commandRefusal(node, board.state(nodeId)!!)?.let { reason ->
            return board.recordCommand(
                nodeId,
                FleetCommandRecord(FleetCommandTier.ACTION, summary, FleetCommandStatus.REFUSED, reason, nowMs()),
            )
        }

        val requestId = newRequestId()
        val result = runner.run(
            FleetCommandTool.NAME,
            mapOf(
                FleetCommandTool.NODE_ID to nodeId,
                FleetCommandTool.CAPABILITY_ID to capabilityId,
                FleetCommandTool.INPUT_JSON to inputJson,
                FleetCommandTool.REQUEST_ID to requestId,
            ),
        )
        val executed = outcomes.take(requestId)
        val record = if (executed == null) {
            FleetCommandRecord(FleetCommandTier.ACTION, summary, FleetCommandStatus.DENIED, result.describe(), nowMs())
        } else {
            val status = if (executed is ToolResult.Success) FleetCommandStatus.SUCCEEDED else FleetCommandStatus.FAILED
            FleetCommandRecord(FleetCommandTier.ACTION, summary, status, executed.describe(), nowMs())
        }
        return board.recordCommand(nodeId, record)
    }

    private fun requireNode(nodeId: String): FleetNode =
        board.node(nodeId) ?: throw IllegalArgumentException("Unknown fleet node '$nodeId'")

    private fun commandSummary(capabilityId: String, inputJson: String): String =
        "$capabilityId $inputJson".trim().let { if (it.length > MAX_SUMMARY) it.take(MAX_SUMMARY) + "…" else it }

    companion object {
        const val STATUS_COMMAND = "check status"
        private const val MAX_SUMMARY = 200
    }
}
