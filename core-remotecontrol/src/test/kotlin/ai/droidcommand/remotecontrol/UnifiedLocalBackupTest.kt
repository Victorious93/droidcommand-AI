package ai.droidcommand.remotecontrol

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnifiedLocalBackupTest {
    @Test
    fun `export and import round-trip macros mappings and pairings without secrets`() {
        val dir = Files.createTempDirectory("remote-backup")
        val file = dir.resolve("backup.json")
        val store = UnifiedLocalBackupStore(file, nowMs = { 12L })
        val config = RemoteControlConfig(
            mappings = listOf(mapping(id = "ping", smsCommandId = "ping")),
            macros = listOf(sampleMacro(id = "night", voicePhrase = "good night fleet")),
            pairings = listOf(pairing()),
            targets = listOf(
                TargetDevice(
                    nodeId = "node-1",
                    displayName = "Phone",
                    integrationClass = ai.droidcommand.security.FleetIntegrationClass.EXTERNAL_NODE,
                ),
            ),
            externalControlEnabled = true,
        )
        val tray = listOf(
            NodeStatusSnapshot(
                sourceNodeRef = "node-1",
                domain = StatusTrayDomain.BROWSER,
                actionRef = "status",
                receivedAtMs = 11,
            ),
        )
        store.export(config, tray)
        val raw = String(Files.readAllBytes(file), Charsets.UTF_8)
        assertFalse(raw.contains("whitelist"))
        assertFalse(raw.contains("apiKey"))
        assertFalse(raw.contains("codeWord"))
        assertTrue(raw.contains("\"version\": 2") || raw.contains("\"version\":2"))
        assertTrue(raw.contains("pairings"))
        val imported = store.import()
        assertEquals(2, imported.version)
        assertEquals(1, imported.config.mappings.size)
        assertEquals("ping", imported.config.mappings.single().id)
        assertEquals("night", imported.config.macros.single().id)
        assertEquals(TEST_SOURCE, imported.config.pairings.single().sourceNodeRef)
        assertEquals("node-1", imported.statusTray.single().sourceNodeRef)
        assertEquals(StatusTrayDomain.BROWSER, imported.statusTray.single().domain)
        assertEquals(12L, imported.exportedAtMs)
        assertTrue(imported.config.externalControlEnabled)
    }
}
