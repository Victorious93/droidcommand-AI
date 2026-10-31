package ai.droidcommand.deviceconnect

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class P256Test {
    @Test
    fun `base point times order is infinity`() {
        assertTrue(P256.multiply(P256.G, P256.N).isInfinity)
    }

    @Test
    fun `twice G matches the known P-256 double`() {
        val doubled = P256.twice(P256.G)
        assertEquals(
            BigInteger("7cf27b188d034f7e8a52380304b51ac3c08969e277f21b35a60b48fc47669978", 16),
            doubled.x,
        )
        assertEquals(
            BigInteger("07775510db8ed040293d9ac69f7430dbba7dade63ce982299e04b79d227873d1", 16),
            doubled.y,
        )
    }

    @Test
    fun `uncompressed round trip keeps the generator`() {
        val encoded = P256.G.encodeUncompressed()
        assertEquals(P256.G, P256Point.decodeUncompressed(encoded))
    }

    @Test
    fun `RFC 9382 M and N sit on the curve`() {
        assertEquals(P256.SPAKE_M, P256.onCurve(P256.SPAKE_M.x!!, P256.SPAKE_M.y!!))
        assertEquals(P256.SPAKE_N, P256.onCurve(P256.SPAKE_N.x!!, P256.SPAKE_N.y!!))
    }
}
