package ai.droidcommand.fleet.messenger

import ai.droidcommand.rootforge.BridgeRequest
import ai.droidcommand.rootforge.FailureKind
import ai.droidcommand.rootforge.RootForgeResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/** Result of a bridge call; the same typed success/failure the SSH clients return. */
typealias BridgeResult<T> = RootForgeResult<T>

/**
 * A transport that carries one bridge operation to a paired EXTERNAL_NODE and returns the typed,
 * validated result. Mirrors the contract of the RootForge / Flash clients, but node-agnostic:
 * the paired node is an argument, nothing about a particular product is built in.
 */
interface ExternalNodeTransport {
    fun call(
        node: PairedNode,
        operation: String,
        parameters: JsonObject = JsonObject(emptyMap()),
        timeoutMs: Int = MessengerWire.DEFAULT_TIMEOUT_MS,
    ): BridgeResult<JsonObject>
}

/**
 * [ExternalNodeTransport] over the Android Messenger bridge. Order of events for every call:
 *  1. trust: resolve the target package and require its signing certificate to be exactly the paired one
 *     ([FailureKind.UNTRUSTED_NODE] otherwise) — nothing is bound or sent before this passes;
 *  2. connect through [MessengerChannel] ([FailureKind.TRANSPORT] on failure);
 *  3. send the serialized [BridgeRequest] and wait up to `timeoutMs` + [MessengerWire.REPLY_GRACE_MS];
 *  4. validate the reply (see [BridgeResponseValidator]).
 * The connection is closed after the exchange; nothing is cached between calls.
 */
class MessengerBridgeClient(
    private val trust: NodeTrustVerifier,
    private val channel: MessengerChannel,
    private val newRequestId: () -> String = { "dca-" + UUID.randomUUID().toString() },
) : ExternalNodeTransport {
    override fun call(node: PairedNode, operation: String, parameters: JsonObject, timeoutMs: Int): BridgeResult<JsonObject> {
        require(operation.isNotBlank()) { "operation must not be blank" }
        require(timeoutMs in 1..MessengerWire.MAX_TIMEOUT_MS) { "timeoutMs must be in 1..${MessengerWire.MAX_TIMEOUT_MS}" }

        val identity = when (val decision = trust.verify(node)) {
            is TrustDecision.Refused -> return RootForgeResult.Failure(FailureKind.UNTRUSTED_NODE, decision.reason)
            is TrustDecision.Trusted -> decision.identity
        }

        val requestId = newRequestId()
        val requestJson = messengerJson.encodeToString(
            BridgeRequest(requestId = requestId, targetNodeId = node.nodeId, operation = operation, timeoutMs = timeoutMs, parameters = parameters),
        )
        val replyJson = runCatching {
            channel.open(node, identity, timeoutMs.toLong()).getOrThrow().use { session ->
                session.exchange(requestJson, timeoutMs + MessengerWire.REPLY_GRACE_MS).getOrThrow()
            }
        }.getOrElse { return RootForgeResult.Failure(FailureKind.TRANSPORT, it.message ?: "transport failure") }
        return BridgeResponseValidator.validate(replyJson, node, requestId)
    }
}

/**
 * Looks nodes up by id in the [PairedNodeStore]. With nothing paired every call is refused with
 * [FailureKind.UNTRUSTED_NODE] and nothing is contacted, which is the shipped default.
 */
class PairedNodeGateway(
    private val store: PairedNodeStore,
    private val transport: ExternalNodeTransport,
) {
    fun pairedNodes(): List<PairedNode> = store.all()

    fun call(
        nodeId: String,
        operation: String,
        parameters: JsonObject = JsonObject(emptyMap()),
        timeoutMs: Int = MessengerWire.DEFAULT_TIMEOUT_MS,
    ): BridgeResult<JsonObject> {
        val node = store.find(nodeId)
            ?: return RootForgeResult.Failure(FailureKind.UNTRUSTED_NODE, "node $nodeId is not paired")
        return transport.call(node, operation, parameters, timeoutMs)
    }
}
