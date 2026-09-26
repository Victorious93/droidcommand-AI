package ai.droidcommand.remote.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HkdfTest {
    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun `matches RFC 5869 test case 1`() {
        val ikm = hex("0b".repeat(22))
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val prk = Hkdf.extract(salt, ikm)
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", prk.toHex())
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            Hkdf.expand(prk, info, 42).toHex(),
        )
    }

    @Test
    fun `matches RFC 5869 test case 3 (empty salt and info)`() {
        val ikm = hex("0b".repeat(22))
        val okm = Hkdf.derive(ByteArray(0), ikm, ByteArray(0), 42)
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            okm.toHex(),
        )
    }

    @Test
    fun `rejects an output length outside 1 to 255 hash lengths`() {
        assertFailsWith<IllegalArgumentException> { Hkdf.expand(ByteArray(32), ByteArray(0), 0) }
        assertFailsWith<IllegalArgumentException> { Hkdf.expand(ByteArray(32), ByteArray(0), 255 * 32 + 1) }
    }
}
