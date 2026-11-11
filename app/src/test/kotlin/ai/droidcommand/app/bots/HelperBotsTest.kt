package ai.droidcommand.app.bots

import ai.droidcommand.skills.mcp.McpToolBinding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HelperBotsTest {
    @Test
    fun `validate rejects blank name`() {
        assertEquals("Give the bot a name.", HelperBots.validate("  ", "do stuff"))
    }

    @Test
    fun `round trip encode decode`() {
        val bots = listOf(
            HelperBot("1", "Note taker", "Save short notes", setOf("notes_save"), "gpt"),
        )
        val again = HelperBots.decode(HelperBots.encode(bots))
        assertEquals(bots, again)
    }

    @Test
    fun `system prompt forbids tools when none allowed`() {
        val prompt = HelperBots.systemPrompt(HelperBot("1", "Chatty", "Be brief"))
        assertTrue(prompt.contains("may not use any device tools"))
    }

    @Test
    fun `risky tools listed`() {
        val options = listOf(
            BotToolOption("shell", "run", true),
            BotToolOption("search", "look up", false),
        )
        assertEquals(listOf("shell"), HelperBots.riskyTools(setOf("shell", "search"), options))
    }

    @Test
    fun `handoff is one-shot`() {
        HelperBotHandoff.offer(HelperBot("9", "X", "Y"))
        assertNotNull(HelperBotHandoff.consume())
        assertNull(HelperBotHandoff.consume())
    }

    @Test
    fun `MCP tool bindings are saved with the bot and read back`() {
        val bot = HelperBot(
            id = "1",
            name = "Note taker",
            instructions = "Save short notes",
            allowedTools = setOf("notes_save", "mcp_notes_search"),
            mcpBindings = mapOf("mcp_notes_search" to McpToolBinding("0b0c5f0e-6f0a-4d5e-9a57-3a1f6f1f2a10", "search")),
        )
        assertEquals(listOf(bot), HelperBots.decode(HelperBots.encode(listOf(bot))))
    }

    @Test
    fun `a bot saved by the older version reads with no bindings, so nothing is guessed`() {
        val older = """[{"id":"1","name":"Old","instructions":"x","tools":["mcp_notes_search","notes_save"],"model":""}]"""
        val bot = HelperBots.decode(older).single()
        assertEquals(setOf("mcp_notes_search", "notes_save"), bot.allowedTools)
        assertTrue(bot.mcpBindings.isEmpty())
    }

    @Test
    fun `a broken or stray binding is dropped instead of crashing or being trusted`() {
        val text = """[{"id":"1","name":"B","instructions":"x","tools":["mcp_a_one","mcp_a_two"],"model":"",
            "mcp":[{"tool":"mcp_a_one","server":"s1","remote":"one"},{"tool":"mcp_a_two","server":""},
                   {"tool":"mcp_a_other","server":"s1","remote":"other"},7]}]"""
        assertEquals(mapOf("mcp_a_one" to McpToolBinding("s1", "one")), HelperBots.decode(text).single().mcpBindings)
    }

    @Test
    fun `the prompt names only the tools the bot can use now`() {
        val bot = HelperBot("1", "B", "x", setOf("notes_save", "mcp_notes_search"))
        val prompt = HelperBots.systemPrompt(bot, allowedNow = setOf("notes_save"))
        assertTrue("notes_save" in prompt)
        assertTrue("mcp_notes_search" !in prompt)
    }
}
