package ai.droidcommand.fleet.messenger

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MessengerTimeoutException(message: String) : RuntimeException(message)

/**
 * Connection-level seam. [open] binds the node's `<package>.action.BRIDGE` service and returns a
 * live [MessengerSession]; [MessengerBridgeClient] only calls it after [NodeTrustVerifier] trusted
 * the package, and passes the verified [PackageIdentity] so the implementation can pin to it.
 * The Android implementation is `AndroidMessengerChannel` in `:app`.
 */
interface MessengerChannel {
    fun open(node: PairedNode, verified: PackageIdentity, timeoutMs: Long): Result<MessengerSession>
}

/** One bound connection. Closed by the client after the single request/response exchange. */
interface MessengerSession : AutoCloseable {
    /** Sends a `what=1` request carrying [requestJson] and returns the `what=2` reply's response JSON. */
    fun exchange(requestJson: String, timeoutMs: Long): Result<String>
}

/**
 * A one-shot reply slot a Messenger handler completes and the calling thread waits on. The first
 * outcome wins; later replies are ignored (a node cannot answer one request twice). Pure JVM so the
 * timeout behaviour is unit-tested without Android.
 */
class PendingReply {
    private val latch = CountDownLatch(1)
    private val outcome = AtomicReference<Result<String>?>(null)

    fun complete(responseJson: String): Boolean = settle(Result.success(responseJson))

    fun fail(cause: Throwable): Boolean = settle(Result.failure(cause))

    private fun settle(result: Result<String>): Boolean {
        if (!outcome.compareAndSet(null, result)) return false
        latch.countDown()
        return true
    }

    fun await(timeoutMs: Long): Result<String> {
        val arrived = try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return Result.failure(e)
        }
        return if (arrived) outcome.get()!! else Result.failure(MessengerTimeoutException("no reply within $timeoutMs ms"))
    }
}
