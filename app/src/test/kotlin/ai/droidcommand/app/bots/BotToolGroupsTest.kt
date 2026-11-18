package ai.droidcommand.app.bots

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BotToolGroupsTest {
    private fun opt(name: String, risky: Boolean = false) = BotToolOption(name, "does $name", risky)

    @Test
    fun `every tool lands in exactly one group`() {
        val names = listOf(
            "run_shell_command", "read_file", "list_directory", "tap", "get_battery_status", "list_contacts", "build_project",
            "web_search", "ask_ai", "notes_save", "fleet_send_command", "n8n_run_workflow", "something_new",
        )
        val groups = BotToolGroups.group(names.map { opt(it) })
        assertEquals(names.sorted(), groups.flatMap { g -> g.tools.map { it.name } }.sorted())
    }

    @Test
    fun `specific groups win over the generic phone prefixes`() {
        val groups = BotToolGroups.group(listOf(opt("list_directory"), opt("list_contacts"), opt("get_ui_tree"))).associate { it.title to it.tools.map { t -> t.name } }
        assertEquals(listOf("list_directory"), groups["Files"])
        assertEquals(listOf("list_contacts"), groups["Phone"])
        assertEquals(listOf("get_ui_tree"), groups["Screen control"])
    }

    @Test
    fun `MCP tools are grouped per server and sorted after the built-in groups`() {
        val a = "mcp_ab12_search"
        val b = "mcp_cd34_fetch"
        val groups = BotToolGroups.group(listOf(opt(b), opt("tap"), opt(a))) { if (it == a) "Home PC" else "Docs" }
        assertEquals(listOf("Screen control", "MCP · Docs", "MCP · Home PC"), groups.map { it.title })
    }

    @Test
    fun `an MCP tool whose server is not loaded still shows, under a plain heading`() {
        val groups = BotToolGroups.group(listOf(opt("mcp_ab12_search")))
        assertEquals(listOf("MCP · an MCP server"), groups.map { it.title })
    }

    @Test
    fun `filter matches name or description, ignoring case`() {
        val options = listOf(opt("Read_File"), BotToolOption("tap", "Taps the screen", false))
        assertEquals(listOf("Read_File"), BotToolGroups.filter(options, "read_f").map { it.name })
        assertEquals(listOf("tap"), BotToolGroups.filter(options, "SCREEN").map { it.name })
        assertTrue(BotToolGroups.filter(options, "  ").size == 2)
    }

    @Test
    fun `VictorSMS tools have their own group, not Fleet or Other`() {
        val groups = BotToolGroups.group(listOf(opt("victorsms_send"), opt("victorsms_send_status"), opt("fleet_send_command"))).associate { it.title to it.tools.map { t -> t.name } }
        assertEquals(listOf("victorsms_send", "victorsms_send_status"), groups["Texts (VictorSMS)"])
        assertEquals(listOf("fleet_send_command"), groups["Fleet & remote"])
    }
}
