package ai.droidcommand.llm.local

import ai.droidcommand.agent.Message
import ai.droidcommand.agent.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextFitTest {
    private fun user(text: String) = Message(Role.USER, text)

    private fun reply(text: String) = Message(Role.ASSISTANT, text)

    // 300 ASCII characters estimate to 100 tokens, plus 12 for the turn's template markers.
    private val turnText = "x".repeat(300)
    private val turnCost = 112

    @Test
    fun `a conversation that fits is sent whole`() {
        val history = listOf(user("hi"), reply("hello"), user("how are you"))

        val fitted = ContextFit.fit("be brief", history, promptBudgetTokens = 1_000)

        assertEquals(history, fitted.messages)
        assertEquals(0, fitted.dropped)
        assertTrue(fitted.fits)
    }

    @Test
    fun `the oldest turns go first and the newest message always stays`() {
        val history = listOf(user(turnText), reply(turnText), user(turnText), reply(turnText), user(turnText))

        val fitted = ContextFit.fit(null, history, promptBudgetTokens = turnCost * 3 + 10)

        assertEquals(history.takeLast(3), fitted.messages)
        assertEquals(2, fitted.dropped)
        assertTrue(fitted.fits)
    }

    @Test
    fun `a history that would start on a reply starts on the next question instead`() {
        val history = listOf(user(turnText), reply(turnText), user(turnText), reply(turnText), user(turnText))

        // Room for two turns: the last reply and the last question. The reply's own question was dropped, so it goes too.
        val fitted = ContextFit.fit(null, history, promptBudgetTokens = turnCost * 2 + 10)

        assertEquals(listOf(history.last()), fitted.messages)
        assertEquals(4, fitted.dropped)
        assertEquals(Role.USER, fitted.messages.first().role)
    }

    @Test
    fun `a newest message that cannot fit is reported and not cut`() {
        val history = listOf(user("short"), reply("short"), user("x".repeat(30_000)))

        val fitted = ContextFit.fit(null, history, promptBudgetTokens = 1_000)

        assertFalse(fitted.fits)
        assertEquals(history.last(), fitted.messages.last())
    }

    @Test
    fun `the system prompt counts against the budget`() {
        val fitted = ContextFit.fit("s".repeat(3_000), listOf(user("hi")), promptBudgetTokens = 1_000)
        assertFalse(fitted.fits)
    }

    @Test
    fun `text outside ASCII is counted per character so CJK does not overrun the window`() {
        val cjk = "你".repeat(900)
        val history = listOf(user(cjk), reply(cjk), user(cjk))

        val fitted = ContextFit.fit(null, history, promptBudgetTokens = 1_000)

        // Counted like ASCII (a third) two of these would fit; counted per character only the newest does.
        assertEquals(listOf(history.last()), fitted.messages)
        assertTrue(fitted.fits)
    }

    @Test
    fun `an empty conversation fits`() {
        val fitted = ContextFit.fit(null, emptyList(), promptBudgetTokens = 100)
        assertEquals(emptyList(), fitted.messages)
        assertTrue(fitted.fits)
    }

    @Test
    fun `the reply allowance is what was asked for but never more than half the window`() {
        assertEquals(512, ContextFit.replyBudget(contextTokens = 2_048, requestedMaxTokens = 512))
        assertEquals(1_024, ContextFit.replyBudget(contextTokens = 2_048, requestedMaxTokens = 4_096))
        assertEquals(1, ContextFit.replyBudget(contextTokens = 2_048, requestedMaxTokens = 0))
    }

    @Test
    fun `the prompt gets what is left after the reply and the template`() {
        assertEquals(2_048 - 512 - 32, ContextFit.promptBudget(contextTokens = 2_048, replyTokens = 512))
    }
}
