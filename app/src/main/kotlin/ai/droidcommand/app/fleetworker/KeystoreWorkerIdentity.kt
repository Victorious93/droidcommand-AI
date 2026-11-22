package ai.droidcommand.app.fleetworker

import ai.droidcommand.fleet.worker.WorkerIdentity
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.ProviderException
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

// Never run on a device. Follows the key parameters of AndroidSecretsVault (StrongBox first, then the platform default, no
// fallback outside the AndroidKeyStore). The signature format matches what the coordinator verifies: SHA256withECDSA, DER.

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val DEFAULT_ALIAS = "droidcommand.fleet.worker.identity.v1"

/**
 * This phone's own identity for the fleet coordinator: a non-extractable EC P-256 key in the AndroidKeyStore, created the
 * first time it is needed and never replaced (replacing it would orphan the pairing). Nothing is shared with any other
 * phone or app. [protection] says honestly whether the key is in secure hardware on this device.
 */
class KeystoreWorkerIdentity(private val alias: String = DEFAULT_ALIAS) : WorkerIdentity {
    private val keyStore: KeyStore get() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override fun publicKeyPem(): String {
        val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(publicKey().encoded)
        return "-----BEGIN PUBLIC KEY-----\n$b64\n-----END PUBLIC KEY-----\n"
    }

    override fun sign(message: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(privateKey())
        update(message)
        sign()
    }

    /** "hardware-backed", "software-backed" or "unknown", from what the Keystore reports for this key. */
    fun protection(): String = runCatching {
        val key = privateKey()
        val info = KeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE).getKeySpec(key, KeyInfo::class.java)
        @Suppress("DEPRECATION")
        if (info.isInsideSecureHardware) "hardware-backed" else "software-backed"
    }.getOrDefault("unknown")

    private fun ensure() {
        if (keyStore.containsAlias(alias)) return
        try {
            generate(strongBox = true)
        } catch (_: ProviderException) {
            generate(strongBox = false)
        }
    }

    private fun generate(strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setIsStrongBoxBacked(strongBox)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).apply { initialize(spec) }.generateKeyPair()
    }

    private fun privateKey(): PrivateKey {
        ensure()
        return keyStore.getKey(alias, null) as PrivateKey
    }

    private fun publicKey(): PublicKey {
        ensure()
        return keyStore.getCertificate(alias).publicKey
    }
}
