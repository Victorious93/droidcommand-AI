package ai.droidcommand.deviceconnect

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Snapshot codec for encrypted vault / file storage. Never logs private
 * keys or passwords. Unknown or excluded rows are dropped on decode.
 */
object DeviceConnectCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(snapshot: DeviceConnectSnapshot): String = json.encodeToString(SnapshotDto.from(snapshot))

    fun decode(raw: String): DeviceConnectSnapshot? = runCatching {
        json.decodeFromString(SnapshotDto.serializer(), raw).toModel()
    }.getOrNull()

    @Serializable
    private data class SnapshotDto(
        val keepListening: Boolean = false,
        val identity: IdentityDto? = null,
        val peers: List<PeerDto> = emptyList(),
        val lockouts: List<LockoutDto> = emptyList(),
    ) {
        fun toModel(): DeviceConnectSnapshot = DeviceConnectSnapshot(
            keepListening = keepListening,
            identity = identity?.let { DeviceIdentity(it.deviceId, it.publicHex, it.privateHex) },
            peers = peers.mapNotNull { row ->
                val kind = runCatching { DeviceKind.valueOf(row.kind) }.getOrNull() ?: return@mapNotNull null
                runCatching { TrustedDevice(row.deviceId, row.displayName, kind, row.publicHex, row.linkedAtMs) }.getOrNull()
            },
            lockouts = lockouts.map { DeviceLockout(it.key, it.failures, it.lockedUntilMs) },
        )

        companion object {
            fun from(snapshot: DeviceConnectSnapshot) = SnapshotDto(
                keepListening = snapshot.keepListening,
                identity = snapshot.identity?.let { IdentityDto(it.deviceId, it.publicHex, it.privateHex) },
                peers = snapshot.peers.map { PeerDto(it.deviceId, it.displayName, it.kind.name, it.publicHex, it.linkedAtMs) },
                lockouts = snapshot.lockouts.map { LockoutDto(it.key, it.failures, it.lockedUntilMs) },
            )
        }
    }

    @Serializable
    private data class IdentityDto(val deviceId: String, val publicHex: String, val privateHex: String)

    @Serializable
    private data class PeerDto(
        val deviceId: String,
        val displayName: String,
        val kind: String,
        val publicHex: String,
        val linkedAtMs: Long,
    )

    @Serializable
    private data class LockoutDto(val key: String, val failures: Int = 0, val lockedUntilMs: Long = 0L)
}
