package ai.droidcommand.remote.pairing

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF with HMAC-SHA256, exactly as specified by RFC 5869 (checked against
 * its published test vectors in `HkdfTest`). The JDK has no public HKDF API
 * before Java 24's KDF preview, so this is the small, well-specified
 * extract-then-expand construction written directly on [Mac].
 */
object Hkdf {
    private const val ALGORITHM = "HmacSHA256"
    private const val HASH_LEN = 32

    fun extract(salt: ByteArray, inputKeyMaterial: ByteArray): ByteArray {
        // RFC 5869 §2.2: an empty salt means HashLen zero bytes.
        val effectiveSalt = if (salt.isEmpty()) ByteArray(HASH_LEN) else salt
        return hmac(effectiveSalt, inputKeyMaterial)
    }

    fun expand(pseudoRandomKey: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * HASH_LEN) { "HKDF output length must be 1..${255 * HASH_LEN}, got $length" }
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            previous = hmac(pseudoRandomKey, previous + info + byteArrayOf(counter.toByte()))
            val take = minOf(previous.size, length - written)
            previous.copyInto(output, written, 0, take)
            written += take
            counter++
        }
        return output
    }

    fun derive(salt: ByteArray, inputKeyMaterial: ByteArray, info: ByteArray, length: Int): ByteArray =
        expand(extract(salt, inputKeyMaterial), info, length)

    internal fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(key, ALGORITHM))
        return mac.doFinal(data)
    }
}
