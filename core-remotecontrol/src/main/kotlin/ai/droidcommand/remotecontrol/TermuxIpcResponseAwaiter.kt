package ai.droidcommand.remotecontrol

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * One-shot rendezvous between a blocking TERMUX_IPC sender and the asynchronous PendingIntent reply.
 *
 * First delivery wins; anything arriving after the wait ended (timeout, cancel, or an earlier reply) is
 * ignored, so a late VictorSuite answer can never be mistaken for the response to a different request.
 * Pure JVM so the timing and cleanup rules are unit-testable without a device.
 *
 * Cancellation and timeout only stop *waiting*. The envelope has already been handed to the node and
 * cannot be recalled; nothing here re-sends it.
 */
class TermuxIpcResponseAwaiter(
    private val pollMs: Long = 100L,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    sealed class Outcome {
        data class Delivered(val raw: String?) : Outcome()
        data object TimedOut : Outcome()
        data object Cancelled : Outcome()
    }

    private val latch = CountDownLatch(1)
    private val delivered = AtomicReference<Outcome.Delivered?>()

    /** Returns true only for the delivery that was accepted. */
    fun deliver(raw: String?): Boolean {
        if (!delivered.compareAndSet(null, Outcome.Delivered(raw))) return false
        latch.countDown()
        return true
    }

    fun await(timeoutMs: Long, isCancelled: () -> Boolean = { false }): Outcome {
        val deadline = nowMs() + timeoutMs
        while (true) {
            delivered.get()?.let { return it }
            if (isCancelled()) return Outcome.Cancelled
            val left = deadline - nowMs()
            if (left <= 0L) return delivered.get() ?: Outcome.TimedOut
            latch.await(minOf(left, pollMs), TimeUnit.MILLISECONDS)
        }
    }
}
