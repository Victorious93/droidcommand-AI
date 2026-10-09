package ai.droidcommand.app.approval

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ComposeApprovalPromptTest {
    private fun ask(prompt: ComposeApprovalPrompt, reason: String, handle: ApprovalHandle = ApprovalHandle()): Pair<Thread, AtomicReference<Boolean?>> {
        val out = AtomicReference<Boolean?>(null)
        val t = Thread { out.set(prompt.requestApproval(reason, handle)) }.apply { isDaemon = true; start() }
        return t to out
    }

    private fun awaitPending(p: ComposeApprovalPrompt, reason: String?) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (p.pending.value?.reason != reason && System.nanoTime() < end) Thread.sleep(5)
        assertEquals(reason, p.pending.value?.reason)
    }

    @Test
    fun approve_and_deny_resolve_the_pending_request() {
        val p = ComposeApprovalPrompt()
        val (t1, r1) = ask(p, "one")
        awaitPending(p, "one")
        p.resolve(true)
        t1.join(5000)
        assertEquals(true, r1.get())
        assertNull(p.pending.value)

        val (t2, r2) = ask(p, "two")
        awaitPending(p, "two")
        p.resolve(false)
        t2.join(5000)
        assertEquals(false, r2.get())
    }

    @Test
    fun cancel_denies_that_request_and_clears_the_dialog() {
        val p = ComposeApprovalPrompt()
        val h = ApprovalHandle()
        val (t, r) = ask(p, "voice won", h)
        awaitPending(p, "voice won")
        h.cancel()
        t.join(5000)
        assertEquals(false, r.get())
        assertNull(p.pending.value)
    }

    @Test
    fun an_abandoned_request_cannot_swallow_the_next_answer() {
        // The old one-slot queue: after a cancel, the next dialog tap was consumed by the stale waiter.
        val p = ComposeApprovalPrompt()
        val h = ApprovalHandle()
        val (t1, _) = ask(p, "first", h)
        awaitPending(p, "first")
        h.cancel()
        t1.join(5000)

        val (t2, r2) = ask(p, "second")
        awaitPending(p, "second")
        p.resolve(true)
        t2.join(5000)
        assertEquals(true, r2.get())
    }

    @Test
    fun a_stale_cancel_never_denies_a_later_request() {
        val p = ComposeApprovalPrompt()
        val h = ApprovalHandle()
        val (t1, _) = ask(p, "first", h)
        awaitPending(p, "first")
        p.resolve(true)
        t1.join(5000)

        val (t2, r2) = ask(p, "second")
        awaitPending(p, "second")
        h.cancel() // belongs to the finished first request
        Thread.sleep(50)
        assertNull(r2.get(), "second request must still be waiting")
        p.resolve(true)
        t2.join(5000)
        assertEquals(true, r2.get())
    }

    @Test
    fun a_cancel_before_the_request_starts_denies_it_immediately() {
        val p = ComposeApprovalPrompt()
        val h = ApprovalHandle().also { it.cancel() }
        val (t, r) = ask(p, "late", h)
        t.join(5000)
        assertEquals(false, r.get())
    }

    @Test
    fun concurrent_requests_are_shown_one_at_a_time() {
        val p = ComposeApprovalPrompt()
        val (t1, r1) = ask(p, "A")
        awaitPending(p, "A")
        val (t2, r2) = ask(p, "B")
        Thread.sleep(50)
        assertEquals("A", p.pending.value?.reason, "B must wait, not overwrite A")
        p.resolve(true)
        t1.join(5000)
        awaitPending(p, "B")
        p.resolve(false)
        t2.join(5000)
        assertEquals(true, r1.get())
        assertEquals(false, r2.get())
    }
}
