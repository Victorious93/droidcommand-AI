package ai.droidcommand.remote

/**
 * A request was aborted through its [RequestCancellation]. Deliberately not an [java.io.IOException]:
 * [RemoteClient] retries I/O failures, and a cancelled request must never be sent again.
 */
class RequestCancelled : RuntimeException("Request cancelled")

/**
 * Lets another thread abort an in-flight request: put one on [HttpRequestSpec.cancellation] and call [cancel].
 * The transport ends the request with [RequestCancelled] instead of a response, including while it is still
 * waiting for the first byte. Cancelling before the request starts makes it fail without connecting.
 *
 * One instance may cover several requests made one after another (not at the same time).
 */
class RequestCancellation {
    @Volatile
    var isCancelled: Boolean = false
        private set

    private var abort: (() -> Unit)? = null

    fun cancel() {
        val action = synchronized(this) {
            isCancelled = true
            abort
        }
        runCatching { action?.invoke() }
    }

    fun throwIfCancelled() {
        if (isCancelled) throw RequestCancelled()
    }

    /** For transports: [action] must make the blocked request fail. Throws [RequestCancelled] if already cancelled. */
    fun attach(action: () -> Unit) {
        synchronized(this) {
            if (isCancelled) throw RequestCancelled()
            abort = action
        }
    }

    /** For transports: the request is over, so a later [cancel] must not touch its connection or thread. */
    fun detach() {
        synchronized(this) { abort = null }
    }
}
