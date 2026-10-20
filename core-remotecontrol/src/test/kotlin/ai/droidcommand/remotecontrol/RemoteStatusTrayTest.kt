package ai.droidcommand.remotecontrol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RemoteStatusTrayTest {
    @Test
    fun `consume then publish returns the latest snapshot per source`() {
        val tray = InMemoryRemoteStatusTray()
        tray.consume(
            NodeStatusSnapshot(
                sourceNodeRef = "node-a",
                domain = StatusTrayDomain.MESH,
                capability = "mesh.status",
                healthy = true,
                actionRef = "status",
                payload = mapOf("ok" to "true"),
                receivedAtMs = 1,
            ),
        )
        tray.consume(
            NodeStatusSnapshot(
                sourceNodeRef = "node-a",
                domain = StatusTrayDomain.VPN,
                capability = "vpn.status",
                healthy = false,
                actionRef = "status",
                payload = mapOf("ok" to "false"),
                receivedAtMs = 2,
            ),
        )
        tray.consume(
            NodeStatusSnapshot(
                sourceNodeRef = "node-b",
                domain = StatusTrayDomain.SMS,
                actionRef = "status",
                receivedAtMs = 3,
            ),
        )
        val published = tray.publish()
        assertEquals(listOf("node-a", "node-b"), published.map { it.sourceNodeRef })
        assertEquals(StatusTrayDomain.VPN, published.single { it.sourceNodeRef == "node-a" }.domain)
        assertEquals(false, published.single { it.sourceNodeRef == "node-a" }.healthy)
        assertEquals(StatusTrayDomain.SMS, published.single { it.sourceNodeRef == "node-b" }.domain)
        assertNull(published.single { it.sourceNodeRef == "node-b" }.healthy)
    }

    @Test
    fun `dispatcher consumes inbound envelopes onto the tray`() {
        val tray = InMemoryRemoteStatusTray()
        val dispatcher = RemoteCommandDispatcher(
            InMemoryRemoteControlStore(
                inboundConfig(mappings = listOf(mapping(id = "ping", smsCommandId = "ping"))),
            ),
            ReplayGuard(nowMs = { 50_000L }),
            tray,
        )
        dispatcher.dispatch(envelope(actionRef = "ping", issuedAt = 50_000L), source())
        val rows = tray.publish()
        assertEquals(1, rows.size)
        assertEquals(TEST_SOURCE, rows.single().sourceNodeRef)
        assertEquals("ping", rows.single().actionRef)
        assertEquals(StatusTrayDomain.SMS, rows.single().domain)
    }
}
