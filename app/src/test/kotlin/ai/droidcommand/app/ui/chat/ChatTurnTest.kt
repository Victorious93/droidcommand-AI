package ai.droidcommand.app.ui.chat

import ai.droidcommand.llm.factory.ChatResult
import ai.droidcommand.llm.factory.SavedChat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ChatTurnTest {
    private val inFlight = ChatUiState(
        lines = listOf(ChatLine(true, "earlier question"), ChatLine(false, "earlier answer")),
        savedChats = listOf(SavedChat("c1", "earlier question")),
        streaming = "half a rep",
        sending = true,
    )

    @Test
    fun `a reply adds the question and the answer and ends the turn`() {
        val chats = listOf(SavedChat("c1", "earlier question"), SavedChat("c2", "new"))
        val next = inFlight.afterTurn(ChatResult.Reply("the answer", notice = "web search failed"), "  the question \n", localNotice = null, refreshedChats = chats)

        assertEquals(inFlight.lines + ChatLine(true, "the question") + ChatLine(false, "the answer"), next.lines)
        assertEquals("", next.streaming)
        assertFalse(next.sending)
        assertEquals("web search failed", next.notice)
        assertEquals(chats, next.savedChats)
        assertNull(next.error)
        assertNull(next.pendingInput)
    }

    @Test
    fun `a reply keeps the old chat list when the new one could not be read`() {
        val next = inFlight.afterTurn(ChatResult.Reply("ok"), "q", localNotice = null, refreshedChats = null)
        assertEquals(inFlight.savedChats, next.savedChats)
    }

    @Test
    fun `the on-device note shows only when the reply brought none of its own`() {
        assertEquals("not used on device", inFlight.afterTurn(ChatResult.Reply("ok"), "q", "not used on device", null).notice)
        assertEquals("from the reply", inFlight.afterTurn(ChatResult.Reply("ok", notice = "from the reply"), "q", "not used on device", null).notice)
    }

    @Test
    fun `a failed turn leaves the transcript alone, shows why, and hands the text back for another try`() {
        val typed = "  a long question the user does not want to type again  "
        val next = inFlight.afterTurn(ChatResult.Failure("Request failed: timeout"), typed, localNotice = "ignored", refreshedChats = listOf(SavedChat("x", "x")))

        assertEquals(inFlight.lines, next.lines)
        assertEquals(inFlight.savedChats, next.savedChats)
        assertEquals("", next.streaming)
        assertFalse(next.sending)
        assertEquals("Request failed: timeout", next.error)
        // Exactly what was typed, so a slash command or deliberate spacing comes back unchanged.
        assertEquals(typed, next.pendingInput)
        assertNull(next.notice)
    }
}
