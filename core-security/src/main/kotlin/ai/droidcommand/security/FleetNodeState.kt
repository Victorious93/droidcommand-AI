package ai.droidcommand.security

import ai.droidcommand.agent.ToolResult

/**
 * Plain, JVM-testable data and rules behind the Fleet Orchestrator screen: which node is reachable,
 * what the last command was, how it ended, and when. No Android types here; the Android app supplies
 * the transports ([FleetNodeTransport]) that adapt the existing per-class clients.
 *
 * Honesty rules encoded below, not left to the UI:
 * - A node starts [FleetReachability.UNKNOWN] with no command history. Nothing is ever pre-filled.
 * - [FleetReachability.REACHABLE] is only ever set from a transport's own probe result.
 * - Whether a node may receive an action command is decided by [FleetControlPolicy], from the node's
 *   integration class and the wiring that actually exists, never from the UI.
 */
enum class FleetReachability { REACHABLE, OFFLINE, UNKNOWN }

/** READ_ONLY_STATUS cannot change anything on the node. ACTION always goes through the approval gate. */
enum class FleetCommandTier { READ_ONLY_STATUS, ACTION }

enum class FleetCommandStatus {
    SUCCEEDED,

    /** The command reached the node (or its transport) and failed. */
    FAILED,

    /** The owner declined the approval dialog, or policy denied the call. The node was never contacted. */
    DENIED,

    /** The hub refused before asking for approval (e.g. the node is read-only). The node was never contacted. */
    REFUSED,
}

/** One fleet member as the orchestrator sees it. [packageName] is the Android package, when there is one. */
data class FleetNode(
    val id: String,
    val displayName: String,
    val integrationClass: FleetIntegrationClass,
    val status: FleetHonestyStatus,
    val packageName: String?,
) {
    init {
        require(id.isNotBlank()) { "Fleet node id must not be blank" }
    }

    companion object {
        fun from(member: FleetMemberDescriptor, id: String = member.id, packageName: String? = null) =
            FleetNode(id, member.displayName, member.integrationClass, member.status, packageName)
    }
}

/**
 * What a transport learned about a node. [commandChannel] is true only when the node exposes a way to
 * receive an action command that the hub can actually use today.
 */
data class FleetProbeResult(
    val reachability: FleetReachability,
    val detail: String,
    val commandChannel: Boolean = false,
) {
    init {
        require(!commandChannel || reachability == FleetReachability.REACHABLE) {
            "A command channel can only exist on a reachable node"
        }
    }
}

data class FleetCommandRecord(
    val tier: FleetCommandTier,
    val command: String,
    val status: FleetCommandStatus,
    val result: String,
    val atMs: Long,
)

data class FleetNodeState(
    val nodeId: String,
    val reachability: FleetReachability = FleetReachability.UNKNOWN,
    val detail: String = NOT_CHECKED,
    val commandChannel: Boolean = false,
    val checkedAtMs: Long? = null,
    val lastCommand: FleetCommandRecord? = null,
) {
    companion object {
        const val NOT_CHECKED = "Not checked yet"
    }
}

enum class FleetControlMode {
    /** The hub has a wired transport; commands are possible when the node is reachable and exposes a command channel. */
    INTERACTIVE,

    /** External node. The on-phone Messenger transport is not wired, so the hub can show status only. */
    PAIRING_REQUIRED,

    /** Termux / VictorSuite. The default executor is still the refusing NullTermuxExecutor. */
    NO_RUNNER_YET,

    /** Catalogued but nothing real to talk to yet (APK not landed, or a stub only). */
    PLANNED,
}

data class FleetControl(
    val mode: FleetControlMode,
    val label: String,
    val canCheckStatus: Boolean,
)

/**
 * Facts about which transports exist today. They are inputs, not defaults to flip casually: each one
 * must be backed by a real, wired transport before it is set.
 */
data class FleetWiring(
    val boundCompanionIds: Set<String> = emptySet(),
    val messengerTransportWired: Boolean = false,
    val termuxRunnerWired: Boolean = false,
)

class FleetControlPolicy(private val wiring: FleetWiring = FleetWiring()) {
    fun controlFor(node: FleetNode): FleetControl = when (node.integrationClass) {
        FleetIntegrationClass.BOUND_COMPANION ->
            if (node.id in wiring.boundCompanionIds) {
                FleetControl(FleetControlMode.INTERACTIVE, "bound companion", canCheckStatus = true)
            } else {
                FleetControl(FleetControlMode.PLANNED, "planned: companion APK has not landed", canCheckStatus = false)
            }

        FleetIntegrationClass.EXTERNAL_NODE ->
            if (wiring.messengerTransportWired) {
                FleetControl(FleetControlMode.INTERACTIVE, "external node", canCheckStatus = true)
            } else if (node.status == FleetHonestyStatus.PLANNED) {
                FleetControl(
                    FleetControlMode.PAIRING_REQUIRED,
                    "pairing required (planned: their bridge is a stub or has not landed)",
                    canCheckStatus = true,
                )
            } else {
                FleetControl(FleetControlMode.PAIRING_REQUIRED, "pairing required", canCheckStatus = true)
            }

        FleetIntegrationClass.TERMUX_IPC ->
            if (wiring.termuxRunnerWired) {
                FleetControl(FleetControlMode.INTERACTIVE, "Termux runner", canCheckStatus = true)
            } else {
                FleetControl(FleetControlMode.NO_RUNNER_YET, "no runner yet", canCheckStatus = true)
            }

        FleetIntegrationClass.HOST_PROCESS_TOOL ->
            FleetControl(FleetControlMode.PLANNED, "planned: stub only", canCheckStatus = false)
    }

    /** Why [node] may not be sent an action command right now, or null when it may (approval is still required). */
    fun commandRefusal(node: FleetNode, state: FleetNodeState): String? {
        val control = controlFor(node)
        if (control.mode != FleetControlMode.INTERACTIVE) return "Commands are disabled: ${control.label}"
        if (state.reachability != FleetReachability.REACHABLE) {
            return "Node is not known to be reachable. Check status first."
        }
        if (!state.commandChannel) return "Node is reachable but exposes no command channel to the hub: ${state.detail}"
        return null
    }
}

/** The adapter seam to the existing per-class clients. Implementations must not invent a new wire protocol. */
interface FleetNodeTransport {
    /** Read-only. Must not change anything on the node. */
    fun probe(node: FleetNode): FleetProbeResult

    /** Sends an action. Only ever called from [FleetCommandTool], which sits behind the approval gate. */
    fun sendCommand(node: FleetNode, capabilityId: String, inputJson: String): ToolResult
}

/** Routes to the transport for a node's integration class. A class with no transport reports UNKNOWN and refuses commands. */
class FleetTransportRouter(private val byClass: Map<FleetIntegrationClass, FleetNodeTransport>) : FleetNodeTransport {
    override fun probe(node: FleetNode): FleetProbeResult =
        byClass[node.integrationClass]?.probe(node)
            ?: FleetProbeResult(FleetReachability.UNKNOWN, "No transport is wired for ${node.integrationClass}")

    override fun sendCommand(node: FleetNode, capabilityId: String, inputJson: String): ToolResult =
        byClass[node.integrationClass]?.sendCommand(node, capabilityId, inputJson)
            ?: ToolResult.Failure("No transport is wired for ${node.integrationClass}")
}

/** In-memory, thread-safe record of every node's latest state. Not persisted: a restart returns every node to "Not checked yet". */
class FleetNodeBoard(nodes: List<FleetNode>, private val control: FleetControlPolicy = FleetControlPolicy()) {
    private val nodesById: Map<String, FleetNode> = nodes.associateBy { it.id }
    private val order: List<String> = nodes.map { it.id }
    private val states = HashMap<String, FleetNodeState>()

    init {
        require(nodesById.size == nodes.size) { "duplicate fleet node id" }
        for (node in nodes) {
            val ctl = control.controlFor(node)
            states[node.id] = FleetNodeState(
                nodeId = node.id,
                detail = if (ctl.canCheckStatus) FleetNodeState.NOT_CHECKED else ctl.label,
            )
        }
    }

    val nodes: List<FleetNode> get() = order.map { nodesById.getValue(it) }

    fun node(id: String): FleetNode? = nodesById[id]

    @Synchronized
    fun state(id: String): FleetNodeState? = states[id]

    @Synchronized
    fun snapshot(): List<FleetNodeState> = order.map { states.getValue(it) }

    @Synchronized
    fun recordProbe(id: String, probe: FleetProbeResult, nowMs: Long): FleetNodeState =
        update(id) {
            it.copy(
                reachability = probe.reachability,
                detail = probe.detail,
                commandChannel = probe.commandChannel,
                checkedAtMs = nowMs,
            )
        }

    @Synchronized
    fun recordCommand(id: String, record: FleetCommandRecord): FleetNodeState = update(id) { it.copy(lastCommand = record) }

    private fun update(id: String, change: (FleetNodeState) -> FleetNodeState): FleetNodeState {
        val current = states[id] ?: throw IllegalArgumentException("Unknown fleet node '$id'")
        return change(current).also { states[id] = it }
    }
}
