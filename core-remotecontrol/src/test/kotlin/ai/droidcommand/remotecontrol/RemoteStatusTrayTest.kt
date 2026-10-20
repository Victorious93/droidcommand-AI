package ai.droidcommand.remotecontrol

import ai.droidcommand.security.FleetIntegrationClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteStatusTrayTest {
    @Test
    fun `consume merges domains per nodeId and keeps the later updatedAtMs`() {
        val tray = InMemoryRemoteStatusTray()
        tray.consume(
            NodeStatusSnapshot.of(
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
            NodeStatusSnapshot.of(
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
            NodeStatusSnapshot.of(
                sourceNodeRef = "node-b",
                domain = StatusTrayDomain.SMS,
                actionRef = "status",
                receivedAtMs = 3,
            ),
        )
        val published = tray.publish()
        assertEquals(listOf("node-a", "node-b"), published.map { it.nodeId })
        val nodeA = published.single { it.nodeId == "node-a" }
        assertEquals(listOf(StatusTrayDomain.MESH, StatusTrayDomain.VPN), nodeA.domains.map { it.name })
        assertEquals(StatusTrayState.OK, nodeA.domains.single { it.name == StatusTrayDomain.MESH }.state)
        assertEquals(StatusTrayState.DOWN, nodeA.domains.single { it.name == StatusTrayDomain.VPN }.state)
        assertEquals(false, nodeA.healthy)
        assertEquals(2L, nodeA.updatedAtMs)
        assertEquals("node-a", nodeA.sourceNodeRef)
        assertEquals(StatusTrayDomain.SMS, published.single { it.nodeId == "node-b" }.domain)
        assertNull(published.single { it.nodeId == "node-b" }.healthy)
    }

    @Test
    fun `publish expires rows older than five minutes when a clock is given`() {
        val tray = InMemoryRemoteStatusTray()
        tray.consume(
            NodeStatusSnapshot.of(
                sourceNodeRef = "old",
                domain = StatusTrayDomain.NODE_HEALTH,
                receivedAtMs = 1_000L,
            ),
        )
        tray.consume(
            NodeStatusSnapshot.of(
                sourceNodeRef = "fresh",
                domain = StatusTrayDomain.NODE_HEALTH,
                receivedAtMs = 250_000L,
            ),
        )
        val withoutClock = tray.publish()
        assertEquals(listOf("old", "fresh"), withoutClock.map { it.nodeId })
        val withClock = tray.publish(nowMs = 301_001L)
        assertEquals(listOf("fresh"), withClock.map { it.nodeId })
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
        dispatcher.dispatch(envelope(actionRef = "ping", timestampMs = 50_000L), source())
        val rows = tray.publish()
        assertEquals(1, rows.size)
        assertEquals(TEST_SOURCE, rows.single().nodeId)
        assertEquals("ping", rows.single().actionRef)
        assertEquals(StatusTrayDomain.SMS, rows.single().domain)
        assertEquals(FleetRemoteEnvelope.VICTOR_SMS_PACKAGE, rows.single().packageName)
        assertEquals(FleetIntegrationClass.EXTERNAL_NODE, rows.single().connectionClass)
    }

    @Test
    fun `consume refuses snapshots that carry secret field names`() {
        val tray = InMemoryRemoteStatusTray()
        val error = assertFailsWith<IllegalArgumentException> {
            tray.consume(
                NodeStatusSnapshot.of(
                    sourceNodeRef = "node-a",
                    domain = StatusTrayDomain.SMS,
                    payload = mapOf("apiKey" to "nope"),
                    receivedAtMs = 1,
                ),
            )
        }
        assertTrue(error.message!!.contains("secret"))
    }
}
