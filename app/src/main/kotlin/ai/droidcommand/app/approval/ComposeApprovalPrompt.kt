package ai.droidcommand.app.approval

import ai.droidcommand.security.ApprovalPrompt
import java.util.concurrent.CompletableFuture
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class PendingApproval(val reason: String)

/**
 * Lets someone other than the dialog (the voice path) abandon one specific request. Cancelling resolves that
 * request as denied and removes its dialog; it never touches any other request, so a stale cancel cannot deny a
 * later approval.
 */
class ApprovalHandle {
    @Volatile private var future: CompletableFuture<Boolean>? = null

    @Volatile private var cancelled = false

    internal fun attach(f: CompletableFuture<Boolean>) {
        future = f
        if (cancelled) f.complete(false)
    }

    fun cancel() {
        cancelled = true
        future?.complete(false)
    }
}

/**
 * Bridges core-security's synchronous [ApprovalPrompt] contract to a Compose
 * confirmation dialog ([ApprovalHost]). [requestApproval] is invoked by
 * `SecureToolExecutor` from a background thread (every tool-invoking
 * ViewModel here dispatches on `Dispatchers.IO`, never the main thread) and
 * blocks until [resolve] is called from the dialog's Approve/Deny button,
 * which runs on the main/UI thread. This is a real blocking gate — the same
 * guarantee `cli.ConsoleApprovalPrompt` gives by blocking on stdin — not a GUI
 * that merely displays a dialog while the tool call proceeds regardless.
 *
 * Requests are serialized: a second request waits for the first to finish
 * instead of overwriting it, and each request has its own future, so an
 * answer (or an [ApprovalHandle.cancel]) can only ever resolve the request it
 * belongs to. (The earlier one-slot queue let an abandoned waiter swallow the
 * next tap.) Answering when nothing is pending is ignored.
 */
@Singleton
class ComposeApprovalPrompt @Inject constructor() : ApprovalPrompt {
    private val _pending = MutableStateFlow<PendingApproval?>(null)
    val pending: StateFlow<PendingApproval?> = _pending

    private val gate = ReentrantLock(true)

    @Volatile private var current: CompletableFuture<Boolean>? = null

    override fun requestApproval(reason: String): Boolean = requestApproval(reason, ApprovalHandle())

    /** Same gate, with a [handle] a caller can use to cancel this one request. */
    fun requestApproval(reason: String, handle: ApprovalHandle): Boolean = gate.withLock {
        val mine = CompletableFuture<Boolean>()
        current = mine
        handle.attach(mine)
        _pending.value = PendingApproval(reason)
        try {
            mine.get()
        } finally {
            current = null
            _pending.value = null
        }
    }

    /** Called from [ApprovalHost]'s Approve/Deny buttons, on the main thread. */
    fun resolve(approved: Boolean) {
        current?.complete(approved)
    }
}
