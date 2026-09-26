package ai.droidcommand.remote.discovery

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real UDP on loopback; nothing here needs a LAN or broadcast. */
class DeviceDiscoveryTest {
    private val loopback = InetAddress.getLoopbackAddress()
    private val responders = mutableListOf<DiscoveryResponder>()

    @AfterTest
    fun stop() {
        responders.forEach(DiscoveryResponder::close)
    }

    private fun startResponder(name: String, tcpPort: Int): InetSocketAddress {
        val responder = DiscoveryResponder(name, tcpPort)
        responders += responder
        return responder.start(InetSocketAddress(loopback, 0))
    }

    private val quick = Duration.ofMillis(500)

    @Test
    fun `a discoverable device answers with its name and TCP port`() {
        val udp = startResponder("Pixel in the kitchen", 7100)
        val found = DeviceDiscovery.discover(listOf(udp), quick)
        assertEquals(listOf(DiscoveredDevice("Pixel in the kitchen", InetSocketAddress(loopback, 7100))), found)
    }

    @Test
    fun `several devices are each reported once`() {
        val first = startResponder("Phone", 7100)
        val second = startResponder("Tablet", 7200)
        // Probing the first twice still lists it once.
        val found = DeviceDiscovery.discover(listOf(first, second, first), quick)
        assertEquals(setOf("Phone" to 7100, "Tablet" to 7200), found.map { it.name to it.address.port }.toSet())
        assertEquals(2, found.size)
    }

    @Test
    fun `nothing answering gives an empty list after the timeout`() {
        val silent = DatagramSocket(InetSocketAddress(loopback, 0))
        silent.use {
            val started = System.nanoTime()
            assertEquals(emptyList(), DeviceDiscovery.discover(listOf(InetSocketAddress(loopback, it.localPort)), Duration.ofMillis(200)))
            assertTrue(Duration.ofNanos(System.nanoTime() - started) >= Duration.ofMillis(150))
        }
    }

    @Test
    fun `the responder ignores anything that isn't a well-formed probe`() {
        val udp = startResponder("Phone", 7100)
        DatagramSocket(InetSocketAddress(loopback, 0)).use { socket ->
            socket.soTimeout = 300
            val nonce = ByteArray(DeviceDiscovery.NONCE_BYTES) { 7 }
            val probe = DeviceDiscovery.encodeProbe(nonce)
            val malformed = listOf(
                "hello".toByteArray(),
                probe.copyOf(DeviceDiscovery.PROBE_SIZE - 1),
                probe.copyOf(DeviceDiscovery.PROBE_SIZE + 1),
                probe.copyOf().also { it[5] = 99 },
            )
            for (bytes in malformed) {
                socket.send(DatagramPacket(bytes, bytes.size, udp))
            }
            val buffer = ByteArray(1024)
            val answered = runCatching { socket.receive(DatagramPacket(buffer, buffer.size)) }.isSuccess
            assertEquals(false, answered, "no malformed probe should get a reply")
            // A well-formed probe from the same socket still does.
            socket.send(DatagramPacket(probe, probe.size, udp))
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            val reply = DeviceDiscovery.decodeReply(packet.data.copyOf(packet.length))
            assertTrue(reply!!.nonce.contentEquals(nonce))
        }
    }

    @Test
    fun `a reply to someone else's probe is ignored`() {
        val impostor = DatagramSocket(InetSocketAddress(loopback, 0))
        impostor.use {
            val thread = Thread.ofVirtual().start {
                val buffer = ByteArray(DeviceDiscovery.PROBE_SIZE)
                val packet = DatagramPacket(buffer, buffer.size)
                impostor.receive(packet)
                val stale = DeviceDiscovery.encodeReply(ByteArray(DeviceDiscovery.NONCE_BYTES), 7100, "Impostor")
                impostor.send(DatagramPacket(stale, stale.size, packet.socketAddress))
            }
            assertEquals(emptyList(), DeviceDiscovery.discover(listOf(InetSocketAddress(loopback, impostor.localPort)), quick))
            thread.join()
        }
    }

    @Test
    fun `every reply is smaller than the probe, even with the longest name`() {
        val longName = "📱".repeat(500)
        val reply = DeviceDiscovery.encodeReply(ByteArray(DeviceDiscovery.NONCE_BYTES), 65535, longName)
        assertTrue(reply.size < DeviceDiscovery.PROBE_SIZE, "reply is ${reply.size} bytes")
        val decoded = DeviceDiscovery.decodeReply(reply)!!
        assertEquals(DeviceDiscovery.MAX_NAME_CODE_POINTS, decoded.name.codePointCount(0, decoded.name.length))
        assertEquals(65535, decoded.tcpPort)
    }

    @Test
    fun `truncated or padded replies are rejected`() {
        val reply = DeviceDiscovery.encodeReply(ByteArray(DeviceDiscovery.NONCE_BYTES), 7100, "Phone")
        assertNull(DeviceDiscovery.decodeReply(reply.copyOf(reply.size - 1)))
        assertNull(DeviceDiscovery.decodeReply(reply + 0))
        assertNull(DeviceDiscovery.decodeReply(reply.copyOf(10)))
    }
}
