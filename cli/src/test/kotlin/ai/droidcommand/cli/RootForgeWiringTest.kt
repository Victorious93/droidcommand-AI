package ai.droidcommand.cli

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RootForgeWiringTest {
    private val nodeJson = """{"nodes":[{"node_id":"rf-0123456789abcdef0123456789abcdef","host":"h.test","user":"rfbridge",""" +
        """"identity_file":"/k","host_key":"ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample"}]}"""

    @Test fun `unset registers nothing so RootForge stays optional`() {
        assertTrue(rootForgeTools(null).isEmpty())
        assertTrue(rootForgeTools("  ").isEmpty())
    }

    @Test fun `invalid file registers nothing and warns`() {
        val warnings = mutableListOf<String>()
        val f = Files.createTempFile("nodes", ".json").also { Files.writeString(it, "{bad") }
        assertTrue(rootForgeTools(f.toString()) { warnings += it }.isEmpty())
        assertEquals(1, warnings.size)
        assertTrue(rootForgeTools("/nonexistent/nodes.json") { warnings += it }.isEmpty())
    }

    @Test fun `valid file registers the two passive tools`() {
        val f = Files.createTempFile("nodes", ".json").also { Files.writeString(it, nodeJson) }
        assertEquals(setOf("rootforge_capabilities", "rootforge_list_devices"), rootForgeTools(f.toString()).map { it.spec.name }.toSet())
    }

    @Test fun `default session has no RootForge tools`() {
        val session = buildSession(device = null)
        assertTrue(session.registry.list().none { it.name.startsWith("rootforge_") })
    }
}
