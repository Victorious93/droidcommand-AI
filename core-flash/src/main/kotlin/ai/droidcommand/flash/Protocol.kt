package ai.droidcommand.flash

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Victorious Flash bridge wire contract, protocol major 1 — the same envelope as the RootForge
 * bridge, plus streamed *progress frames* for long operations. Canonical definition:
 * `crates/flash-bridge/src/lib.rs` in `Victorious93/victorious-flash`; this is a hand-kept mirror.
 *
 * A frame carrying `progress` is never final. The first frame without `progress` is the answer.
 */
const val FLASH_PROTOCOL_MAJOR = 1

internal val flashJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
data class FlashRequest(
    @SerialName("protocol_major") val protocolMajor: Int = FLASH_PROTOCOL_MAJOR,
    @SerialName("request_id") val requestId: String,
    @SerialName("target_node_id") val targetNodeId: String,
    val operation: String,
    @SerialName("timeout_ms") val timeoutMs: Int = 10_000,
    val parameters: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class FlashErrorBody(val category: String, val message: String)

@Serializable
data class FlashProgress(val phase: String, val done: Long, val total: Long)

@Serializable
data class FlashResponse(
    @SerialName("protocol_major") val protocolMajor: Int,
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("node_id") val nodeId: String? = null,
    val ok: Boolean,
    val result: JsonObject? = null,
    val error: FlashErrorBody? = null,
    val progress: FlashProgress? = null,
)

@Serializable
data class FlashDevice(
    val name: String,
    val path: String,
    val size: Long,
    val model: String = "",
    val transport: String = "",
    val mounted: List<String> = emptyList(),
)

@Serializable
data class FlashDeviceList(val devices: List<FlashDevice>)

/** Operation names (exact strings on the wire). */
object FlashOps {
    const val CAPABILITIES = "flash.capabilities.get"
    const val CATALOG_LIST = "flash.catalog.list"
    const val CATALOG_UPDATE = "flash.catalog.update"
    const val DEVICES_LIST = "flash.devices.list"
    const val VERIFY = "flash.verify"
    const val READBACK_VERIFY = "flash.readback.verify"
    const val IMAGE_WRITE = "flash.image.write"
}
