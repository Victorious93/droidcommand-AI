package ai.droidcommand.cli

import ai.droidcommand.agent.ToolResult
import ai.droidcommand.flash.FlashClient
import ai.droidcommand.flash.FlashNodeRegistry
import ai.droidcommand.flash.FlashTransport
import ai.droidcommand.security.ApprovalPrompt
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FlashWiringTest {
    private val nodeId = "vf-0123456789abcdef0123456789abcdef"
    private val nodeJson = """{"nodes":[{"node_id":"$nodeId","host":"h.test","user":"vfbridge",""" +
        """"identity_file":"/k","host_key":"ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample"}]}"""

    @Test fun `unset or invalid registers nothing`() {
        assertTrue(flashNodeTools(null).isEmpty())
        val warnings = mutableListOf<String>()
        val f = Files.createTempFile("n", ".json").also { Files.writeString(it, "{bad") }
        assertTrue(flashNodeTools(f.toString(), warn = { warnings += it }).isEmpty())
        assertEquals(1, warnings.size)
        assertTrue(buildSession(device = null).registry.list().none { it.name.startsWith("flash_") })
    }

    @Test fun `valid file registers the flash tools`() {
        val f = Files.createTempFile("n", ".json").also { Files.writeString(it, nodeJson) }
        assertTrue("flash_write_image" in flashNodeTools(f.toString()).map { it.spec.name })
    }

    @Test fun `write asks for approval on every call and a denial sends nothing`() {
        val f = Files.createTempFile("n", ".json").also { Files.writeString(it, nodeJson) }
        val sent = mutableListOf<String>()
        val transport = object : FlashTransport {
            override fun exchange(requestLine: String, timeoutMs: Long, onLine: (String) -> Boolean): Result<Unit> {
                sent += requestLine
                val rid = Regex("\"request_id\":\"([^\"]+)\"").find(requestLine)!!.groupValues[1]
                onLine("""{"protocol_major":1,"request_id":"$rid","node_id":"$nodeId","ok":true,"result":{"verified":true}}""")
                return Result.success(Unit)
            }
        }
        val tools = flashNodeTools(f.toString(), registryFactory = { FlashNodeRegistry(it) { n -> FlashClient(n, transport) } })
        var asks = 0
        var answer = true
        val prompt = object : ApprovalPrompt {
            override fun requestApproval(reason: String): Boolean {
                asks++
                return answer
            }
        }
        val session = buildSession(prompt, device = null, flashTools = tools).session
        val input = mapOf(
            "node" to nodeId, "image" to "/i.iso", "device" to "/dev/sdb",
            "sha256" to "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "confirm" to "sdb",
        )
        assertIs<ToolResult.Success>(session.runPilotInstruction("flash_write_image", input))
        assertIs<ToolResult.Success>(session.runPilotInstruction("flash_write_image", input))
        assertEquals(2, asks)
        answer = false
        assertIs<ToolResult.Failure>(session.runPilotInstruction("flash_write_image", input))
        assertEquals(3, asks)
        assertEquals(2, sent.size)
    }
}
