package ai.droidcommand.app.bots

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
}
