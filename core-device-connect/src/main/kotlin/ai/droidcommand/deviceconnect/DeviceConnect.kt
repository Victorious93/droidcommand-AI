package ai.droidcommand.deviceconnect

import java.security.SecureRandom

/**
 * LAN device pairing (phone↔phone or phone↔PC). Complements, and does not
 * replace, PR #262's fleet-app password pairing. That handshake is HMAC
 * over a package+cert; this one is SPAKE2 over a user password so the
 * password never crosses the wire on a local socket.
 *
 * Lockout, TOFU, revoke, PBKDF2 parameters, and OFF-by-default are copied
 * from #262's `PasswordPairing` so the two PRs stay conceptually aligned.
 * They do not share a store. See `docs/DEVICE_CONNECT.md`.
 */
object DeviceConnect {
    const val PROTOCOL = "device-connect"
    const val PROTOCOL_VERSION = 1
    const val DEFAULT_PORT = 17890
    const val MAX_FAILURES = 5
    const val LOCKOUT_MS = 15 * 60_000L
    const val MIN_PASSWORD_CHARS = Spake2.MIN_PASSWORD_CHARS
    const val CHALLENGE_TTL_MS = 5 * 60_000L
    const val HANDSHAKE_TIMEOUT_MS = 20_000
    const val MAX_CHAT_CHARS = 8_000
    const val MAX_FILE_CHARS = 32_000
    const val IDENTITY_BYTES = 32
    const val GENERATED_PASSWORD_CHARS = 10
    const val STORE_ID = "device-connect.snapshot"
    private const val PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun generatePassword(random: SecureRandom = SecureRandom()): String {
        val chars = CharArray(GENERATED_PASSWORD_CHARS)
        val bytes = ByteArray(GENERATED_PASSWORD_CHARS)
        random.nextBytes(bytes)
        for (i in chars.indices) {
            chars[i] = PASSWORD_ALPHABET[(bytes[i].toInt() and 0xFF) % PASSWORD_ALPHABET.length]
        }
        return String(chars)
    }

    fun generateDeviceId(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytesToHex(bytes)
    }

    fun inviteOf(host: String, port: Int, deviceId: String): String =
        "dca-connect:$PROTOCOL_VERSION|$host|$port|$deviceId"

    fun parseInvite(raw: String): Triple<String, Int, String>? {
        val text = raw.trim()
        val parts = text.removePrefix("dca-connect:").split('|')
        if (parts.size < 4) return null
        val version = parts[0].toIntOrNull() ?: return null
        if (version != PROTOCOL_VERSION) return null
        val host = parts[1].trim()
        val port = parts[2].toIntOrNull() ?: return null
        val deviceId = parts[3].trim()
        if (host.isBlank() || port !in 1..65535 || deviceId.isBlank()) return null
        return Triple(host, port, deviceId)
    }
}

enum class DeviceKind {
    PHONE,
    PC,
}

enum class DeviceConnectPhase {
    IDLE,
    LISTENING,
    JOINING,
    VERIFY,
    SESSION,
    LOCKED,
    FAILED,
}

data class DeviceIdentity(
    val deviceId: String,
    val publicHex: String,
    val privateHex: String,
)

data class TrustedDevice(
    val deviceId: String,
    val displayName: String,
    val kind: DeviceKind,
    val publicHex: String,
    val linkedAtMs: Long,
)

data class DeviceLockout(
    val key: String,
    val failures: Int = 0,
    val lockedUntilMs: Long = 0L,
)

data class DeviceConnectSnapshot(
    val keepListening: Boolean = false,
    val identity: DeviceIdentity? = null,
    val peers: List<TrustedDevice> = emptyList(),
    val lockouts: List<DeviceLockout> = emptyList(),
)

sealed class DeviceConnectDecision {
    data class Ok(val message: String, val extra: String? = null) : DeviceConnectDecision()
    data class Refused(val reason: String, val locked: Boolean = false) : DeviceConnectDecision()
}

data class DeviceStatus(
    val app: String = "DroidCommand",
    val phase: DeviceConnectPhase,
    val listening: Boolean,
    val keepListening: Boolean,
    val localAddresses: List<String>,
    val port: Int?,
    val deviceId: String?,
    val invite: String?,
    val verificationCode: String?,
    val peerName: String?,
    val peers: List<TrustedDevice>,
)

data class InboxNote(
    val fromDeviceId: String,
    val fromName: String,
    val text: String,
    val receivedAtMs: Long,
)

interface DeviceConnectStore {
    fun load(): DeviceConnectSnapshot
    fun save(snapshot: DeviceConnectSnapshot)
}

class InMemoryDeviceConnectStore(
    initial: DeviceConnectSnapshot = DeviceConnectSnapshot(),
) : DeviceConnectStore {
    private var snapshot = initial

    @Synchronized override fun load(): DeviceConnectSnapshot = snapshot

    @Synchronized override fun save(snapshot: DeviceConnectSnapshot) {
        this.snapshot = snapshot
    }
}

interface DeviceInbox {
    fun post(note: InboxNote)
    fun drain(): List<InboxNote>
}

class InMemoryDeviceInbox : DeviceInbox {
    private val notes = mutableListOf<InboxNote>()

    @Synchronized override fun post(note: InboxNote) {
        notes += note
    }

    @Synchronized override fun drain(): List<InboxNote> {
        val copy = notes.toList()
        notes.clear()
        return copy
    }
}
