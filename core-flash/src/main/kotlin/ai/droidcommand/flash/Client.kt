package ai.droidcommand.flash

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

enum class FlashFailureKind { TRANSPORT, MALFORMED_RESPONSE, PROTOCOL_MISMATCH, NODE_IDENTITY_MISMATCH, REMOTE_ERROR, LOCAL_REFUSAL }

sealed class FlashResult<out T> {
    data class Success<T>(val value: T) : FlashResult<T>()
    data class Failure(val kind: FlashFailureKind, val message: String, val remoteCategory: String? = null) : FlashResult<Nothing>()
}

/** Typed parameters of a raw write. [confirm] is the operator's typed device name (e.g. `sdb`). */
data class WriteRequest(val image: String, val device: String, val sha256: String, val confirm: String, val catalogId: String? = null)

/**
 * Typed client for ONE flash node. EVERY frame — progress and final — is checked for protocol major,
 * the paired node id and the request's correlation id; one bad frame fails the call. The request's
 * `target_node_id` is the paired id, so the node also refuses requests meant for another machine.
 */
class FlashClient(
    private val node: FlashNodeConfig,
    private val transport: FlashTransport,
    private val newRequestId: () -> String = { "dca-" + UUID.randomUUID().toString() },
) {
    val nodeId: String get() = node.nodeId

    fun capabilities() = call(FlashOps.CAPABILITIES)
    fun catalogList() = call(FlashOps.CATALOG_LIST)
    fun catalogUpdate(url: String, sha256: String) = call(FlashOps.CATALOG_UPDATE, params("url" to url, "sha256" to sha256), 120_000)

    fun listDevices(): FlashResult<FlashDeviceList> = when (val r = call(FlashOps.DEVICES_LIST)) {
        is FlashResult.Failure -> r
        is FlashResult.Success -> runCatching { flashJson.decodeFromJsonElement(FlashDeviceList.serializer(), r.value) }.fold(
            { FlashResult.Success(it) },
            { FlashResult.Failure(FlashFailureKind.MALFORMED_RESPONSE, "unexpected devices payload: ${it.message}") },
        )
    }

    fun verifyImage(image: String, sha256: String) = call(FlashOps.VERIFY, params("image" to image, "sha256" to sha256), 30 * 60_000)

    fun readbackVerify(device: String, sha256: String, length: Long, onProgress: (FlashProgress) -> Unit = {}) =
        call(
            FlashOps.READBACK_VERIFY,
            buildJsonObject {
                put("device", JsonPrimitive(device))
                put("sha256", JsonPrimitive(sha256))
                put("length", JsonPrimitive(length))
            },
            60 * 60_000, onProgress,
        )

    fun writeImage(w: WriteRequest, onProgress: (FlashProgress) -> Unit = {}): FlashResult<JsonObject> {
        // Local pre-checks only narrow; the node and its pkexec helper re-validate everything.
        if (w.confirm.isBlank()) return FlashResult.Failure(FlashFailureKind.LOCAL_REFUSAL, "typed confirmation is required")
        if (!SHA_RE.matches(w.sha256)) return FlashResult.Failure(FlashFailureKind.LOCAL_REFUSAL, "sha256 must be 64 hex chars")
        val p = buildJsonObject {
            put("image", JsonPrimitive(w.image))
            put("device", JsonPrimitive(w.device))
            put("sha256", JsonPrimitive(w.sha256.lowercase()))
            put("confirm", JsonPrimitive(w.confirm))
            w.catalogId?.let { put("catalog_id", JsonPrimitive(it)) }
        }
        return call(FlashOps.IMAGE_WRITE, p, 2 * 60 * 60_000, onProgress)
    }

    private fun params(vararg kv: Pair<String, String>) = JsonObject(kv.associate { it.first to JsonPrimitive(it.second) })

    internal fun call(
        operation: String,
        parameters: JsonObject = JsonObject(emptyMap()),
        timeoutMs: Int = 10_000,
        onProgress: (FlashProgress) -> Unit = {},
    ): FlashResult<JsonObject> {
        val requestId = newRequestId()
        val request = FlashRequest(requestId = requestId, targetNodeId = node.nodeId, operation = operation, timeoutMs = timeoutMs, parameters = parameters)
        var outcome: FlashResult<JsonObject>? = null
        val t = transport.exchange(flashJson.encodeToString(request), timeoutMs + 15_000L) { line ->
            val frame = runCatching { flashJson.decodeFromString<FlashResponse>(line) }.getOrNull()
            outcome = when {
                frame == null -> fail(FlashFailureKind.MALFORMED_RESPONSE, "response is not a valid bridge frame")
                frame.protocolMajor != FLASH_PROTOCOL_MAJOR ->
                    fail(FlashFailureKind.PROTOCOL_MISMATCH, "node speaks protocol ${frame.protocolMajor}, client speaks $FLASH_PROTOCOL_MAJOR")
                frame.nodeId != node.nodeId ->
                    fail(FlashFailureKind.NODE_IDENTITY_MISMATCH, "response came from ${frame.nodeId ?: "an unidentified node"}, expected ${node.nodeId}")
                frame.requestId != requestId -> fail(FlashFailureKind.MALFORMED_RESPONSE, "response does not correlate with the request")
                frame.progress != null -> {
                    onProgress(frame.progress)
                    null
                }
                !frame.ok -> FlashResult.Failure(FlashFailureKind.REMOTE_ERROR, frame.error?.message ?: "node reported an error", frame.error?.category)
                frame.result == null -> fail(FlashFailureKind.MALFORMED_RESPONSE, "ok response without a result")
                else -> FlashResult.Success(frame.result)
            }
            outcome != null
        }
        outcome?.let { return it }
        return fail(FlashFailureKind.TRANSPORT, t.exceptionOrNull()?.message ?: "no final response")
    }

    private fun fail(kind: FlashFailureKind, msg: String) = FlashResult.Failure(kind, msg)

    private companion object {
        val SHA_RE = Regex("^[0-9a-fA-F]{64}$")
    }
}
