package ai.droidcommand.agent

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A thread-safe pause/resume signal a caller can hand to [ObjectiveEngine.run]'s
 * `isPaused` / `awaitResume` parameters, so a running objective suspends on one
 * thread (the engine loop) at the request of another (a UI pause button, a
 * supervising task).
 *
 * This is the real wait/notify pause source named as follow-up when
 * [ObjectiveEngine] first grew its pause hook: unlike that hook's default
 * `awaitResume` (a fixed 50 ms busy-poll), [awaitResume] here blocks on a
 * [java.util.concurrent.locks.Condition] and is woken the instant [resume] is
 * called — zero-latency resume and effectively zero idle wakeups while paused.
 *
 * **Why the wait is still bounded.** [awaitResume] waits at most
 * [maxWaitMillis] (default [DEFAULT_MAX_WAIT_MILLIS]) per call rather than
 * forever. [ObjectiveEngine]'s loop is `while (isPaused() && !isCancelled())
 * awaitResume()`: a pure unbounded wait would never return if the task is
 * cancelled while paused but never resumed, deadlocking the engine. The bound
 * guarantees the loop re-checks cancellation within at most [maxWaitMillis]
 * even if [resume] is never called, while a real [resume] still wakes it
 * immediately via `signalAll`. The bound trades a bounded worst-case
 * cancel-latency for liveness; it is not a poll interval (no wakeup happens at
 * all between a `pause` and either a `resume` or the timeout).
 *
 * Typical use:
 * ```
 * val pause = PauseController()
 * engine.run(objective, isPaused = pause::isPaused, awaitResume = { pause.awaitResume() })
 * // on another thread: pause.pause() ... later ... pause.resume()
 * ```
 */
class PauseController(private val maxWaitMillis: Long = DEFAULT_MAX_WAIT_MILLIS) {
    init {
        require(maxWaitMillis > 0) { "maxWaitMillis must be > 0, got $maxWaitMillis" }
    }

    private val lock = ReentrantLock()
    private val resumed = lock.newCondition()

    @Volatile
    private var paused = false

    /** Whether the signal is currently paused. Safe to read from any thread (`@Volatile`). */
    fun isPaused(): Boolean = paused

    /** Requests a pause. Idempotent — pausing an already-paused controller is a no-op. */
    fun pause() {
        lock.withLock { paused = true }
    }

    /**
     * Clears the pause and wakes every thread currently blocked in [awaitResume]
     * immediately. Idempotent — resuming a non-paused controller is a no-op that
     * still issues the wake (harmless; a woken waiter simply re-checks and
     * returns).
     */
    fun resume() {
        lock.withLock {
            paused = false
            resumed.signalAll()
        }
    }

    /**
     * If paused, blocks the calling thread until [resume] is called or at most
     * [maxWaitMillis] elapses, whichever comes first; returns immediately if not
     * paused. A single call never blocks longer than [maxWaitMillis], so a caller
     * that loops on it (as [ObjectiveEngine] does) keeps control often enough to
     * observe cancellation. A spurious wakeup is harmless — the caller's own
     * `while (isPaused() && !isCancelled())` loop decides whether to wait again.
     *
     * Restores the thread's interrupted status and returns if the wait is
     * interrupted, rather than swallowing the interrupt, so a caller using
     * interruption for cancellation still sees it.
     */
    fun awaitResume() {
        lock.withLock {
            if (paused) {
                try {
                    resumed.await(maxWaitMillis, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    companion object {
        /**
         * Default maximum a single [awaitResume] blocks before returning so the
         * caller can re-check cancellation. One second: long enough that a long
         * pause costs almost no wakeups, short enough that a cancel-while-paused
         * is acted on promptly.
         */
        const val DEFAULT_MAX_WAIT_MILLIS = 1_000L
    }
}
