package ai.droidcommand.remotecontrol

import ai.droidcommand.rootforge.BRIDGE_PROTOCOL_MAJOR
import ai.droidcommand.rootforge.BridgeErrorBody
import ai.droidcommand.rootforge.BridgeResponse
import ai.droidcommand.security.FleetIntegrationClass
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Encode/decode the frozen STATUS-TRAY snapshot. Wire is the JSON object
 * in [docs/specs/STATUS_TRAY.md]. No live network bind.
 */
object NodeStatusSnapshotCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(snapshot: NodeStatusSnapshot): String {
        assertEncodable(snapshot)
        return json.encodeToString(NodeStatusSnapshotDto.serializer(), NodeStatusSnapshotDto.from(snapshot))
    }

    fun decode(raw: String): NodeStatusSnapshot {
        val dto = try {
            json.decodeFromString(NodeStatusSnapshotDto.serializer(), raw)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("Invalid NodeStatusSnapshot: ${e.message}", e)
        }
        val snapshot = dto.toModel()
        assertEncodable(snapshot)
        return snapshot
    }

    fun encodeList(snapshots: List<NodeStatusSnapshot>): String {
        snapshots.forEach(::assertEncodable)
        return json.encodeToString(
            NodeStatusSnapshotListDto.serializer(),
            NodeStatusSnapshotListDto(snapshots = snapshots.map { NodeStatusSnapshotDto.from(it) }),
        )
    }

    fun decodeList(raw: String): List<NodeStatusSnapshot> {
        val asList = try {
            json.decodeFromString(NodeStatusSnapshotListDto.serializer(), raw)
        } catch (_: SerializationException) {
            null
        }
        if (asList != null && asList.snapshots.isNotEmpty()) {
            return asList.snapshots.map { it.toModel().also(::assertEncodable) }
        }
        val single = try {
            decode(raw)
        } catch (_: IllegalArgumentException) {
            null
        }
        if (single != null) return listOf(single)
        val empty = try {
            json.decodeFromString(NodeStatusSnapshotListDto.serializer(), raw)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("Invalid status-tray payload: ${e.message}", e)
        }
        return empty.snapshots.map { it.toModel().also(::assertEncodable) }
    }

    fun encodeBridgeResponse(
        snapshots: List<NodeStatusSnapshot>,
        requestId: String,
        nodeId: String? = null,
        aggregated: Boolean = snapshots.size != 1,
    ): String {
        val result: JsonObject = if (aggregated) {
            json.encodeToJsonElement(
                NodeStatusSnapshotListDto.serializer(),
                NodeStatusSnapshotListDto(snapshots = snapshots.map { NodeStatusSnapshotDto.from(it).also { dto -> assertEncodable(dto.toModel()) } }),
            ).jsonObject
        } else {
            val snapshot = snapshots.single()
            assertEncodable(snapshot)
            json.encodeToJsonElement(NodeStatusSnapshotDto.serializer(), NodeStatusSnapshotDto.from(snapshot)).jsonObject
        }
        val response = BridgeResponse(
            protocolMajor = BRIDGE_PROTOCOL_MAJOR,
            requestId = requestId,
            nodeId = nodeId,
            ok = true,
            result = result,
        )
        return json.encodeToString(BridgeResponse.serializer(), response)
    }

    fun decodeBridgeResponse(raw: String): List<NodeStatusSnapshot> {
        val response = try {
            json.decodeFromString(BridgeResponse.serializer(), raw)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("Invalid BridgeResponse: ${e.message}", e)
        }
        if (!response.ok) {
            val category = response.error?.category ?: RemoteErrorCategory.INTERNAL.wire
            throw StatusTrayError(RemoteErrorCategory.fromWire(category) ?: RemoteErrorCategory.INTERNAL, response.error?.message ?: category)
        }
        val result = response.result ?: return emptyList()
        return decodeList(result.toString())
    }

    fun encodeBridgeError(category: RemoteErrorCategory, requestId: String, message: String = category.wire): String {
        val response = BridgeResponse(
            protocolMajor = BRIDGE_PROTOCOL_MAJOR,
            requestId = requestId,
            ok = false,
            error = BridgeErrorBody(category = category.wire, message = message),
        )
        return json.encodeToString(BridgeResponse.serializer(), response)
    }

    fun fromInbound(source: SourceIdentity, envelope: FleetRemoteEnvelope): NodeStatusSnapshot {
        val embedded = envelope.args[ARG_SNAPSHOT]
        if (!embedded.isNullOrBlank()) {
            val parsed = decode(embedded)
            return parsed.copy(
                nodeId = source.sourceNodeRef,
                packageName = source.packageName ?: parsed.packageName,
            )
        }
        val domain = StatusTrayDomain.fromSource(source, envelope)
        val state = envelope.args["state"]?.let { StatusTrayState.fromWire(it) }
            ?: StatusTrayState.fromHealthy(envelope.args["healthy"]?.toBooleanStrictOrNull())
        return NodeStatusSnapshot(
            nodeId = source.sourceNodeRef,
            packageName = source.packageName,
            connectionClass = connectionClassFrom(envelope.args["connectionClass"]),
            updatedAtMs = envelope.timestampMs,
            domains = listOf(
                StatusTrayDomainStatus(
                    name = domain,
                    state = state,
                    detail = envelope.args["detail"],
                    originalName = envelope.args["domain"]?.takeIf { domain == StatusTrayDomain.OTHER },
                ),
            ),
            capability = envelope.actionRef,
            actionRef = envelope.actionRef,
            payload = envelope.args.filterKeys { it !in INTERNAL_ARG_KEYS },
        )
    }

    fun assertEncodable(snapshot: NodeStatusSnapshot) {
        StatusTrayPrivacy.assertSafe(snapshot)
    }

    const val ARG_SNAPSHOT = "snapshot"

    private val INTERNAL_ARG_KEYS = setOf(
        ARG_SNAPSHOT, "domain", "state", "detail", "healthy", "connectionClass", "nodeId", "packageName",
    )

    fun connectionClassFrom(raw: String?): FleetIntegrationClass {
        if (raw.isNullOrBlank()) return FleetIntegrationClass.EXTERNAL_NODE
        return FleetIntegrationClass.entries.find { it.name.equals(raw, ignoreCase = true) }
            ?: throw IllegalArgumentException("unknown connectionClass '$raw'")
    }
}

class StatusTrayError(
    val category: RemoteErrorCategory,
    message: String,
) : IllegalArgumentException(message)

object StatusTrayPrivacy {
    val FORBIDDEN_KEY_FRAGMENTS = listOf(
        "password",
        "passwd",
        "api_key",
        "apikey",
        "api-key",
        "api_token",
        "token",
        "secret",
        "codeword",
        "code_word",
        "whitelist",
        "signingkey",
        "signing_key",
        "privatekey",
        "private_key",
        "keystore",
        "cookie",
        "authorization",
        "bearer",
    )

    fun isForbiddenName(name: String): Boolean {
        val folded = name.lowercase().replace("-", "").replace("_", "")
        return FORBIDDEN_KEY_FRAGMENTS.any { fragment ->
            folded.contains(fragment.replace("-", "").replace("_", ""))
        }
    }

    fun assertSafe(snapshot: NodeStatusSnapshot) {
        snapshot.payload.keys.forEach { key ->
            if (isForbiddenName(key)) {
                throw IllegalArgumentException("status snapshot must not carry secret field '$key'")
            }
        }
        snapshot.domains.forEach { domain ->
            val detail = domain.detail ?: return@forEach
            FORBIDDEN_KEY_FRAGMENTS.forEach { fragment ->
                if (detail.lowercase().contains(fragment)) {
                    throw IllegalArgumentException("status detail must not carry '$fragment'")
                }
            }
        }
    }
}

@Serializable
internal data class NodeStatusSnapshotListDto(
    val snapshots: List<NodeStatusSnapshotDto> = emptyList(),
)

@Serializable
internal data class NodeStatusSnapshotWireDto(
    val name: String,
    val state: String,
    val detail: String? = null,
) {
    fun toModel(): StatusTrayDomainStatus {
        val domain = StatusTrayDomain.fromWire(name)
        return StatusTrayDomainStatus(
            name = domain,
            state = StatusTrayState.fromWire(state),
            detail = detail,
            originalName = name.takeIf { domain == StatusTrayDomain.OTHER && !StatusTrayDomain.entries.any { it.wire == name } },
        )
    }

    companion object {
        fun from(status: StatusTrayDomainStatus) = NodeStatusSnapshotWireDto(
            name = status.wireName(),
            state = status.state.wire,
            detail = status.detail,
        )
    }
}

@Serializable
internal data class NodeStatusSnapshotDto(
    val nodeId: String = "",
    val packageName: String? = null,
    val connectionClass: String = FleetIntegrationClass.EXTERNAL_NODE.name,
    val updatedAtMs: Long = 0,
    val domains: List<NodeStatusSnapshotWireDto> = emptyList(),
    val capability: String = "status",
    val actionRef: String = "status",
    val payload: Map<String, String> = emptyMap(),
    val sourceNodeRef: String? = null,
    val receivedAtMs: Long? = null,
    val domain: String? = null,
    val healthy: Boolean? = null,
) {
    fun toModel(): NodeStatusSnapshot {
        val id = nodeId.ifBlank { sourceNodeRef ?: "" }
        val domainRows = if (domains.isNotEmpty()) {
            domains.map { it.toModel() }
        } else {
            listOf(
                StatusTrayDomainStatus(
                    name = StatusTrayDomain.fromWire(domain ?: StatusTrayDomain.NODE_HEALTH.wire),
                    state = StatusTrayState.fromHealthy(healthy),
                ),
            )
        }
        return NodeStatusSnapshot(
            nodeId = id,
            packageName = packageName,
            connectionClass = NodeStatusSnapshotCodec.connectionClassFrom(connectionClass),
            updatedAtMs = updatedAtMs.takeIf { it > 0 } ?: receivedAtMs ?: 0L,
            domains = domainRows,
            capability = capability,
            actionRef = actionRef.ifBlank { "status" },
            payload = payload,
        )
    }

    companion object {
        fun from(snapshot: NodeStatusSnapshot) = NodeStatusSnapshotDto(
            nodeId = snapshot.nodeId,
            packageName = snapshot.packageName,
            connectionClass = snapshot.connectionClass.name,
            updatedAtMs = snapshot.updatedAtMs,
            domains = snapshot.domains.map { NodeStatusSnapshotWireDto.from(it) },
            capability = snapshot.capability,
            actionRef = snapshot.actionRef,
            payload = snapshot.payload,
        )
    }
}
