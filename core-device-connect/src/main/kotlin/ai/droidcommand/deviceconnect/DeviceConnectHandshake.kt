package ai.droidcommand.deviceconnect

import ai.droidcommand.remote.pairing.SecureChannel
import ai.droidcommand.remote.pairing.SessionKeys
import kotlinx.serialization.Serializable
import java.security.SecureRandom

/**
 * One SPAKE2 pairing attempt. Single use. The password never leaves this
 * object. After [shared] is set, both sides show [verificationCode] and
 * wait for a matching confirm before [finishSession].
 */
class DeviceConnectHandshake(
    private val role: Role,
    private val password: String,
    private val local: DeviceIdentity,
    private val localKind: DeviceKind,
    private val salt: ByteArray,
    private val random: SecureRandom = SecureRandom(),
) {
    enum class Role { INITIATOR, RESPONDER }

    private val initiator = if (role == Role.INITIATOR) Spake2.Initiator(password, salt, random) else null
    private val responder = if (role == Role.RESPONDER) Spake2.Responder(password, salt, random) else null

    var localShare: Spake2.PublicShare? = null
        private set
    var peerShare: Spake2.PublicShare? = null
        private set
    var peerHello: HandshakeHello? = null
        private set
    var shared: ByteArray? = null
        private set
    var verificationCode: String? = null
        private set

    private var cachedHello: HandshakeHello? = null

    fun localHello(): HandshakeHello {
        cachedHello?.let { return it }
        val share = if (role == Role.INITIATOR) initiator!!.share() else responder!!.share()
        localShare = share
        return HandshakeHello(
            version = DeviceConnect.PROTOCOL_VERSION,
            deviceId = local.deviceId,
            kind = localKind.name,
            publicHex = local.publicHex,
            saltHex = bytesToHex(salt),
            shareHex = bytesToHex(share.encode()),
        ).also { cachedHello = it }
    }

    fun acceptPeer(hello: HandshakeHello): DeviceConnectDecision {
        if (hello.version != DeviceConnect.PROTOCOL_VERSION) {
            return DeviceConnectDecision.Refused("This app version cannot pair with that device.")
        }
        if (hello.deviceId.isBlank() || hello.publicHex.isBlank()) {
            return DeviceConnectDecision.Refused("The other device sent a broken hello.")
        }
        val share = runCatching { Spake2.PublicShare.decode(hexToBytes(hello.shareHex)) }
            .getOrElse { return DeviceConnectDecision.Refused("The other device sent a broken pairing share.") }
        peerHello = hello
        peerShare = share
        val secret = if (role == Role.INITIATOR) initiator!!.finish(share) else responder!!.finish(share)
        shared = secret
        verificationCode = Spake2.verificationCode(secret)
        return DeviceConnectDecision.Ok("Check that both devices show the same code.")
    }

    fun confirmMac(): ByteArray {
        val secret = checkNotNull(shared) { "handshake is not finished" }
        return Spake2.confirmMac(secret, role.name.lowercase(), transcript())
    }

    fun verifyPeerMac(mac: ByteArray): Boolean {
        val secret = shared ?: return false
        val expectedRole = if (role == Role.INITIATOR) Role.RESPONDER else Role.INITIATOR
        val expected = Spake2.confirmMac(secret, expectedRole.name.lowercase(), transcript())
        return Spake2.macsEqual(expected, mac)
    }

    fun identityMessage(): ByteArray = transcript() + local.deviceId.toByteArray()

    fun peerIdentityMessage(): ByteArray {
        val peer = checkNotNull(peerHello)
        return transcript() + peer.deviceId.toByteArray()
    }

    fun finishSession(): Pair<SessionKeys, SecureChannel> {
        val secret = checkNotNull(shared)
        val (send, recv) = Spake2.sessionKeys(secret, initiatorToResponder = role == Role.INITIATOR)
        val keys = SessionKeys(send, recv)
        return keys to SecureChannel(keys)
    }

    fun transcript(): ByteArray {
        val localHello = HandshakeHello(
            version = DeviceConnect.PROTOCOL_VERSION,
            deviceId = local.deviceId,
            kind = localKind.name,
            publicHex = local.publicHex,
            saltHex = bytesToHex(salt),
            shareHex = bytesToHex(checkNotNull(localShare).encode()),
        )
        val peer = checkNotNull(peerHello)
        val first = if (role == Role.INITIATOR) localHello else peer
        val second = if (role == Role.INITIATOR) peer else localHello
        return buildString {
            append("dca-device-connect-v1|")
            append(first.deviceId).append('|').append(first.publicHex).append('|').append(first.shareHex).append('|')
            append(second.deviceId).append('|').append(second.publicHex).append('|').append(second.shareHex)
        }.toByteArray()
    }
}

@Serializable
data class HandshakeHello(
    val version: Int,
    val deviceId: String,
    val kind: String,
    val publicHex: String,
    val saltHex: String,
    val shareHex: String,
)
