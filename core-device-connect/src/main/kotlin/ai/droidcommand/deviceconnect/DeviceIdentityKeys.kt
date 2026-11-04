package ai.droidcommand.deviceconnect

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Long-term P-256 identity used for trust-on-first-use after SPAKE2.
 * The private key never leaves the store. Comparison is of the public
 * X.509 encoding, hex-encoded.
 */
object DeviceIdentityKeys {
    fun generate(deviceId: String = DeviceConnect.generateDeviceId(), random: SecureRandom = SecureRandom()): DeviceIdentity {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), random)
        val pair = kpg.generateKeyPair()
        return DeviceIdentity(
            deviceId = deviceId,
            publicHex = bytesToHex(pair.public.encoded),
            privateHex = bytesToHex(pair.private.encoded),
        )
    }

    fun sign(identity: DeviceIdentity, message: ByteArray): ByteArray {
        val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(hexToBytes(identity.privateHex)))
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(key)
        sig.update(message)
        return sig.sign()
    }

    fun verify(publicHex: String, message: ByteArray, signature: ByteArray): Boolean {
        return runCatching {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(hexToBytes(publicHex)))
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(key)
            sig.update(message)
            sig.verify(signature)
        }.getOrDefault(false)
    }
}
