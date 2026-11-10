package ai.droidcommand.skills.mcp

import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class McpToolCatalogTest {
    private class BuiltIn(name: String) : Tool {
        override val spec = ToolSpec(name = name, description = "built in")
        override fun execute(input: Map<String, String>): ToolResult = ToolResult.Success("ok")
    }

    @Test
    fun `lists a server's tools into the registry with namespaced names`() {
        val registry = ToolRegistry().apply { register(BuiltIn("notes")) }
        val source = FakeMcpToolSource(listOf(READ_TOOL, WRITE_TOOL))
        val catalog = McpToolCatalog(registry, RecordingApproval()) { source }

        val statuses = catalog.refresh(enabled = true, servers = listOf(HOME))

        val loaded = assertIs<McpServerStatus.Loaded>(statuses.single())
        assertEquals(listOf("mcp_home_search_notes", "mcp_home_add_note"), loaded.toolNames)
        assertEquals(setOf("notes", "mcp_home_search_notes", "mcp_home_add_note"), registry.list().map { it.name }.toSet())
        registry.list().filter { it.name.startsWith("mcp_") }.forEach { spec ->
            assertEquals(SecurityLevel.SENSITIVE, spec.securityLevel)
            assertTrue(spec.requiresConfirmation)
            assertTrue(spec.allowedModes.isEmpty(), "MCP tools are never offered to the Pilot/Forge planners")
        }
    }

    @Test
    fun `off by default means nothing is contacted and nothing is registered`() {
        val registry = ToolRegistry()
        var created = 0
        val catalog = McpToolCatalog(registry, RecordingApproval()) {
            created++
            FakeMcpToolSource(listOf(READ_TOOL))
        }

        val status = catalog.refresh(enabled = false, servers = listOf(HOME)).single()

        assertIs<McpServerStatus.Skipped>(status)
        assertEquals(0, created)
        assertTrue(registry.list().isEmpty())
    }

    @Test
    fun `a turned-off server is skipped`() {
        val registry = ToolRegistry()
        val catalog = McpToolCatalog(registry, RecordingApproval()) { FakeMcpToolSource(listOf(READ_TOOL)) }
        assertIs<McpServerStatus.Skipped>(catalog.refresh(true, listOf(HOME.copy(enabled = false))).single())
        assertTrue(registry.list().isEmpty())
    }

    @Test
    fun `refresh replaces old tools and turning off removes them all`() {
        val registry = ToolRegistry()
        val first = FakeMcpToolSource(listOf(READ_TOOL, WRITE_TOOL))
        val second = FakeMcpToolSource(listOf(DELETE_TOOL))
        var sources = listOf(first, second)
        val catalog = McpToolCatalog(registry, RecordingApproval()) { sources.first().also { sources = sources.drop(1) } }

        catalog.refresh(true, listOf(HOME))
        catalog.refresh(true, listOf(HOME))
        assertEquals(listOf("mcp_home_delete_note"), registry.list().map { it.name })
        assertTrue(first.closed, "the old connection is closed")

        catalog.refresh(false, listOf(HOME))
        assertTrue(registry.list().isEmpty())
        assertTrue(second.closed)
    }

    @Test
    fun `a server that is down gives a plain message and does not stop the others`() {
        val registry = ToolRegistry()
        val other = McpServerConfig("lab", "Lab box", "https://lab.local/mcp")
        val catalog = McpToolCatalog(registry, RecordingApproval()) { server ->
            if (server.id == "home") {
                FakeMcpToolSource(emptyList(), listFailure = McpException("Could not reach Home PC. Check the address and that it is running."))
            } else {
                FakeMcpToolSource(listOf(READ_TOOL))
            }
        }

        val statuses = catalog.refresh(true, listOf(HOME, other))

        val failed = assertIs<McpServerStatus.Failed>(statuses[0])
        assertEquals("Could not reach Home PC. Check the address and that it is running.", failed.words)
        assertIs<McpServerStatus.Loaded>(statuses[1])
        assertEquals(listOf("mcp_lab_search_notes"), registry.list().map { it.name })
    }

    @Test
    fun `an unexpected crash while listing is still a plain message`() {
        val catalog = McpToolCatalog(ToolRegistry(), RecordingApproval()) {
            FakeMcpToolSource(emptyList(), listFailure = IllegalStateException("boom \u0000 stack"))
        }
        val failed = assertIs<McpServerStatus.Failed>(catalog.refresh(true, listOf(HOME)).single())
        assertEquals("Home PC did not answer properly.", failed.words)
        assertFalse(failed.words.contains("boom"))
    }

    @Test
    fun `a remote tool can never take a built-in tool's name`() {
        val registry = ToolRegistry().apply { register(BuiltIn("search_notes")) }
        val catalog = McpToolCatalog(registry, RecordingApproval()) { FakeMcpToolSource(listOf(READ_TOOL)) }
        catalog.refresh(true, listOf(HOME))
        assertEquals("built in", registry.get("search_notes").spec.description)
        assertTrue(registry.list().any { it.name == "mcp_home_search_notes" })
    }

    @Test
    fun `tool names are cleaned for LLM providers and capped at 64 characters`() {
        assertEquals("mcp_home_git_status_v2", McpBridgedTool.toolName("home", "git.status v2"))
        assertEquals(64, McpBridgedTool.toolName("home", "x".repeat(200)).length)
    }

    @Test
    fun `server urls must be http or https and never carry a password`() {
        assertIs<McpServerUrl.Check.Ok>(McpServerUrl.check("https://pc.local:8080/mcp"))
        val lan = assertIs<McpServerUrl.Check.Ok>(McpServerUrl.check("http://192.168.1.20:8080/mcp"))
        assertTrue(lan.warning != null)
        assertIs<McpServerUrl.Check.Rejected>(McpServerUrl.check("ftp://pc.local/mcp"))
        assertIs<McpServerUrl.Check.Rejected>(McpServerUrl.check("https://me:secret@pc.local/mcp"))
        assertIs<McpServerUrl.Check.Rejected>(McpServerUrl.check(""))
    }

    @Test
    fun `new server ids are short slugs that do not collide`() {
        assertEquals("homepc", McpServerConfig.newId("Home PC!", emptySet()))
        assertEquals("homepc2", McpServerConfig.newId("Home PC", setOf("homepc")))
        assertEquals("server", McpServerConfig.newId("***", emptySet()))
    }
}
