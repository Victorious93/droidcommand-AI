package ai.droidcommand.agent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Exercises the real `ScheduledExecutorService` underneath — a live
 * background thread genuinely invoking the task on a cadence, not a fake
 * standing in for one. This is the specific property OD-008 documents
 * OpenDroid's own routine scheduler never actually had.
 */
class ScheduledExecutorServiceSchedulerTest {
    @Test
    fun `actually fires the task repeatedly on a real background thread`() {
        val scheduler = ScheduledExecutorServiceScheduler()
        val count = AtomicInteger(0)
        val sawThreeFirings = CountDownLatch(3)

        val cancellable = scheduler.scheduleAtFixedRate(initialDelayMs = 10, periodMs = 20) {
            count.incrementAndGet()
            sawThreeFirings.countDown()
        }

        assertTrue(sawThreeFirings.await(5, TimeUnit.SECONDS), "expected at least 3 real firings within 5s")
        cancellable.cancel()
        scheduler.close()
    }

    @Test
    fun `cancelling stops further firings`() {
        val scheduler = ScheduledExecutorServiceScheduler()
        val count = AtomicInteger(0)
        val sawOneFiring = CountDownLatch(1)

        val cancellable = scheduler.scheduleAtFixedRate(initialDelayMs = 10, periodMs = 20) {
            count.incrementAndGet()
            sawOneFiring.countDown()
        }
        assertTrue(sawOneFiring.await(5, TimeUnit.SECONDS))
        cancellable.cancel()
        val countAtCancel = count.get()

        Thread.sleep(150) // several periods' worth of time in which a cancelled schedule must not fire again

        scheduler.close()
        assertTrue(count.get() == countAtCancel, "expected no firings after cancel, saw ${count.get() - countAtCancel} more")
    }

    @Test
    fun `close shuts the executor down so a later schedule attempt is rejected`() {
        val scheduler = ScheduledExecutorServiceScheduler()
        scheduler.close()

        assertFailsWith<RejectedExecutionException> {
            scheduler.scheduleAtFixedRate(initialDelayMs = 0, periodMs = 1000) { }
        }
    }
}
