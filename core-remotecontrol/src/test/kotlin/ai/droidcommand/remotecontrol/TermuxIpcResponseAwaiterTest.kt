package ai.droidcommand.remotecontrol

import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TermuxIpcResponseAwaiterTest {
    @Test
    fun `returns the reply delivered from another thread`() {
        val awaiter = TermuxIpcResponseAwaiter(pollMs = 5)
        thread { Thread.sleep(30); awaiter.deliver("""{"ok":true}""") }
        val out = assertIs<TermuxIpcResponseAwaiter.Outcome.Delivered>(awaiter.await(2_000))
        assertEquals("""{"ok":true}""", out.raw)
    }

    @Test
    fun `times out when nothing arrives`() {
        val started = System.currentTimeMillis()
        val out = TermuxIpcResponseAwaiter(pollMs = 5).await(60)
        assertEquals(TermuxIpcResponseAwaiter.Outcome.TimedOut, out)
        assertTrue(System.currentTimeMillis() - started < 1_500)
    }

    @Test
    fun `cancel stops waiting`() {
        var polls = 0
        val out = TermuxIpcResponseAwaiter(pollMs = 5).await(5_000) { ++polls > 2 }
        assertEquals(TermuxIpcResponseAwaiter.Outcome.Cancelled, out)
    }

    @Test
    fun `a late or duplicate reply is ignored once the wait ended`() {
        val awaiter = TermuxIpcResponseAwaiter(pollMs = 5)
        assertTrue(awaiter.deliver("first"))
        assertFalse(awaiter.deliver("second"))
        assertEquals("first", assertIs<TermuxIpcResponseAwaiter.Outcome.Delivered>(awaiter.await(50)).raw)
    }

    @Test
    fun `a reply delivered after timeout does not resurrect the request for a new waiter`() {
        val awaiter = TermuxIpcResponseAwaiter(pollMs = 5)
        assertEquals(TermuxIpcResponseAwaiter.Outcome.TimedOut, awaiter.await(20))
        // Each send builds its own awaiter, so a stale broadcast can only reach the dead one.
        val next = TermuxIpcResponseAwaiter(pollMs = 5)
        awaiter.deliver("stale")
        assertEquals(TermuxIpcResponseAwaiter.Outcome.TimedOut, next.await(20))
    }
}
