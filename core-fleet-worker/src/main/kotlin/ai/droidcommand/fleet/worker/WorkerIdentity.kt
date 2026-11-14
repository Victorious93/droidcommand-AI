package ai.droidcommand.fleet.worker

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * The phone's own cryptographic identity. Production implementation (in :app) wraps an Android Keystore EC P-256 key
 * so the private key never leaves secure hardware where available; this module only needs to sign and show the public key.
 * Each phone has a distinct key; nothing is shared fleet-wide.
 */
interface WorkerIdentity {
    /** SubjectPublicKeyInfo as PEM. */
    fun publicKeyPem(): String

    /** SHA256withECDSA, DER-encoded signature (the format Android Keystore emits). */
    fun sign(message: ByteArray): ByteArray
}

/** Software EC P-256 identity for JVM tests and non-Keystore hosts. NOT hardware-backed; says so. */
class SoftwareWorkerIdentity(private val pair: KeyPair = generate()) : WorkerIdentity {
    val protection: String = "software (not hardware-backed)"
    override fun publicKeyPem(): String {
        val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.public.encoded)
        return "-----BEGIN PUBLIC KEY-----\n$b64\n-----END PUBLIC KEY-----\n"
    }
    override fun sign(message: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(message)
            sign()
        }

    companion object {
        fun generate(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    }
}
