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
}
