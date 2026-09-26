package ai.droidcommand.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerSentEventParserTest {
    private fun parse(vararg lines: String, finish: Boolean = false): List<ServerSentEvent> {
        val events = mutableListOf<ServerSentEvent>()
        val parser = ServerSentEventParser { events += it }
        lines.forEach(parser::feed)
        if (finish) parser.finish()
        return events
    }

    @Test
    fun `dispatches an event with its name on a blank line`() {
        assertEquals(listOf(ServerSentEvent("ping", "{}")), parse("event: ping", "data: {}", ""))
    }

    @Test
    fun `joins multiple data lines with newlines`() {
        assertEquals(listOf(ServerSentEvent(null, "a\nb")), parse("data: a", "data: b", ""))
    }

    @Test
    fun `ignores comments, unknown fields, and events with no data`() {
        assertTrue(parse(": keep-alive", "id: 7", "retry: 100", "event: empty", "").isEmpty())
    }

    @Test
    fun `strips exactly one leading space after the colon`() {
        assertEquals("  x", parse("data:   x", "").single().data)
        assertEquals("y", parse("data:y", "").single().data)
    }

    @Test
    fun `the event name does not leak into the next event`() {
        assertEquals(listOf(ServerSentEvent("a", "1"), ServerSentEvent(null, "2")), parse("event: a", "data: 1", "", "data: 2", ""))
    }

    @Test
    fun `finish dispatches a trailing event with no blank line after it`() {
        assertTrue(parse("data: tail").isEmpty())
        assertEquals(listOf(ServerSentEvent(null, "tail")), parse("data: tail", finish = true))
    }
}
