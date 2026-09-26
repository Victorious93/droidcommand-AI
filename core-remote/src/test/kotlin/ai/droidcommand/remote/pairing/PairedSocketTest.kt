package ai.droidcommand.remote.pairing

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every test here runs a real TCP server and client on loopback. */
class PairedSocketTest {
    private val registry = PairingRegistry()
    private val handled = AtomicInteger()
    private val servers = mutableListOf<PairedSocketServer>()

    @AfterTest
    fun stopServers() {
        servers.forEach(PairedSocketServer::close)
    }

    private fun startServer(
        gate: AuthGate = AuthGate(),
        handshakeTimeout: Duration = Duration.ofSeconds(5),
        onConnection: (PairedConnection) -> Unit = ::echoUppercase,
    ): InetSocketAddress {
        val server = PairedSocketServer(registry, gate, handshakeTimeout) {
            handled.incrementAndGet()
            onConnection(it)
        }
        servers += server
        return server.start()
    }

    private fun echoUppercase(connection: PairedConnection) {
        while (true) {
            val message = connection.receive() ?: return
            connection.send(message.decodeToString().uppercase().toByteArray())
        }
    }

    @Test
    fun `a paired controller exchanges encrypted messages with the device`() {
        val address = startServer()
        val laptop = registry.pair("Laptop")
        PairedSocketClient.connect(address, laptop.device.id, laptop.secret).use { connection ->
            connection.send("pm list packages".toByteArray())
            assertEquals("PM LIST PACKAGES", connection.receive()?.decodeToString())
            connection.send("getprop ro.build.version.sdk".toByteArray())
            assertEquals("GETPROP RO.BUILD.VERSION.SDK", connection.receive()?.decodeToString())
        }
        assertEquals(1, handled.get())
    }

    @Test
    fun `a controller with the wrong secret is refused and never reaches the handler`() {
        val address = startServer()
        val laptop = registry.pair("Laptop")
        assertFailsWith<PairingRejectedException> {
            PairedSocketClient.connect(address, laptop.device.id, PairingSecret.generate())
        }
        assertEquals(0, handled.get())
    }

    @Test
    fun `an unknown controller is refused`() {
        val address = startServer()
        val error = assertFailsWith<PairingRejectedException> {
            PairedSocketClient.connect(address, "not-paired", PairingSecret.generate())
        }
        assertEquals("Pairing rejected", error.message)
        assertEquals(0, handled.get())
    }

    @Test
    fun `revoking a controller cuts its live connection`() {
        val connected = CountDownLatch(1)
        val released = CountDownLatch(1)
        val address = startServer { connection ->
            connected.countDown()
            // Blocks until the connection is closed by the revocation.
            assertNull(connection.receive())
            released.countDown()
        }
        val laptop = registry.pair("Laptop")
        PairedSocketClient.connect(address, laptop.device.id, laptop.secret).use { client ->
            assertTrue(connected.await(5, TimeUnit.SECONDS))
            assertEquals(1, registry.openChannelCount(laptop.device.id))
            registry.revoke(laptop.device.id)
            assertTrue(released.await(5, TimeUnit.SECONDS), "the device-side receive should unblock")
            assertNull(client.receive(), "the controller should see the connection close")
        }
        assertFailsWith<PairingRejectedException> {
            PairedSocketClient.connect(address, laptop.device.id, laptop.secret)
        }
    }

    @Test
    fun `repeated failures from one address lock that address out`() {
        val address = startServer(gate = AuthGate(maxFailures = 2))
        repeat(2) {
            assertFailsWith<PairingRejectedException> {
                PairedSocketClient.connect(address, "guess-$it", PairingSecret.generate())
            }
        }
        val laptop = registry.pair("Laptop")
        val error = assertFailsWith<PairingRejectedException> {
            PairedSocketClient.connect(address, laptop.device.id, laptop.secret)
        }
        assertTrue(error.message!!.contains("Too many failed attempts"))
    }

    @Test
    fun `a peer that stalls during the handshake is dropped`() {
        val address = startServer(handshakeTimeout = Duration.ofMillis(200))
        Socket(address.address, address.port).use { socket ->
            socket.soTimeout = 5_000
            // Send nothing; the server should give up and close.
            assertEquals(-1, socket.getInputStream().read())
        }
        assertEquals(0, handled.get())
    }

    @Test
    fun `an oversized frame is refused without allocating it`() {
        val address = startServer()
        Socket(address.address, address.port).use { socket ->
            socket.soTimeout = 5_000
            val out = DataOutputStream(socket.getOutputStream())
            out.writeByte(Frames.CLIENT_HELLO.toInt())
            out.writeInt(Int.MAX_VALUE)
            out.flush()
            assertEquals(-1, DataInputStream(socket.getInputStream()).read())
        }
        assertEquals(0, handled.get())
    }

    @Test
    fun `closing the server closes live connections`() {
        val connected = CountDownLatch(1)
        val address = startServer { connection ->
            connected.countDown()
            connection.receive()
        }
        val laptop = registry.pair("Laptop")
        PairedSocketClient.connect(address, laptop.device.id, laptop.secret).use { client ->
            assertTrue(connected.await(5, TimeUnit.SECONDS))
            servers.single().close()
            assertNull(client.receive())
        }
    }
}
