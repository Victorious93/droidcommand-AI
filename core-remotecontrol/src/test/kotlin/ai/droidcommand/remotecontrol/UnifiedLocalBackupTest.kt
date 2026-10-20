package ai.droidcommand.remotecontrol

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class UnifiedLocalBackupTest {
    @Test
    fun `export and import round-trip mappings without secrets`() {
        val dir = Files.createTempDirectory("remote-backup")
        val file = dir.resolve("backup.json")
        val store = UnifiedLocalBackupStore(file, nowMs = { 12L })
        val config = RemoteControlConfig(
            mappings = listOf(mapping(id = "ping", smsCommandId = "ping")),
            targets = listOf(
                TargetDevice(
                    nodeId = "node-1",
                    displayName = "Phone",
                    integrationClass = ai.droidcommand.security.FleetIntegrationClass.EXTERNAL_NODE,
                ),
            ),
        )
        val tray = listOf(
            NodeStatusSnapshot(sourceNodeRef = "node-1", actionRef = "status", receivedAtMs = 11),
        )
        store.export(config, tray)
        val raw = String(Files.readAllBytes(file), Charsets.UTF_8)
        assertFalse(raw.contains("whitelist"))
        assertFalse(raw.contains("apiKey"))
        assertFalse(raw.contains("codeWord"))
        val imported = store.import()
        assertEquals(1, imported.config.mappings.size)
        assertEquals("ping", imported.config.mappings.single().id)
        assertEquals("node-1", imported.statusTray.single().sourceNodeRef)
        assertEquals(12L, imported.exportedAtMs)
    }
}
