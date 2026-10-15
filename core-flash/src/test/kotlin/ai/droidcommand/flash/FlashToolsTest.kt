package ai.droidcommand.flash

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.rootforge.SshCommand
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FlashToolsTest {
    private val t = FakeTransport { listOf(progress(), ok(result = """{"verified":true}""")) }
    private val registry = FlashNodeRegistry(listOf(node())) { FlashClient(it, t) { "req-1" } }
    private val tools = flashTools(registry).associateBy { it.spec.name }

    @Test fun `every tool is sensitive remote control and always asks`() {
        assertEquals(7, tools.size)
        for (tool in tools.values) {
            assertEquals(SecurityLevel.SENSITIVE, tool.spec.securityLevel, tool.spec.name)
            assertEquals(PermissionCategory.REMOTE_CONTROL, tool.spec.permissionCategory, tool.spec.name)
            assertTrue(tool.spec.requiresConfirmation, tool.spec.name)
        }
    }

    @Test fun `node is required and exact`() {
        assertIs<ToolResult.Failure>(tools.getValue("flash_capabilities").execute(emptyMap()))
        assertIs<ToolResult.Failure>(tools.getValue("flash_capabilities").execute(mapOf("node" to NODE_A.dropLast(1))))
        assertTrue(t.sent.isEmpty())
    }

    @Test fun `write tool refuses missing or mismatched confirmation before any network call`() {
        val w = tools.getValue("flash_write_image")
        val base = mapOf("node" to NODE_A, "image" to "/i.iso", "device" to "/dev/sdb", "sha256" to SHA)
        assertIs<ToolResult.Failure>(w.execute(base))
        assertIs<ToolResult.Failure>(w.execute(base + ("confirm" to "sda")))
        assertTrue(t.sent.isEmpty())
        assertIs<ToolResult.Success>(w.execute(base + ("confirm" to "sdb")))
        assertEquals(1, t.sent.size)
    }

    @Test fun `ssh argv reuses the pinned host key builder`() {
        val argv = SshCommand.build(node().sshTarget, Path.of("/tmp/kh"))
        assertTrue("StrictHostKeyChecking=yes" in argv && "HostKeyAlgorithms=ssh-ed25519" in argv)
        assertEquals(listOf("--", "vf.example.test"), argv.takeLast(2))
    }

    @Test fun `process transport streams frames until the final one`() {
        val script = Files.createTempFile("bridge", ".sh")
        Files.writeString(script, "#!/bin/sh\nread l\necho '${progress()}'\necho '${ok()}'\nsleep 5\n")
        script.toFile().setExecutable(true)
        val c = FlashClient(node(), ProcessFlashTransport(listOf(script.toString()))) { "req-1" }
        var n = 0
        val start = System.currentTimeMillis()
        assertIs<FlashResult.Success<*>>(c.writeImage(WriteRequest("/i", "/dev/sdb", SHA, "sdb")) { n++ })
        assertEquals(1, n)
        assertTrue(System.currentTimeMillis() - start < 4000, "should not wait for the process to exit")
    }
}
