package ai.droidcommand.phonemic

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Phone side of the VictorLinux "VMIC1" microphone stream (see VictorLinux docs/phone-microphone.md).
 * Pure JVM: no Android classes, so it runs in unit tests against the real Python receiver. The Victorious
 * Keyboard app carries a byte-identical copy of this file (package differs) so neither app depends on the other.
 *
 * Privacy: audio only ever lives in the bounded [LiveQueue]; nothing here writes audio or secrets to disk or logs.
 */
const val SAMPLE_RATE = 16_000
const val FRAME_MS = 20
const val FRAME_BYTES = SAMPLE_RATE * FRAME_MS / 1000 * 2
const val MAX_QUEUE_MS = 400
const val MAX_FRAME_PAYLOAD = 64 * 1024

enum class Provider(val wire: String, val label: String) {
    DROIDCOMMAND("droidcommand", "DroidCommand-AI"),
    KEYBOARD("keyboard", "Victorious Keyboard"),
}

/** `vmic1;host;port;sessionId;secretHex;pcId;deviceId;provider;spkiSha256;expiryEpochS` */
data class MicOffer(
    val host: String, val port: Int, val sessionId: String, val secret: ByteArray,
    val pcId: String, val deviceId: String, val provider: String, val spkiSha256: String, val expiresAtS: Long,
) {
    fun expired(nowS: Long) = nowS >= expiresAtS
    override fun toString() = "MicOffer(pc=$pcId, device=$deviceId, provider=$provider)" // never prints the secret

    companion object {
        private val SAFE = Regex("^[A-Za-z0-9._-]{1,96}$")
        private val HEX = Regex("^[0-9a-f]+$")

        /** Returns null for anything malformed; the caller shows "not a valid offer". */
        fun parse(text: String): MicOffer? {
            val p = text.trim().split(";")
            if (p.size != 10 || p[0] != "vmic1") return null
            val port = p[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            if (p[1].isEmpty() || p[1].length > 255 || ';' in p[1]) return null
            if (!(SAFE.matches(p[3]) && SAFE.matches(p[5]) && SAFE.matches(p[6]))) return null
            if (Provider.entries.none { it.wire == p[7] }) return null
            if (p[4].length != 64 || !HEX.matches(p[4]) || p[8].length != 64 || !HEX.matches(p[8])) return null
            val exp = p[9].toLongOrNull() ?: return null
            val secret = ByteArray(32) { p[4].substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            return MicOffer(p[1], port, p[3], secret, p[5], p[6], p[7], p[8], exp)
        }
    }
}

object Frames {
    const val CHALLENGE = 0; const val HELLO = 1; const val HELLO_OK = 2
    const val AUDIO = 3; const val CTRL = 4; const val BYE = 5

    fun write(out: DataOutputStream, type: Int, payload: ByteArray) {
        require(payload.size <= MAX_FRAME_PAYLOAD)
        val b = ByteBuffer.allocate(5 + payload.size).putInt(payload.size).put(type.toByte()).put(payload)
        out.write(b.array()); out.flush()
    }

    fun read(inp: DataInputStream): Pair<Int, ByteArray> {
        val n = inp.readInt()
        if (n < 0 || n > MAX_FRAME_PAYLOAD) throw java.io.IOException("frame too large")
        val t = inp.readUnsignedByte()
        return t to ByteArray(n).also { inp.readFully(it) }
    }

    fun audio(seq: Int, pcm: ByteArray): ByteArray = ByteBuffer.allocate(4 + pcm.size).putInt(seq).put(pcm).array()

    fun helloMac(o: MicOffer, nonce: ByteArray): String {
        val msg = "VMIC1|${o.pcId}|${o.deviceId}|${o.provider}|${o.sessionId}|".toByteArray() + nonce
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(o.secret, "HmacSHA256")) }
        return mac.doFinal(msg).joinToString("") { "%02x".format(it) }
    }
}

/** Certificate pinning exactly like the fleet: accept only the cert whose SubjectPublicKeyInfo hashes to the offer's pin. */
class SpkiPinTrust(private val pinHex: String) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw java.security.cert.CertificateException("no client auth")
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val got = MessageDigest.getInstance("SHA-256").digest(chain[0].publicKey.encoded).joinToString("") { "%02x".format(it) }
        if (!MessageDigest.isEqual(got.toByteArray(), pinHex.toByteArray())) throw java.security.cert.CertificateException("pin mismatch")
    }
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Bounded live buffer: when the network stalls the OLDEST audio is dropped; it is never queued without limit. */
class LiveQueue(maxMs: Int = MAX_QUEUE_MS) {
    private val max = maxOf(1, maxMs / FRAME_MS)
    private val q = ArrayDeque<ByteArray>()
    var dropped = 0; private set
    @Synchronized fun push(f: ByteArray) { q.addLast(f); while (q.size > max) { q.removeFirst(); dropped++ } }
    @Synchronized fun pop(): ByteArray? = q.removeFirstOrNull()
    @Synchronized fun clear() = q.clear()
    @Synchronized fun size() = q.size
}

enum class ShareState { IDLE, CONNECTING, SHARING, MUTED, SILENCED, STOPPED }

/** Pure state machine for the phone UI/notification. Capture only runs in SHARING. */
class ShareStateMachine {
    var state = ShareState.IDLE; private set
    fun start() { if (state == ShareState.IDLE || state == ShareState.STOPPED) state = ShareState.CONNECTING }
    fun connected() { if (state == ShareState.CONNECTING) state = ShareState.SHARING }
    fun mute() { if (state == ShareState.SHARING || state == ShareState.SILENCED) state = ShareState.MUTED }
    fun unmute() { if (state == ShareState.MUTED) state = ShareState.SHARING }
    /** Another app took the mic, a call started, or the privacy switch is on: Android delivers silence. */
    fun silenced(on: Boolean) { if (on && state == ShareState.SHARING) state = ShareState.SILENCED else if (!on && state == ShareState.SILENCED) state = ShareState.SHARING }
    fun stop() { state = ShareState.STOPPED }
    val capturing get() = state == ShareState.SHARING
    val wire get() = when (state) { ShareState.MUTED -> "muted"; ShareState.SILENCED -> "silenced"; else -> "sharing" }
}

/** One connected stream. Not thread-safe except [offer] and [control], which may be called from the capture thread. */
class MicStream private constructor(private val sock: Socket, private val out: DataOutputStream, val offer: MicOffer) : AutoCloseable {
    private var seq = 0
    @Synchronized fun sendPcm(pcm: ByteArray) { Frames.write(out, Frames.AUDIO, Frames.audio(seq++, pcm)) }
    @Synchronized fun control(state: String) { Frames.write(out, Frames.CTRL, """{"state":"$state"}""".toByteArray()) }
    override fun close() {
        try { synchronized(this) { Frames.write(out, Frames.BYE, ByteArray(0)) } } catch (_: Exception) {}
        try { sock.close() } catch (_: Exception) {}
    }

    companion object {
        /** Connects with certificate pinning, authenticates with the session secret, returns a ready stream. */
        fun connect(o: MicOffer, nowS: Long = System.currentTimeMillis() / 1000, timeoutMs: Int = 5000): MicStream {
            require(!o.expired(nowS)) { "offer expired" }
            val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(SpkiPinTrust(o.spkiSha256)), SecureRandom()) }
            val raw = Socket().apply { connect(java.net.InetSocketAddress(o.host, o.port), timeoutMs); soTimeout = timeoutMs }
            val s = ctx.socketFactory.createSocket(raw, o.host, o.port, true) as SSLSocket
            s.enabledProtocols = s.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
            s.startHandshake()
            val inp = DataInputStream(s.inputStream); val out = DataOutputStream(s.outputStream)
            val (t, nonce) = Frames.read(inp)
            check(t == Frames.CHALLENGE) { "unexpected frame" }
            val hello = """{"session_id":"${o.sessionId}","pc_id":"${o.pcId}","device_id":"${o.deviceId}","provider":"${o.provider}","mac":"${Frames.helloMac(o, nonce)}"}"""
            Frames.write(out, Frames.HELLO, hello.toByteArray())
            val (ok, _) = Frames.read(inp)
            if (ok != Frames.HELLO_OK) { s.close(); throw SecurityException("PC rejected the session") }
            s.soTimeout = 0
            return MicStream(s, out, o)
        }
    }
}
