package ai.droidcommand.security

import java.time.Duration

/**
 * Which fleet job the current thread is running, so the prompt below knows whose approval is being asked for. [offer] is the
 * plan this job belongs to as the coordinator listed it (already reduced to what this phone could ever cover), or null when the
 * assignment came with no usable plan.
 */
class BatchJobContext(
    val jobId: String,
    val toolName: String,
    val argsDigest: String,
    val offer: BatchOffer? = null,
)

/**
 * Set by the fleet worker around the one tool call a job makes, on that job's own thread, and always restored. Thread-local on
 * purpose: parallel jobs on parallel threads can never see each other's context.
 */
object BatchJobScope {
    private val holder = ThreadLocal<BatchJobContext?>()

    fun current(): BatchJobContext? = holder.get()

    fun <T> within(context: BatchJobContext, block: () -> T): T {
        val previous = holder.get()
        holder.set(context)
        try {
            return block()
        } finally {
            holder.set(previous)
        }
    }
}

/**
 * Shows the owner a reviewed batch and returns how long they want it to last, or null when they say no (or do not answer). It is
 * called on a job's thread and may block until the owner answers, exactly like an ordinary approval tap.
 */
fun interface BatchOfferHandler {
    fun decide(review: BatchReview.Reviewable): Duration?
}

/**
 * Stands in front of the owner's approval prompt. When a fleet job asks for approval and [approvals] says that exact job (id,
 * tool, arguments) is covered by a batch the owner approved, the answer is yes and the owner is not asked again. In every other
 * case, including a job with no context, a reason-only request, or any mismatch, the request goes to [delegate] unchanged and
 * the owner's own answer is returned.
 *
 * With an [offers] handler it also makes the offer itself, and only at the moment a job is about to ask for a tap anyway (so a
 * plan whose jobs never need approval never shows a card): the first such job of a plan puts the reviewed batch in front of the
 * owner, once. Jobs of the same plan running in parallel wait on that one question instead of each asking their own; after
 * it, a job is covered or asks for its own tap as before. A declined, unanswered, expired or exhausted batch is **not** offered
 * again for the same plan. (A plan that could not even be reviewed because the feature is off is not counted as offered.)
 *
 * It sits *inside* [SecureToolExecutor]'s approval step, so it can only ever replace a tap that policy had already decided to
 * ask for: a denial, a mode or initiator refusal, a missing grant or a full audit log are all decided before this is reached.
 * [onCovered] lets the worker tell the coordinator the job was covered (as a progress note, never as completion).
 */
class BatchApprovalPrompt(
    private val delegate: ApprovalPrompt,
    private val approvals: BatchApprovals,
    private val offers: BatchOfferHandler? = null,
    private val maxRememberedPlans: Int = 256,
    private val onCovered: (jobId: String) -> Unit = {},
) : ApprovalPrompt {
    private val offeredPlans = object : LinkedHashMap<String, Any>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?) = size > maxRememberedPlans
    }
    private val planLocks = object : LinkedHashMap<String, Any>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?) = size > maxRememberedPlans
    }

    override fun requestApproval(reason: String): Boolean = delegate.requestApproval(reason)

    override fun requestApproval(toolName: String, input: Map<String, String>, reason: String): Boolean {
        val job = BatchJobScope.current()
        if (job != null && job.toolName == toolName) {
            if (covered(job)) return true
            val offer = job.offer
            val handler = offers
            if (offer != null && handler != null) {
                // One owner question per plan: the lock is per plan, so parallel jobs of THIS plan queue behind it and other
                // plans are not held up.
                synchronized(lockFor(offer.planId)) {
                    if (covered(job)) return true
                    if (!wasOffered(offer.planId)) {
                        when (val review = approvals.review(offer)) {
                            is BatchReview.Reviewable -> {
                                markOffered(offer.planId)
                                val ttl = runCatching { handler.decide(review) }.getOrNull()
                                if (ttl != null) approvals.approve(review, ttl)
                            }
                            is BatchReview.NothingToCover -> markOffered(offer.planId)
                            // Refused (the switch is off, the plan is too big or malformed): nothing was asked, so a later job of the
                            // plan may still get the question, for instance after the owner turns the feature on.
                            is BatchReview.Refused -> Unit
                        }
                    }
                }
                if (covered(job)) return true
            }
        }
        return delegate.requestApproval(toolName, input, reason)
    }

    private fun covered(job: BatchJobContext): Boolean {
        if (!approvals.tryCover(job.jobId, job.toolName, job.argsDigest)) return false
        runCatching { onCovered(job.jobId) }
        return true
    }

    private fun lockFor(planId: String): Any = synchronized(planLocks) { planLocks.getOrPut(planId) { Any() } }

    private fun wasOffered(planId: String): Boolean = synchronized(offeredPlans) { offeredPlans.containsKey(planId) }

    private fun markOffered(planId: String) {
        synchronized(offeredPlans) { offeredPlans[planId] = planId }
    }
}
