package ai.droidcommand.fleet.messenger

import ai.droidcommand.rootforge.BridgeRequest
import ai.droidcommand.rootforge.BridgeResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val PKG = "com.example.node"
const val NODE_ID = "node-0123456789abcdef"
val CERT = "ab".repeat(32)
val OTHER_CERT = "cd".repeat(32)

fun pairedNode(cert: String = CERT) = PairedNode.of(PKG, NODE_ID, cert)

fun identity(vararg certs: String = arrayOf(CERT), uid: Int = 10_123) = PackageIdentity(uid, certs.toSet())

fun resolverOf(identity: PackageIdentity?) = PackageIdentityResolver { identity }

fun okResult(): JsonObject = buildJsonObject { put("hello", JsonPrimitive("world")) }

fun response(
    request: BridgeRequest,
    major: Int = 1,
    nodeId: String? = NODE_ID,
    requestId: String? = request.requestId,
    ok: Boolean = true,
    result: JsonObject? = okResult(),
    error: ai.droidcommand.rootforge.BridgeErrorBody? = null,
): String = messengerJson.encodeToString(BridgeResponse(major, requestId, nodeId, ok, result, error))

/** Records every interaction so tests can prove nothing was opened or sent. */
class FakeChannel(private val reply: (requestJson: String) -> Result<String>) : MessengerChannel {
    var opens = 0
    var openedWith: PackageIdentity? = null
    var sent = mutableListOf<String>()
    var exchangeTimeoutMs: Long? = null
    var closed = 0
    var openFailure: Throwable? = null

    override fun open(node: PairedNode, verified: PackageIdentity, timeoutMs: Long): Result<MessengerSession> {
        opens++
        openedWith = verified
        openFailure?.let { return Result.failure(it) }
        return Result.success(
            object : MessengerSession {
                override fun exchange(requestJson: String, timeoutMs: Long): Result<String> {
                    sent += requestJson
                    exchangeTimeoutMs = timeoutMs
                    return reply(requestJson)
                }

                override fun close() {
                    closed++
                }
            },
        )
    }
}

fun decodeRequest(json: String): BridgeRequest = messengerJson.decodeFromString(json)

fun client(channel: MessengerChannel, identity: PackageIdentity? = identity(), requestId: String = "dca-test-1"): ExternalNodeTransport =
    MessengerBridgeClient(NodeTrustVerifier(resolverOf(identity)), channel) { requestId }

/** A channel whose node answers with [build] applied to the decoded request. */
fun echoing(build: (BridgeRequest) -> String) = FakeChannel { Result.success(build(decodeRequest(it))) }
