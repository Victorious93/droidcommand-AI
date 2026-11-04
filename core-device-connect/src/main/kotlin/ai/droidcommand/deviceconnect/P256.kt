package ai.droidcommand.deviceconnect

import java.math.BigInteger
import java.security.SecureRandom

/**
 * Affine P-256 (secp256r1) arithmetic used by [Spake2]. Infinity is a
 * dedicated value. Points that are not on the curve are refused.
 */
internal class P256Point internal constructor(
    val x: BigInteger?,
    val y: BigInteger?,
) {
    val isInfinity: Boolean get() = x == null && y == null

    fun encodeUncompressed(): ByteArray {
        check(!isInfinity) { "cannot encode the point at infinity" }
        val xb = x!!.toUnsigned(P256.COORD_BYTES)
        val yb = y!!.toUnsigned(P256.COORD_BYTES)
        return byteArrayOf(0x04) + xb + yb
    }

    override fun equals(other: Any?): Boolean =
        other is P256Point && x == other.x && y == other.y

    override fun hashCode(): Int = 31 * (x?.hashCode() ?: 0) + (y?.hashCode() ?: 0)

    override fun toString(): String = if (isInfinity) "P256Point(inf)" else "P256Point(<redacted>)"

    companion object {
        val INFINITY: P256Point = P256Point(null, null)

        fun decodeUncompressed(bytes: ByteArray): P256Point {
            require(bytes.size == 1 + 2 * P256.COORD_BYTES && bytes[0] == 0x04.toByte()) {
                "P-256 point must be an uncompressed 65-byte encoding"
            }
            val x = BigInteger(1, bytes.copyOfRange(1, 1 + P256.COORD_BYTES))
            val y = BigInteger(1, bytes.copyOfRange(1 + P256.COORD_BYTES, bytes.size))
            return P256.onCurve(x, y)
        }

        fun decodeCompressed(bytes: ByteArray): P256Point {
            require(bytes.size == 1 + P256.COORD_BYTES && (bytes[0] == 0x02.toByte() || bytes[0] == 0x03.toByte())) {
                "P-256 compressed point must be 33 bytes"
            }
            val x = BigInteger(1, bytes.copyOfRange(1, bytes.size))
            val y = P256.decompressY(x, odd = bytes[0] == 0x03.toByte())
            return P256.onCurve(x, y)
        }
    }
}

internal object P256 {
    const val COORD_BYTES = 32

    val P: BigInteger = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    val A: BigInteger = P.subtract(BigInteger.valueOf(3))
    val B: BigInteger = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)
    val N: BigInteger = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
    val G: P256Point = P256Point(
        BigInteger("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", 16),
        BigInteger("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5", 16),
    )

    /** RFC 9382 SPAKE2 M (compressed). */
    val SPAKE_M: P256Point = P256Point.decodeCompressed(
        hexToBytes("02886e2f97ace46e55ba9dd7242579f2993b64e16ef3dcab95afd497333d8fa12f"),
    )

    /** RFC 9382 SPAKE2 N (compressed). */
    val SPAKE_N: P256Point = P256Point.decodeCompressed(
        hexToBytes("03d8bbd6c639c62937b04d997f38c3770719c629d7014d49a24b4f98baa1292b49"),
    )

    fun onCurve(x: BigInteger, y: BigInteger): P256Point {
        require(x.signum() >= 0 && x < P && y.signum() >= 0 && y < P) { "coordinate out of range" }
        val left = y.modPow(BigInteger.TWO, P)
        val right = x.modPow(BigInteger.valueOf(3), P).add(A.multiply(x)).add(B).mod(P)
        require(left == right) { "point is not on P-256" }
        return P256Point(x, y)
    }

    fun decompressY(x: BigInteger, odd: Boolean): BigInteger {
        require(x.signum() >= 0 && x < P) { "x out of range" }
        val y2 = x.modPow(BigInteger.valueOf(3), P).add(A.multiply(x)).add(B).mod(P)
        // p ≡ 3 (mod 4): sqrt = y2^((p+1)/4)
        val y = y2.modPow(P.add(BigInteger.ONE).shiftRight(2), P)
        require(y.modPow(BigInteger.TWO, P) == y2) { "x is not a valid compressed P-256 point" }
        return if (y.testBit(0) == odd) y else P.subtract(y)
    }

    fun add(p: P256Point, q: P256Point): P256Point {
        if (p.isInfinity) return q
        if (q.isInfinity) return p
        val px = p.x!!
        val py = p.y!!
        val qx = q.x!!
        val qy = q.y!!
        if (px == qx) {
            return if (py.add(qy).mod(P) == BigInteger.ZERO) P256Point.INFINITY else twice(p)
        }
        val s = qy.subtract(py).multiply(qx.subtract(px).modInverse(P)).mod(P)
        val xr = s.multiply(s).subtract(px).subtract(qx).mod(P)
        val yr = s.multiply(px.subtract(xr)).subtract(py).mod(P)
        return P256Point(xr, yr)
    }

    fun twice(p: P256Point): P256Point {
        if (p.isInfinity) return p
        val px = p.x!!
        val py = p.y!!
        if (py == BigInteger.ZERO) return P256Point.INFINITY
        val s = px.multiply(px).multiply(BigInteger.valueOf(3)).add(A)
            .multiply(py.shiftLeft(1).modInverse(P)).mod(P)
        val xr = s.multiply(s).subtract(px.shiftLeft(1)).mod(P)
        val yr = s.multiply(px.subtract(xr)).subtract(py).mod(P)
        return P256Point(xr, yr)
    }

    fun negate(p: P256Point): P256Point =
        if (p.isInfinity) p else P256Point(p.x, P.subtract(p.y!!).mod(P))

    fun multiply(p: P256Point, k: BigInteger): P256Point {
        var scalar = k.mod(N)
        if (scalar.signum() == 0 || p.isInfinity) return P256Point.INFINITY
        var result = P256Point.INFINITY
        var addend = p
        var n = scalar
        while (n.signum() > 0) {
            if (n.testBit(0)) result = add(result, addend)
            addend = twice(addend)
            n = n.shiftRight(1)
        }
        return result
    }

    fun randomScalar(random: SecureRandom): BigInteger {
        val bytes = ByteArray(COORD_BYTES + 8)
        while (true) {
            random.nextBytes(bytes)
            val k = BigInteger(1, bytes).mod(N)
            if (k.signum() > 0) return k
        }
    }
}

internal fun BigInteger.toUnsigned(size: Int): ByteArray {
    val raw = toByteArray()
    val start = if (raw.isNotEmpty() && raw[0] == 0.toByte()) 1 else 0
    val useful = raw.size - start
    require(useful <= size) { "integer does not fit in $size bytes" }
    return ByteArray(size).also { dest ->
        raw.copyInto(dest, size - useful, start, raw.size)
    }
}

internal fun hexToBytes(hex: String): ByteArray {
    val clean = hex.replace(":", "").replace(" ", "")
    require(clean.length % 2 == 0) { "odd hex length" }
    return ByteArray(clean.length / 2) { i ->
        clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}

internal fun bytesToHex(bytes: ByteArray): String =
    bytes.joinToString("") { b -> "%02x".format(b) }
