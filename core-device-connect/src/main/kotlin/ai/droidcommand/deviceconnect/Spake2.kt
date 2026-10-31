package ai.droidcommand.deviceconnect

import ai.droidcommand.remote.pairing.Hkdf
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * SPAKE2 over P-256 with a PBKDF2-stretched password.
 *
 * Constants M/N are the RFC 9382 P-256 points. The password is stretched
 * with the same PBKDF2-HMAC-SHA256 parameters as PR #262's fleet password
 * pairing (210 000 iterations, 16-byte salt, 32-byte key) so a short
 * human password is not used as a curve scalar. The password itself never
 * crosses the wire.
 *
 * Both sides must use the same password and salt. The salt is public and
 * is sent in the first handshake message.
 */
object Spake2 {
    const val PBKDF2_ITERATIONS = 210_000
    const val KEY_BYTES = 32
    const val SALT_BYTES = 16
    const val MIN_PASSWORD_CHARS = 8
    const val MAX_PASSWORD_CHARS = 128

    class PublicShare internal constructor(internal val point: P256Point) {
        fun encode(): ByteArray = point.encodeUncompressed()

        companion object {
            fun decode(bytes: ByteArray): PublicShare = PublicShare(P256Point.decodeUncompressed(bytes))
        }
    }

    class Initiator(
        password: String,
        salt: ByteArray,
        private val random: SecureRandom = SecureRandom(),
    ) {
        private val w = passwordToScalar(password, salt)
        private var x: BigInteger? = null
        private var xPoint: P256Point? = null

        fun share(): PublicShare {
            check(x == null) { "initiator share already created" }
            val scalar = P256.randomScalar(random)
            x = scalar
            val point = P256.add(P256.multiply(P256.G, scalar), P256.multiply(P256.SPAKE_M, w))
            xPoint = point
            return PublicShare(point)
        }

        fun finish(peer: PublicShare): ByteArray {
            val scalar = checkNotNull(x) { "call share() first" }
            val peerBare = P256.add(peer.point, P256.negate(P256.multiply(P256.SPAKE_N, w)))
            val shared = P256.multiply(peerBare, scalar)
            return encodeShared(shared)
        }
    }

    class Responder(
        password: String,
        salt: ByteArray,
        private val random: SecureRandom = SecureRandom(),
    ) {
        private val w = passwordToScalar(password, salt)
        private var y: BigInteger? = null

        fun share(): PublicShare {
            check(y == null) { "responder share already created" }
            val scalar = P256.randomScalar(random)
            y = scalar
            val point = P256.add(P256.multiply(P256.G, scalar), P256.multiply(P256.SPAKE_N, w))
            return PublicShare(point)
        }

        fun finish(peer: PublicShare): ByteArray {
            val scalar = checkNotNull(y) { "call share() first" }
            val peerBare = P256.add(peer.point, P256.negate(P256.multiply(P256.SPAKE_M, w)))
            val shared = P256.multiply(peerBare, scalar)
            return encodeShared(shared)
        }
    }

    fun newSalt(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(SALT_BYTES).also(random::nextBytes)

    fun passwordToScalar(password: String, salt: ByteArray): BigInteger {
        require(password.length in MIN_PASSWORD_CHARS..MAX_PASSWORD_CHARS) {
            "Password must be $MIN_PASSWORD_CHARS to $MAX_PASSWORD_CHARS characters."
        }
        require(salt.size == SALT_BYTES) { "salt must be $SALT_BYTES bytes" }
        val key = deriveKey(password, salt)
        val scalar = BigInteger(1, key).mod(P256.N).let { if (it.signum() == 0) BigInteger.ONE else it }
        key.fill(0)
        return scalar
    }

    fun deriveKey(password: String, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun confirmMac(shared: ByteArray, role: String, transcript: ByteArray): ByteArray =
        Hkdf.derive(ByteArray(0), shared, "dca-device-connect-v1|$role".toByteArray() + transcript, KEY_BYTES)

    fun verificationCode(shared: ByteArray): String {
        val raw = Hkdf.derive(ByteArray(0), shared, "dca-device-connect-v1|sas".toByteArray(), 3)
        val n = ((raw[0].toInt() and 0xFF) shl 16) or ((raw[1].toInt() and 0xFF) shl 8) or (raw[2].toInt() and 0xFF)
        val a = (n / 1000) % 1000
        val b = n % 1000
        return "%03d-%03d".format(a, b)
    }

    fun sessionKeys(shared: ByteArray, initiatorToResponder: Boolean): Pair<ByteArray, ByteArray> {
        val sendInfo = if (initiatorToResponder) "dca-device-connect-v1|i2r" else "dca-device-connect-v1|r2i"
        val recvInfo = if (initiatorToResponder) "dca-device-connect-v1|r2i" else "dca-device-connect-v1|i2r"
        val send = Hkdf.derive(ByteArray(0), shared, sendInfo.toByteArray(), KEY_BYTES)
        val recv = Hkdf.derive(ByteArray(0), shared, recvInfo.toByteArray(), KEY_BYTES)
        return send to recv
    }

    fun macsEqual(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    private fun encodeShared(point: P256Point): ByteArray {
        require(!point.isInfinity) { "SPAKE2 produced the point at infinity" }
        return point.x!!.toUnsigned(P256.COORD_BYTES)
    }
}
