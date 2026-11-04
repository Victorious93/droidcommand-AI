package ai.droidcommand.deviceconnect

import java.security.SecureRandom

/**
 * Pairing state machine: identity, lockout, TOFU, revoke, keep-listening.
 * Transport lives in [DeviceConnectTransport]. Copied lockout / TOFU /
 * revoke rules from PR #262's `PasswordPairingService`.
 */
class DeviceConnectService(
    private val store: DeviceConnectStore,
    private val inbox: DeviceInbox = InMemoryDeviceInbox(),
    private val random: () -> SecureRandom = { SecureRandom() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    @Volatile
    var phase: DeviceConnectPhase = DeviceConnectPhase.IDLE
        private set

    @Volatile
    var keepListening: Boolean = store.load().keepListening
        private set

    @Volatile
    var lastVerification: String? = null
        private set

    @Volatile
    var lastPeerName: String? = null
        private set

    @Volatile
    var lastError: String? = null
        private set

    fun snapshot(): DeviceConnectSnapshot = store.load()

    fun inbox(): DeviceInbox = inbox

    fun identity(): DeviceIdentity {
        val current = snapshot()
        current.identity?.let { return it }
        val created = DeviceIdentityKeys.generate(random = random())
        save(current.copy(identity = created))
        return created
    }

    fun setKeepListening(on: Boolean) {
        keepListening = on
        save(snapshot().copy(keepListening = on))
        if (!on && phase == DeviceConnectPhase.LISTENING) {
            // Transport stops the socket; this only records the preference.
        }
    }

    fun setPhase(next: DeviceConnectPhase, error: String? = null) {
        phase = next
        lastError = error
        if (next != DeviceConnectPhase.VERIFY && next != DeviceConnectPhase.SESSION) {
            lastVerification = null
        }
    }

    fun rememberVerification(code: String, peerName: String) {
        lastVerification = code
        lastPeerName = peerName
        phase = DeviceConnectPhase.VERIFY
    }

    fun rememberSession(peerName: String) {
        lastPeerName = peerName
        lastVerification = null
        phase = DeviceConnectPhase.SESSION
    }

    fun generatePassword(): String = DeviceConnect.generatePassword(random())

    fun peers(): List<TrustedDevice> = snapshot().peers

    fun revoke(deviceId: String): Boolean {
        val current = snapshot()
        val next = current.peers.filterNot { it.deviceId == deviceId }
        if (next.size == current.peers.size) return false
        save(current.copy(peers = next))
        return true
    }

    fun lockoutDecision(key: String): DeviceConnectDecision? {
        val row = snapshot().lockouts.find { it.key == key } ?: return null
        if (row.lockedUntilMs > clock()) {
            phase = DeviceConnectPhase.LOCKED
            return DeviceConnectDecision.Refused(
                "Too many wrong passwords. Try again in a few minutes.",
                locked = true,
            )
        }
        return null
    }

    fun failAttempt(key: String, reason: String): DeviceConnectDecision {
        val current = snapshot()
        val existing = current.lockouts.find { it.key == key } ?: DeviceLockout(key)
        val failures = existing.failures + 1
        val lockedUntil = if (failures >= DeviceConnect.MAX_FAILURES) clock() + DeviceConnect.LOCKOUT_MS else 0L
        save(
            current.copy(
                lockouts = current.lockouts.filterNot { it.key == key } + DeviceLockout(key, failures, lockedUntil),
            ),
        )
        val locked = lockedUntil > 0L
        if (locked) phase = DeviceConnectPhase.LOCKED else phase = DeviceConnectPhase.FAILED
        lastError = reason
        return DeviceConnectDecision.Refused(reason, locked = locked)
    }

    fun tofuConflict(deviceId: String, publicHex: String): DeviceConnectDecision? {
        val existing = snapshot().peers.find { it.deviceId == deviceId } ?: return null
        if (existing.publicHex.equals(publicHex, ignoreCase = true)) return null
        return DeviceConnectDecision.Refused("This device already paired with a different key. Forget it first, then pair again.")
    }

    fun link(deviceId: String, displayName: String, kind: DeviceKind, publicHex: String): DeviceConnectDecision {
        tofuConflict(deviceId, publicHex)?.let { return it }
        val current = snapshot()
        val peer = TrustedDevice(deviceId, displayName, kind, publicHex.lowercase(), clock())
        save(
            current.copy(
                peers = current.peers.filterNot { it.deviceId == deviceId } + peer,
                lockouts = current.lockouts.filterNot { it.key == deviceId },
            ),
        )
        lastPeerName = displayName
        return DeviceConnectDecision.Ok("Paired with $displayName.")
    }

    fun acceptLinked(deviceId: String, publicHex: String): DeviceConnectDecision {
        val peer = snapshot().peers.find { it.deviceId == deviceId }
            ?: return DeviceConnectDecision.Refused("This device is not paired yet.")
        if (!peer.publicHex.equals(publicHex, ignoreCase = true)) {
            return DeviceConnectDecision.Refused("This device already paired with a different key. Forget it first, then pair again.")
        }
        return DeviceConnectDecision.Ok("Still the same paired device.")
    }

    fun postChat(fromDeviceId: String, fromName: String, text: String): DeviceConnectDecision {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return DeviceConnectDecision.Refused("Nothing to send.")
        if (trimmed.length > DeviceConnect.MAX_CHAT_CHARS) {
            return DeviceConnectDecision.Refused("That note is too long.")
        }
        inbox.post(InboxNote(fromDeviceId, fromName, trimmed, clock()))
        return DeviceConnectDecision.Ok("Note saved to chat.")
    }

    fun postFile(fromDeviceId: String, fromName: String, name: String, text: String): DeviceConnectDecision {
        if (name.isBlank()) return DeviceConnectDecision.Refused("The file has no name.")
        if (text.length > DeviceConnect.MAX_FILE_CHARS) {
            return DeviceConnectDecision.Refused("That file is too large to send as text.")
        }
        return postChat(fromDeviceId, fromName, "File $name:\n$text")
    }

    fun status(
        listening: Boolean,
        port: Int?,
        addresses: List<String>,
        invite: String?,
    ): DeviceStatus {
        val id = snapshot().identity?.deviceId
        return DeviceStatus(
            phase = phase,
            listening = listening,
            keepListening = keepListening,
            localAddresses = addresses,
            port = port,
            deviceId = id,
            invite = invite,
            verificationCode = lastVerification,
            peerName = lastPeerName,
            peers = peers(),
        )
    }

    private fun save(snapshot: DeviceConnectSnapshot) {
        store.save(snapshot)
    }
}
