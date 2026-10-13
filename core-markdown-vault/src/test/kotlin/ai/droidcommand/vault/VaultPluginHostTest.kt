package ai.droidcommand.vault

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultPluginHostTest {
    @Test
    fun `install starts disabled and an enabled plugin only rewrites preview text`() {
        val root = Files.createTempDirectory("plugins")
        val host = VaultPluginHost(root)
        assertTrue(host.sandboxDescription().contains("PLANNED"))
        val installed = host.install(
            VaultPluginManifest(
                id = "daily",
                name = "Daily",
                version = "1.0",
                previewPrefix = "PREFIX ",
                noteTemplate = "# {{title}}\n",
                rewrites = listOf(LiteralRewrite("secret", "hidden")),
            ),
        )
        assertEquals(PluginEnablement.DISABLED, installed.enablement)
        assertEquals("secret", host.applyPreview("secret"))
        assertEquals("", host.newNoteBody("Today"))

        host.setEnabled("daily", true)
        assertEquals("PREFIX hidden", host.applyPreview("secret"))
        assertEquals("# Today\n", host.newNoteBody("Today"))

        host.setEnabled("daily", false)
        assertEquals("secret", host.applyPreview("secret"))
    }

    @Test
    fun `rejects code-shaped ids and a broken manifest cannot be enabled`() {
        val root = Files.createTempDirectory("plugins-bad")
        val host = VaultPluginHost(root)
        assertFailsWith<VaultChangeRejected> {
            host.install(VaultPluginManifest(id = "../escape", name = "Bad", version = "1"))
        }
        val dir = root.resolve(".plugins").resolve("broken")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("manifest.json"), "{not json")
        Files.writeString(dir.resolve("enabled"), "true")
        val record = host.list().single()
        assertNull(record.manifest)
        assertEquals(PluginEnablement.DISABLED, record.enablement)
        assertFailsWith<VaultChangeRejected> { host.setEnabled("broken", true) }
    }
}
