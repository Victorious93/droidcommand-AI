package ai.droidcommand.coding

import ai.droidcommand.remote.pairing.PairedSocketClient
import ai.droidcommand.remote.pairing.PairedSocketServer
import ai.droidcommand.remote.pairing.PairingRegistry
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CodingTunnelTest {
    @Test
    fun `a session walks plan then edit then report and never claims a cloud edit`() {
        val tunnel = LocalCodingTunnel(newId = { "s1" })
        val planned = tunnel.start("rename the button")
        assertEquals(CodingPhase.PLAN, planned.phase)
        assertTrue(planned.plan.contains("No cloud model"))
        assertEquals("", planned.edit)

        val edited = tunnel.advance(planned.id)
        assertEquals(CodingPhase.EDIT, edited.phase)
        assertTrue(edited.edit.contains("No files were written"))

        val reported = tunnel.advance(planned.id)
        assertEquals(CodingPhase.REPORT, reported.phase)
        assertTrue(reported.report.contains("not applied"))
        assertEquals(reported, tunnel.advance(planned.id))
    }

    @Test
    fun `cancel stops before a report`() {
        val tunnel = LocalCodingTunnel(newId = { "s1" })
        val started = tunnel.start("stop me")
        val cancelled = tunnel.cancel(started.id)
        assertEquals(CodingPhase.CANCELLED, cancelled.phase)
        assertEquals(cancelled, tunnel.advance(started.id))
    }

    @Test
    fun `wire round trip keeps newlines in the goal`() {
        val session = CodingSession("id", "line\nline", CodingPhase.PLAN, "p\\n", "e", "r")
        assertEquals(session, CodingWire.decode(CodingWire.encode(session)))
    }

    @Test
    fun `the log reloads every saved session`() {
        val dir = Files.createTempDirectory("coding-log")
        val log = FileCodingLog(dir.resolve("sessions.log"))
        val tunnel = LocalCodingTunnel(newId = { "s1" })
        val session = tunnel.advance(tunnel.start("keep\nthis").id)
        log.save(listOf(session))
        assertEquals(listOf(session), FileCodingLog(dir.resolve("sessions.log")).load())
    }

    @Test
    fun `a paired connection carries one session from plan through report`() {
        val registry = PairingRegistry()
        val peer = CodingTunnelPeer(LocalCodingTunnel(newId = { "remote-1" }))
        val server = PairedSocketServer(registry) { peer.handle(it) }
        try {
            val address = server.start()
            val laptop = registry.pair("Laptop")
            PairedSocketClient.connect(address, laptop.device.id, laptop.secret).use { connection ->
                val client = PairedCodingTunnel(connection)
                val planned = client.start("fix the label")
                assertEquals(CodingPhase.PLAN, planned.phase)
                assertEquals("remote-1", planned.id)
                assertEquals(CodingPhase.EDIT, client.advance(planned.id).phase)
                val done = client.advance(planned.id)
                assertEquals(CodingPhase.REPORT, done.phase)
                assertTrue(done.report.contains("not applied"))
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `an empty goal is refused`() {
        assertFailsWith<IllegalArgumentException> { LocalCodingTunnel().start("  ") }
    }
}
