package ai.droidcommand.remote.pairing

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant

/**
 * A three-message, mutually-authenticating handshake between a controller
 * (the side issuing commands) and a device, both holding the same
 * [PairingSecret]:
 *
 * 1. controller → device: [ClientHello] (a fresh 32-byte nonce)
 * 2. device → controller: [ServerHello] (its own nonce, plus an HMAC proof
 *    that it knows the secret, bound to both nonces)
 * 3. controller → device: [ClientFinish] (the controller's HMAC proof)
 *
 * Authentication happens inside the handshake, before either side has a
 * [SecureChannel]: a peer that can't prove the secret never gets session
 * keys, so there is no "connected but not yet authenticated" state to
 * misuse. The secret itself never crosses the wire. Proofs are role-labelled,
 * so one side's proof can't be reflected back as the other's, and both
 * nonces are fresh per handshake, so a recorded proof can't be replayed.
 * Proofs are compared in constant time.
 *
 * Session keys come from HKDF-SHA256 over the secret, salted with both
 * nonces, with one key per direction ([SessionKeys]).
 *
 * The device side consults an [AuthGate] so repeated wrong proofs from one
 * peer lock it out. Because an observer of a handshake could test guesses
 * of the secret offline against a proof, the secret must be full-entropy
 * ([PairingSecret.generate]); never derive it from a short human-typed PIN.
 *
 * This is the protocol only. It is transport-agnostic: callers carry
 * [toBytes] output over whatever connection they have and must run steps in
 * order on that connection.
 */
object PairingHandshake {
    const val VERSION: Byte = 1
    const val NONCE_BYTES = 32
    const val PROOF_BYTES = 32

    private val SERVER_PROOF_LABEL = "droidcommand-pair-v1 device-proof".toByteArray()
    private val CLIENT_PROOF_LABEL = "droidcommand-pair-v1 controller-proof".toByteArray()
    private val CONTROLLER_TO_DEVICE_INFO = "droidcommand-pair-v1 controller-to-device".toByteArray()
    private val DEVICE_TO_CONTROLLER_INFO = "droidcommand-pair-v1 device-to-controller".toByteArray()

    class ClientHello(clientNonce: ByteArray) {
        val clientNonce: ByteArray = clientNonce.copyOf()

        init {
            require(clientNonce.size == NONCE_BYTES) { "clientNonce must be $NONCE_BYTES bytes" }
        }

        fun toBytes(): ByteArray = byteArrayOf(VERSION) + clientNonce

        companion object {
            fun fromBytes(bytes: ByteArray): ClientHello {
                requireFrame(bytes, 1 + NONCE_BYTES, "ClientHello")
                return ClientHello(bytes.copyOfRange(1, 1 + NONCE_BYTES))
            }
        }
    }

    class ServerHello(serverNonce: ByteArray, serverProof: ByteArray) {
        val serverNonce: ByteArray = serverNonce.copyOf()
        val serverProof: ByteArray = serverProof.copyOf()

        init {
            require(serverNonce.size == NONCE_BYTES) { "serverNonce must be $NONCE_BYTES bytes" }
            require(serverProof.size == PROOF_BYTES) { "serverProof must be $PROOF_BYTES bytes" }
        }

        fun toBytes(): ByteArray = byteArrayOf(VERSION) + serverNonce + serverProof

        companion object {
            fun fromBytes(bytes: ByteArray): ServerHello {
                requireFrame(bytes, 1 + NONCE_BYTES + PROOF_BYTES, "ServerHello")
                return ServerHello(
                    bytes.copyOfRange(1, 1 + NONCE_BYTES),
                    bytes.copyOfRange(1 + NONCE_BYTES, 1 + NONCE_BYTES + PROOF_BYTES),
                )
            }
        }
    }

    class ClientFinish(clientProof: ByteArray) {
        val clientProof: ByteArray = clientProof.copyOf()

        init {
            require(clientProof.size == PROOF_BYTES) { "clientProof must be $PROOF_BYTES bytes" }
        }

        fun toBytes(): ByteArray = byteArrayOf(VERSION) + clientProof

        companion object {
            fun fromBytes(bytes: ByteArray): ClientFinish {
                requireFrame(bytes, 1 + PROOF_BYTES, "ClientFinish")
                return ClientFinish(bytes.copyOfRange(1, 1 + PROOF_BYTES))
            }
        }
    }

    sealed class ControllerResult {
        /** Send [finish] to the device, then use [keys] for a [SecureChannel]. */
        class Paired(val finish: ClientFinish, val keys: SessionKeys) : ControllerResult()

        /** The device could not prove it knows the secret. Send nothing further. */
        data class Rejected(val reason: String) : ControllerResult()
    }

    sealed class DeviceResponse {
        /** Send [hello] to the controller and keep [pending] for its [ClientFinish]. */
        class Challenge(val hello: ServerHello, val pending: Device.PendingPairing) : DeviceResponse()

        /** The peer is locked out by the [AuthGate]; no handshake was attempted. */
        data class LockedOut(val until: Instant) : DeviceResponse()
    }

    sealed class DeviceResult {
        class Paired(val keys: SessionKeys) : DeviceResult()

        data class Rejected(val reason: String) : DeviceResult()

        data class LockedOut(val until: Instant) : DeviceResult()
    }

    /** The controller's side. Single use: one [hello], then one [finish]. */
    class Controller(secret: PairingSecret, private val random: SecureRandom = SecureRandom()) {
        private val secret = secret.toByteArray()
        private var clientNonce: ByteArray? = null
        private var finished = false

        fun hello(): ClientHello {
            check(clientNonce == null) { "hello() already called; a Controller handshake is single-use" }
            val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
            clientNonce = nonce
            return ClientHello(nonce)
        }

        fun finish(serverHello: ServerHello): ControllerResult {
            val cn = checkNotNull(clientNonce) { "Call hello() before finish()" }
            check(!finished) { "finish() already called; a Controller handshake is single-use" }
            finished = true
            val expected = proof(secret, SERVER_PROOF_LABEL, cn, serverHello.serverNonce)
            if (!MessageDigest.isEqual(expected, serverHello.serverProof)) {
                return ControllerResult.Rejected("Device proof did not match the pairing secret")
            }
            val (toDevice, toController) = deriveKeys(secret, cn, serverHello.serverNonce)
            return ControllerResult.Paired(
                ClientFinish(proof(secret, CLIENT_PROOF_LABEL, cn, serverHello.serverNonce)),
                SessionKeys(sendKey = toDevice, receiveKey = toController),
            )
        }
    }

    /** The device's side. One instance may serve many handshakes; each gets its own [PendingPairing]. */
    class Device(
        secret: PairingSecret,
        private val gate: AuthGate = AuthGate(),
        private val random: SecureRandom = SecureRandom(),
    ) {
        private val secret = secret.toByteArray()

        fun respond(peerId: String, clientHello: ClientHello): DeviceResponse {
            gate.lockedUntil(peerId)?.let { return DeviceResponse.LockedOut(it) }
            val sn = ByteArray(NONCE_BYTES).also(random::nextBytes)
            val cn = clientHello.clientNonce
            val hello = ServerHello(sn, proof(secret, SERVER_PROOF_LABEL, cn, sn))
            return DeviceResponse.Challenge(hello, PendingPairing(peerId, cn, sn))
        }

        /** State between [respond] and the controller's [ClientFinish]. Single use. */
        inner class PendingPairing internal constructor(
            private val peerId: String,
            private val clientNonce: ByteArray,
            private val serverNonce: ByteArray,
        ) {
            private var completed = false

            fun complete(finish: ClientFinish): DeviceResult {
                check(!completed) { "complete() already called; a pending pairing is single-use" }
                completed = true
                gate.lockedUntil(peerId)?.let { return DeviceResult.LockedOut(it) }
                val expected = proof(secret, CLIENT_PROOF_LABEL, clientNonce, serverNonce)
                if (!MessageDigest.isEqual(expected, finish.clientProof)) {
                    gate.recordFailure(peerId)
                    return DeviceResult.Rejected("Controller proof did not match the pairing secret")
                }
                gate.recordSuccess(peerId)
                val (toDevice, toController) = deriveKeys(secret, clientNonce, serverNonce)
                return DeviceResult.Paired(SessionKeys(sendKey = toController, receiveKey = toDevice))
            }
        }
    }

    private fun proof(secret: ByteArray, label: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray): ByteArray =
        Hkdf.hmac(secret, label + clientNonce + serverNonce)

    /** Returns (controller-to-device key, device-to-controller key). */
    private fun deriveKeys(secret: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray): Pair<ByteArray, ByteArray> {
        val prk = Hkdf.extract(salt = clientNonce + serverNonce, inputKeyMaterial = secret)
        return Hkdf.expand(prk, CONTROLLER_TO_DEVICE_INFO, SessionKeys.KEY_BYTES) to
            Hkdf.expand(prk, DEVICE_TO_CONTROLLER_INFO, SessionKeys.KEY_BYTES)
    }

    private fun requireFrame(bytes: ByteArray, expectedSize: Int, name: String) {
        require(bytes.size == expectedSize) { "$name must be $expectedSize bytes, got ${bytes.size}" }
        require(bytes[0] == VERSION) { "Unsupported $name version ${bytes[0]}; expected $VERSION" }
    }
}
