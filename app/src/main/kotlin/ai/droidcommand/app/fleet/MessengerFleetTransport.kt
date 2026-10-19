package ai.droidcommand.app.fleet

import ai.droidcommand.agent.ToolResult
import ai.droidcommand.fleet.messenger.ExternalNodeCatalog
import ai.droidcommand.fleet.messenger.PairedNodeGateway
import ai.droidcommand.rootforge.FailureKind
import ai.droidcommand.rootforge.RootForgeResult
import ai.droidcommand.security.FleetNode
import ai.droidcommand.security.FleetNodeTransport
import ai.droidcommand.security.FleetProbeResult
import ai.droidcommand.security.FleetReachability
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * [FleetNodeTransport] over the real, already-built-and-tested `core-fleet-messenger` Messenger
 * bridge — [PairedNodeGateway] — replacing [UnwiredMessengerTransport] for every EXTERNAL_NODE
 * member [ExternalNodeCatalog] lists (RootForge, File Manager, Keyboard, Mesh, Browser alike).
 * Node-agnostic: nothing about one specific product is built in here, matching
 * [ai.droidcommand.fleet.messenger.ExternalNodeTransport]'s own node-agnostic design.
 *
 * `capabilityId` (from [ai.droidcommand.security.FleetCommandTool]'s input) is passed straight
 * through as the bridge `operation` string (e.g. `"rootforge.adb.shell"`, `"mesh.up"`) — this
 * transport does not translate or restrict it; [PairedNodeGateway]/the node itself are what
 * enforce which operations exist and which tier they are.
 *
 * [probe] is read-only: it sends only the node's catalogued `capabilities.get` op, exactly like
 * [BoundCompanionTransport]'s own ping-style check. An unpaired node reports [FleetReachability.UNKNOWN]
 * (pairing required), never a fabricated REACHABLE/OFFLINE answer. Nothing here has run against a
 * real paired phone.
 */
class MessengerFleetTransport(
    private val gateway: PairedNodeGateway,
    private val isInstalled: (String) -> Boolean,
) : FleetNodeTransport {
    override fun probe(node: FleetNode): FleetProbeResult {
        val spec = ExternalNodeCatalog.byId(node.id)
            ?: return FleetProbeResult(FleetReachability.UNKNOWN, "No bridge operations catalogued for ${node.id}")
        val paired = gateway.pairedNodes().any { it.nodeId == node.id }
        if (!paired) {
            val pkg = node.packageName
            return if (pkg != null && isInstalled(pkg)) {
                FleetProbeResult(FleetReachability.UNKNOWN, "Pairing required. $pkg is installed but not paired.")
            } else {
                FleetProbeResult(FleetReachability.UNKNOWN, "Pairing required. Not installed, or installed but not yet probed.")
            }
        }
        return when (val r = gateway.call(node.id, spec.capabilitiesOperation)) {
            is RootForgeResult.Success -> FleetProbeResult(FleetReachability.REACHABLE, "Bridge answered ${spec.capabilitiesOperation}", commandChannel = true)
            is RootForgeResult.Failure -> when (r.kind) {
                FailureKind.TRANSPORT -> FleetProbeResult(FleetReachability.OFFLINE, "Bridge unreachable: ${r.message}")
                FailureKind.UNTRUSTED_NODE -> FleetProbeResult(FleetReachability.UNKNOWN, "Pairing required. ${r.message}")
                else -> FleetProbeResult(FleetReachability.UNKNOWN, "Bridge replied but is not usable (${r.kind}): ${r.message}")
            }
        }
    }

    override fun sendCommand(node: FleetNode, capabilityId: String, inputJson: String): ToolResult {
        val parameters = parseParameters(inputJson)
            ?: return ToolResult.Failure("input is not a JSON object: ${inputJson.take(200)}")
        // ReplyExpectation.AUTO (the gateway's own default): it already infers ACK for a `*.up` operation
        // and RESULT otherwise, so this never needs to duplicate NodeOperations.isFireAndForget itself.
        return when (val r = gateway.call(node.id, capabilityId, parameters)) {
            is RootForgeResult.Success -> ToolResult.Success(r.value.toString())
            is RootForgeResult.Failure -> ToolResult.Failure(
                "${node.displayName} ${r.kind.name.lowercase()}: ${r.message}" + (r.remoteCategory?.let { " [$it]" } ?: ""),
            )
        }
    }

    private fun parseParameters(inputJson: String): JsonObject? =
        runCatching { Json.parseToJsonElement(inputJson) as? JsonObject }.getOrNull()
}
