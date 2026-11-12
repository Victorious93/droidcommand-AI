package ai.droidcommand.app.mcp

import ai.droidcommand.skills.mcp.McpServerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpServerCodecTest {
    @Test
    fun `round-trips the server list without any token`() {
        val servers = listOf(
            McpServerConfig("homepc", "Home PC", "https://home.local/mcp"),
            McpServerConfig("lab", "Lab", "http://192.168.1.9:8080/mcp", enabled = false),
        )
        val text = McpServerCodec.encode(servers)
        assertEquals(servers, McpServerCodec.decode(text))
        assertFalse(text.contains("token", ignoreCase = true))
    }

    @Test
    fun `a broken saved list reads as empty instead of crashing`() {
        assertTrue(McpServerCodec.decode("{not json").isEmpty())
        assertTrue(McpServerCodec.decode(null).isEmpty())
        assertEquals(
            listOf("ok"),
            McpServerCodec.decode("""[{"id":"BAD ID","url":"x"},{"id":"ok","url":"https://a/mcp"},{"nope":1}]""").map { it.id },
        )
    }

    @Test
    fun `a server keeps its own instance id and an older saved server reads as the old kind`() {
        val added = McpServerConfig("notes", "Notes", "https://new.test/mcp", instanceId = McpServerConfig.newInstanceId())
        assertEquals(listOf(added), McpServerCodec.decode(McpServerCodec.encode(listOf(added))))

        val older = McpServerCodec.decode("""[{"id":"notes","label":"Notes","url":"https://old.test/mcp","enabled":true}]""").single()
        assertEquals("pre-notes", older.instanceId)
        assertEquals(older, McpServerCodec.decode(McpServerCodec.encode(listOf(older))).single())
        assertTrue(added.instanceId != older.instanceId, "a server added now is never the old kind")

        assertTrue(McpServerCodec.decode("""[{"id":"notes","url":"https://x.test/mcp","instance":"not ok!"}]""").isEmpty())
    }
}
