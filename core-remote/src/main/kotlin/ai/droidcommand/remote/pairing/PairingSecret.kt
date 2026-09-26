package ai.droidcommand.remote.pairing

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The shared secret a controller and a device agree on out of band (shown
 * as a QR code or typed code on one side, entered on the other) before they
 * ever talk over the network. It is never sent on the wire: [PairingHandshake]
 * only exchanges HMAC proofs of knowing it, and session keys are derived from
 * it with [Hkdf].
 *
 * Always exactly [SIZE_BYTES] bytes of [SecureRandom] output when made by
 * [generate]. Comparison is constant-time ([matches]) so a peer probing
 * candidate secrets learns nothing from timing. [toString] is redacted so a
 * secret can't leak into a log line by accident.
 */
class PairingSecret(bytes: ByteArray) {
    private val bytes: ByteArray = bytes.copyOf()

    init {
        require(bytes.size == SIZE_BYTES) { "A pairing secret must be exactly $SIZE_BYTES bytes, got ${bytes.size}" }
    }

    /** A copy of the raw secret bytes, for key derivation and proofs. */
    fun toByteArray(): ByteArray = bytes.copyOf()

    /** URL-safe, unpadded Base64 — the form shown to a user or put in a QR code. */
    fun encode(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Constant-time equality, via [MessageDigest.isEqual]. */
    fun matches(other: PairingSecret): Boolean = MessageDigest.isEqual(bytes, other.bytes)

    override fun equals(other: Any?): Boolean = other is PairingSecret && matches(other)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "PairingSecret(<redacted>)"

    companion object {
        const val SIZE_BYTES = 32

        fun generate(random: SecureRandom = SecureRandom()): PairingSecret {
            val bytes = ByteArray(SIZE_BYTES)
            random.nextBytes(bytes)
            return PairingSecret(bytes)
        }

        /** Parses [encode]'s output. Throws [IllegalArgumentException] on bad Base64 or the wrong length. */
        fun decode(encoded: String): PairingSecret = PairingSecret(Base64.getUrlDecoder().decode(encoded.trim()))
    }
}
