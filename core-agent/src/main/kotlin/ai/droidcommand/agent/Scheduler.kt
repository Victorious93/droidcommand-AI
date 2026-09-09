package ai.droidcommand.agent

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Stops a schedule created by [Scheduler.scheduleAtFixedRate]. */
fun interface Cancellable {
    fun cancel()
}

/**
 * Abstracts "run this later, repeatedly" so [MacroScheduler]'s own logic
 * (rejecting a duplicate schedule, looking a macro up fresh on every
 * firing, reporting what happened) can be unit-tested against a fake
 * without a real wall-clock wait, while [ScheduledExecutorServiceScheduler]
 * is the one production implementation that actually fires [task] — there
 * is no "record a schedule for something else to read later" state
 * anywhere on this path, which is exactly the gap `docs/AUDIT_2026-09-05.md`
 * documents OpenDroid's own routine engine left open (OD-008: a persisted
 * `"cron:<expr>"` string nothing ever dispatched on).
 */
fun interface Scheduler {
    /** [task] runs once after [initialDelayMs], then every [periodMs] until the returned [Cancellable] is cancelled. */
    fun scheduleAtFixedRate(initialDelayMs: Long, periodMs: Long, task: () -> Unit): Cancellable
}

/**
 * A real [Scheduler] backed by [ScheduledExecutorService]: [Scheduler.scheduleAtFixedRate]'s
 * task is actually invoked by a live background thread at the given
 * cadence, not merely recorded for something else to read later.
 *
 * [executor] defaults to a single daemon-threaded executor so an instance
 * never keeps the JVM alive on its own; [close] shuts it down (a
 * currently-running firing finishes, no new one starts).
 *
 * If a scheduled [Scheduler.scheduleAtFixedRate] task throws, the JDK's own
 * `ScheduledExecutorService` contract suppresses all of that task's future
 * firings — [MacroScheduler] relies on this class never letting that
 * happen by catching everything a firing could throw before it reaches
 * here.
 */
class ScheduledExecutorServiceScheduler(
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "macro-scheduler").apply { isDaemon = true }
    },
) : Scheduler, AutoCloseable {
    override fun scheduleAtFixedRate(initialDelayMs: Long, periodMs: Long, task: () -> Unit): Cancellable {
        val future = executor.scheduleAtFixedRate(task, initialDelayMs, periodMs, TimeUnit.MILLISECONDS)
        return Cancellable { future.cancel(false) }
    }

    override fun close() {
        executor.shutdownNow()
    }
}
