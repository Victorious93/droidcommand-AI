package ai.droidcommand.remotecontrol

import kotlin.test.Test
import kotlin.test.assertEquals

class RemoteStatusTrayTest {
    @Test
    fun `consume then publish returns the latest snapshot per source`() {
        val tray = InMemoryRemoteStatusTray(nowMs = { 9L })
        tray.consume(
            NodeStatusSnapshot(
                sourceNodeRef = "node-a",
                actionRef = "status",
                payload = mapOf("ok" to "true"),
                receivedAtMs = 1,
            ),
        )
        tray.consume(
            NodeStatusSnapshot(
                sourceNodeRef = "node-a",
                actionRef = "status",
                payload = mapOf("ok" to "false"),
                receivedAtMs = 2,
            ),
        )
        tray.consume(
            NodeStatusSnapshot(
                sourceNodeRef = "node-b",
                actionRef = "status",
                receivedAtMs = 3,
            ),
        )
        val published = tray.publish()
        assertEquals(listOf("node-a", "node-b"), published.map { it.sourceNodeRef })
        assertEquals("false", published.single { it.sourceNodeRef == "node-a" }.payload["ok"])
    }

    @Test
    fun `dispatcher consumes inbound envelopes onto the tray`() {
        val tray = InMemoryRemoteStatusTray(nowMs = { 50_000L })
        val dispatcher = RemoteCommandDispatcher(
            InMemoryRemoteControlStore(
                RemoteControlConfig(mappings = listOf(mapping(id = "ping", smsCommandId = "ping"))),
            ),
            ReplayGuard(nowMs = { 50_000L }),
            tray,
        )
        dispatcher.dispatch(envelope(actionRef = "ping", timestampMs = 50_000L), source())
        val rows = tray.publish()
        assertEquals(1, rows.size)
        assertEquals(TEST_SOURCE, rows.single().sourceNodeRef)
        assertEquals("ping", rows.single().actionRef)
    }
}
