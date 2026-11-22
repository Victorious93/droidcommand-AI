package ai.droidcommand.phonemic

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.*

class PhoneMicTest {
    private val good = "vmic1;10.0.0.5;47821;abc123;" + "ab".repeat(32) + ";desk;dev1;keyboard;" + "cd".repeat(32) + ";9999999999"

    @Test fun parsesOfferAndRejectsMalformedOnes() {
        val o = assertNotNull(MicOffer.parse(good))
        assertEquals("desk", o.pcId); assertEquals(32, o.secret.size); assertFalse(o.toString().contains("abab"))
        assertNull(MicOffer.parse(good.replace("keyboard", "evil")))
        assertNull(MicOffer.parse(good.replace("desk", "de;sk")))
        assertNull(MicOffer.parse(good.replace("47821", "99999")))
        assertNull(MicOffer.parse("vmic2" + good.drop(5)))
        assertNull(MicOffer.parse(good.replace("ab".repeat(32), "zz".repeat(32))))
        assertTrue(o.expired(9999999999))
    }

    @Test fun liveQueueDropsOldestAndStaysBounded() {
        val q = LiveQueue(100); repeat(50) { q.push(byteArrayOf(it.toByte())) }
        assertEquals(5, q.size()); assertEquals(45, q.pop()!![0].toInt()); assertEquals(45, q.dropped)
        q.clear(); assertEquals(0, q.size())
    }

    @Test fun stateMachineCapturesOnlyWhenSharing() {
        val m = ShareStateMachine(); assertFalse(m.capturing)
        m.start(); assertFalse(m.capturing); m.connected(); assertTrue(m.capturing)
        m.mute(); assertFalse(m.capturing); assertEquals("muted", m.wire); m.unmute(); assertTrue(m.capturing)
        m.silenced(true); assertEquals("silenced", m.wire); assertFalse(m.capturing); m.silenced(false); assertTrue(m.capturing)
        m.stop(); m.mute(); assertEquals(ShareState.STOPPED, m.state); assertFalse(m.capturing)
    }

    /** Real interop: runs the VictorLinux Python receiver (-Dvictorlinux.dir=/path/to/VictorLinux) and compares what it recorded. */
    @Test fun streamsToRealPythonReceiverOverPinnedTls() {
        val root = System.getProperty("victorlinux.dir") ?: System.getenv("VICTORLINUX_DIR")
        assumeTrue(root != null && File(root, "airootfs/usr/local/bin/victor-mic").exists() && python() != null)
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val wav = File(dir, "rx.wav"); val port = 47000 + (ProcessHandle.current().pid() % 900).toInt()
        val pb = ProcessBuilder(python()!!, "$root/airootfs/usr/local/bin/victor-mic", "serve", "--port", "$port", "--pc-id", "desk",
            "--offer", "Nord:dev1:droidcommand", "--record", wav.path, "--seconds", "12")
        pb.environment().putAll(mapOf("VICTORFLEET_LIB" to "$root/fleet", "XDG_CONFIG_HOME" to dir.path))
        val tone = ByteArray(16000 * 2).also { b -> for (i in 0 until 16000) { val v = (12000 * sin(2 * PI * 440 * i / 16000)).toInt(); b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte() } }
        val p = pb.start()
        try {
            val offer = assertNotNull(MicOffer.parse(p.inputStream.bufferedReader().readLine()))
            Thread.sleep(800)
            MicStream.connect(offer, offer.expiresAtS - 100).use { s ->
                for (i in tone.indices step FRAME_BYTES) s.sendPcm(tone.copyOfRange(i, minOf(i + FRAME_BYTES, tone.size)))
                Thread.sleep(300)
            }
            Thread.sleep(500)
        } finally { p.destroy(); p.waitFor(10, TimeUnit.SECONDS) }
        val pcm = wav.readBytes().drop(44).toByteArray()
        assertContentEquals(tone, pcm)
    }

    @Test fun readRequestRoundTripsAndIsGatedByOptIn() {
        val root = System.getProperty("victorlinux.dir") ?: System.getenv("VICTORLINUX_DIR")
        assumeTrue(root != null && File(root, "airootfs/usr/local/bin/victor-mic").exists() && python() != null)
        val dir = kotlin.io.path.createTempDirectory().toFile(); val port = 47200 + (ProcessHandle.current().pid() % 700).toInt()
        val pb = ProcessBuilder(python()!!, "$root/airootfs/usr/local/bin/victor-mic", "serve", "--port", "$port", "--pc-id", "desk",
            "--offer", "N:d1:droidcommand:3600:read", "--offer", "M:d2:keyboard", "--read-test-text", "Hello \"world\"\nline two", "--seconds", "10")
        pb.environment().putAll(mapOf("VICTORFLEET_LIB" to "$root/fleet", "XDG_CONFIG_HOME" to dir.path))
        val p = pb.start()
        try {
            val r = p.inputStream.bufferedReader()
            val allowed = assertNotNull(MicOffer.parse(r.readLine())); val denied = assertNotNull(MicOffer.parse(r.readLine()))
            Thread.sleep(800)
            MicStream.connect(allowed, allowed.expiresAtS - 100).use { s ->
                val res = s.requestRead()
                assertTrue(res.ok); assertEquals("Hello \"world\"\nline two", res.text); assertEquals("desk", res.pc); assertEquals("Test window", res.window)
            }
            MicStream.connect(denied, denied.expiresAtS - 100).use { s ->
                val res = s.requestRead(); assertFalse(res.ok); assertEquals("not-enabled", res.reason); assertEquals("", res.text)
            }
        } finally { p.destroy() }
    }

    @Test fun wrongPinIsRefused() {
        val root = System.getProperty("victorlinux.dir") ?: System.getenv("VICTORLINUX_DIR")
        assumeTrue(root != null && File(root, "airootfs/usr/local/bin/victor-mic").exists() && python() != null)
        val dir = kotlin.io.path.createTempDirectory().toFile(); val port = 47100 + (ProcessHandle.current().pid() % 800).toInt()
        val pb = ProcessBuilder(python()!!, "$root/airootfs/usr/local/bin/victor-mic", "serve", "--port", "$port", "--offer", "N:d:keyboard", "--seconds", "6")
        pb.environment().putAll(mapOf("VICTORFLEET_LIB" to "$root/fleet", "XDG_CONFIG_HOME" to dir.path))
        val p = pb.start()
        try {
            val o = assertNotNull(MicOffer.parse(p.inputStream.bufferedReader().readLine())); Thread.sleep(800)
            assertFails { MicStream.connect(o.copy(spkiSha256 = "00".repeat(32)), o.expiresAtS - 100) }
            assertFails { MicStream.connect(o.copy(secret = ByteArray(32)), o.expiresAtS - 100) }
        } finally { p.destroy() }
    }

    private fun python(): String? = listOf("python3", "/usr/local/bin/python3", "/usr/bin/python3").firstOrNull { runCatching { ProcessBuilder(it, "-V").start().waitFor() == 0 }.getOrDefault(false) }
    private fun assumeTrue(c: Boolean) { org.junit.jupiter.api.Assumptions.assumeTrue(c) }
}
