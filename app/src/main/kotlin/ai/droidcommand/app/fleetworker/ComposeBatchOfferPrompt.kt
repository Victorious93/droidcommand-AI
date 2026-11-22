package ai.droidcommand.app.fleetworker

import ai.droidcommand.security.BatchOfferHandler
import ai.droidcommand.security.BatchReview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

/** What the dialog shows: the reviewed batch and the durations the owner may pick from (never longer than the policy allows). */
data class PendingBatchOffer(val review: BatchReview.Reviewable, val choices: List<Duration>)

/**
 * Puts a reviewed batch in front of the owner and waits for the answer, on the calling job's thread, like
 * `ComposeApprovalPrompt` does for a single confirmation. One offer at a time; answering when nothing is pending is ignored; an
 * offer the owner never answers is a "no" after [waitFor] so a job cannot hang forever. Whatever the owner picks is only a
 * *request*: `BatchApprovals.approve` still applies every limit.
 */
@Singleton
class ComposeBatchOfferPrompt @Inject constructor() : BatchOfferHandler {
    private val _pending = MutableStateFlow<PendingBatchOffer?>(null)
    val pending: StateFlow<PendingBatchOffer?> = _pending

    private val gate = ReentrantLock(true)

    @Volatile private var current: CompletableFuture<Duration?>? = null

    @Volatile var waitFor: Duration = Duration.ofMinutes(10)

    override fun decide(review: BatchReview.Reviewable): Duration? = gate.withLock {
        val future = CompletableFuture<Duration?>()
        current = future
        _pending.value = PendingBatchOffer(review, choicesFor(review))
        try {
            future.get(waitFor.toMillis(), TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            null
        } finally {
            _pending.value = null
            current = null
        }
    }

    /** Called from the dialog's buttons: [duration] to approve for that long, or null for "No, ask me each time". */
    fun answer(duration: Duration?) {
        current?.complete(duration)
    }

    /** Three short choices. Whatever is picked, `BatchApprovals.approve` still caps it at its own maximum. */
    private fun choicesFor(@Suppress("UNUSED_PARAMETER") review: BatchReview.Reviewable): List<Duration> =
        listOf(Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofMinutes(60))
}
