package ai.droidcommand.app.approval

import ai.droidcommand.security.ApprovalPrompt
import java.util.concurrent.ArrayBlockingQueue
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class PendingApproval(val reason: String)

/**
 * Bridges core-security's synchronous [ApprovalPrompt] contract to a Compose
 * confirmation dialog ([ApprovalHost]). [requestApproval] is invoked by
 * `SecureToolExecutor` from a background thread (every tool-invoking
 * ViewModel here dispatches on `Dispatchers.IO`, never the main thread) and
 * blocks on a one-slot queue until [resolve] is called from the dialog's
 * Approve/Deny button, which runs on the main/UI thread. This is a real
 * blocking gate — the same guarantee `cli.ConsoleApprovalPrompt` gives by
 * blocking on stdin — not a GUI that merely displays a dialog while the
 * tool call proceeds regardless.
 */
@Singleton
class ComposeApprovalPrompt @Inject constructor() : ApprovalPrompt {
    private val _pending = MutableStateFlow<PendingApproval?>(null)
    val pending: StateFlow<PendingApproval?> = _pending

    private val responseQueue = ArrayBlockingQueue<Boolean>(1)

    override fun requestApproval(reason: String): Boolean {
        _pending.value = PendingApproval(reason)
        val approved = responseQueue.take()
        _pending.value = null
        return approved
    }

    /** Called from [ApprovalHost]'s Approve/Deny buttons, on the main thread. */
    fun resolve(approved: Boolean) {
        responseQueue.offer(approved)
    }
}
