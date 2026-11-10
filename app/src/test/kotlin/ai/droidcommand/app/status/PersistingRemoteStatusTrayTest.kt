package ai.droidcommand.app.status

import ai.droidcommand.remotecontrol.NodeStatusSnapshot
import ai.droidcommand.remotecontrol.StatusTrayDomain
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersistingRemoteStatusTrayTest {
    @Test
    fun `saving the tray notifies the listener so a widget can refresh`() {
        val dir = createTempDirectory("status-tray-test")
        val hits = AtomicInteger(0)
        val tray = PersistingRemoteStatusTray(
            dir.resolve("status-tray.json"),
            onPersisted = { hits.incrementAndGet() },
        )
        assertEquals(0, hits.get())
        tray.consume(
            NodeStatusSnapshot.of(
                sourceNodeRef = "phone",
                domain = StatusTrayDomain.NODE_HEALTH,
                receivedAtMs = 1_000L,
            ),
        )
        assertEquals(1, hits.get())
        assertTrue(dir.resolve("status-tray.json").toFile().isFile)
    }
}
